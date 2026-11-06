package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.app.AndroidAppHelper
import android.content.Context
import android.os.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import java.lang.reflect.*

object OppoReflect {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    fun context(): Context? = AndroidAppHelper.currentApplication()
    fun postMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    fun read(instance: Any, vararg names: String): Any? {
        for (name in names) {
            val method = allMethods(instance.javaClass).firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) return invoke(method, instance)
            findField(instance.javaClass, name)?.let { it.isAccessible = true; return it.get(instance) }
        }
        return null
    }
    fun readString(instance: Any, vararg names: String) = read(instance, *names) as? String
    fun call(instance: Any, name: String, vararg args: Any?): Any? {
        val method = allMethods(instance.javaClass).firstOrNull {
            it.name == name && it.parameterCount == args.size && it.parameterTypes.zip(args).all { (type, value) -> accepts(type, value) }
        } ?: throw NoSuchMethodException("${instance.javaClass.name}.$name/${args.size}")
        return invoke(method, instance, *args)
    }
    fun callFirst(instance: Any, names: List<String>, vararg args: Any?): Boolean {
        for (name in names) try { call(instance, name, *args); return true }
        catch (_: NoSuchMethodException) { continue }
        catch (error: Exception) { BridgeLog.e("callback $name failed", error); return false }
        return false
    }
    fun set(instance: Any, property: String, value: Any?) {
        val name = "set" + property.replaceFirstChar { it.uppercase() }
        try { call(instance, name, value) }
        catch (_: NoSuchMethodException) {
            val field = findField(instance.javaClass, property) ?: throw NoSuchFieldException("${instance.javaClass.name}.$property")
            field.isAccessible = true
            field.set(instance, value)
        }
    }
    fun fieldByType(instance: Any, className: String) = fields(instance).firstOrNull { it.first.type.name == className }?.second
    fun fields(instance: Any): Sequence<Pair<Field, Any?>> = sequence {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                field.isAccessible = true
                yield(field to field.get(instance))
            }
            type = type.superclass
        }
    }
    fun allMethods(clazz: Class<*>): List<Method> {
        val methods = mutableListOf<Method>()
        var type: Class<*>? = clazz
        while (type != null) { methods += type.declaredMethods; type = type.superclass }
        return methods
    }
    private fun findField(clazz: Class<*>, name: String): Field? {
        var type: Class<*>? = clazz
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { return it }
            type = type.superclass
        }
        return null
    }
    private fun accepts(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        val boxed = when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> type
        }
        return boxed.isInstance(value)
    }
    private fun invoke(method: Method, instance: Any, vararg args: Any?): Any? {
        method.isAccessible = true
        try { return method.invoke(instance, *args) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
}
