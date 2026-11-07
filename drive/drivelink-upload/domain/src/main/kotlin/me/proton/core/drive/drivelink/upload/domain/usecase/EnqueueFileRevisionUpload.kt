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

import me.proton.core.drive.link.domain.entity.FileId
import java.io.File

/**
 * Fork addition: schedules the upload of [file] as a new revision of [fileId].
 *
 * Uploads of the same file run one after the other, in the order they were scheduled, and
 * survive process death.
 */
interface EnqueueFileRevisionUpload {
    /**
     * @param currentActiveRevisionId active revision of the file when it was opened for write.
     * @param isBasedOnDownloadedRevision true when the written content started from the downloaded
     * [currentActiveRevisionId] ("rw"/"wa"), so that it must not replace a newer revision.
     */
    suspend operator fun invoke(
        fileId: FileId,
        currentActiveRevisionId: String,
        file: File,
        isBasedOnDownloadedRevision: Boolean,
    ): Result<Unit>
}
