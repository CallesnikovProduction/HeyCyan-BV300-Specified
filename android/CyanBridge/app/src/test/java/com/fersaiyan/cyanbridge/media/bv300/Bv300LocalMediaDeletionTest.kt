package com.fersaiyan.cyanbridge.media.bv300

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Bv300LocalMediaDeletionTest {
    private val onBoth = Bv300MediaItem("remote", "photo.jpg", "photo.jpg", Bv300MediaType.PHOTO,
        localUri = "content://media/external/images/media/1", localBytes = 10)
    private val onPhone = Bv300MediaItem("phone", "", "audio.opus", Bv300MediaType.AUDIO,
        localUri = "content://media/external/audio/media/2", localBytes = 20, remoteAvailable = false)
    private val onGlasses = Bv300MediaItem("not-downloaded", "video.mp4", "video.mp4", Bv300MediaType.VIDEO)

    @Test fun candidatesContainOnlySelectedPhoneCopies() {
        val items = listOf(onBoth, onPhone, onGlasses)
        assertEquals(listOf(onPhone.localUri), localDeleteTargets(items, setOf("phone", "not-downloaded", "unknown")))
        assertTrue(localDeleteTargets(items, setOf("not-downloaded")).isEmpty())
    }

    @Test fun deletingPhoneCopyPreservesGlassesEntryAndDropsPhoneOnlyEntry() {
        val result = afterLocalDelete(listOf(onBoth, onPhone, onGlasses),
            setOf(onBoth.localUri!!, onPhone.localUri!!))
        assertEquals(listOf("remote", "not-downloaded"), result.map { it.id })
        assertNull(result.first().localUri)
        assertNull(result.first().localBytes)
        assertTrue(result.first().remoteAvailable)
    }

    @Test fun failedDeletionLeavesPhoneCopyVisible() {
        assertEquals(listOf(onBoth, onPhone), afterLocalDelete(listOf(onBoth, onPhone), emptySet()))
    }
}
