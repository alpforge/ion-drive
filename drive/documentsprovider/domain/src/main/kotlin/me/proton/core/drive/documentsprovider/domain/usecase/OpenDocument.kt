/*
 * Copyright (c) 2022-2023 Proton AG.
 * This file is part of Proton Core.
 *
 * Proton Core is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Core is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Core.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.proton.core.drive.documentsprovider.domain.usecase

import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.drive.base.domain.coroutines.ApplicationScope
import me.proton.core.drive.base.domain.log.LogTag
import me.proton.core.drive.base.domain.log.logId
import me.proton.core.drive.base.domain.usecase.GetCacheFolder
import me.proton.core.drive.base.domain.usecase.ReportError
import me.proton.core.drive.base.domain.util.coRunCatchingException
import me.proton.core.drive.documentsprovider.domain.entity.DocumentId
import me.proton.core.drive.drivelink.domain.entity.DriveLink
import me.proton.core.drive.drivelink.domain.extension.isShareReadOnly
import me.proton.core.drive.drivelink.download.domain.extension.throwable
import me.proton.core.drive.drivelink.download.domain.usecase.GetFile
import me.proton.core.drive.drivelink.upload.domain.usecase.UploadAlreadyCreatedFiles
import me.proton.core.drive.drivelink.upload.domain.manager.PendingFileRevisions
import me.proton.core.drive.drivelink.upload.domain.usecase.EnqueueFileRevisionUpload
import me.proton.core.drive.linkupload.domain.entity.UploadFileLink
import me.proton.core.drive.linkupload.domain.extension.parentLinkId
import me.proton.core.drive.linkupload.domain.extension.userId
import me.proton.core.drive.linkupload.domain.extension.volumeId
import me.proton.core.drive.upload.domain.usecase.CancelUploadFile
import me.proton.core.util.kotlin.CoreLogger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenDocument @Inject constructor(
    private val withUploadFileLink: WithUploadFileLink,
    private val withDriveLinkFile: WithDriveLinkFile,
    private val withDriveLinkFolder: WithDriveLinkFolder,
    private val getFile: GetFile,
    private val getCacheFolder: GetCacheFolder,
    private val cancelUploadFile: CancelUploadFile,
    private val uploadFiles: UploadAlreadyCreatedFiles,
    private val reportError: ReportError,
    private val enqueueFileRevisionUpload: EnqueueFileRevisionUpload,
    private val pendingFileRevisions: PendingFileRevisions,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    private val handlerThread = Handler(HandlerThread("OpenDocumentThread").apply {
        start()
    }.looper)

    private val mutexMap = ConcurrentHashMap<DocumentId, Mutex>()

    suspend operator fun invoke(
        documentId: DocumentId,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        openUploadFileLink(documentId, mode, signal) ?: openDriveLinkFile(documentId, mode, signal)

    @Suppress("UNUSED_PARAMETER")
    private suspend fun openUploadFileLink(
        documentId: DocumentId,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor? =
        withUploadFileLink(documentId) { userId, uploadFileLink ->
            if (mode == null || mode.contains("w").not()) {
                throw UnsupportedOperationException("$mode is not supported, only 'w' for upload file node")
            }
            val file = uploadFileLink.cacheFolder()
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_WRITE_ONLY,
                handlerThread
            ) { exception ->
                if (exception != null) {
                    runBlocking { cancelUploadFile(uploadFileLink) }
                } else runBlocking {
                    withDriveLinkFolder(DocumentId(userId, uploadFileLink.parentLinkId)) { _, driveLink ->
                        uploadFiles(
                            folder = driveLink,
                            uploadFiles = listOf(uploadFileLink to Uri.fromFile(file).toString()),
                            shouldDeleteSource = true,
                        )
                    }
                }
            }
        }

    private suspend fun UploadFileLink.cacheFolder() =
        File(getCacheFolder(userId, volumeId.id, "upload_$id"), "tmp")
            .apply {
                if (!exists()) {
                    createNewFile()
                }
            }

    private suspend fun openDriveLinkFile(
        documentId: DocumentId,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = withDriveLinkFile(documentId) { _, driveLink ->
        when {
            mode == null -> throw UnsupportedOperationException("null mode is not supported")
            mode.contains("w") -> openDriveLinkFileForWrite(documentId, driveLink, mode, signal)
            mode.contains("r") -> openDriveLinkFileForRead(documentId, driveLink, signal)
            else -> throw UnsupportedOperationException("$mode is not supported, only 'r' or 'w'")
        }
    }

    private suspend fun openDriveLinkFileForRead(
        documentId: DocumentId,
        driveLink: DriveLink.File,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = getDecryptedFile(documentId, driveLink, signal)

        if (signal?.isCanceled == true) {
            file.delete()
            error("Request was canceled")
        }

        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY,
            handlerThread,
        ) { error ->
            if (error != null) {
                CoreLogger.w(
                    LogTag.DOCUMENTS_PROVIDER,
                    error,
                    "An error occurred when file ${documentId.linkId?.id?.logId()} was used by another app"
                )
            }
        }
    }

    /**
     * Fork addition: the calling app writes into a local file; when it closes the file
     * descriptor, the content is scheduled to be uploaded as a new revision of the Drive file
     * (see [EnqueueFileRevisionUpload]).
     *
     * The caller gets "success" as soon as it closes the file, before the upload completes.
     * If the content cannot become a new revision, it is uploaded as a conflict copy next to
     * the file instead.
     */
    private suspend fun openDriveLinkFileForWrite(
        documentId: DocumentId,
        driveLink: DriveLink.File,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val linkLogId = driveLink.id.id.logId()
        if (driveLink.isShareReadOnly) {
            throw FileNotFoundException("Document is read-only")
        }
        val parsedMode = ParcelFileDescriptor.parseMode(mode)
        val keepsExistingContent = (parsedMode and ParcelFileDescriptor.MODE_TRUNCATE) == 0
        val tmpFile = pendingFileRevisions.createForWriting(driveLink.volumeId, driveLink.id)
        var isBasedOnDownloadedRevision = false
        val initialDigest: ByteArray? = try {
            if (keepsExistingContent) {
                // "rw" or "wa": the caller expects the current content to be there. A previous
                // write that is not uploaded yet is more recent than the downloaded revision.
                val pending = pendingFileRevisions.latest(driveLink.volumeId, driveLink.id)
                val copiedFromPending = pending?.let { file -> copyIfStillPending(file, tmpFile) } == true
                if (!copiedFromPending) {
                    getDecryptedFile(documentId, driveLink, signal).copyTo(tmpFile, overwrite = true)
                    isBasedOnDownloadedRevision = true
                }
                tmpFile.sha256()
            } else {
                null
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }

        if (signal?.isCanceled == true) {
            tmpFile.delete()
            error("Request was canceled")
        }

        CoreLogger.i(LogTag.DOCUMENTS_PROVIDER, "Opening $linkLogId for write (mode=$mode)")
        return ParcelFileDescriptor.open(tmpFile, parsedMode, handlerThread) { error ->
            if (error != null) {
                CoreLogger.w(
                    LogTag.DOCUMENTS_PROVIDER,
                    error,
                    "Write to $linkLogId was aborted by the other app, nothing uploaded"
                )
                tmpFile.delete()
                return@open
            }
            applicationScope.launch(Dispatchers.IO) {
                coRunCatchingException {
                    if (initialDigest != null && initialDigest.contentEquals(tmpFile.sha256())) {
                        CoreLogger.i(LogTag.DOCUMENTS_PROVIDER, "$linkLogId unchanged, nothing uploaded")
                        tmpFile.delete()
                        return@coRunCatchingException
                    }
                    val pendingFile = pendingFileRevisions.markPending(driveLink.volumeId, driveLink.id, tmpFile)
                    enqueueFileRevisionUpload(
                        fileId = driveLink.id,
                        currentActiveRevisionId = driveLink.activeRevisionId,
                        file = pendingFile,
                        isBasedOnDownloadedRevision = isBasedOnDownloadedRevision,
                    ).getOrThrow()
                }.onFailure { error ->
                    reportError(
                        tag = LogTag.DOCUMENTS_PROVIDER,
                        error = error,
                        message = "New revision of $linkLogId could not be scheduled",
                    )
                }
            }
        }
    }

    /**
     * The pending content may be uploaded and deleted while it is being copied.
     */
    private fun copyIfStillPending(pending: File, target: File): Boolean = try {
        pending.copyTo(target, overwrite = true)
        true
    } catch (e: IOException) {
        CoreLogger.d(LogTag.DOCUMENTS_PROVIDER, e, "Pending content is gone, using the downloaded revision")
        false
    }

    private suspend fun getDecryptedFile(
        documentId: DocumentId,
        driveLink: DriveLink.File,
        signal: CancellationSignal?,
    ): File {
        val state = mutexMap.getOrPut(documentId) { Mutex() }.withLock {
            getFile(driveLink, checkSignature = false).first { state ->
                when (state) {
                    is GetFile.State.Ready -> true
                    is GetFile.State.Error -> true
                    else -> signal?.isCanceled == true
                }
            }
        }
        if (state !is GetFile.State.Ready) {
            state.throwable?.let{ error ->
                reportError(
                    tag = LogTag.DOCUMENTS_PROVIDER,
                    error = error,
                    message = "Document could not be opened",
                )
            }
            throw FileNotFoundException("Document could not be opened")
        }
        return File(state.uri.path!!)
    }

    private fun File.sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
