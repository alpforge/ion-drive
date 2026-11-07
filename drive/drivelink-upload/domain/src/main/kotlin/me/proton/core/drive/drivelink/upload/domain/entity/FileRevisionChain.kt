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
package me.proton.core.drive.drivelink.upload.domain.entity

/**
 * Fork addition: revisions this device has replaced while uploading successive writes of the
 * same file, and the last revision it uploaded.
 *
 * A write opened before the previous one was uploaded still knows the revision that was active
 * at that time. When that revision was replaced by this device (and not by another one), the
 * write must replace [uploadedRevisionId] instead, unless its content started from the
 * downloaded revision: then it does not contain the previous write and replacing it would lose
 * that write, so the upload is left to fail as a conflict.
 */
data class FileRevisionChain(
    val supersededRevisionIds: Set<String>,
    val uploadedRevisionId: String,
) {
    fun isContinuedBy(openedRevisionId: String, isBasedOnDownloadedRevision: Boolean): Boolean =
        !isBasedOnDownloadedRevision && openedRevisionId in supersededRevisionIds
}

/**
 * Revision that a write opened on [openedRevisionId] must replace.
 */
fun FileRevisionChain?.currentActiveRevisionId(
    openedRevisionId: String,
    isBasedOnDownloadedRevision: Boolean,
): String = this
    ?.takeIf { chain -> chain.isContinuedBy(openedRevisionId, isBasedOnDownloadedRevision) }
    ?.uploadedRevisionId
    ?: openedRevisionId

/**
 * Chain after a write opened on [openedRevisionId] was uploaded as [uploadedRevisionId].
 */
fun FileRevisionChain?.next(
    openedRevisionId: String,
    isBasedOnDownloadedRevision: Boolean,
    uploadedRevisionId: String,
): FileRevisionChain {
    val previous = this?.takeIf { chain -> chain.isContinuedBy(openedRevisionId, isBasedOnDownloadedRevision) }
    val replacedRevisionId = previous.currentActiveRevisionId(openedRevisionId, isBasedOnDownloadedRevision)
    return FileRevisionChain(
        supersededRevisionIds = previous?.supersededRevisionIds.orEmpty() + openedRevisionId + replacedRevisionId,
        uploadedRevisionId = uploadedRevisionId,
    )
}
