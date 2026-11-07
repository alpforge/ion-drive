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
package me.proton.core.drive.drivelink.upload.domain.usecase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import me.proton.core.drive.base.domain.log.LogTag
import me.proton.core.drive.base.domain.log.logId
import me.proton.core.drive.base.domain.provider.ProtonDriveClientProvider
import me.proton.core.drive.base.domain.util.coRunCatchingException
import me.proton.core.drive.drivelink.domain.entity.DriveLink
import me.proton.core.drive.eventmanager.base.domain.usecase.UpdateEventAction
import me.proton.core.drive.link.domain.extension.revisionUid
import me.proton.core.drive.link.domain.extension.userId
import me.proton.core.util.kotlin.CoreLogger
import me.proton.drive.sdk.UploadController
import me.proton.drive.sdk.Uploader
import me.proton.drive.sdk.entity.FileRevisionUploaderRequest
import me.proton.drive.sdk.entity.RevisionUid
import java.io.File
import java.nio.channels.Channels
import java.time.Instant
import javax.inject.Inject

/**
 * Fork addition: uploads the content of [File] as a new revision of an existing Drive file and
 * returns the uid of the revision that was created.
 *
 * The SDK refuses the upload when the file's active revision is no longer
 * [currentActiveRevisionId] (e.g. the file was modified from another device in the meantime).
 */
class UploadFileRevision @Inject constructor(
    private val protonDriveClientProvider: ProtonDriveClientProvider,
    private val updateEventAction: UpdateEventAction,
) {

    suspend operator fun invoke(
        driveLink: DriveLink.File,
        currentActiveRevisionId: String,
        file: File,
    ): Result<RevisionUid> = coRunCatchingException(Dispatchers.IO) {
        val linkLogId = driveLink.id.id.logId()
        CoreLogger.i(
            LogTag.UPLOAD,
            "Uploading new revision for $linkLogId (${file.length()} bytes)",
        )
        val revisionUid = updateEventAction(driveLink.userId, driveLink.volumeId) {
            val client = protonDriveClientProvider.getOrCreate(driveLink.userId).getOrThrow()
            val uploader: Uploader = client.uploader(
                FileRevisionUploaderRequest(
                    currentActiveRevisionUid = driveLink.id.revisionUid(
                        volumeId = driveLink.volumeId,
                        revisionId = currentActiveRevisionId,
                    ),
                    lastModificationTime = Instant.now(),
                    size = file.length(),
                )
            )
            uploader.use {
                file.inputStream().use { inputStream ->
                    coroutineScope {
                        val controller: UploadController = uploader.uploadFromStream(
                            coroutineScope = this,
                            channel = Channels.newChannel(inputStream),
                        )
                        controller.use { controller.awaitCompletion() }
                    }
                }
            }.revisionUid
        }
        CoreLogger.i(LogTag.UPLOAD, "New revision uploaded for $linkLogId")
        revisionUid
    }
}
