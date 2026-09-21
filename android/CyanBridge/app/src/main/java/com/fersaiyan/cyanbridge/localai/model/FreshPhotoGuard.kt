package com.fersaiyan.cyanbridge.localai.model

import java.io.File

object FreshPhotoGuard {
    fun isFresh(photo: File, requestStartedAtMs: Long): Boolean =
        photo.isFile && photo.length() > 0 && photo.lastModified() >= requestStartedAtMs
}
