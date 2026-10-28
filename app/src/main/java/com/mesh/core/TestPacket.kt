package com.mesh.core

import org.json.JSONObject

data class TestPacket(
    val t: String,
    val id: String,
    val seq: Long = 0L,
    val data: String? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("t", t); put("id", id); put("seq", seq)
        data?.let { put("data", it) }
    }.toString()

    companion object {
        fun parse(s: String): TestPacket? = try {
            val o = JSONObject(s)
            TestPacket(
                t = o.getString("t"),
                id = o.getString("id"),
                seq = o.optLong("seq", 0L),
                data = o.optString("data", "").takeIf { it.isNotEmpty() }
            )
        } catch (_: Exception) { null }
    }
}
