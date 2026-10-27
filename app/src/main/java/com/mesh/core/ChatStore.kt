package com.mesh.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(
    val id: Long,
    val peerNodeId: String,
    val fromMe: Boolean,
    val kind: String,          // "text" | "file"
    val body: String,          // text или file_name
    val filePath: String?,     // для kind=file
    val tsMs: Long
)

class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chats.json")
    private val cache = mutableMapOf<String, MutableList<Message>>()
    private var nextId = 1L

    init { load() }

    @Synchronized
    fun add(peerNodeId: String, msg: Message): Message {
        val m = if (msg.id == 0L) msg.copy(id = nextId++) else { nextId = maxOf(nextId, msg.id + 1); msg }
        cache.getOrPut(peerNodeId) { mutableListOf() }.add(m)
        save()
        return m
    }

    @Synchronized
    fun history(peerNodeId: String): List<Message> = cache[peerNodeId]?.toList() ?: emptyList()

    @Synchronized
    fun updateFilePath(peerNodeId: String, msgId: Long, path: String) {
        cache[peerNodeId]?.firstOrNull { it.id == msgId }?.let { old ->
            val idx = cache[peerNodeId]!!.indexOf(old)
            cache[peerNodeId]!![idx] = old.copy(filePath = path)
            save()
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val peers = root.optJSONObject("chats") ?: return
            for (peer in peers.keys()) {
                val arr = peers.getJSONArray(peer)
                val list = mutableListOf<Message>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Message(
                        id = o.getLong("id"),
                        peerNodeId = peer,
                        fromMe = o.getBoolean("fromMe"),
                        kind = o.getString("kind"),
                        body = o.getString("body"),
                        filePath = o.optString("filePath").takeIf { it.isNotEmpty() },
                        tsMs = o.getLong("ts")
                    ))
                    nextId = maxOf(nextId, o.getLong("id") + 1)
                }
                cache[peer] = list
            }
        } catch (_: Exception) { }
    }

    private fun save() {
        try {
            val root = JSONObject()
            val peers = JSONObject()
            for ((peer, list) in cache) {
                val arr = JSONArray()
                for (m in list) {
                    arr.put(JSONObject().apply {
                        put("id", m.id); put("fromMe", m.fromMe)
                        put("kind", m.kind); put("body", m.body)
                        put("filePath", m.filePath ?: ""); put("ts", m.tsMs)
                    })
                }
                peers.put(peer, arr)
            }
            root.put("chats", peers)
            file.writeText(root.toString())
        } catch (_: Exception) { }
    }
}
