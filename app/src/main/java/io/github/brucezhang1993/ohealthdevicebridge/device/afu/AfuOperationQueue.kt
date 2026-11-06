package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import java.util.ArrayDeque

/** Called on the session thread; a missing Android callback must terminate the queue. */
internal class AfuOperationQueue(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onError: (Exception) -> Unit,
) {
    private class Operation(val key: Any, val description: String, val start: () -> Boolean, val done: () -> Unit)
    private val pending = ArrayDeque<Operation>()
    private var active: Operation? = null
    private var cancelTimeout: (() -> Unit)? = null
    private var closed = false

    fun enqueue(key: Any, description: String, start: () -> Boolean, done: () -> Unit = {}) {
        if (closed) return
        if (pending.size + (if (active == null) 0 else 1) >= MAX_OPERATIONS) {
            fail(IllegalStateException("GATT queue capacity exceeded"))
            return
        }
        pending.add(Operation(key, description, start, done))
        drain()
    }

    fun complete(key: Any, success: Boolean) {
        val operation = active ?: return
        if (closed || operation.key != key) return
        cancelTimeout?.invoke()
        cancelTimeout = null
        active = null
        if (!success) {
            fail(IllegalStateException("GATT operation failed: ${operation.description}"))
            return
        }
        try {
            operation.done()
            drain()
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun close() {
        closed = true
        cancelTimeout?.invoke()
        cancelTimeout = null
        active = null
        pending.clear()
    }

    private fun drain() {
        if (closed || active != null || pending.isEmpty()) return
        val operation = pending.removeFirst()
        active = operation
        cancelTimeout = schedule(OPERATION_TIMEOUT_MS) {
            if (active === operation) fail(IllegalStateException("GATT operation timeout: ${operation.description}"))
        }
        try {
            if (!operation.start()) fail(IllegalStateException("GATT operation could not start: ${operation.description}"))
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Exception) {
        if (closed) return
        close()
        onError(error)
    }

    companion object {
        const val OPERATION_TIMEOUT_MS = 5_000L
        const val MAX_OPERATIONS = 64
    }
}
