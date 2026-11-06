package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import java.security.MessageDigest

internal object OppoAccount {
    fun key(cl: ClassLoader): String {
        val helper = cl.loadClass("com.heytap.health.account.AccountHelper")
        val manager = helper.getMethod("getAccountManager").invoke(null) ?: error("OPPO account unavailable")
        val id = OppoReflect.call(manager, "getSsoid") as? String
        check(!id.isNullOrBlank()) { "OPPO account is not signed in" }
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
