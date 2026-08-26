package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelIdentity
import com.discountscreener.core.runtime.ModelInput
import com.discountscreener.core.runtime.ModelOutput
import com.discountscreener.core.runtime.ModelSource
import java.security.MessageDigest

/**
 * Activity-diagram factory. The first dialect this runtime ships.
 *
 * A later dialect is another factory. It does not change [PumlModel] or [PumlEngine].
 */
object ActivityPumlModelFactory : PumlModelFactory {
    override fun load(source: PumlSource, host: PumlHost): PumlModel {
        var document = parseActivityDocument(source.text)
        var identity = ModelIdentity(
            id = document.title,
            version = "puml-runtime/1",
            source = ModelSource.Puml(uri = source.uri, sha256 = sha256(source.text)),
        )
        return BoundPumlModel(identity, document, InterpretingPumlEngine, host)
    }
}

internal class BoundPumlModel(
    override val identity: ModelIdentity,
    override val document: PumlDocument,
    private val engine: PumlEngine,
    private val host: PumlHost,
) : PumlModel {
    override fun evaluate(input: ModelInput): ModelOutput = engine.run(document, input, host)
}

internal fun parseActivityDocument(text: String): PumlDocument {
    require(text.contains("@startuml")) { "PUML source must start a plantuml document" }
    require(text.contains("@enduml")) { "PUML source must close the plantuml document" }
    var title = Regex("""^title\s+(.+)$""", RegexOption.MULTILINE).find(text)
        ?.groupValues?.get(1)?.trim()
        ?: error("PUML activity document requires a title")
    var legend = extractLegend(text)
    var body = stripIgnored(text)
    var lines = logicalLines(body)
    var parser = ActivityParser(lines)
    var partitions = parser.parsePartitions()
    require(partitions.isNotEmpty()) { "PUML activity document requires at least one partition" }
    return PumlDocument(
        title = title,
        partitions = partitions,
        legend = legend,
        sourceText = text,
        tables = extractTables(text),
    )
}

private fun extractTables(text: String): Map<String, List<String>> {
    var tables = LinkedHashMap<String, List<String>>()
    var notes = Regex(
        """note (?:right|left)\s*(.*?)\s*end note""",
        RegexOption.DOT_MATCHES_ALL,
    ).findAll(text)
    notes.forEach { note ->
        var lines = note.groupValues[1].lines().map { it.trim() }.filter { it.isNotEmpty() }
        var i = 0
        while (i < lines.size) {
            var header = Regex("""^([A-Za-z_][\w.]*),\s*first match:\s*$""").matchEntire(lines[i])
            if (header != null) {
                var key = header.groupValues[1]
                var rows = ArrayList<String>()
                i += 1
                while (i < lines.size) {
                    var row = Regex("""^\d+\s+(\S+)""").find(lines[i]) ?: break
                    rows.add(row.groupValues[1])
                    i += 1
                }
                if (rows.isNotEmpty()) tables[key] = rows
                continue
            }
            i += 1
        }
    }
    return tables
}

private fun extractLegend(text: String): List<String> {
    var match = Regex("""legend left\s*(.*?)\s*endlegend""", RegexOption.DOT_MATCHES_ALL)
        .find(text) ?: return emptyList()
    return match.groupValues[1].lines().map { it.trim() }.filter { it.isNotEmpty() }
}

private fun stripIgnored(text: String): String {
    var withoutLegend = Regex("""legend left\s*.*?\s*endlegend""", RegexOption.DOT_MATCHES_ALL)
        .replace(text, "")
    var withoutNotes = Regex("""note (right|left)\s*.*?\s*end note""", RegexOption.DOT_MATCHES_ALL)
        .replace(withoutLegend, "\n")
    return withoutNotes.lines().map { raw ->
        var line = raw.trim()
        when {
            line.isEmpty() -> ""
            line.startsWith("'") -> ""
            line.startsWith("skinparam ") -> ""
            line.startsWith("@startuml") -> ""
            line.startsWith("@enduml") -> ""
            line.startsWith("title ") -> ""
            else -> line
        }
    }.filter { it.isNotEmpty() }.joinToString("\n")
}

private fun logicalLines(body: String): List<String> {
    var lines = body.lines()
    var out = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        var line = lines[i].trim()
        if (line.startsWith(":") && !line.endsWith(";")) {
            var buf = StringBuilder(line)
            i += 1
            while (i < lines.size && !buf.endsWith(";")) {
                buf.append(' ').append(lines[i].trim())
                i += 1
            }
            out.add(buf.toString())
        } else {
            out.add(line)
            i += 1
        }
    }
    return out
}

private class ActivityParser(private val lines: List<String>) {
    var index: Int = 0

    fun parsePartitions(): List<PumlPartition> {
        var partitions = ArrayList<PumlPartition>()
        while (index < lines.size) {
            var line = lines[index]
            when {
                line == "start" || line == "stop" -> index += 1
                line.startsWith("partition ") -> partitions.add(parsePartition())
                else -> error("unsupported activity syntax: $line")
            }
        }
        return partitions
    }

    private fun parsePartition(): PumlPartition {
        var header = lines[index]
        var nameMatch = Regex("""partition\s+"([^"]+)"\s*\{""").find(header)
            ?: error("partition header must be quoted: $header")
        index += 1
        var steps = parseUntil(setOf("}"))
        if (index < lines.size && lines[index] == "}") index += 1
        return PumlPartition(name = nameMatch.groupValues[1], steps = steps)
    }

    private fun parseUntil(enders: Set<String>): List<PumlStep> {
        var steps = ArrayList<PumlStep>()
        while (index < lines.size && !atBlockEnd(enders)) {
            steps.addAll(parseOne())
        }
        return steps
    }

    private fun atBlockEnd(enders: Set<String>): Boolean {
        var line = lines[index]
        if ("elseif" in enders && line.startsWith("elseif")) return true
        if ("else" in enders && line.startsWith("else") && !line.startsWith("elseif")) return true
        if ("endif" in enders && line == "endif") return true
        return line in enders
    }

    private fun parseOne(): List<PumlStep> {
        var line = lines[index]
        return when {
            line.startsWith("if ") -> listOf(parseIf())
            line == "split" -> listOf(parseSplit())
            line.startsWith(":") && line.endsWith(";") -> {
                index += 1
                parseActivityBox(line.removePrefix(":").removeSuffix(";").trim())
            }
            line == "stop" -> {
                index += 1
                listOf(PumlStep.Stop)
            }
            else -> error("unsupported activity step: $line")
        }
    }

    private fun parseIf(): PumlStep.Branch {
        var head = lines[index]
        var cond = extractIfCondition(head)
        index += 1
        var yes = parseUntil(setOf("elseif", "else", "endif"))
        var no = ArrayList<PumlStep>()
        if (index < lines.size && lines[index].startsWith("elseif")) {
            no.add(parseIf())
        } else if (index < lines.size && lines[index].startsWith("else")) {
            index += 1
            no.addAll(parseUntil(setOf("endif")))
            if (index < lines.size && lines[index] == "endif") index += 1
        } else if (index < lines.size && lines[index] == "endif") {
            index += 1
        }
        return PumlStep.Branch(condition = parseCondition(cond), yes = yes, no = no)
    }

    private fun parseSplit(): PumlStep.Split {
        index += 1
        var arms = ArrayList<List<PumlStep>>()
        while (index < lines.size && lines[index] != "end split") {
            if (lines[index] == "split again") {
                index += 1
                continue
            }
            var arm = ArrayList<PumlStep>()
            while (
                index < lines.size &&
                lines[index] != "split again" &&
                lines[index] != "end split"
            ) {
                arm.addAll(parseOne())
            }
            arms.add(arm)
        }
        if (index < lines.size && lines[index] == "end split") index += 1
        return PumlStep.Split(arms)
    }
}

private fun extractIfCondition(header: String): String {
    var open = header.indexOf('(')
    var thenAt = header.indexOf(") then")
    require(open >= 0 && thenAt > open) { "if header must be if (cond?) then: $header" }
    return header.substring(open + 1, thenAt).replace("\\n", " ").replace("?", "").trim()
}

private val EMIT_BOX = Regex(
    """^([A-Z][A-Za-z0-9_]*)(?:\(([^)]*)\))?(?:\s+reason=([A-Za-z_][\w.]*))?$""",
)

private fun parseActivityBox(body: String): List<PumlStep> {
    var text = body.replace(Regex("""\s+"""), " ").trim()
    if (isEmitBox(text) || text.startsWith("flag ") || text == "stop") {
        return listOf(parseSingleBox(text))
    }
    var chunks = splitBoxAssignments(body)
    return chunks.map { parseSingleBox(it.trim()) }
}

private fun isEmitBox(text: String): Boolean = EMIT_BOX.matchEntire(text) != null

private fun splitBoxAssignments(body: String): List<String> {
    var parts = body.split(Regex("""(?=\b[A-Za-z_][\w.]*\s*(=|←))"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return if (parts.size <= 1) listOf(body.trim()) else parts
}

private fun parseSingleBox(body: String): PumlStep {
    var text = body.trim()
    when {
        text.startsWith("flag ") -> return PumlStep.Flag(text.removePrefix("flag ").trim())
        text == "stop" -> return PumlStep.Stop
        "← empty" in text || text.endsWith("← empty") -> {
            var name = text.substringBefore("←").trim()
            return PumlStep.Clear(name)
        }
        "←" in text -> {
            var name = text.substringBefore("←").trim()
            var rhs = text.substringAfter("←").trim()
            return PumlStep.Assign(name, parseExpression(rhs))
        }
    }
    var assign = Regex("""^([A-Za-z_][\w.]*)\s*=\s*(.+)$""").matchEntire(text)
    if (assign != null) {
        return PumlStep.Assign(assign.groupValues[1], parseExpression(assign.groupValues[2]))
    }
    var emit = EMIT_BOX.matchEntire(text.replace(Regex("""\s+"""), " ").trim())
    if (emit != null) {
        var label = emit.groupValues[1]
        var arg = emit.groupValues[2]
        var reason = emit.groupValues[3]
        var fields = linkedMapOf<String, String>()
        if (arg.isNotEmpty()) fields["arg"] = arg
        if (reason.isNotEmpty()) fields["reason"] = reason
        return PumlStep.Emit(label = label, fields = fields)
    }
    if ("=" !in text && "←" !in text) {
        return PumlStep.BareCall(text)
    }
    error("unsupported activity box: $body")
}

internal fun parseCondition(text: String): PumlExpr = parseBoolean(text.trim())

internal fun parseExpression(text: String): PumlExpr {
    var trimmed = text.trim()
    if (looksArithmetic(trimmed)) return parseArithmetic(trimmed)
    if (looksCompare(trimmed)) return parseBoolean(trimmed)
    return PumlExpr.Phrase(trimmed)
}

private fun looksCompare(text: String): Boolean {
    var ops = listOf("≠", "<=", "≥", "≤", ">=", "<", ">", "==")
    if (ops.any { indexOfTop(text, it) > 0 }) return true
    return text.endsWith(" missing") || text.endsWith(" empty") ||
        " missing or " in text || " empty or " in text
}

private fun indexOfTop(text: String, op: String): Int {
    var depth = 0
    var i = 0
    while (i <= text.length - op.length) {
        var c = text[i]
        when (c) {
            '(' -> {
                depth += 1
                i += 1
            }
            ')' -> {
                depth -= 1
                i += 1
            }
            else -> {
                if (depth == 0 && text.startsWith(op, i)) return i
                i += 1
            }
        }
    }
    return -1
}

private fun looksArithmetic(text: String): Boolean {
    if (Regex("""[A-Za-z]-[A-Za-z]""").containsMatchIn(text)) return false
    if (text.any { it.isWhitespace() && it != ' ' }) return false
    var allowed = text.replace(Regex("""[A-Za-z_][\w.]*"""), "x")
        .replace(Regex("""\d+(\.\d+)?"""), "1")
        .replace("×", "*")
        .replace(" ", "")
    return allowed.all { it in "x1+-*/()*" } && (text.contains('+') || text.contains('-') ||
        text.contains('×') || text.contains('/') || text.contains('*'))
}

private fun parseArithmetic(text: String): PumlExpr {
    var tokens = lexArithmetic(text)
    var parser = ArithParser(tokens)
    var expr = parser.parseAdd()
    require(parser.done()) { "trailing arithmetic tokens in $text" }
    return expr
}

private fun lexArithmetic(text: String): List<String> {
    var out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        var c = text[i]
        when {
            c.isWhitespace() -> i += 1
            c == '×' || c == '+' || c == '-' || c == '/' || c == '*' || c == '(' || c == ')' -> {
                out.add(c.toString())
                i += 1
            }
            c.isDigit() -> {
                var j = i
                while (j < text.length && (text[j].isDigit() || text[j] == '.')) j += 1
                out.add(text.substring(i, j))
                i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j += 1
                out.add(text.substring(i, j))
                i = j
            }
            else -> error("bad arithmetic char '$c' in $text")
        }
    }
    return out
}

private class ArithParser(private val tokens: List<String>) {
    var i: Int = 0

    fun done(): Boolean = i >= tokens.size

    fun parseAdd(): PumlExpr {
        var left = parseMul()
        while (i < tokens.size && tokens[i] in setOf("+", "-")) {
            var op = tokens[i]
            i += 1
            left = PumlExpr.Binary(op, left, parseMul())
        }
        return left
    }

    fun parseMul(): PumlExpr {
        var left = parsePrimary()
        while (i < tokens.size && tokens[i] in setOf("×", "*", "/")) {
            var op = if (tokens[i] == "*") "×" else tokens[i]
            i += 1
            left = PumlExpr.Binary(op, left, parsePrimary())
        }
        return left
    }

    fun parsePrimary(): PumlExpr {
        var tok = tokens.getOrNull(i) ?: error("expected arithmetic token")
        if (tok == "(") {
            i += 1
            var inner = parseAdd()
            require(tokens.getOrNull(i) == ")") { "missing ) " }
            i += 1
            return inner
        }
        i += 1
        tok.toDoubleOrNull()?.let { return PumlExpr.Number(it) }
        return PumlExpr.Ident(tok)
    }
}

private fun parseBoolean(text: String): PumlExpr {
    var normalized = text.replace("\\n", " ").trim()
    normalized = normalized.replace(
        Regex("""(\S+)\s+missing or\s+([≤<>=]+)\s*(\S+)"""),
        "$1 missing or $1 $2 $3",
    )
    return parseOr(normalized)
}

private fun parseOr(text: String): PumlExpr {
    var parts = splitTop(text, " or ")
    if (parts.size == 1) return parseAnd(parts[0])
    return parts.map(::parseAnd).reduce { a, b -> PumlExpr.Binary("or", a, b) }
}

private fun parseAnd(text: String): PumlExpr {
    var parts = splitTop(text, " and ")
    if (parts.size == 1) return parseUnaryBool(parts[0])
    return parts.map(::parseUnaryBool).reduce { a, b -> PumlExpr.Binary("and", a, b) }
}

private fun parseUnaryBool(text: String): PumlExpr {
    var t = text.trim()
    if (t.startsWith("not ")) return PumlExpr.Unary("not", parseUnaryBool(t.removePrefix("not ").trim()))
    return parseCompare(t)
}

private fun parseCompare(text: String): PumlExpr {
    var t = text.trim()
    var ops = listOf("≠", "<=", "≥", "≤", ">=", "<", ">", "==")
    for (op in ops) {
        var idx = indexOfTop(t, op)
        if (idx > 0) {
            var left = t.substring(0, idx).trim()
            var right = t.substring(idx + op.length).trim()
            return PumlExpr.Binary(op, parseCompareAtom(left), parseCompareAtom(right))
        }
    }
    if (t.endsWith(" missing")) {
        return PumlExpr.Unary("missing", parseCompareAtom(t.removeSuffix(" missing").trim()))
    }
    if (t.endsWith(" empty")) {
        return PumlExpr.Unary("empty", parseCompareAtom(t.removeSuffix(" empty").trim()))
    }
    if (t == "true") return PumlExpr.Bool(true)
    if (t == "false") return PumlExpr.Bool(false)
    if (t.contains(' ') || t.contains('(')) return PumlExpr.Phrase(t)
    return PumlExpr.Ident(t)
}

private fun parseCompareAtom(text: String): PumlExpr {
    var t = text.trim()
    t.toDoubleOrNull()?.let { return PumlExpr.Number(it) }
    if (t.contains(' ') || '(' in t) return PumlExpr.Phrase(t)
    return PumlExpr.Ident(t)
}

private fun splitTop(text: String, sep: String): List<String> {
    var out = ArrayList<String>()
    var depth = 0
    var start = 0
    var i = 0
    while (i < text.length) {
        var c = text[i]
        when (c) {
            '(' -> depth += 1
            ')' -> depth -= 1
        }
        if (depth == 0 && text.regionMatches(i, sep, 0, sep.length, ignoreCase = true)) {
            out.add(text.substring(start, i).trim())
            i += sep.length
            start = i
            continue
        }
        i += 1
    }
    out.add(text.substring(start).trim())
    return out.filter { it.isNotEmpty() }
}

private fun sha256(text: String): String {
    var bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { b -> "%02x".format(b) }
}
