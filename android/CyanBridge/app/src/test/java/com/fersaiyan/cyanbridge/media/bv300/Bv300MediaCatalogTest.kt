package com.fersaiyan.cyanbridge.media.bv300

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bv300MediaCatalogTest {
    private val device = "AA:BB:CC:DD:EE:FF"

    @Test fun parsesAllBv300MediaTypesAndRejectsUnsafePaths() {
        val items = Bv300MediaCatalog.parse(device, """
            20260924120000.jpg
            20260924120001.mp4
            audio/20260924120002.opus
            ../secret.jpg
            /absolute.mp4
            notes.txt
        """.trimIndent(), 123L)
        assertEquals(listOf(Bv300MediaType.PHOTO, Bv300MediaType.VIDEO, Bv300MediaType.AUDIO), items.map { it.type })
        assertEquals("audio/20260924120002.opus", items[2].remotePath)
        assertEquals("aa:bb:cc:dd:ee:ff:20260924120000.jpg", items[0].id)
    }

    @Test fun reconcilesGlassesPhoneAndDeletedPhoneCopy() {
        val remote = Bv300MediaCatalog.parse(device, "one.jpg\ntwo.mp4", 200L)
        val local = listOf(remote[0].copy(localUri = "content://saved/one", localBytes = 10),
            Bv300MediaCatalog.parse(device, "gone.opus", 100L).single().copy(localUri = "content://saved/gone", localBytes = 9),
            remote[1].copy(localUri = "content://deleted/two", localBytes = 30))
        val result = reconcileMedia(remote, local) { it.localUri != "content://deleted/two" }
        assertEquals("content://saved/one", result[0].localUri)
        assertEquals(null, result[1].localUri)
        assertFalse(result[2].remoteAvailable)
        assertEquals("content://saved/gone", result[2].localUri)
        assertEquals(setOf(remote[1].id), downloadNewIds(result))
    }

    @Test fun incompleteTransferCannotBeVerified() {
        assertTrue(verifyTransferBytes(42, 42))
        assertTrue(verifyTransferBytes(42, -1))
        assertFalse(verifyTransferBytes(41, 42))
        assertFalse(verifyTransferBytes(0, -1))
    }
}
