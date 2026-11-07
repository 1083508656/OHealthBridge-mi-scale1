package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * One-shot cleanup of the unclaimed rows that earlier module builds left behind.
 *
 * Early versions wrote WeightBodyFat rows straight into table 1021 without going through OPPO's
 * claim/attribution logic, so every test weighing landed in `BodyFatUnclaimedListActivity` as an
 * unclaimed record. On this device that accumulated into many duplicate rows of the same weight.
 *
 * ## Why the rows are unclaimed
 *
 * `BodyFatStore` rows are claimed by matching `WeightBodyFat.userTagId` against
 * `FamilyMemberInfo.userTagId` where `subAccount == 0` (see `l.call()` in
 * OPPO_WEIGHT_FLOW_REVERSE.md §1.7). Rows written by the module carried the device tag instead,
 * so they matched no member and stayed unclaimed.
 *
 * ## How the cleanup works
 *
 * `BodyFatUnclaimedViewModel.u(userTagId, weightId)` is OPPO's own "discard this unclaimed row"
 * entry point (`@44a04c`, coroutine -> `BodyFatUnclaimedViewModel$deleteItem$1`). The module calls
 * it once per leftover row, which is exactly what the swipe-to-delete action on that screen does.
 *
 * Runs once, guarded by a preference flag, and only in the UI process where the page's ViewModel
 * classes are loaded.
 */
object UnclaimedCleanupHook {

    private const val VIEW_MODEL = "com.heytap.health.bodyfat.viewmodel.BodyFatUnclaimedViewModel"
    private const val PREFS = "ohealth_device_bridge"
    private const val PREF_DONE = "unclaimed_cleanup_done_v1"

    private const val TABLE_WEIGHT = 1021

    @Volatile private var ran = false
    @Volatile private var profile: OppoVersionProfile = OppoVersionProfile.V610

    /** Installs the hook; the cleanup itself runs after the unclaimed page is created. */
    fun install(cl: ClassLoader, ctx: Context) {
        runCatching {
            if (done(ctx)) {
                BridgeLog.i("unclaimed cleanup: already done")
                return
            }
            profile = OppoVersionProfile.detect(cl)
            val vmCls = XposedHelpers.findClass(VIEW_MODEL, cl)
            XposedBridge.hookAllConstructors(vmCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (ran) return
                    ran = true
                    val vm = param.thisObject ?: return
                    Thread({ sweep(cl, ctx, vm) }, "oh-unclaimed-cleanup").start()
                }
            })
            BridgeLog.i("unclaimed cleanup hook installed")
        }.onFailure { BridgeLog.e("unclaimed cleanup hook failed", it as? Exception) }
    }

    /**
     * Reads every unclaimed row and discards it through the page's own ViewModel method.
     *
     * The rows are read from the page's own list LiveData rather than by querying the database, so
     * only rows OPPO already considers unclaimed are touched. The field and method names differ by
     * version: 6.10.x uses `l` / `u`, 6.9.x uses `dataList` / `deleteItem`.
     */
    private fun sweep(cl: ClassLoader, ctx: Context, vm: Any) {
        // The list arrives asynchronously; give the page a moment to publish it.
        var rows: List<*> = emptyList<Any?>()
        repeat(20) {
            if (rows.isNotEmpty()) return@repeat
            Thread.sleep(500)
            rows = runCatching {
                val live = XposedHelpers.getObjectField(vm, profile.unclaimedListField)
                val value = live.javaClass.getMethod("getValue").invoke(live)
                (value as? List<*>) ?: emptyList<Any?>()
            }.getOrDefault(emptyList<Any?>())
        }
        if (rows.isEmpty()) {
            BridgeLog.i("unclaimed cleanup: nothing to do")
            markDone(ctx)
            return
        }
        BridgeLog.i("unclaimed cleanup: ${rows.size} leftover row(s)")

        var removed = 0
        rows.filterIsInstance<Any>().forEach { row ->
            val weightId = stringOf(row, "weightId")
            val tag = stringOf(row, "userTagId") ?: ""
            if (weightId.isNullOrBlank()) {
                BridgeLog.i("unclaimed cleanup: row without weightId (${row.javaClass.simpleName})")
                return@forEach
            }
            val ok = runCatching {
                vm.javaClass.getMethod(profile.unclaimedDeleteMethod, String::class.java, String::class.java)
                    .invoke(vm, tag, weightId)
                true
            }.onFailure { BridgeLog.e("unclaimed delete failed", it as? Exception) }
                .getOrDefault(false)
            if (ok) {
                removed++
                val grams = stringOf(row, "weight")
                BridgeLog.i("unclaimed cleanup: discarded weight=${grams}g id=${weightId.takeLast(6)}")
            }
        }
        BridgeLog.i("unclaimed cleanup: requested ${removed}/${rows.size}")
        if (removed > 0) markDone(ctx)
    }

    /** Reads a bean property through its getter, or the field directly if no getter exists. */
    private fun stringOf(row: Any, prop: String): String? {
        val getter = "get" + prop.replaceFirstChar { it.uppercase() }
        runCatching {
            val value = row.javaClass.getMethod(getter).invoke(row)
            if (value != null) return value.toString()
        }
        return runCatching {
            val field = row.javaClass.getDeclaredField(prop).apply { isAccessible = true }
            field.get(row)?.toString()
        }.getOrNull()
    }

    private fun done(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_DONE, false)

    private fun markDone(ctx: Context) {
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_DONE, true).apply()
        }
    }
}