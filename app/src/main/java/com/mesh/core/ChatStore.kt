package com.mesh.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(
    val id: Long,
    val peerNodeId: String,
    val fromMe: Boolean,
    val kind: String,
    val body: String,
    val filePath: String?,
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
    fun allPeers(): List<String> = cache.keys.toList()

    @Synchronized
    fun lastMessage(peerNodeId: String): Message? = cache[peerNodeId]?.lastOrNull()

    private fun load() {
        if (!file.exists()) { InAppLogger.write('I', "Chat", "no file, empty history"); return }
        try {
            val text = file.readText()
            InAppLogger.write('I', "Chat", "load: file ${file.length()}B")
            val root = JSONObject(text)
            val peers = root.optJSONObject("chats") ?: return
            for (peer in peers.keys()) {
                val arr = peers.getJSONArray(peer)
                val list = mutableListOf<Message>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Message(o.getLong("id"), peer, o.getBoolean("fromMe"),
                        o.getString("kind"), o.getString("body"), o.optString("filePath").takeIf { it.isNotEmpty() }, o.getLong("ts")))
                    nextId = maxOf(nextId, o.getLong("id") + 1)
                }
                cache[peer] = list
            }
            val total = cache.values.sumOf { it.size }
            InAppLogger.write('I', "Chat", "loaded ${cache.size} peers, $total messages")
        } catch (e: Exception) {
            InAppLogger.write('E', "Chat", "load failed", e)
        }
    }

    private fun save() {
        try {
            val root = JSONObject(); val peers = JSONObject()
            for ((peer, list) in cache) {
                val arr = JSONArray()
                for (m in list) arr.put(JSONObject().apply {
                    put("id", m.id); put("fromMe", m.fromMe); put("kind", m.kind)
                    put("body", m.body); put("filePath", m.filePath ?: ""); put("ts", m.tsMs)
                })
                peers.put(peer, arr)
            }
            root.put("chats", peers); file.writeText(root.toString())
            val total = cache.values.sumOf { it.size }
            InAppLogger.write('D', "Chat", "saved ${cache.size} peers, $total msgs, ${file.length()}B")
        } catch (e: Exception) {
            InAppLogger.write('E', "Chat", "save failed", e)
        }
    }
}
