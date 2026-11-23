package com.thatgfsj.qaa.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 工具参数解析失败，映射稳定错误码 E_ARGS_INVALID。 */
class ToolArgsException(message: String) : IllegalArgumentException(message)

/**
 * argsJson 解析辅助（docs/design/harness-core-design.md §3.3 通用约定：
 * args 为 JSON 对象，未知字段忽略，字符串一律 UTF-8）。
 */
internal object ToolArgs {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 args 为 JSON 对象；空串、非合法 JSON、非对象一律抛 [ToolArgsException]。 */
    fun parseObject(argsJson: String): JsonObject {
        val trimmed = argsJson.trim()
        if (trimmed.isEmpty()) throw ToolArgsException("args 为空")
        val element = try {
            json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            throw ToolArgsException("args 不是合法 JSON: " + (e.message ?: e.javaClass.simpleName))
        }
        return element as? JsonObject ?: throw ToolArgsException("args 必须是 JSON 对象")
    }

    /** 取必填字符串字段；缺失或非字符串类型抛 [ToolArgsException]。 */
    fun requireString(obj: JsonObject, key: String): String {
        val value = obj[key] ?: throw ToolArgsException("缺少必填字段: " + key)
        if (value !is JsonPrimitive || !value.isString) {
            throw ToolArgsException("字段 " + key + " 必须是字符串")
        }
        return value.content
    }

    /** 取可选字符串字段；缺失返回 null，存在但非字符串抛 [ToolArgsException]。 */
    fun optionalString(obj: JsonObject, key: String): String? {
        val value = obj[key] ?: return null
        if (value !is JsonPrimitive || !value.isString) {
            throw ToolArgsException("字段 " + key + " 必须是字符串")
        }
        return value.content
    }

    /** 取可选布尔字段；缺失返回默认值，存在但非布尔抛 [ToolArgsException]。 */
    fun optionalBoolean(obj: JsonObject, key: String, default: Boolean): Boolean {
        val value = obj[key] ?: return default
        if (value !is JsonPrimitive) throw ToolArgsException("字段 " + key + " 必须是布尔值")
        return value.booleanOrNull ?: throw ToolArgsException("字段 " + key + " 必须是布尔值")
    }
}
