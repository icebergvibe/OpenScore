package org.openscore.health

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.openscore.net.SimpleXml
import org.openscore.net.XmlNode

/**
 * A response's structure as a set of key paths, so a live response can be compared with the
 * captured sample without asserting on values (which change every day).
 *
 * JSON paths: `games`, `games[]`, `games[].id`, `clock.running`. Arrays contribute the union of
 * their elements' keys, so an optional field present on any element counts. XML paths are
 * element names joined with `/`, attributes as `@name`: `schedule/tournament`,
 * `schedule/tournament/@tournament-id`.
 */
public object Shape {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Keys that samples carry but real responses never do (CONTRIBUTING.md truncation markers). */
    private val sampleOnlyKeys = setOf("_openscore_note", "_truncated_array")

    public fun parseJson(text: String): JsonElement = json.parseToJsonElement(text)

    /** A sample stored as `{"_openscore_note": …, "_truncated_array": [...]}` is served as the bare array. */
    public fun unwrapSample(element: JsonElement): JsonElement =
        (element as? JsonObject)?.get("_truncated_array") ?: element

    /**
     * @property paths every key path down to the requested depth
     * @property leaves paths whose value is `null` or an empty array/object in *this* response, so a
     *   comparison can excuse the children the sample has below them (an off-day `games: []`).
     */
    public data class JsonShape(val paths: Set<String>, val leaves: Set<String>) {
        /** Paths of [expected] this shape lacks, ignoring children of its own empty/null leaves. */
        public fun missing(expected: Set<String>): List<String> =
            (expected - paths).filterNot { p -> leaves.any { leaf -> p.startsWith("$leaf.") || p.startsWith("$leaf[]") } }.sorted()
    }

    public fun jsonShape(element: JsonElement, depth: Int, mapOfObjects: Boolean = false): JsonShape {
        val root = if (mapOfObjects) firstObjectValue(element) ?: return JsonShape(emptySet(), emptySet()) else element
        val out = LinkedHashSet<String>()
        val leaves = LinkedHashSet<String>()
        collect(root, "", depth, out, leaves)
        return JsonShape(out, leaves)
    }

    public fun jsonPaths(element: JsonElement, depth: Int, mapOfObjects: Boolean = false): Set<String> =
        jsonShape(element, depth, mapOfObjects).paths

    private fun firstObjectValue(element: JsonElement): JsonElement? = when (element) {
        is JsonObject -> element.values.firstOrNull { it is JsonObject }
        is JsonArray -> element.firstOrNull { it is JsonObject }
        else -> null
    }

    private fun collect(element: JsonElement, prefix: String, depth: Int, out: MutableSet<String>, leaves: MutableSet<String>) {
        if (depth <= 0) return
        when (element) {
            is JsonObject -> {
                if (element.isEmpty() && prefix.isNotEmpty()) leaves += prefix
                element.forEach { (k, v) ->
                    if (k in sampleOnlyKeys) return@forEach
                    val path = if (prefix.isEmpty()) k else "$prefix.$k"
                    out += path
                    if (v is JsonNull) leaves += path
                    collect(v, path, depth - 1, out, leaves)
                }
            }
            is JsonArray -> {
                if (prefix.isNotEmpty()) out += "$prefix[]"
                if (element.isEmpty()) leaves += prefix
                val path = "$prefix[]"
                // Union over elements: a field present on any element is part of the shape.
                for (e in element) collect(e, path, depth, out, leaves)
            }
            else -> Unit
        }
    }

    /**
     * Whether [path] (same grammar as [jsonPaths]) exists in [element]. Like the shape, arrays are
     * a union: `games[].id` holds if any game has a non-null `id`; `games[]` holds for an empty array.
     */
    public fun hasJsonPath(element: JsonElement, path: String): Boolean {
        var current: List<JsonElement> = listOf(element)
        val segments = path.split('.')
        for ((i, segment) in segments.withIndex()) {
            val key = segment.removeSuffix("[]")
            val wantArray = segment.endsWith("[]")
            val next = current.mapNotNull { e -> (if (key.isEmpty()) e else (e as? JsonObject)?.get(key))?.takeUnless { it is JsonNull } }
            if (next.isEmpty()) return false
            if (!wantArray) { current = next; continue }
            val arrays = next.filterIsInstance<JsonArray>()
            if (arrays.isEmpty()) return false
            current = arrays.flatten()
            if (current.isEmpty()) return i == segments.lastIndex
        }
        return true
    }

    public fun parseXml(text: String): XmlNode = SimpleXml.parse(text)

    public fun xmlPaths(node: XmlNode, depth: Int): Set<String> {
        val out = LinkedHashSet<String>()
        collectXml(node, "", depth, out)
        return out
    }

    private fun collectXml(node: XmlNode, prefix: String, depth: Int, out: MutableSet<String>) {
        if (depth <= 0) return
        val path = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
        out += path
        node.attributes.keys.forEach { out += "$path/@$it" }
        // Siblings with the same name share a path (a list); union their children like JSON arrays.
        node.children.forEach { collectXml(it, path, depth - 1, out) }
    }

    public fun hasXmlPath(node: XmlNode, path: String): Boolean {
        val segments = path.split('/')
        if (segments.first() != node.name) return false
        var current = listOf(node)
        for (segment in segments.drop(1)) {
            if (segment.startsWith("@")) return current.any { it.attributes.containsKey(segment.drop(1)) }
            current = current.flatMap { it.childrenNamed(segment) }
            if (current.isEmpty()) return false
        }
        return true
    }

    /** XML paths in [expected] that are not in [actual]. */
    public fun missingXml(expected: Set<String>, actual: Set<String>): List<String> = (expected - actual).sorted()
}
