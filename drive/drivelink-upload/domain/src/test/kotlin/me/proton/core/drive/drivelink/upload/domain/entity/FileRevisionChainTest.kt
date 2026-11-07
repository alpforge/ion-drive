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

import org.junit.Assert.assertEquals
import org.junit.Test

class FileRevisionChainTest {

    @Test
    fun `first write replaces the revision it was opened on`() {
        val chain: FileRevisionChain? = null

        assertEquals("R0", chain.currentActiveRevisionId("R0", isBasedOnDownloadedRevision = false))
        assertEquals(
            FileRevisionChain(setOf("R0"), "R1"),
            chain.next("R0", isBasedOnDownloadedRevision = true, uploadedRevisionId = "R1"),
        )
    }

    @Test
    fun `writes opened before the previous upload replace the revision this device uploaded`() {
        val afterFirst = null.next("R0", isBasedOnDownloadedRevision = false, uploadedRevisionId = "R1")

        assertEquals("R1", afterFirst.currentActiveRevisionId("R0", isBasedOnDownloadedRevision = false))

        val afterSecond = afterFirst.next("R0", isBasedOnDownloadedRevision = false, uploadedRevisionId = "R2")

        assertEquals(FileRevisionChain(setOf("R0", "R1"), "R2"), afterSecond)
        assertEquals("R2", afterSecond.currentActiveRevisionId("R0", isBasedOnDownloadedRevision = false))
        assertEquals("R2", afterSecond.currentActiveRevisionId("R1", isBasedOnDownloadedRevision = false))
    }

    @Test
    fun `write based on the downloaded revision does not replace a revision uploaded since`() {
        val chain = FileRevisionChain(setOf("R0"), "R1")

        assertEquals("R0", chain.currentActiveRevisionId("R0", isBasedOnDownloadedRevision = true))
        assertEquals(
            FileRevisionChain(setOf("R0"), "R5"),
            chain.next("R0", isBasedOnDownloadedRevision = true, uploadedRevisionId = "R5"),
        )
    }

    @Test
    fun `write opened on a revision from another device keeps it`() {
        val chain = FileRevisionChain(setOf("R0"), "R1")

        assertEquals("R3", chain.currentActiveRevisionId("R3", isBasedOnDownloadedRevision = false))
        assertEquals(
            FileRevisionChain(setOf("R3"), "R4"),
            chain.next("R3", isBasedOnDownloadedRevision = false, uploadedRevisionId = "R4"),
        )
    }
}
