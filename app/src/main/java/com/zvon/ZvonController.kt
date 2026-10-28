package com.zvon

import kotlinx.coroutines.CompletableDeferred

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
}
