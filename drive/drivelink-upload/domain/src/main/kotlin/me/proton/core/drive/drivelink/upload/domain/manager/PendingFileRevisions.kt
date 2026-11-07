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
package me.proton.core.drive.drivelink.upload.domain.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.drive.base.domain.usecase.GetPermanentFolder
import me.proton.core.drive.link.domain.entity.FileId
import me.proton.core.drive.link.domain.extension.userId
import me.proton.core.drive.volume.domain.entity.VolumeId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork addition: local content written by other apps into an existing Drive file, waiting to
 * be uploaded as a new revision.
 *
 * Files live in the permanent folder (not the cache) so that the system cannot drop them
 * before they reach Drive. A file is first created in a "writing" folder while the other app
 * has it open, and only becomes pending once it was closed, so that content still being
 * written (or abandoned half-way) is never used as the base of another write. Pending files
 * are named after the time they were closed, which gives the order of the writes.
 */
@Singleton
class PendingFileRevisions @Inject constructor(
    private val getPermanentFolder: GetPermanentFolder,
) {

    suspend fun createForWriting(volumeId: VolumeId, fileId: FileId): File = withContext(Dispatchers.IO) {
        val folder = folder(WRITING_FOLDER, volumeId, fileId).apply { mkdirs() }
        File.createTempFile("write", EXTENSION, folder)
    }

    /**
     * Moves [file], closed by the other app, to the pending folder and returns its new location.
     */
    suspend fun markPending(volumeId: VolumeId, fileId: FileId, file: File): File =
        withContext(Dispatchers.IO) {
            val folder = folder(PENDING_FOLDER, volumeId, fileId).apply { mkdirs() }
            var timestamp = System.currentTimeMillis()
            while (File(folder, "$timestamp$EXTENSION").exists()) {
                timestamp++
            }
            file.moveTo(File(folder, "$timestamp$EXTENSION"))
        }

    /**
     * Most recent content written for [fileId] and not uploaded yet.
     */
    suspend fun latest(volumeId: VolumeId, fileId: FileId): File? = withContext(Dispatchers.IO) {
        folder(PENDING_FOLDER, volumeId, fileId)
            .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.maxByOrNull { file -> file.name.removeSuffix(EXTENSION).toLongOrNull() ?: 0L }
    }

    /**
     * Moves [file] out of the pending folder so that it is no longer used as the base of a
     * later write, and returns its new location.
     */
    suspend fun moveToConflicts(volumeId: VolumeId, fileId: FileId, file: File): File =
        withContext(Dispatchers.IO) {
            val folder = folder(CONFLICT_FOLDER, volumeId, fileId).apply { mkdirs() }
            file.moveTo(File(folder, file.name))
        }

    private fun File.moveTo(target: File): File {
        if (!renameTo(target)) {
            copyTo(target, overwrite = true)
            delete()
        }
        return target
    }

    private suspend fun folder(name: String, volumeId: VolumeId, fileId: FileId) =
        File(getPermanentFolder(fileId.userId), "$name/${volumeId.id}/${fileId.id}")

    private companion object {
        const val WRITING_FOLDER = "writing_revisions"
        const val PENDING_FOLDER = "pending_revisions"
        const val CONFLICT_FOLDER = "revision_conflicts"
        const val EXTENSION = ".tmp"
    }
}
