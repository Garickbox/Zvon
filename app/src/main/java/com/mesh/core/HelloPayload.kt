package com.mesh.core

import org.json.JSONObject

data class HelloPayload(val name: String) {
    fun encode(): ByteArray = JSONObject().apply { put("n", name) }.toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(b: ByteArray): HelloPayload? = try {
            if (b.isEmpty()) null
            else HelloPayload(JSONObject(String(b, Charsets.UTF_8)).optString("n", ""))
                .takeIf { it.name.isNotBlank() }
        } catch (_: Exception) { null }
    }
}
