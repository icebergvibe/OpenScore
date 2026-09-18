package org.openscore.providers.mlb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Minimal RFC 6902 engine for MLB's `/diffPatch` response. */
internal object MlbJsonPatch {
    fun apply(document: JsonElement, patchSets: JsonArray): JsonElement = patchSets.fold(document) { current, patchSet ->
        val operations = patchSet.jsonObject["diff"] as? JsonArray
            ?: throw IllegalArgumentException("MLB diffPatch set has no diff array")
        operations.fold(current, ::applyOperation)
    }

    private fun applyOperation(document: JsonElement, raw: JsonElement): JsonElement {
        val operation = raw.jsonObject
        val op = operation.string("op")
        val path = pointer(operation.string("path"))
        return when (op) {
            "add" -> add(document, path, operation.value())
            "remove" -> remove(document, path)
            "replace" -> replace(document, path, operation.value())
            "copy" -> add(document, path, at(document, pointer(operation.string("from"))))
            "move" -> {
                val from = pointer(operation.string("from"))
                require(path != from && path.take(from.size) != from) { "Cannot move a JSON value into itself" }
                add(remove(document, from), path, at(document, from))
            }
            "test" -> {
                require(at(document, path) == operation.value()) { "MLB diffPatch test failed at ${operation.string("path")}" }
                document
            }
            else -> throw IllegalArgumentException("Unsupported MLB diffPatch operation '$op'")
        }
    }

    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("MLB diffPatch has no '$name'")

    private fun JsonObject.value(): JsonElement = this["value"]
        ?: throw IllegalArgumentException("MLB diffPatch operation has no value")

    private fun pointer(raw: String): List<String> = when (raw) {
        "" -> emptyList()
        else -> {
            require(raw.startsWith('/')) { "Invalid JSON pointer '$raw'" }
            raw.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
        }
    }

    private fun at(document: JsonElement, path: List<String>): JsonElement = path.fold(document) { current, part ->
        when (current) {
            is JsonObject -> current[part] ?: throw IllegalArgumentException("No JSON object member '$part'")
            is JsonArray -> current[index(part, current.size)]
            else -> throw IllegalArgumentException("Cannot descend into JSON primitive")
        }
    }

    private fun add(document: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        return editParent(document, path) { parent, token ->
            when (parent) {
                is JsonObject -> JsonObject(parent + (token to value))
                is JsonArray -> {
                    val i = if (token == "-") parent.size else insertionIndex(token, parent.size)
                    JsonArray(parent.take(i) + value + parent.drop(i))
                }
                else -> throw IllegalArgumentException("Cannot add into JSON primitive")
            }
        }
    }

    private fun remove(document: JsonElement, path: List<String>): JsonElement {
        require(path.isNotEmpty()) { "Cannot remove the document root" }
        return editParent(document, path) { parent, token ->
            when (parent) {
                is JsonObject -> {
                    require(token in parent) { "No JSON object member '$token'" }
                    JsonObject(parent - token)
                }
                is JsonArray -> JsonArray(parent.filterIndexed { i, _ -> i != index(token, parent.size) })
                else -> throw IllegalArgumentException("Cannot remove from JSON primitive")
            }
        }
    }

    private fun replace(document: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        return editParent(document, path) { parent, token ->
            when (parent) {
                is JsonObject -> {
                    require(token in parent) { "No JSON object member '$token'" }
                    JsonObject(parent + (token to value))
                }
                is JsonArray -> {
                    val i = index(token, parent.size)
                    JsonArray(parent.mapIndexed { index, current -> if (index == i) value else current })
                }
                else -> throw IllegalArgumentException("Cannot replace in JSON primitive")
            }
        }
    }

    private fun editParent(document: JsonElement, path: List<String>, edit: (JsonElement, String) -> JsonElement): JsonElement {
        fun descend(current: JsonElement, position: Int): JsonElement {
            if (position == path.lastIndex) return edit(current, path[position])
            val token = path[position]
            val child = when (current) {
                is JsonObject -> current[token] ?: throw IllegalArgumentException("No JSON object member '$token'")
                is JsonArray -> current[index(token, current.size)]
                else -> throw IllegalArgumentException("Cannot descend into JSON primitive")
            }
            val changed = descend(child, position + 1)
            return when (current) {
                is JsonObject -> JsonObject(current + (token to changed))
                is JsonArray -> JsonArray(current.mapIndexed { i, item -> if (i == index(token, current.size)) changed else item })
            }
        }
        return descend(document, 0)
    }

    private fun index(token: String, size: Int): Int = token.toIntOrNull()?.also {
        require(it in 0 until size) { "JSON array index '$token' is out of range" }
    } ?: throw IllegalArgumentException("Invalid JSON array index '$token'")

    private fun insertionIndex(token: String, size: Int): Int = token.toIntOrNull()?.also {
        require(it in 0..size) { "JSON array insertion index '$token' is out of range" }
    } ?: throw IllegalArgumentException("Invalid JSON array insertion index '$token'")
}
