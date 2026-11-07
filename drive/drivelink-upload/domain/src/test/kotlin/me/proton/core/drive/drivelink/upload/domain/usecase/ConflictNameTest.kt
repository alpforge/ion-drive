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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ConflictNameTest {

    private val now = LocalDateTime.of(2026, 10, 31, 9, 5, 7)

    @Test
    fun `keeps the extension at the end`() {
        assertEquals(
            "backup (# Edit conflict 2026-10-31 090507C #).json",
            conflictName("backup.json", now),
        )
    }

    @Test
    fun `only the last extension is kept apart`() {
        assertEquals(
            "archive.tar (# Edit conflict 2026-10-31 090507C #).gz",
            conflictName("archive.tar.gz", now),
        )
    }

    @Test
    fun `name without extension or starting with a dot`() {
        assertEquals("notes (# Edit conflict 2026-10-31 090507C #)", conflictName("notes", now))
        assertEquals(".env (# Edit conflict 2026-10-31 090507C #)", conflictName(".env", now))
    }
}
