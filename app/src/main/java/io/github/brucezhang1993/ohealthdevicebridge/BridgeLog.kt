package io.github.brucezhang1993.ohealthdevicebridge

import de.robv.android.xposed.XposedBridge
import io.github.brucezhang1993.ohealthdevicebridge.hook.oppo.ContextHolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BridgeLog {
    private const val FILE = "oh_bridge.log"
    private const val MAX_BYTES = 256 * 1024L
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun i(message: String) {
        XposedBridge.log("${BridgeConstants.LOG_TAG} $message")
        append(message)
    }

    fun e(message: String, error: Throwable? = null) {
        XposedBridge.log("${BridgeConstants.LOG_TAG} ERROR $message")
        if (error != null) XposedBridge.log(error)
        append("ERROR $message${error?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
    }

    fun maskedMac(mac: String): String {
        val p = mac.split(':')
        return if (p.size == 6) "${p[0]}:${p[1]}:**:**:${p[4]}:${p[5]}" else "**:**:**:**:**:**"
    }

    /** Mirror to a file so the flow can be checked without adb / a PC connection. */
    private fun append(line: String) {
        runCatching {
            val ctx = ContextHolder.get() ?: return
            val dir = runCatching { ctx.getExternalFilesDir(null) }.getOrNull() ?: ctx.filesDir
            val file = File(dir, FILE)
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText("${stamp.format(Date())} $line\n")
        }
    }
}
