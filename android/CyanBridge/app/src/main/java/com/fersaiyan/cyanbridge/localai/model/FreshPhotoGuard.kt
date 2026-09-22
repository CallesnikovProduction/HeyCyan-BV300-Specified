package com.fersaiyan.cyanbridge.localai.model

import java.io.File

object FreshPhotoGuard {
    fun isFresh(photo: File, requestStartedAtMs: Long): Boolean =
        photo.isFile && photo.length() > 0 && photo.lastModified() >= requestStartedAtMs

    fun isOwnedFresh(photo: File, requestId: String, requestStartedAtMs: Long): Boolean =
        photo.name == "bv300_${requestId}.jpg" && isFresh(photo, requestStartedAtMs)
}
