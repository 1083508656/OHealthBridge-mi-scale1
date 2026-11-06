package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Pins the account owner to the top of the family-member list.
 *
 * OPPO sorts DBFamilyMemberInfo rows by createTimestamp, so after a member is re-created (or the
 * owner signs in again) the owner can slide to the bottom. The device page then compares a new
 * weighing against whatever member happens to be first, and the "体重差距过大，请确认是您本人"
 * dialog fires while the user is still stepping on the scale.
 *
 * FamilyMemberInfoStore.h(DataReadOption) / d(List, DataReadOption) are the read paths used by the
 * member manager and the body-fat pages. Reordering their result is enough — nothing is written
 * back to the database, so no personal data is touched.
 */
object FamilyOrderHook {
    private const val STORE = "com.heytap.databaseengineservice.store.business.FamilyMemberInfoStore"

    fun install(cl: ClassLoader) {
        runCatching {
            val cls = XposedHelpers.findClass(STORE, cl)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    BridgeLog.i("family store read fired: ${param.method?.name}")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val raw = param.result as? List<*>
                    BridgeLog.i(
                        "family store after ${param.method?.name}: " +
                            (raw?.let { "size=${it.size} first=${it.firstOrNull()?.javaClass?.simpleName}" }
                                ?: "result=${param.result?.javaClass?.simpleName}")
                    )
                    if (raw == null) return
                    if (raw.size < 2) return
                    val owner = raw.firstOrNull { isOwner(it) } ?: run {
                        BridgeLog.i("family order: owner not found among ${raw.size} rows")
                        return
                    }
                    if (raw.firstOrNull() === owner) return
                    // Always rebuild: the store may hand back an immutable or platform list.
                    val reordered = ArrayList<Any?>(raw.size)
                    reordered.add(owner)
                    raw.forEach { if (it !== owner) reordered.add(it) }
                    param.result = reordered
                    BridgeLog.i("family order fixed: owner pinned first (size=${raw.size})")
                }
            }
            var installed = 0
            installed += XposedBridge.hookAllMethods(cls, "h", hook).size
            installed += XposedBridge.hookAllMethods(cls, "d", hook).size
            BridgeLog.i("family order hook installed ($installed)")
        }.onFailure { BridgeLog.e("family order hook failed", it as? Exception) }
    }

    @Volatile private var diagLeft = 6

    /** subAccount == 0 marks the account owner; fall back to userTagId == ssoid. */
    private fun isOwner(row: Any?): Boolean {
        if (row == null) return false
        val sub = runCatching { XposedHelpers.getIntField(row, "subAccount") }.getOrDefault(-999)
        val tag = runCatching { XposedHelpers.getObjectField(row, "userTagId") as? String }.getOrNull()
            ?: runCatching { row.javaClass.getMethod("getUserTagId").invoke(row) as? String }.getOrNull()
        val ssoid = runCatching { XposedHelpers.getObjectField(row, "ssoid") as? String }.getOrNull()
            ?: runCatching { row.javaClass.getMethod("getSsoid").invoke(row) as? String }.getOrNull()
        if (diagLeft > 0) {
            diagLeft--
            // Only the last four characters of the ids are logged, and no member name is ever read.
            BridgeLog.i(
                "family row ${row.javaClass.simpleName} sub=$sub " +
                    "tag=${tag?.takeLast(4)} ssoid=${ssoid?.takeLast(4)}"
            )
        }
        if (sub == 0) return true
        return tag != null && ssoid != null && tag == ssoid
    }
}
