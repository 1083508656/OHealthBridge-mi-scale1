package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Picks the family member a measurement belongs to by nearest reference weight, and hands back
 * that member's real profile row (sex / height / birthday / last_weight) so the body-composition
 * pipeline calculates with the member's own data instead of the main account's.
 *
 * Member rows, their order and their profiles are owned by OPPO Health's own database. This
 * class only reads them; it never creates, reorders or rewrites any member data.
 */
object FamilyMatcher {
    data class Member(
        val tag: String,
        val name: String?,
        val sex: String?,          // OPPO family rows: "0"=male, "1"=female; account rows: "M"/"F"
        val heightMm: Int,         // 1730 = 173cm
        val birthday: String?,     // yyyy-MM-dd
        val lastWeight: Double,
    ) {
        val male: Boolean get() = sex == "M" || sex == "0"
        val age: Int
            get() {
                val y = birthday?.take(4)?.toIntOrNull() ?: return 30
                val m = birthday.substringAfter('-', "01").take(2).toIntOrNull() ?: 1
                val d = birthday.substringAfterLast('-', "01").take(2).toIntOrNull() ?: 1
                val cal = java.util.Calendar.getInstance()
                val nowY = cal.get(java.util.Calendar.YEAR)
                val nowM = cal.get(java.util.Calendar.MONTH) + 1
                val nowD = cal.get(java.util.Calendar.DAY_OF_MONTH)
                var a = nowY - y
                if (nowM < m || (nowM == m && nowD < d)) a--
                return a.coerceIn(5, 120)
            }
    }

    fun match(cl: ClassLoader, ctx: Context, weightKg: Double, defaultTag: String): Member {
        val members = all(cl, ctx)
        if (members.isEmpty()) {
            BridgeLog.i("MATCH no member rows, fallback main tag=${defaultTag.take(8)}…")
            return Member(defaultTag, null, null, 0, null, 0.0)
        }
        val main = members.firstOrNull { it.tag == defaultTag }
        val candidates = members.filter { it.lastWeight > 0.0 }
        val best = candidates.minByOrNull { Math.abs(it.lastWeight - weightKg) }
        if (best == null) {
            BridgeLog.i("MATCH no reference weight, fallback main tag=${defaultTag.take(8)}…")
            return main ?: Member(defaultTag, null, null, 0, null, 0.0)
        }
        val diff = Math.abs(best.lastWeight - weightKg)
        // Guard against a wild match: beyond 15kg the row is not a plausible owner.
        if (diff > 15.0 && main != null) {
            BridgeLog.i("MATCH w=$weightKg best=${best.name} diff=$diff too far, fallback main")
            return main
        }
        BridgeLog.i("MATCH w=$weightKg -> ${best.name} tag=${best.tag.take(8)}… diff=$diff " +
            "sex=${best.sex} height=${best.heightMm}mm birthday=${best.birthday} ref=${best.lastWeight}kg")
        return best
    }

    /** All live member rows for the signed-in account (read-only; order is not relied upon). */
    fun all(cl: ClassLoader, ctx: Context): List<Member> {
        val db = dbHandle(cl, ctx) ?: return emptyList()
        return runCatching {
            val cursor = db.javaClass.getMethod("rawQuery", String::class.java, Array<String>::class.java)
                .invoke(db, "SELECT user_tag_id, user_name, sex, height, birthday, last_weight FROM DBFamilyMemberInfoTable WHERE deleted=0 ORDER BY created_timestamp ASC", null)!!
            val cls = cursor.javaClass
            val moveNext = cls.getMethod("moveToNext")
            val getString = cls.getMethod("getString", Int::class.javaPrimitiveType)
            val out = ArrayList<Member>()
            while (moveNext.invoke(cursor) as Boolean) {
                val tag = getString.invoke(cursor, 0) as? String ?: continue
                val name = getString.invoke(cursor, 1) as? String
                val sex = getString.invoke(cursor, 2) as? String
                val height = (getString.invoke(cursor, 3) as? String)?.toIntOrNull() ?: 0
                val birthday = getString.invoke(cursor, 4) as? String
                val lastWeight = (getString.invoke(cursor, 5) as? String)?.toDoubleOrNull() ?: 0.0
                out.add(Member(tag, name, sex, height, birthday, lastWeight))
            }
            runCatching { cls.getMethod("close").invoke(cursor) }
            out
        }.onFailure { BridgeLog.e("MATCH read members failed", it as? Exception) }.getOrDefault(emptyList())
    }

    private fun dbHandle(cl: ClassLoader, ctx: Context): Any? = runCatching {
        val appDb = cl.loadClass("com.heytap.databaseengineservice.db.AppDatabase")
            .getMethod("G", Context::class.java).invoke(null, ctx) ?: return null
        val helper = appDb.javaClass.getMethod("getOpenHelper").invoke(appDb)
        helper!!.javaClass.getMethod("getWritableDatabase").invoke(helper)
    }.getOrNull()
}
