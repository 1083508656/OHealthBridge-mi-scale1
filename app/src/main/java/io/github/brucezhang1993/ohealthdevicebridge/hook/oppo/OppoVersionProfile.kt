package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XposedHelpers

/**
 * Describes the per-version surface of OPPO Health's body-fat measure page.
 *
 * OPPO renamed the whole module between 6.9.x and 6.10.x:
 *
 *  - 6.9.37: no obfuscation. Field/method names are human-readable
 *    (`viewModel`, `measureData`, `MeasureEvent`, `pendingBooheeRaw`, `commitPendingMeasure`,
 *     `navigateToDetail`, `BooheeWeightSaver.saveLockedMeasure`).
 *  - 6.10.13/14/15: obfuscated. `BodyFatMeasureViewModel` keeps the same class name, but its
 *    fields are single letters (`l` = measure LiveData, `w` = BHScaleModel, `x` = weight kg,
 *    `v` = member tag) and the value holder is `com.heytap.device.ui.weight.n`.
 *
 * All facts here were verified byte-level against real APKs (see OPPO_WEIGHT_FLOW_REVERSE.md).
 * A profile is selected by probing, never by trusting the version string, so an unexpected future
 * rename degrades instead of crashing.
 */
enum class OppoVersionProfile(
    /** Human label for logs. */
    val label: String,

    /** The `BodyFatMeasureActivity` field that holds the ViewModel. */
    val activityVmField: String,

    /** The `BodyFatMeasureViewModel` field that holds the measure LiveData. */
    val vmMeasureLiveDataField: String,

    /** The `BodyFatMeasureViewModel` field that holds the member tag id. */
    val vmMemberTagField: String,

    /** The `BodyFatMeasureViewModel` field that holds the raw BHScaleModel (settle guard). */
    val vmModelField: String,

    /** The `BodyFatMeasureViewModel` field that holds the weight in kg (settle guard). */
    val vmWeightField: String,

    /** The `BodyFatMeasureViewModel` field holding the device MAC. */
    val vmMacField: String,

    /**
     * FQCN of the value posted to the measure LiveData, or null when the profile posts a
     * named type directly.
     */
    val measureValueClass: String?,

    /** Whether the measure value carries a stable/locked flag. */
    val valueHasStable: Boolean,

    /** The `BodyFatUnclaimedViewModel` method that deletes one row. */
    val unclaimedDeleteMethod: String,

    /** The `BodyFatUnclaimedViewModel` field holding the list of unclaimed rows. */
    val unclaimedListField: String,
) {
    /** 6.10.13 / 6.10.14 / 6.10.15 — obfuscated single-letter fields. */
    V610(
        label = "6.10.x",
        activityVmField = "k",
        vmMeasureLiveDataField = "l",
        vmMemberTagField = "v",
        vmModelField = "w",
        vmWeightField = "x",
        vmMacField = "u",
        measureValueClass = "com.heytap.device.ui.weight.n",
        valueHasStable = true,
        unclaimedDeleteMethod = "u",
        unclaimedListField = "l",
    ),

    /** 6.9.37 — readable names, no settle guard, MeasureEvent carries the locked flag. */
    V69(
        label = "6.9.x",
        activityVmField = "viewModel",
        vmMeasureLiveDataField = "measureData",
        vmMemberTagField = "measurementUserTagId",
        vmModelField = "pendingBooheeRaw",
        vmWeightField = "pendingBooheeKg",
        vmMacField = "targetMac",
        measureValueClass = "com.heytap.device.ui.weight.BodyFatMeasureViewModel\$MeasureEvent",
        valueHasStable = true,
        unclaimedDeleteMethod = "deleteItem",
        unclaimedListField = "dataList",
    );

    companion object {
        private const val ACTIVITY = "com.heytap.device.ui.weight.BodyFatMeasureActivity"

        /**
         * Detects the running OPPO Health build by probing the Activity's ViewModel field name.
         *
         * `viewModel` exists only on 6.9.x; `k` exists only on 6.10.x. This is independent of the
         * version string, so a re-sign or regional build cannot fool it.
         */
        fun detect(cl: ClassLoader): OppoVersionProfile {
            val act = XposedHelpers.findClass(ACTIVITY, cl)
            val v69 = runCatching {
                XposedHelpers.findField(act, V69.activityVmField)
                V69
            }.getOrNull()
            if (v69 != null) return V69
            return V610  // fall back; the 6.10 field names were verified on three builds
        }
    }
}