package com.mesh.core

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Contact(
    val nodeId: String,
    val name: String,
    val mac: String,
    val lastIp: String,
    val lastSeenMs: Long,
    @Volatile var online: Boolean = false
)

class ContactsStore(context: Context) {
    private val file = File(context.filesDir, "contacts.json")
    private val map = mutableMapOf<String, Contact>()
    init { load() }
    @Synchronized fun all(): List<Contact> = map.values.sortedByDescending { it.lastSeenMs }
    @Synchronized fun get(nodeId: String): Contact? = map[nodeId]
    @Synchronized fun upsert(nodeId: String, name: String, mac: String, ip: String) {
        val existing = map[nodeId]
        map[nodeId] = Contact(nodeId, name.ifBlank { existing?.name ?: nodeId.take(8) },
            mac, ip, System.currentTimeMillis(), existing?.online ?: false)
        save()
    }
    @Synchronized fun markOnline(nodeId: String, online: Boolean) {
        map[nodeId]?.let { it.online = online; if (online) map[nodeId] = it.copy(lastSeenMs = System.currentTimeMillis()) }
    }
    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            for (k in root.keys()) {
                val o = root.getJSONObject(k)
                map[k] = Contact(k, o.optString("name"), o.optString("mac"), o.optString("lastIp"), o.optLong("lastSeen"))
            }
        } catch (_: Exception) { }
    }
    private fun save() {
        try {
            val root = JSONObject()
            for ((k, c) in map) root.put(k, JSONObject().apply {
                put("name", c.name); put("mac", c.mac); put("lastIp", c.lastIp); put("lastSeen", c.lastSeenMs)
            })
            file.writeText(root.toString())
        } catch (_: Exception) { }
    }
}
