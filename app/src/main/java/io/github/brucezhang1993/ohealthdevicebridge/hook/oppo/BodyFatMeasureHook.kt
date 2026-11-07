package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Drives OPPO's own body-fat measure page with readings from a Mi Body Composition Scale.
 *
 * ## Native chain (reverse engineered, see OPPO_WEIGHT_FLOW_REVERSE.md)
 *
 *   BLE WeightData_A3
 *     -> com.lifesense.device.scale.utils.b$a.run()
 *     -> ma1.a.onReceiveWeightData(WeightData)          // fan-out to subscribers
 *     -> BodyFatMeasureViewModel.f(WeightData)          // postValue(n(weight, true))
 *     -> com.heytap.device.ui.weight.c.onChanged()      // i=0 branch, payload n
 *     -> BodyFatMeasureActivity.u6(runnable, tag)       // REQUIRES vm.w != null && vm.x > 0
 *     -> l.call()                                       // builds WeightBodyFat, inserts row 1021
 *     -> d.run() -> w6(D)                               // "measurement settled"
 *     -> h.run(): vendor == LIFESENSE ? y6() : A6()
 *     -> e.run(): case 0/1 -> finish(); case 2 -> p6()  // p6 opens the analysis screen
 *
 * The single reason a Xiaomi scale never worked is [u6]'s guard:
 *
 *     if (vm.w == null || vm.x <= 0) return;
 *
 * `vm.w` (a BHScaleModel) is only produced by the Lifesense/Boohee raw-data callbacks. A Xiaomi
 * scale never runs those, so `vm.w` stays null, `u6()` silently returns, and the page sits on
 * "正在测量" forever with nothing in the log.
 *
 * So the module's one real job is: build a BHScaleModel for each reading with OPPO's own algorithm
 * entry points and put it where `u6()` will find it.
 *
 * Everything else the module used to do either did nothing or broke the flow:
 *  - `utils.y.h()` raises "体重差距过大，请确认是您本人", and confirming it is the *only* route to
 *    `e.run case 2 -> p6() -> BodyCompositionDetailActivity`. Blocking it removes the destination.
 *  - Forcing the completion runnable's flag only changes "finish now" into "finish after 6s".
 *  - Calling `w6()` directly skips the insert, so `vm.z`/`vm.A`/`vm.B` stay null and `p6()` falls
 *    through to the unclaimed list.
 *  - Hopping processes with a Parcelable record crashes on classloader mismatch.
 *
 * ## Version coverage
 *
 * Every class and method name used here is identical in OPPO Health 6.10.13, 6.10.14 and 6.10.15
 * (6.10.13_16d7513_261030 and 6.10.14_0bb104d_261105 verified byte-level; 6.10.15 by signature).
 * Nothing is version-gated, and every optional step is wrapped so an unexpected obfuscation change
 * degrades instead of crashing.
 */
object BodyFatMeasureHook {
    private const val ACTIVITY = "com.heytap.device.ui.weight.BodyFatMeasureActivity"
    private const val VIEW_MODEL = "com.heytap.device.ui.weight.BodyFatMeasureViewModel"

    /** UI value holder posted to `vm.l`: n(weight, settled). */
    private const val WEIGHT_VALUE = "com.heytap.device.ui.weight.n"

    private const val WEIGHT_DATA = "com.lifesense.device.scale.device.dto.receive.WeightData"

    /** The 30-field body-fat model that `u6()` insists on. */
    private const val BH_SCALE_MODEL = "com.boohee.scale_sdk.data.BHScaleModel"
    private const val BH_USER_MODEL = "com.boohee.scale_sdk.user.BHUserModel"
    private const val BH_USER_BUILDER = "com.boohee.scale_sdk.user.BHUserModel\$Builder"
    private const val BH_USER_GENDER = "com.boohee.scale_sdk.user.BHUserGender"

    /** OPPO's own "raw weight + impedance + profile -> full body-fat model" converter. */
    private const val WEIGHT_DATA_HELPER = "com.boohee.scale_sdk.BHWeightDataHelper"

    /** OPPO's own g0 -> BHUserModel converter. */
    private const val USER_MODEL_MAPPER = "com.heytap.device.ui.weight.scale.boohee.h0"
    private const val USER_PROFILE = "com.heytap.device.ui.weight.utils.g0"

    /** OPPO's own profile lookup (owner / family member). */
    private const val PROFILE_HELPER = "com.heytap.device.ui.weight.utils.h0"

    private const val MEMBER_INFO = "com.heytap.deviceengine.model.weight.FamilyMemberInfo"

    /** Neutral height used only when the member profile carries none. */
    private const val FALLBACK_HEIGHT_CM = 170f

    private const val TABLE_FAMILY_MEMBER = 1020

    /**
     * The BLE loop lives in :SportDaemonService while the measure page lives in the main process,
     * so the ViewModel reference cannot be shared through a static field. Readings travel as a
     * package-scoped broadcast and are replayed onto the ViewModel here.
     */
    private const val ACTION_LIVE = "io.github.brucezhang1993.ohealthdevicebridge.MEASURE_LIVE"
    private const val EXTRA_WEIGHT = "weight"
    private const val EXTRA_RESISTANCE = "resistance"
    private const val EXTRA_STABLE = "stable"

    /** ViewModel of the currently open measure page (main process only), or null. */
    @Volatile private var viewModel: Any? = null

    @Volatile private var receiverRegistered = false

    /** Version surface of the running OPPO Health, detected once. */
    @Volatile private var profile: OppoVersionProfile = OppoVersionProfile.V610

    /** Cached BHUserModel; the weight field is refreshed per reading. */
    @Volatile private var userModel: Any? = null
    @Volatile private var userModelResolved = false

    fun install(cl: ClassLoader, appContext: android.content.Context? = null) {
        runCatching {
            profile = OppoVersionProfile.detect(cl)
            BridgeLog.i("measure hook: version profile=${profile.label}")

            val act = XposedHelpers.findClass(ACTIVITY, cl)
            XposedBridge.hookAllMethods(act, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val vm = runCatching {
                        XposedHelpers.getObjectField(param.thisObject, profile.activityVmField)
                    }.getOrNull()
                    if (vm != null) {
                        viewModel = vm
                        // Fresh page, fresh profile: the selected member may have changed.
                        userModel = null
                        userModelResolved = false
                        BridgeLog.i("measure page opened; viewModel captured")
                    }
                }
            })
            XposedBridge.hookAllMethods(act, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (viewModel != null) BridgeLog.i("measure page closed")
                    viewModel = null
                }
            })
            // 6.10.x only: u6() silently refuses a reading when vm.w/x are not set. Log that
            // gate so a regressions shows up instead of hanging the page.
            if (profile == OppoVersionProfile.V610) {
                XposedBridge.hookAllMethods(act, "u6", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val vm = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, profile.activityVmField)
                        }.getOrNull() ?: return
                        val model = runCatching { XposedHelpers.getObjectField(vm, profile.vmModelField) }.getOrNull()
                        val weight = runCatching { XposedHelpers.getDoubleField(vm, profile.vmWeightField) }.getOrDefault(0.0)
                        BridgeLog.i("u6 called model=${model != null} weight=$weight")
                    }
                })
            }
            registerReceiver(cl, appContext)
            BridgeLog.i("measure hook installed on BodyFatMeasureActivity")
        }.onFailure { BridgeLog.e("measure hook failed", it as? Exception) }
    }

    /** True while OPPO's measure page is in the foreground (main process only). */
    fun isMeasuring(): Boolean = viewModel != null

    /** Listens for readings broadcast by the daemon process and renders them on the page. */
    private fun registerReceiver(cl: ClassLoader, appContext: android.content.Context?) {
        if (receiverRegistered) return
        val ctx = appContext ?: OppoReflect.context()
        if (ctx == null) { BridgeLog.i("measure receiver: no context yet"); return }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                if (intent == null || intent.action != ACTION_LIVE) return
                val w = intent.getDoubleExtra(EXTRA_WEIGHT, 0.0)
                val r = intent.getDoubleExtra(EXTRA_RESISTANCE, 0.0)
                val stable = intent.getBooleanExtra(EXTRA_STABLE, false)
                if (w > 0) pushLiveLocal(cl, w, r, stable)
            }
        }
        runCatching {
            val filter = android.content.IntentFilter(ACTION_LIVE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            BridgeLog.i("measure receiver registered")
        }.onFailure { BridgeLog.e("measure receiver failed", it as? Exception) }
    }

    /**
     * Send a live reading from the BLE process to the UI process. Safe to call from either side;
     * harmless when no measure page is open (the receiver drops it because viewModel is null).
     */
    fun broadcastLive(ctx: android.content.Context, weightKg: Double, resistanceOhm: Double, stable: Boolean = false) {
        runCatching {
            val intent = android.content.Intent(ACTION_LIVE).apply {
                setPackage("com.heytap.health")
                putExtra(EXTRA_WEIGHT, weightKg)
                putExtra(EXTRA_RESISTANCE, resistanceOhm)
                putExtra(EXTRA_STABLE, stable)
            }
            ctx.sendBroadcast(intent)
        }
    }

    /**
     * Push one reading onto the ViewModel; must run in the process that owns the page.
     *
     * Two things have to happen together, and skipping either one is why earlier attempts stalled:
     *
     * 1. The settle guard must be satisfied. On 6.10.x that is `vm.w != null && vm.x > 0`, which
     *    `u6()` checks before it will persist anything; the model comes from OPPO's own converter.
     *    6.9.x has no such guard, but it still needs the raw model so its own write path can
     *    compute the body-fat metrics.
     * 2. The value posted to the measure LiveData must carry the real `stable` flag. On both
     *    versions the native sink hardcodes the settled state, so the module posts the value
     *    itself: `n(weight, stable)` on 6.10.x, `MeasureEvent(weight, stable, LIFESENSE)` on 6.9.x.
     */
    private fun pushLiveLocal(cl: ClassLoader, weightKg: Double, resistanceOhm: Double, stable: Boolean) {
        val vm = viewModel ?: return
        runCatching {
            feedScaleModel(cl, vm, weightKg, resistanceOhm)

            val value = buildMeasureValue(cl, weightKg, stable)
            val liveData = runCatching {
                XposedHelpers.getObjectField(vm, profile.vmMeasureLiveDataField)
            }.getOrNull()
            if (liveData == null) {
                BridgeLog.i("measure LiveData field '${profile.vmMeasureLiveDataField}' missing")
            } else {
                liveData.javaClass.getMethod("postValue", Any::class.java).invoke(liveData, value)
            }
            BridgeLog.i("measure page live %.2f kg stable=$stable".format(weightKg))
        }.onFailure { BridgeLog.e("measure push failed", it as? Exception) }
    }

    /** Builds the value object carried by the measure LiveData for the detected version. */
    private fun buildMeasureValue(cl: ClassLoader, weightKg: Double, stable: Boolean): Any? = runCatching {
        when (profile) {
            OppoVersionProfile.V610 -> {
                cl.loadClass(WEIGHT_VALUE)
                    .getConstructor(Double::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .newInstance(weightKg, stable)
            }
            OppoVersionProfile.V69 -> {
                // MeasureEvent(D weightKg, Z locked, ScaleVendor vendor)
                val vendor = cl.loadClass("com.heytap.device.ui.weight.scale.ScaleVendor")
                val lifespense = vendor.getField("LIFESENSE").get(null)
                cl.loadClass("com.heytap.device.ui.weight.BodyFatMeasureViewModel\$MeasureEvent")
                    .getConstructor(
                        Double::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        vendor
                    )
                    .newInstance(weightKg, stable, lifespense)
            }
        }
    }.onFailure { BridgeLog.e("measure value build failed", it as? Exception) }.getOrNull()

    /**
     * Populates the ViewModel's raw-model / weight fields so the write path can persist the
     * reading and (on 6.10.x) `u6()` accepts it.
     */
    private fun feedScaleModel(cl: ClassLoader, vm: Any, weightKg: Double, resistanceOhm: Double) {
        val model = buildScaleModel(cl, vm, weightKg, resistanceOhm)
        if (model == null) {
            BridgeLog.i("scale model unavailable, write path will not run")
            return
        }
        XposedHelpers.setObjectField(vm, profile.vmModelField, model)
        XposedHelpers.setDoubleField(vm, profile.vmWeightField, weightKg)
    }

    /**
     * Builds a complete BHScaleModel for one reading.
     *
     * `BHWeightDataHelper.handleScaleModelDetailByScaleModel(raw, user)` is OPPO's own
     * "weight + impedance + profile -> every body-composition metric" implementation; BHScaleManager
     * uses it too. Reusing it keeps the stored WeightBodyFat identical to a native measurement.
     */
    private fun buildScaleModel(cl: ClassLoader, vm: Any, weightKg: Double, resistanceOhm: Double): Any? {
        return runCatching {
            val modelCls = cl.loadClass(BH_SCALE_MODEL)
            val raw = modelCls.getConstructor().newInstance()
            modelCls.getField("weight").setFloat(raw, weightKg.toFloat())
            if (resistanceOhm > 0) {
                modelCls.getField("bodyResistance").setFloat(raw, resistanceOhm.toFloat())
            }
            // second is the sample timestamp in seconds; l.call() passes it into the row.
            modelCls.getField("second").setLong(raw, System.currentTimeMillis() / 1000)
            modelCls.getField("isLockData").setBoolean(raw, false)
            modelCls.getField("isHistory").setBoolean(raw, false)
            attachDeviceMac(cl, modelCls, raw, vm)

            val user = resolveUserModel(cl, vm, weightKg)
            if (user == null) {
                // No profile: weight/BMI only. The row stays valid and the page can still finish.
                return raw
            }
            runCatching {
                val helper = cl.loadClass(WEIGHT_DATA_HELPER)
                helper.getMethod(
                    "handleScaleModelDetailByScaleModel", modelCls, cl.loadClass(BH_USER_MODEL)
                ).invoke(null, raw, user) as Any?
            }.onFailure { BridgeLog.e("body-fat computation failed", it as? Exception) }.getOrNull() ?: raw
        }.onFailure { BridgeLog.e("scale model build failed", it as? Exception) }.getOrNull()
    }

    private fun attachDeviceMac(cl: ClassLoader, modelCls: Class<*>, raw: Any, vm: Any) {
        runCatching {
            val mac = XposedHelpers.getObjectField(vm, profile.vmMacField) as? String
            if (mac.isNullOrBlank()) return
            val field = modelCls.getField("deviceModel")
            val deviceType = field.type
            val device = deviceType.getConstructor().newInstance()
            runCatching { deviceType.getField("deviceMac").set(device, mac) }
            field.set(raw, device)
        }
    }

    /**
     * Resolves the BHUserModel used by the body-fat algorithm, preferring OPPO's own profile chain:
     *
     *     utils.h0.f() / utils.h0.g(tag)  -> g0 (owner or member profile)
     *     boohee.h0.a(g0, weight)         -> BHUserModel
     *
     * If that yields nothing (incomplete member profile), a minimal model is built from the
     * FamilyMemberInfo row so the algorithm still has height/age/sex to work with.
     */
    private fun resolveUserModel(cl: ClassLoader, vm: Any, weightKg: Double): Any? {
        if (userModelResolved) {
            val cached = userModel ?: return null
            return refreshWeight(cl, cached, weightKg)
        }
        userModelResolved = true
        val tag = runCatching { XposedHelpers.getObjectField(vm, profile.vmMemberTagField) as? String }.getOrNull()

        userModel = runCatching {
            val profileHelper = cl.loadClass(PROFILE_HELPER)
            val instance = profileHelper.getField("INSTANCE").get(null)
            val g0: Any? = if (tag.isNullOrBlank()) {
                profileHelper.getMethod("f").invoke(instance)
            } else {
                profileHelper.getMethod("g", String::class.java).invoke(instance, tag)
            }
            if (g0 == null) return@runCatching null
            val mapper = cl.loadClass(USER_MODEL_MAPPER)
            val mapperInstance = mapper.getField("INSTANCE").get(null)
            mapper.getMethod("a", cl.loadClass(USER_PROFILE), Float::class.javaObjectType)
                .invoke(mapperInstance, g0, weightKg.toFloat())
        }.onFailure { BridgeLog.e("profile lookup failed", it as? Exception) }.getOrNull()

        if (userModel != null) {
            BridgeLog.i("profile resolved from OPPO member list")
            return userModel
        }
        val built = buildFallbackUserModel(cl, tag, weightKg)
        userModel = built
        if (built != null) BridgeLog.i("profile built from FamilyMemberInfo fallback")
        return built
    }

    /** Keeps the cached profile's weight in sync with the current reading. */
    private fun refreshWeight(cl: ClassLoader, user: Any, weightKg: Double): Any? = runCatching {
        cl.loadClass(BH_USER_MODEL).getField("weight").setFloat(user, weightKg.toFloat())
        user
    }.getOrElse { null }

    /**
     * Minimal BHUserModel from the bound member's FamilyMemberInfo, which already carries
     * birthday / height / sex. Age is derived from birthday; anything missing falls back to a
     * neutral value so the computation still runs instead of leaving body fat empty.
     */
    private fun buildFallbackUserModel(cl: ClassLoader, tag: String?, weightKg: Double): Any? = runCatching {
        val member = findMember(cl, tag)
        val builderCls = cl.loadClass(BH_USER_BUILDER)
        val builder = builderCls.getConstructor().newInstance()
        builderCls.getMethod("setAge", Int::class.javaPrimitiveType).invoke(builder, memberAge(member))
        builderCls.getMethod("setHeight", Float::class.javaPrimitiveType).invoke(builder, memberHeight(member))
        builderCls.getMethod("setWeight", Float::class.javaPrimitiveType).invoke(builder, weightKg.toFloat())
        memberSex(cl, member)?.let {
            builderCls.getMethod("setSex", cl.loadClass(BH_USER_GENDER)).invoke(builder, it)
        }
        builderCls.getMethod("build").invoke(builder)
    }.onFailure { BridgeLog.e("fallback profile build failed", it as? Exception) }.getOrNull()

    private fun findMember(cl: ClassLoader, tag: String?): Any? = runCatching {
        if (tag.isNullOrBlank()) return@runCatching null
        val apiCls = cl.loadClass("com.heytap.databaseengine.api.SportHealthDataAPI")
        val api = apiCls.getMethod("getInstance").invoke(null)
        val readCls = cl.loadClass("com.heytap.databaseengine.option.DataReadOption")
        val opt = readCls.getConstructor().newInstance()
        readCls.getMethod("setDataTable", Int::class.javaPrimitiveType).invoke(opt, TABLE_FAMILY_MEMBER)
        val obs = apiCls.getMethod("readSportHealthData", readCls).invoke(api, opt)
        val list = runCatching { obs.javaClass.getMethod("blockingFirst").invoke(obs) }.getOrNull()
        (list as? List<*>)?.filterIsInstance<Any>()?.firstOrNull {
            runCatching { it.javaClass.getMethod("getUserTagId").invoke(it) as? String }
                .getOrNull() == tag
        }
    }.getOrNull()

    private fun memberAge(member: Any?): Int = runCatching {
        val birthday = member?.javaClass?.getMethod("getBirthday")?.invoke(member) as? String
        if (birthday.isNullOrBlank()) return 30
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val date = fmt.parse(birthday.take(10))
        val years = (System.currentTimeMillis() - date.time) / (365.25 * 24 * 3600 * 1000L)
        years.toInt().coerceIn(10, 100)
    }.getOrDefault(30)

    private fun memberHeight(member: Any?): Float = runCatching {
        val raw = member?.javaClass?.getMethod("getHeight")?.invoke(member) as? String
        val cm = raw?.trim()?.toFloatOrNull() ?: return FALLBACK_HEIGHT_CM
        // Stored as cm; guard against metres so the algorithm is not fed a 1.7 cm body.
        if (cm in 50f..250f) cm else if (cm in 1f..2.5f) cm * 100f else FALLBACK_HEIGHT_CM
    }.getOrDefault(FALLBACK_HEIGHT_CM)

    private fun memberSex(cl: ClassLoader, member: Any?): Any? = runCatching {
        val raw = member?.javaClass?.getMethod("getSex")?.invoke(member) as? String
        // FamilyMemberInfo.sex is "1" (male) / "2" (female) in OPPO's schema.
        val male = raw == "1" || raw.equals("male", true) || raw == "M"
        val values = cl.loadClass(BH_USER_GENDER).enumConstants as? Array<*> ?: return null
        values.filterIsInstance<Enum<*>>().firstOrNull {
            val n = it.name.uppercase()
            if (male) n.startsWith("M") else n.startsWith("F")
        }
    }.getOrNull()
}