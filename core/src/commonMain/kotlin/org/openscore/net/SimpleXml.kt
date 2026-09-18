package org.openscore.net

/**
 * A deliberately small XML reader for attribute-oriented feeds (Fogis livescore): elements,
 * attributes, text and entities. No namespaces, DTDs or processing beyond skipping the
 * prolog and comments. Good enough for the shapes documented under apis/; not a general parser.
 */
public class XmlNode(
    public val name: String,
    public val attributes: Map<String, String>,
    public val children: List<XmlNode>,
    public val text: String,
) {
    public operator fun get(attribute: String): String? = attributes[attribute]
    public fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    public fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }
    public fun int(attribute: String): Int? = attributes[attribute]?.trim()?.toIntOrNull()
    public fun bool(attribute: String): Boolean? = attributes[attribute]?.trim()?.lowercase()?.toBooleanStrictOrNull()
}

public object SimpleXml {

    public fun parse(text: String): XmlNode {
        val p = Parser(text)
        p.skipProlog()
        return p.element() ?: throw IllegalArgumentException("No root element")
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipProlog() {
            while (true) {
                skipWs()
                when {
                    s.startsWith("<?", i) -> i = s.indexOf("?>", i).let { if (it < 0) s.length else it + 2 }
                    s.startsWith("<!--", i) -> i = s.indexOf("-->", i).let { if (it < 0) s.length else it + 3 }
                    s.startsWith("<!", i) -> i = s.indexOf(">", i).let { if (it < 0) s.length else it + 1 }
                    else -> return
                }
            }
        }

        fun element(): XmlNode? {
            skipWs()
            if (i >= s.length || s[i] != '<') return null
            i++ // <
            val name = readName()
            val attrs = LinkedHashMap<String, String>()
            while (true) {
                skipWs()
                if (i >= s.length) break
                if (s[i] == '/') { i += 2; return XmlNode(name, attrs, emptyList(), "") } // />
                if (s[i] == '>') { i++; break }
                val an = readName()
                skipWs()
                if (i < s.length && s[i] == '=') {
                    i++; skipWs()
                    val q = s.getOrNull(i) ?: malformed("attribute '$an' of <$name> has no value")
                    i++
                    val end = s.indexOf(q, i).also { if (it < 0) malformed("attribute '$an' of <$name> is not closed") }
                    attrs[an] = decode(s.substring(i, end))
                    i = end + 1
                } else attrs[an] = ""
            }
            val children = ArrayList<XmlNode>()
            val text = StringBuilder()
            while (i < s.length) {
                if (s.startsWith("</", i)) {
                    // A close tag cut off by a truncated body must not rewind to 0 and re-parse forever.
                    val close = s.indexOf('>', i).also { if (it < 0) malformed("close tag of <$name> is not terminated") }
                    i = close + 1
                    return XmlNode(name, attrs, children, decode(text.toString()).trim())
                }
                if (s.startsWith("<!--", i)) { i = s.indexOf("-->", i).let { if (it < 0) s.length else it + 3 }; continue }
                if (s.startsWith("<![CDATA[", i)) {
                    val end = s.indexOf("]]>", i).also { if (it < 0) malformed("CDATA section in <$name> is not terminated") }
                    text.append(s, i + 9, end); i = end + 3; continue
                }
                if (s[i] == '<') { element()?.let(children::add); continue }
                text.append(s[i]); i++
            }
            return XmlNode(name, attrs, children, decode(text.toString()).trim())
        }

        private fun malformed(detail: String): Nothing = throw IllegalArgumentException("Malformed XML: $detail")

        private fun readName(): String {
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/' && s[i] != '=') i++
            return s.substring(start, i)
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }

    public fun decode(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val end = text.indexOf(';', i)
                if (end > i) {
                    val entity = text.substring(i + 1, end)
                    val decoded = when (entity) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        else -> when {
                            entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)?.let(::charSequence)
                            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::charSequence)
                            else -> null
                        }
                    }
                    if (decoded != null) { out.append(decoded); i = end + 1; continue }
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    private fun charSequence(codePoint: Int): String = buildString { appendCodePoint(codePoint) }
}

private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
    if (cp < 0x10000) append(cp.toChar()) else {
        val v = cp - 0x10000
        append((0xD800 + (v shr 10)).toChar()); append((0xDC00 + (v and 0x3FF)).toChar())
    }
    return this
}
