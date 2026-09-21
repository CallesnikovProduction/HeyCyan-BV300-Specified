package com.fersaiyan.cyanbridge.localai

import com.fersaiyan.cyanbridge.localai.model.FreshPhotoGuard
import com.fersaiyan.cyanbridge.localai.model.LocalModelManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelSafetyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun voskRequiresActualModelFiles() {
        val model = temporaryFolder.newFolder("vosk-model")
        assertFalse(LocalModelManager.isValidVoskDirectory(model))
        listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst").forEach { relative ->
            File(model, relative).apply { parentFile!!.mkdirs(); writeText("model") }
        }
        assertTrue(LocalModelManager.isValidVoskDirectory(model))
    }

    @Test fun oldOrEmptyPhotoCannotSatisfyNewRequest() {
        val photo = temporaryFolder.newFile("photo.jpg")
        val requestStart = System.currentTimeMillis()
        photo.writeBytes(byteArrayOf(1, 2, 3))
        photo.setLastModified(requestStart - 5_000)
        assertFalse(FreshPhotoGuard.isFresh(photo, requestStart))
        photo.setLastModified(requestStart + 1)
        assertTrue(FreshPhotoGuard.isFresh(photo, requestStart))
        photo.writeBytes(byteArrayOf())
        assertFalse(FreshPhotoGuard.isFresh(photo, requestStart))
    }
}
