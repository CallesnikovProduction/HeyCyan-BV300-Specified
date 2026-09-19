package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusOggWrapperTest {
    @Test
    fun `already wrapped Ogg is left untouched`() {
        val ogg = "OggS-existing".toByteArray()

        val (bytes, note) = OpusOggWrapper.wrapIfNeeded(ogg)

        assertSame(ogg, bytes)
        assertEquals("ogg-already", note)
    }

    @Test
    fun `three length prefixed packets become Ogg Opus`() {
        val raw = byteArrayOf(2, 0, 1, 2, 2, 0, 3, 4, 2, 0, 5, 6)

        val (bytes, note) = OpusOggWrapper.wrapIfNeeded(raw)
        val payload = bytes.toString(Charsets.ISO_8859_1)

        assertTrue(payload.startsWith("OggS"))
        assertTrue(payload.contains("OpusHead"))
        assertTrue(payload.contains("OpusTags"))
        assertEquals("wrapped packets=3", note)
    }

    @Test
    fun `unrecognized short payload remains raw`() {
        val raw = byteArrayOf(1, 2, 3)

        val (bytes, note) = OpusOggWrapper.wrapIfNeeded(raw)

        assertSame(raw, bytes)
        assertEquals("raw-unwrapped", note)
    }
}
