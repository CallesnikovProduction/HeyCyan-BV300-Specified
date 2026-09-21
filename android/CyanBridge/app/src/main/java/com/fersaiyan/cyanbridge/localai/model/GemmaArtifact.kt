package com.fersaiyan.cyanbridge.localai.model

import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import java.io.File

/** Pinned public Gemma 4 E2B LiteRT bundle used by the BV300 assistant. */
object GemmaArtifact {
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val SIZE_BYTES = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    fun isCandidate(fileName: String): Boolean =
        fileName.matches(Regex("gemma-4-E2B-it(?:_\\d+)?\\.litertlm", RegexOption.IGNORE_CASE))

    fun isVerified(model: InstalledLocalModel): Boolean =
        isCandidate(model.fileName) && model.sizeBytes == SIZE_BYTES &&
            model.sha256.equals(SHA256, ignoreCase = true) &&
            File(model.absolutePath).length() == SIZE_BYTES
}
