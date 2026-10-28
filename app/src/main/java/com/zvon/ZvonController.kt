package com.zvon

import com.mesh.core.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Minimal bridge for file-transfer accept/decline.
 * Stage 6 will extend this object with StateFlow/SharedFlow for call-state,
 * but PendingFileOffer semantics stay unchanged — so the migration is
 * just "move the emit source from MainActivity to MeshForegroundService".
 */
object ZvonController {

    data class PendingFileOffer(
        val id: String,
        val name: String,
        val size: Long,
        val ip: String,
        val decision: CompletableDeferred<Boolean>
    )

    @Volatile var meshNode: com.mesh.core.MeshNode? = null
    @Volatile var pipeline: com.mesh.core.AudioPipeline? = null
    @Volatile var peerTestRunner: com.mesh.core.PeerTestRunner? = null

    /** Активный peerId открытого ChatActivity. Уведомления от него не показываем. */
    @Volatile var activeChatPeerId: String? = null

    private val _chatMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val chatMessages: SharedFlow<Message> = _chatMessages.asSharedFlow()
    fun emitChatMessage(m: Message) { _chatMessages.tryEmit(m) }

    fun setActiveChat(peerId: String?) { activeChatPeerId = peerId }

    @Volatile var pendingFileOffer: PendingFileOffer? = null
    @Volatile var onFileOfferChanged: ((PendingFileOffer?) -> Unit)? = null
    @Volatile var onFileOfferProgress: ((name: String, sent: Long, total: Long, done: Boolean, err: String?) -> Unit)? = null

    /**
     * Called from MeshNode.onFileOffer (background coroutine, MeshNode scope).
     * Blocks until UI resolves the dialog. If Activity dies — coroutine is
     * cancelled by meshNode.close() → CancellationException → finally clears state.
     */
    suspend fun awaitFileDecision(id: String, name: String, size: Long, ip: String): Boolean {
        val pending = PendingFileOffer(id, name, size, ip, CompletableDeferred())
        pendingFileOffer = pending
        try {
            notifyChanged(pending)
            return pending.decision.await()
        } finally {
            pendingFileOffer = null
            notifyChanged(null)
        }
    }

    /** Called from Activity when user taps Accept/Decline. Idempotent. */
    fun resolveFileOffer(accept: Boolean) {
        pendingFileOffer?.decision?.complete(accept)
    }

    private fun notifyChanged(p: PendingFileOffer?) {
        val cb = onFileOfferChanged ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { cb(p) }
    }

    data class PendingPeerTestInvite(
        val id: String,
        val fromIp: String,
        val decision: CompletableDeferred<Boolean>
    )
    @Volatile var pendingPeerTestInvite: PendingPeerTestInvite? = null
    @Volatile var onPeerTestInviteChanged: ((PendingPeerTestInvite?) -> Unit)? = null

    suspend fun awaitPeerTestDecision(id: String, fromIp: String): Boolean {
        val pending = PendingPeerTestInvite(id, fromIp, CompletableDeferred())
        pendingPeerTestInvite = pending
        try {
            notifyPeerTest(pending)
            return pending.decision.await()
        } finally {
            pendingPeerTestInvite = null
            notifyPeerTest(null)
        }
    }

    fun resolvePeerTest(accept: Boolean) {
        pendingPeerTestInvite?.decision?.complete(accept)
    }

    private fun notifyPeerTest(p: PendingPeerTestInvite?) {
        val cb = onPeerTestInviteChanged ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { cb(p) }
    }
}
