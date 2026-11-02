package com.cluefolk.sdk

/**
 * The support widget's theme (appearance v2) for [Cluefolk.setAppearance]: title, color mode,
 * light and dark colors, home screen blocks and how the support screen turns. Build it from the
 * file downloaded in Settings → Widget → Download for build, from the default theme, or either
 * with changes in code; [Builder.build] checks it the way the widget does.
 */
class Appearance internal constructor(internal val json: String, internal val rotation: Rotation) {
    /** SYSTEM follows the device's light or dark mode. */
    enum class Mode { SYSTEM, LIGHT, DARK }

    /**
     * GAME: support turns like the game (landscape, portrait or both). FREE: upright or sideways
     * regardless of the game.
     */
    enum class Rotation { GAME, FREE }

    /**
     * A shortcut of the topics block: [category] is a conversation category key of the project;
     * [icon] is up to 32 characters (for example an emoji) or a PNG or JPEG data URL.
     */
    data class Topic(val label: String, val category: String, val icon: String)

    /**
     * Starts from [json], the text of a file from Settings → Widget → Download for build, or from
     * the default theme when null. Throws IllegalArgumentException when [json] is not valid JSON,
     * not an object, not version 2 or has keys outside the appearance schema. Setters replace the
     * block of their type or add it, keeping the order welcome, topics, contact; [build] checks
     * every value.
     */
    class Builder @JvmOverloads constructor(json: String? = null) {
        private var title: String
        private var mode: Mode
        private var rotation: Rotation
        private var light: Palette
        private var dark: Palette
        private val blocks = ArrayList<Block>()

        init {
            val parsed = JsonParser("appearance JSON", json ?: AppearanceDefaults.JSON).parse()
            require(parsed is JsonObject) { "The appearance JSON must be an object" }
            val root = Fields("appearance", parsed)
            val version = root.raw("version")
            require(version is Double && version == 2.0) { "Appearance version 2 is required" }
            root.keys(setOf("version", "title", "mode", "colors", "blocks"), setOf("rotation"))
            title = root.string("title")
            mode = root.choice("mode", Mode.entries)
            rotation = if (root.has("rotation")) root.choice("rotation", Rotation.entries) else Rotation.GAME
            val colors = root.child("colors")
            colors.keys(setOf("light", "dark"))
            light = Palette.read(colors.child("light"))
            dark = Palette.read(colors.child("dark"))
            root.list("blocks").forEachIndexed { index, raw ->
                val block = Fields("appearance.blocks[$index]", raw)
                block.keys(setOf("type", "title", "text", "items"))
                val items = block.list("items").mapIndexed { item, value -> readTopic(Fields("${block.path}.items[$item]", value)) }
                blocks.add(Block(block.string("type"), block.string("title"), block.string("text"), items))
            }
        }

        fun title(title: String): Builder = apply { this.title = title }

        fun mode(mode: Mode): Builder = apply { this.mode = mode }

        fun rotation(rotation: Rotation): Builder = apply { this.rotation = rotation }

        /** Five #RRGGBB colors of the light theme; the widget derives the rest. */
        fun lightColors(accent: String, action: String, background: String, surface: String, text: String): Builder =
            apply { light = Palette(accent, action, background, surface, text) }

        /** Five #RRGGBB colors of the dark theme; the widget derives the rest. */
        fun darkColors(accent: String, action: String, background: String, surface: String, text: String): Builder =
            apply { dark = Palette(accent, action, background, surface, text) }

        fun welcome(title: String, text: String): Builder = block(Block(WELCOME, title, text, emptyList()))

        /** Up to 12 topics; each opens a new conversation in its category. */
        fun topics(title: String, items: List<Topic>): Builder = block(Block(TOPICS, title, "", items.toList()))

        fun contact(title: String, text: String): Builder = block(Block(CONTACT, title, text, emptyList()))

        /** Checks the theme the way the widget does; throws IllegalArgumentException naming the problem. */
        fun build(): Appearance {
            require(!blank(title)) { "The title must not be blank" }
            require(title.length <= 100) { "The title is longer than 100 characters" }
            light.check("light")
            dark.check("dark")
            require(blocks.size <= 4) { "At most 4 blocks are allowed" }
            val seen = HashSet<String>()
            for (block in blocks) {
                require(block.type in BLOCK_TYPES) { "Block type \"${block.type}\" is not welcome, topics or contact" }
                require(seen.add(block.type)) { "The ${block.type} block appears more than once" }
                require(block.title.length <= 100) { "The ${block.type} title is longer than 100 characters" }
                require(block.text.length <= 1000) { "The ${block.type} text is longer than 1000 characters" }
                require(block.type == TOPICS || block.items.isEmpty()) { "Only the topics block has items" }
                require(block.items.size <= 12) { "At most 12 topics are allowed" }
                block.items.forEachIndexed { index, topic -> checkTopic(topic, index + 1) }
            }
            val json = write()
            require(json.length <= MAX_LENGTH) { "The appearance is larger than 1.5 MB" }
            return Appearance(json, rotation)
        }

        /**
         * Applies the changes a Unity Appearance.Builder records (all keys optional): title, mode,
         * rotation, colors.light / colors.dark (five colors each), welcome {title, text},
         * topics {title, items: [{label, category, icon}]}, contact {title, text}.
         */
        internal fun overrides(json: String): Builder = apply {
            val parsed = JsonParser("appearance overrides JSON", json).parse()
            require(parsed is JsonObject) { "The appearance overrides must be an object" }
            val root = Fields("overrides", parsed)
            root.keys(emptySet(), setOf("title", "mode", "rotation", "colors", WELCOME, TOPICS, CONTACT))
            if (root.has("title")) title(root.string("title"))
            if (root.has("mode")) mode(root.choice("mode", Mode.entries))
            if (root.has("rotation")) rotation(root.choice("rotation", Rotation.entries))
            if (root.has("colors")) {
                val colors = root.child("colors")
                colors.keys(emptySet(), setOf("light", "dark"))
                if (colors.has("light")) Palette.read(colors.child("light")).let {
                    lightColors(it.accent, it.action, it.background, it.surface, it.text)
                }
                if (colors.has("dark")) Palette.read(colors.child("dark")).let {
                    darkColors(it.accent, it.action, it.background, it.surface, it.text)
                }
            }
            if (root.has(WELCOME)) root.child(WELCOME).let {
                it.keys(setOf("title", "text"))
                welcome(it.string("title"), it.string("text"))
            }
            if (root.has(TOPICS)) root.child(TOPICS).let { fields ->
                fields.keys(setOf("title", "items"))
                val items = fields.list("items").mapIndexed { index, value -> readTopic(Fields("${fields.path}.items[$index]", value)) }
                topics(fields.string("title"), items)
            }
            if (root.has(CONTACT)) root.child(CONTACT).let {
                it.keys(setOf("title", "text"))
                contact(it.string("title"), it.string("text"))
            }
        }

        // Replaces the block of this type in place, or inserts it before the first block that
        // comes later in the order welcome, topics, contact.
        private fun block(value: Block): Builder = apply {
            val existing = blocks.indexOfFirst { it.type == value.type }
            if (existing >= 0) {
                blocks[existing] = value
                return@apply
            }
            val rank = BLOCK_TYPES.indexOf(value.type)
            val later = blocks.indexOfFirst { BLOCK_TYPES.indexOf(it.type) > rank }
            if (later < 0) blocks.add(value) else blocks.add(later, value)
        }

        // The keys in the order of appearance.default.json; strings are escaped like JSON.stringify,
        // so the length check matches the widget's.
        private fun write(): String {
            val out = StringBuilder()
            out.append("{\"version\":2,\"title\":").quote(title)
            out.append(",\"mode\":").quote(mode.name.lowercase())
            out.append(",\"rotation\":").quote(rotation.name.lowercase())
            out.append(",\"colors\":{\"light\":")
            light.write(out)
            out.append(",\"dark\":")
            dark.write(out)
            out.append("},\"blocks\":[")
            blocks.forEachIndexed { index, block ->
                if (index > 0) out.append(',')
                out.append("{\"type\":").quote(block.type).append(",\"title\":").quote(block.title)
                    .append(",\"text\":").quote(block.text).append(",\"items\":[")
                block.items.forEachIndexed { item, topic ->
                    if (item > 0) out.append(',')
                    out.append("{\"label\":").quote(topic.label).append(",\"icon\":").quote(topic.icon)
                        .append(",\"category\":").quote(topic.category).append('}')
                }
                out.append("]}")
            }
            return out.append("]}").toString()
        }
    }
}

private const val WELCOME = "welcome"
private const val TOPICS = "topics"
private const val CONTACT = "contact"
private val BLOCK_TYPES = listOf(WELCOME, TOPICS, CONTACT)
private const val MAX_LENGTH = 1_500_000
private const val IMAGE_MAX_LENGTH = 700_040
private val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")
private val CATEGORY_KEY = Regex("[a-z][a-z0-9_]{0,49}")
private val IMAGE = Regex("data:image/(png|jpeg);base64,[A-Za-z0-9+/]+=*")

private class Palette(val accent: String, val action: String, val background: String, val surface: String, val text: String) {
    private val values get() = listOf("accent" to accent, "action" to action, "background" to background, "surface" to surface, "text" to text)

    fun check(theme: String) {
        for ((name, value) in values) require(HEX_COLOR.matches(value)) { "The $theme $name color must be #RRGGBB" }
    }

    fun write(out: StringBuilder) {
        out.append('{')
        values.forEachIndexed { index, (name, value) ->
            if (index > 0) out.append(',')
            out.quote(name).append(':').quote(value)
        }
        out.append('}')
    }

    companion object {
        fun read(fields: Fields): Palette {
            fields.keys(setOf("accent", "action", "background", "surface", "text"))
            return Palette(fields.string("accent"), fields.string("action"), fields.string("background"),
                fields.string("surface"), fields.string("text"))
        }
    }
}

private class Block(val type: String, val title: String, val text: String, val items: List<Appearance.Topic>)

private fun readTopic(fields: Fields): Appearance.Topic {
    fields.keys(setOf("label", "icon", "category"))
    return Appearance.Topic(fields.string("label"), fields.string("category"), fields.string("icon"))
}

private fun checkTopic(topic: Appearance.Topic, number: Int) {
    require(!blank(topic.label)) { "Topic $number label must not be blank" }
    require(topic.label.length <= 80) { "Topic $number label is longer than 80 characters" }
    require(CATEGORY_KEY.matches(topic.category)) {
        "Topic $number category must be a category key: a lowercase letter, then up to 49 lowercase letters, digits or _"
    }
    if (topic.icon.startsWith("data:"))
        require(topic.icon.length <= IMAGE_MAX_LENGTH && IMAGE.matches(topic.icon)) {
            "Topic $number icon must be a base64 PNG or JPEG data URL of at most $IMAGE_MAX_LENGTH characters"
        }
    else
        require(topic.icon.length <= 32 && '<' !in topic.icon && '>' !in topic.icon) {
            "Topic $number icon must be at most 32 characters without < or >"
        }
}

// The whitespace JavaScript's String.prototype.trim removes, as the widget checks titles and labels.
private fun blank(value: String): Boolean =
    value.all { it in "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" || it in '\u2000'..'\u200A' }

// JSON.stringify string escaping: the two-character escapes, \u00XX for other control
// characters and lone surrogates, everything else as is.
private fun StringBuilder.quote(value: String): StringBuilder {
    append('"')
    for (index in value.indices) {
        val char = value[index]
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\b' -> append("\\b")
            char == '\u000C' -> append("\\f")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' ||
                (char.isHighSurrogate() && (index + 1 >= value.length || !value[index + 1].isLowSurrogate())) ||
                (char.isLowSurrogate() && (index == 0 || !value[index - 1].isHighSurrogate())) ->
                append("\\u").append(Integer.toHexString(char.code).padStart(4, '0'))
            else -> append(char)
        }
    }
    return append('"')
}

private class JsonObject(val values: Map<String, Any?>)

/** Typed access to one parsed object; [path] names it in error messages. */
private class Fields(val path: String, value: Any?) {
    private val values = (value as? JsonObject)?.values ?: throw IllegalArgumentException("$path must be an object")

    fun keys(required: Set<String>, optional: Set<String> = emptySet()) {
        values.keys.firstOrNull { it !in required && it !in optional }?.let { throw IllegalArgumentException("Unknown key \"${at(it)}\"") }
        required.firstOrNull { it !in values }?.let { throw IllegalArgumentException("Missing key \"${at(it)}\"") }
    }

    fun has(key: String) = key in values

    fun raw(key: String): Any? = values[key]

    fun string(key: String): String = values[key] as? String ?: throw IllegalArgumentException("${at(key)} must be a string")

    fun list(key: String): List<*> = values[key] as? List<*> ?: throw IllegalArgumentException("${at(key)} must be an array")

    fun child(key: String) = Fields(at(key), values[key])

    fun <T : Enum<T>> choice(key: String, options: List<T>): T {
        val value = string(key)
        return options.firstOrNull { it.name.lowercase() == value }
            ?: throw IllegalArgumentException("${at(key)} must be ${options.joinToString(" or ") { it.name.lowercase() }}")
    }

    private fun at(key: String) = "$path.$key"
}

/**
 * Strict JSON as JSON.parse reads it, the same on devices and in unit tests: objects become
 * [JsonObject] (a repeated key keeps the last value), arrays lists, numbers Double.
 */
private class JsonParser(private val name: String, private val text: String) {
    private var index = 0

    fun parse(): Any? {
        val value = value(0)
        space()
        if (index < text.length) fail("unexpected text after the value")
        return value
    }

    private fun value(depth: Int): Any? {
        if (depth > 32) fail("nesting is too deep")
        space()
        if (index >= text.length) fail("unexpected end")
        return when (val char = text[index]) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (char == '-' || char in '0'..'9') numberValue() else fail("unexpected character")
        }
    }

    private fun objectValue(depth: Int): JsonObject {
        index++
        val values = LinkedHashMap<String, Any?>()
        space()
        if (peek() == '}') {
            index++
            return JsonObject(values)
        }
        while (true) {
            space()
            if (peek() != '"') fail("expected a key")
            val key = stringValue()
            space()
            if (peek() != ':') fail("expected :")
            index++
            values[key] = value(depth + 1)
            space()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonObject(values)
                }
                else -> fail("expected , or }")
            }
        }
    }

    private fun arrayValue(depth: Int): List<Any?> {
        index++
        val values = ArrayList<Any?>()
        space()
        if (peek() == ']') {
            index++
            return values
        }
        while (true) {
            values.add(value(depth + 1))
            space()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return values
                }
                else -> fail("expected , or ]")
            }
        }
    }

    private fun stringValue(): String {
        index++
        val out = StringBuilder()
        while (true) {
            if (index >= text.length) fail("unterminated string")
            val char = text[index++]
            when {
                char == '"' -> return out.toString()
                char == '\\' -> {
                    if (index >= text.length) fail("unterminated string")
                    when (text[index++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail("bad \\u escape")
                            var code = 0
                            repeat(4) {
                                val digit = Character.digit(text[index++], 16)
                                if (digit < 0) fail("bad \\u escape")
                                code = code * 16 + digit
                            }
                            out.append(Char(code))
                        }
                        else -> fail("bad escape")
                    }
                }
                char < ' ' -> fail("control character in a string")
                else -> out.append(char)
            }
        }
    }

    private fun numberValue(): Double {
        val start = index
        if (peek() == '-') index++
        when (peek()) {
            '0' -> index++
            in '1'..'9' -> digits()
            else -> fail("bad number")
        }
        if (peek() == '.') {
            index++
            if (peek() !in '0'..'9') fail("bad number")
            digits()
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            if (peek() !in '0'..'9') fail("bad number")
            digits()
        }
        return text.substring(start, index).toDouble()
    }

    private fun digits() {
        while (peek() in '0'..'9') index++
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, index)) fail("unexpected character")
        index += word.length
        return value
    }

    private fun space() {
        while (index < text.length && text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) index++
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private fun fail(reason: String): Nothing =
        throw IllegalArgumentException("Invalid $name: $reason at character ${index + 1}")
}
