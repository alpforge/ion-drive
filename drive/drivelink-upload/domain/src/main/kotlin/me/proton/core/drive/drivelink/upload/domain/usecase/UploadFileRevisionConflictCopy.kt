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

import android.net.Uri
import me.proton.core.drive.base.domain.entity.TimestampMs
import me.proton.core.drive.base.domain.extension.bytes
import me.proton.core.drive.base.domain.util.coRunCatchingException
import me.proton.core.drive.drivelink.domain.entity.DriveLink
import me.proton.core.drive.drivelink.upload.domain.manager.PendingFileRevisions
import me.proton.core.drive.linkupload.domain.entity.UploadFileDescription
import me.proton.core.drive.linkupload.domain.entity.UploadFileLink
import me.proton.core.drive.linkupload.domain.entity.UploadFileProperties
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Fork addition: keeps content that could not become a new revision of [DriveLink.File] (the
 * file was changed elsewhere, or the upload failed for good) by uploading it as a new file
 * next to it, like the Windows client does for edit conflicts:
 * `name (# Edit conflict yyyy-MM-dd HHmmssC #).ext`.
 */
class UploadFileRevisionConflictCopy @Inject constructor(
    private val uploadFiles: UploadFiles,
    private val pendingFileRevisions: PendingFileRevisions,
) {

    suspend operator fun invoke(
        driveLink: DriveLink.File,
        folder: DriveLink.Folder,
        file: File,
    ): Result<Unit> = coRunCatchingException {
        val copy = pendingFileRevisions.moveToConflicts(driveLink.volumeId, driveLink.id, file)
        uploadFiles(
            folder = folder,
            uploadFileDescriptions = listOf(
                UploadFileDescription(
                    uri = Uri.fromFile(copy).toString(),
                    properties = UploadFileProperties(
                        name = conflictName(driveLink.name, LocalDateTime.now()),
                        mimeType = driveLink.mimeType,
                        size = copy.length().bytes,
                        lastModified = TimestampMs(copy.lastModified()),
                    ),
                )
            ),
            shouldDeleteSource = true,
            priority = UploadFileLink.USER_PRIORITY,
        ).getOrThrow()
    }
}

/**
 * Name of the conflict copy, following the Windows client:
 * `{OriginalName} (# Edit conflict {date} {suffix}C #){.Extension}`.
 */
internal fun conflictName(name: String, now: LocalDateTime): String {
    val extensionIndex = name.lastIndexOf('.').takeIf { index -> index > 0 }
    val baseName = extensionIndex?.let { index -> name.substring(0, index) } ?: name
    val extension = extensionIndex?.let { index -> name.substring(index) }.orEmpty()
    return "$baseName (# Edit conflict ${now.format(DATE_FORMAT)} ${now.format(TIME_FORMAT)}C #)$extension"
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
