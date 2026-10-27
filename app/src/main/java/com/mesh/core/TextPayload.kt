package com.mesh.core

import java.nio.ByteBuffer

data class TextPayload(val text: String) {
    fun encode(): ByteArray = text.toByteArray(Charsets.UTF_8).also {
        require(it.size < 1200) { "text too long" }
    }

    companion object {
        fun decode(d: ByteArray): TextPayload? = try {
            TextPayload(String(d, Charsets.UTF_8))
        } catch (_: Exception) { null }
    }
}
