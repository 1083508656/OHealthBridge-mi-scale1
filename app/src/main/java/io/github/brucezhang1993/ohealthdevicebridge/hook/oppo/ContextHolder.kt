package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context

/**
 * Keeps the target application context for hooks that are invoked without one.
 *
 * Application.attach() can run before getApplicationContext() is wired up (it returns null on
 * some devices), so we fall back to the passed-in Context and allow a later, better context to
 * replace it.
 */
object ContextHolder {
    @Volatile private var ctx: Context? = null

    @Synchronized fun set(c: Context) {
        val candidate = runCatching { c.applicationContext }.getOrNull() ?: c
        if (ctx !== candidate) ctx = candidate
    }

    fun get(): Context? = ctx
}
