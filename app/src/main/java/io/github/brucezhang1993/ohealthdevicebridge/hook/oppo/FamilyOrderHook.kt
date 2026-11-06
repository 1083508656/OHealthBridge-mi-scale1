package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Pins the account owner to the top of the family-member list.
 *
 * OPPO orders DBFamilyMemberInfo rows by createTimestamp. All rows here share the same
 * createTimestamp (1723474900000), so ordering falls back to insertion order and the owner can end
 * up last. The device page then compares a new weighing against whichever member comes first,
 * which is what fires the "体重差距过大，请确认是您本人" dialog while the user is still stepping
 * onto the scale.
 *
 * The owner is identified without reading any member name: its userTagId is the account ssoid
 * (9 digits, e.g. 472591149), while imported members carry 13-digit ids (1786245151502, …).
 * Reordering happens purely in memory on the two store read paths; nothing is written back.
 */
object FamilyOrderHook {
    private const val STORE = "com.heytap.databaseengineservice.store.business.FamilyMemberInfoStore"
    private const val ACTIVITY = "com.heytap.health.bodyfat.ui.BodyFatFamilyManagerActivity"

    /** Account ssoids are 9-10 digits; imported member ids are 13+ digits. */
    private const val OWNER_TAG_MAX_LEN = 12

    fun install(cl: ClassLoader) {
        runCatching {
            val cls = XposedHelpers.findClass(STORE, cl)
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val raw = param.result as? List<*>
                    BridgeLog.i(
                        "family order ${param.method?.name}: result=${raw?.size ?: -1} " +
                            "cls=${raw?.firstOrNull()?.javaClass?.simpleName} " +
                            "tags=${raw?.take(3)?.joinToString(",") { tagOf(it) ?: "?" }}"
                    )
                    if (raw == null || raw.size < 2) return
                    val owner = raw.firstOrNull { isOwner(it) }
                    if (owner == null) {
                        BridgeLog.i("family order: owner not found among ${raw.size} rows")
                        return
                    }
                    if (raw.firstOrNull() === owner) return
                    // Rebuild rather than mutate: the store may return an immutable list.
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

            // The store already returns the owner first, but BodyFatFamilyManagerActivity re-sorts
            // the list for display. Reorder the activity's backing ArrayList after the members
            // have been loaded (onCreate returns before the async load completes, hence the delay).
            val act = XposedHelpers.findClass(ACTIVITY, cl)
            XposedBridge.hookAllMethods(act, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    main.postDelayed({ runCatching { fixActivityList(activity) } }, 800)
                    main.postDelayed({ runCatching { fixActivityList(activity) } }, 2_000)
                }
            })
            installed++
            BridgeLog.i("family order hook installed ($installed)")
        }.onFailure { BridgeLog.e("family order hook failed", it as? Exception) }
    }

    @Volatile private var activityFixed = false

    /** Reorders the activity's `m` ArrayList (member list) and refreshes its adapter `l`. */
    @Suppress("UNCHECKED_CAST")
    private fun fixActivityList(activity: Any?) {
        if (activity == null || activityFixed) return
        val list = runCatching { XposedHelpers.getObjectField(activity, "m") as? ArrayList<Any?> }.getOrNull()
            ?: return
        if (list.size < 2) return
        val owner = list.firstOrNull { isOwner(it) } ?: return
        if (list.firstOrNull() === owner) { activityFixed = true; return }
        list.remove(owner)
        list.add(0, owner)
        val adapter = runCatching { XposedHelpers.getObjectField(activity, "l") }.getOrNull()
        runCatching { adapter?.javaClass?.getMethod("notifyDataSetChanged")?.invoke(adapter) }
        activityFixed = true
        BridgeLog.i("family UI order fixed: owner pinned first (size=${list.size})")
    }

    /** Reads userTagId as a field (DBFamilyMemberInfo) or via getter (FamilyMemberInfo). */
    private fun tagOf(row: Any?): String? {
        if (row == null) return null
        return runCatching { XposedHelpers.getObjectField(row, "userTagId") as? String }.getOrNull()
            ?: runCatching { row.javaClass.getMethod("getUserTagId").invoke(row) as? String }.getOrNull()
    }

    private fun isOwner(row: Any?): Boolean {
        val tag = tagOf(row) ?: return false
        return tag.length < OWNER_TAG_MAX_LEN
    }
}
