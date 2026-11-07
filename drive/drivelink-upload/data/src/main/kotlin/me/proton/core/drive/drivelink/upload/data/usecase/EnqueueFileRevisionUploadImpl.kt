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
package me.proton.core.drive.drivelink.upload.data.usecase

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await
import me.proton.core.drive.base.domain.log.LogTag
import me.proton.core.drive.base.domain.log.logId
import me.proton.core.drive.base.domain.util.coRunCatchingException
import me.proton.core.drive.drivelink.upload.data.worker.UploadFileRevisionWorker
import me.proton.core.drive.drivelink.upload.domain.usecase.EnqueueFileRevisionUpload
import me.proton.core.drive.link.domain.entity.FileId
import me.proton.core.util.kotlin.CoreLogger
import java.io.File
import javax.inject.Inject

class EnqueueFileRevisionUploadImpl @Inject constructor(
    private val workManager: WorkManager,
) : EnqueueFileRevisionUpload {

    override suspend operator fun invoke(
        fileId: FileId,
        currentActiveRevisionId: String,
        file: File,
        isBasedOnDownloadedRevision: Boolean,
    ): Result<Unit> = coRunCatchingException {
        CoreLogger.i(LogTag.UPLOAD, "Scheduling new revision upload for ${fileId.id.logId()}")
        // APPEND_OR_REPLACE: every write of the same file must reach Drive, in order. KEEP would
        // drop a write made while the previous one is still uploading, REPLACE would cancel that
        // upload. Unlike APPEND, a chain that failed or was cancelled does not fail the new write.
        workManager.enqueueUniqueWork(
            uniqueWorkName = UploadFileRevisionWorker.getUniqueWorkName(fileId),
            existingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            request = UploadFileRevisionWorker.getWorkRequest(
                fileId = fileId,
                revisionId = currentActiveRevisionId,
                file = file,
                isBasedOnDownloadedRevision = isBasedOnDownloadedRevision,
            ),
        ).await()
    }
}
