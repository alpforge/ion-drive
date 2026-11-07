/*
 * Copyright (c) 2026 Proton AG.
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
package me.proton.core.drive.drivelink.upload.data.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.proton.core.domain.entity.UserId
import me.proton.core.drive.announce.event.domain.entity.Event
import me.proton.core.drive.base.data.extension.isRetryable
import me.proton.core.drive.base.data.extension.log
import me.proton.core.drive.base.data.workmanager.addTags
import me.proton.core.drive.base.domain.extension.getOrNull
import me.proton.core.drive.base.domain.extension.toResult
import me.proton.core.drive.base.domain.log.LogTag
import me.proton.core.drive.base.domain.log.logId
import me.proton.core.drive.base.domain.util.coRunCatchingException
import me.proton.core.drive.drivelink.crypto.domain.usecase.GetDecryptedDriveLink
import me.proton.core.drive.drivelink.domain.entity.DriveLink
import me.proton.core.drive.drivelink.upload.domain.entity.FileRevisionChain
import me.proton.core.drive.drivelink.upload.domain.entity.currentActiveRevisionId
import me.proton.core.drive.drivelink.upload.domain.entity.next
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_FILE_ID
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_FILE_PATH
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_IS_BASED_ON_DOWNLOADED_REVISION
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_REVISION_ID
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_SHARE_ID
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_SUPERSEDED_REVISION_IDS
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_UPLOADED_REVISION_ID
import me.proton.core.drive.drivelink.upload.data.worker.WorkerKeys.KEY_USER_ID
import me.proton.core.drive.drivelink.upload.domain.usecase.UploadFileRevision
import me.proton.core.drive.drivelink.upload.domain.usecase.UploadFileRevisionConflictCopy
import me.proton.core.drive.link.domain.entity.FileId
import me.proton.core.drive.link.domain.entity.FolderId
import me.proton.core.drive.link.domain.extension.revisionId
import me.proton.core.drive.link.domain.extension.userId
import me.proton.core.drive.notification.domain.entity.NotificationId
import me.proton.core.drive.share.domain.entity.ShareId
import me.proton.core.drive.worker.data.LimitedRetryCoroutineWorker
import me.proton.core.drive.worker.data.usecase.TransferDataNotifier
import me.proton.core.drive.worker.domain.usecase.WorkerRuns
import me.proton.core.util.kotlin.CoreLogger
import java.io.File
import kotlin.Result as KotlinResult

/**
 * Fork addition: uploads content written by another app into an existing Drive file as a new
 * revision of that file.
 *
 * Works for the same file are chained (see [getUniqueWorkName]). WorkManager passes the output
 * of the previous work to the next one, which lets a later write build on the revision this
 * device has just uploaded instead of reporting a conflict with itself.
 *
 * When the content cannot become a new revision (changed elsewhere, or a failure that will not
 * go away), it is uploaded as a conflict copy next to the file instead of being dropped. The
 * worker always succeeds once it is done with its content, so that a failure never cancels the
 * writes queued after it.
 */
@HiltWorker
class UploadFileRevisionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    workerRuns: WorkerRuns,
    private val transferDataNotifier: TransferDataNotifier,
    private val getDecryptedDriveLink: GetDecryptedDriveLink,
    private val uploadFileRevision: UploadFileRevision,
    private val uploadFileRevisionConflictCopy: UploadFileRevisionConflictCopy,
) : LimitedRetryCoroutineWorker(appContext, workerParams, workerRuns) {
    override val userId = UserId(
        requireNotNull(inputData.getString(KEY_USER_ID)) { "User id is required" }
    )
    override val logTag: String get() = LogTag.UPLOAD
    private val fileId = FileId(
        ShareId(
            userId,
            id = requireNotNull(inputData.getString(KEY_SHARE_ID)) { "Share id is required" },
        ),
        id = requireNotNull(inputData.getString(KEY_FILE_ID)) { "File id is required" },
    )
    private val revisionId = requireNotNull(inputData.getString(KEY_REVISION_ID)) {
        "Revision id is required"
    }
    private val file = File(
        requireNotNull(inputData.getString(KEY_FILE_PATH)) { "File path is required" }
    )
    private val isBasedOnDownloadedRevision =
        inputData.getBoolean(KEY_IS_BASED_ON_DOWNLOADED_REVISION, true)

    // Output of the previous work for the same file, if any
    private val chain = inputData.getString(KEY_UPLOADED_REVISION_ID)?.let { uploadedRevisionId ->
        FileRevisionChain(
            supersededRevisionIds = inputData.getStringArray(KEY_SUPERSEDED_REVISION_IDS)?.toSet().orEmpty(),
            uploadedRevisionId = uploadedRevisionId,
        )
    }

    private val linkLogId get() = fileId.id.logId()

    private val transferDataNotification: KotlinResult<Pair<NotificationId, Notification>> =
        transferDataNotifier(
            userId = userId,
            event = Event.TransferData,
        )

    override suspend fun doLimitedRetryWork(): Result {
        if (!file.exists()) {
            CoreLogger.w(logTag, "Content for $linkLogId no longer exists, nothing uploaded")
            return Result.success()
        }
        setForeground()
        return try {
            val driveLink = getDecryptedDriveLink(fileId).toResult()
                .getOrElse { error -> return handleFailure(error, driveLink = null) }
            val currentActiveRevisionId = chain.currentActiveRevisionId(revisionId, isBasedOnDownloadedRevision)
            uploadFileRevision(driveLink, currentActiveRevisionId, file).fold(
                onSuccess = { revisionUid ->
                    file.delete()
                    val next = chain.next(revisionId, isBasedOnDownloadedRevision, revisionUid.revisionId)
                    Result.success(
                        Data.Builder()
                            .putStringArray(KEY_SUPERSEDED_REVISION_IDS, next.supersededRevisionIds.toTypedArray())
                            .putString(KEY_UPLOADED_REVISION_ID, next.uploadedRevisionId)
                            .build()
                    )
                },
                onFailure = { error -> handleFailure(error, driveLink) },
            )
        } finally {
            transferDataNotification.getOrNull(logTag)
                ?.first
                ?.let { notificationId ->
                    transferDataNotifier.dismissNotification(notificationId)
                }
        }
    }

    private suspend fun handleFailure(error: Throwable, driveLink: DriveLink.File?): Result {
        val isRetryable = error.isRetryable && canRetry()
        error.log(logTag, "New revision of $linkLogId could not be uploaded, retryable=$isRetryable")
        return if (isRetryable) {
            Result.retry()
        } else {
            driveLink?.let { saveConflictCopy(driveLink) }
            Result.success()
        }
    }

    private suspend fun saveConflictCopy(driveLink: DriveLink.File) {
        coRunCatchingException {
            val folderId = requireNotNull(driveLink.parentId as? FolderId) { "Parent is not a folder" }
            val folder = getDecryptedDriveLink(userId, folderId).toResult().getOrThrow()
            uploadFileRevisionConflictCopy(driveLink, folder, file).getOrThrow()
        }
            .onSuccess {
                CoreLogger.i(logTag, "Content for $linkLogId uploaded as a conflict copy")
            }
            .onFailure { error ->
                error.log(logTag, "Conflict copy for $linkLogId failed, content kept on the device")
            }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo().getOrThrow()

    private fun createForegroundInfo(): KotlinResult<ForegroundInfo> = coRunCatchingException {
        val transferNotification = transferDataNotification.getOrThrow()
        ForegroundInfo(
            transferNotification.first.id,
            transferNotification.second,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private suspend fun setForeground() {
        createForegroundInfo()
            .getOrNull(logTag, "Failed creating foreground info")
            ?.let { foregroundInfo ->
                try {
                    setForeground(foregroundInfo)
                } catch (e: IllegalStateException) {
                    e.log(logTag, "Failed to set foreground")
                }
            }
    }

    companion object {
        fun getWorkRequest(
            fileId: FileId,
            revisionId: String,
            file: File,
            isBasedOnDownloadedRevision: Boolean,
            tags: List<String> = emptyList(),
        ): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(UploadFileRevisionWorker::class.java)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_USER_ID, fileId.userId.id)
                        .putString(KEY_SHARE_ID, fileId.shareId.id)
                        .putString(KEY_FILE_ID, fileId.id)
                        .putString(KEY_REVISION_ID, revisionId)
                        .putString(KEY_FILE_PATH, file.path)
                        .putBoolean(KEY_IS_BASED_ON_DOWNLOADED_REVISION, isBasedOnDownloadedRevision)
                        .build()
                )
                .addTags(listOf(fileId.userId.id) + tags)
                .build()

        fun getUniqueWorkName(fileId: FileId) =
            "UploadFileRevisionWorker.${fileId.userId.id}.${fileId.shareId.id}.${fileId.id}"
    }
}
