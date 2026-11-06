package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import org.junit.Assert.*
import org.junit.Test

class AfuOperationQueueTest {
    private class Clock {
        private var now = 0L
        private val tasks = mutableMapOf<Any, Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val id = Any(); tasks[id] = now + delay to action
            return { tasks.remove(id); Unit }
        }
        fun advance(delay: Long) {
            now += delay
            tasks.toMap().filterValues { it.first <= now }.forEach { (id, task) -> tasks.remove(id); task.second() }
        }
    }
    @Test fun missingWriteCallbackFailsOnceAndStopsPendingWrites() {
        val clock = Clock(); val errors = mutableListOf<Exception>(); var writes = 0
        val queue = AfuOperationQueue(clock::schedule, errors::add)
        queue.enqueue("a", "handshake", { writes++; true })
        queue.enqueue("b", "profile", { writes++; true })
        clock.advance(5000); queue.complete("a", true); clock.advance(5000)
        assertEquals(1, errors.size); assertEquals(1, writes)
        assertTrue(errors.single().message!!.contains("timeout"))
    }
    @Test fun unrelatedDescriptorDoesNotAdvanceQueue() {
        val clock = Clock(); val errors = mutableListOf<Exception>(); var done = 0
        val queue = AfuOperationQueue(clock::schedule, errors::add)
        val first = Any(); val other = Any()
        queue.enqueue(first, "CCCD on service A", { true }, { done++ })
        queue.complete(other, true); assertEquals(0, done)
        queue.complete(first, true); assertEquals(1, done)
        clock.advance(5000); assertTrue(errors.isEmpty())
    }
    @Test fun cancellationRejectsLateCallbackAndTimeout() {
        val clock = Clock(); val errors = mutableListOf<Exception>(); var done = 0
        val queue = AfuOperationQueue(clock::schedule, errors::add)
        queue.enqueue("a", "write", { true }, { done++ }); queue.close()
        queue.complete("a", true); clock.advance(5000)
        assertEquals(0, done); assertTrue(errors.isEmpty())
    }
    @Test fun failedDownstreamCallbackStopsNextOperation() {
        val clock = Clock(); val errors = mutableListOf<Exception>(); var writes = 0
        val queue = AfuOperationQueue(clock::schedule, errors::add)
        queue.enqueue("a", "write", { writes++; true }, { throw IllegalStateException("callback failed") })
        queue.enqueue("b", "write", { writes++; true }); queue.complete("a", true)
        assertEquals(1, writes); assertEquals("callback failed", errors.single().message)
    }
    @Test fun queueIsBounded() {
        val clock = Clock(); val errors = mutableListOf<Exception>()
        val queue = AfuOperationQueue(clock::schedule, errors::add)
        repeat(65) { queue.enqueue(it, "write", { true }) }
        assertTrue(errors.single().message!!.contains("capacity"))
    }
    @Test fun synchronousStartFailureTerminatesQueue() {
        val clock = Clock(); val errors = mutableListOf<Exception>()
        val queue = AfuOperationQueue(clock::schedule, errors::add)
        queue.enqueue("a", "write", { false }); clock.advance(5000)
        assertEquals(1, errors.size)
    }
    @Test fun virtualMachineErrorIsNotDowngraded() {
        val clock = Clock(); val errors = mutableListOf<Exception>()
        val queue = AfuOperationQueue(clock::schedule, errors::add)
        try { queue.enqueue("a", "write", { throw StackOverflowError("test") }); fail("Error swallowed") }
        catch (_: StackOverflowError) { assertTrue(errors.isEmpty()) }
    }
}
