package com.exodidio.tune.settings

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupManagerTest {
    @Test
    fun `isSafeBackupZipPath blocks traversal and accepts valid archive paths`() {
        assertFalse(isSafeBackupZipPath(""))
        assertFalse(isSafeBackupZipPath("../evil.txt"))
        assertFalse(isSafeBackupZipPath("artwork/../../evil.txt"))
        assertFalse(isSafeBackupZipPath("/etc/passwd"))
        assertFalse(isSafeBackupZipPath("\\windows\\system.ini"))
        assertFalse(isSafeBackupZipPath("artwork\\..\\evil.jpg"))

        assertTrue(isSafeBackupZipPath("manifest.json"))
        assertTrue(isSafeBackupZipPath("preferences.json"))
        assertTrue(isSafeBackupZipPath("artwork/playlist_artwork/cover.jpg"))
        assertTrue(isSafeBackupZipPath("artwork/artist_artwork/artist.png"))
    }

    @Test
    fun `validateBackupTargetFile constrains extracted files inside root directory`() {
        val tempDir = Files.createTempDirectory("tune-backup-test").toFile()
        try {
            assertNull(validateBackupTargetFile(tempDir, "../outside.jpg"))
            assertNull(validateBackupTargetFile(tempDir, "/abs/path.jpg"))
            val safe = validateBackupTargetFile(tempDir, "playlist_artwork/cover.jpg")
            assertNotNull(safe)
            assertTrue(safe!!.canonicalPath.startsWith(tempDir.canonicalPath))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
