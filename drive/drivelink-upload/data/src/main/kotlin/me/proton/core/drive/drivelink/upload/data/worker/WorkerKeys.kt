/*
 * Copyright (c) 2025 Proton AG.
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

internal object WorkerKeys {
    const val KEY_USER_ID = "key.userId"
    const val KEY_SHARE_ID = "key.shareId"
    const val KEY_FOLDER_ID = "key.folderId"
    const val KEY_URI = "key.uri"
    const val KEY_SHOULD_BROADCAST_MESSAGE = "key.shouldBroadcastMessage"
    const val KEY_FILE_ID = "key.fileId"
    const val KEY_REVISION_ID = "key.revisionId"
    const val KEY_FILE_PATH = "key.filePath"
    const val KEY_IS_BASED_ON_DOWNLOADED_REVISION = "key.isBasedOnDownloadedRevision"
    const val KEY_SUPERSEDED_REVISION_IDS = "key.supersededRevisionIds"
    const val KEY_UPLOADED_REVISION_ID = "key.uploadedRevisionId"
}
