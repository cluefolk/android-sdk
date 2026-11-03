package com.cluefolk.sdk

import android.content.pm.ActivityInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AppearanceTest {
    private fun png(
        width: Int = 1,
        height: Int = 1,
        size: Int = 24,
        chunkLength: Int = 13,
        chunkType: String = "IHDR",
    ): String {
        val bytes = ByteArray(maxOf(size, 24))
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes)
        fun putInt(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        putInt(8, chunkLength)
        chunkType.toByteArray().copyInto(bytes, 12)
        putInt(16, width)
        putInt(20, height)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private val tinyPNG = png()
    private val tinyJPEG = "data:image/jpeg;base64,/9j/wAARCAABAAEDAREAAhEAAxEA/9k="
    private val custom = """
        {
          "version": 2,
          "title": "Game help \"desk\"",
          "mode": "dark",
          "rotation": "free",
          "colors": {
            "light": {"accent": "#112233", "action": "#445566", "background": "#FFFFFF", "surface": "#fafafa", "text": "#000000"},
            "dark": {"accent": "#223344", "action": "#556677", "background": "#000000", "surface": "#101010", "text": "#ffffff"}
          },
          "blocks": [
            {"type": "topics", "title": "Pick one", "text": "", "items": [
              {"label": "Billing", "icon": "$tinyPNG", "category": "billing_2"},
              {"label": "Bugs\n", "icon": "\ud83d\udc1b", "category": "bug"}
            ]},
            {"type": "welcome", "title": "Hi", "text": "Line one\u2028line two", "items": []}
          ]
        }
    """.trimIndent()

    // Key order is unspecified, so JSON is compared as plain maps and lists.
    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().toSet().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun plain(json: String) = plain(JSONObject(json))

    private fun json(appearance: Appearance) = JSONObject(appearance.json)

    private fun blocks(appearance: Appearance) = json(appearance).getJSONArray("blocks").let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }
    }

    private fun types(appearance: Appearance) = blocks(appearance).map { it.getString("type") }

    private fun theme(blocks: String, rotation: String? = null) = JSONObject(AppearanceDefaults.JSON)
        .put("blocks", JSONArray(blocks)).apply { if (rotation == null) remove("rotation") else put("rotation", rotation) }.toString()

    private fun fails(message: String, build: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { build() }
        assertTrue("\"${error.message}\" should contain \"$message\"", error.message.orEmpty().contains(message))
    }

    private fun topic(label: String = "Account", category: String = "account", icon: String = "A") =
        Appearance.Topic(label, category, icon)

    @Test
    fun theDefaultBuildIsTheGeneratedDefaultTheme() {
        val built = Appearance.Builder().build()
        assertEquals(plain(AppearanceDefaults.JSON), plain(built.json))
        assertEquals(Appearance.Rotation.GAME, built.rotation)
        assertEquals(plain(AppearanceDefaults.JSON), plain(Appearance.Builder(null).build().json))
        assertEquals(plain(AppearanceDefaults.JSON), plain(Appearance.Builder(AppearanceDefaults.JSON).build().json))
    }

    @Test
    fun aDownloadedFileSurvivesTheRoundTrip() {
        val built = Appearance.Builder(custom).build()
        assertEquals(plain(custom), plain(built.json))
        assertEquals(Appearance.Rotation.FREE, built.rotation)
        // Built JSON is again a valid file, and keeps the file's block order.
        assertEquals(built.json, Appearance.Builder(built.json).build().json)
        assertEquals(listOf("topics", "welcome"), types(built))
    }

    @Test
    fun aFileWithoutRotationTurnsLikeTheGameAndBuildWritesIt() {
        val built = Appearance.Builder(theme("[]")).build()
        assertEquals(Appearance.Rotation.GAME, built.rotation)
        assertEquals("game", json(built).getString("rotation"))
        assertEquals(0, blocks(built).size)
    }

    @Test
    fun buildEscapesStringsLikeJsonStringify() {
        val title = "Quote \" slash \\ / tab \t nl \n bell \u0007 \u2028 emoji \uD83D\uDE00"
        val built = Appearance.Builder().title(title).build()
        assertEquals(title, json(built).getString("title"))
        assertTrue(built.json.contains("\"title\":\"Quote \\\" slash \\\\ / tab \\t nl \\n bell \\u0007 \u2028 emoji \uD83D\uDE00\""))
        val lone = Appearance.Builder().title("a\uD83Db").build()
        assertTrue(lone.json.contains("\"title\":\"a\\ud83db\""))
    }

    @Test
    fun eachSetterChangesItsPart() {
        val items = listOf(topic("Account", "account", "\uD83D\uDC64"), topic("Payments", "purchase", tinyJPEG))
        val built = Appearance.Builder()
            .title("Support")
            .mode(Appearance.Mode.LIGHT)
            .rotation(Appearance.Rotation.FREE)
            .lightColors("#000001", "#000002", "#000003", "#000004", "#000005")
            .darkColors("#00000A", "#00000B", "#00000C", "#00000D", "#00000E")
            .welcome("Hello", "We read everything")
            .topics("Topics", items)
            .contact("Write to us", "Usually within a day")
            .build()
        val value = json(built)
        assertEquals(setOf("version", "title", "mode", "rotation", "colors", "blocks"), value.keys().asSequence().toSet())
        assertEquals(2, value.getInt("version"))
        assertEquals("Support", value.getString("title"))
        assertEquals("light", value.getString("mode"))
        assertEquals("free", value.getString("rotation"))
        assertEquals(Appearance.Rotation.FREE, built.rotation)
        assertEquals(
            mapOf("accent" to "#000001", "action" to "#000002", "background" to "#000003", "surface" to "#000004", "text" to "#000005"),
            plain(value.getJSONObject("colors").getJSONObject("light")),
        )
        assertEquals(
            mapOf("accent" to "#00000A", "action" to "#00000B", "background" to "#00000C", "surface" to "#00000D", "text" to "#00000E"),
            plain(value.getJSONObject("colors").getJSONObject("dark")),
        )
        assertEquals(
            listOf(
                mapOf("type" to "welcome", "title" to "Hello", "text" to "We read everything", "items" to emptyList<Any>()),
                mapOf("type" to "topics", "title" to "Topics", "text" to "", "items" to listOf(
                    mapOf("label" to "Account", "icon" to "\uD83D\uDC64", "category" to "account"),
                    mapOf("label" to "Payments", "icon" to tinyJPEG, "category" to "purchase"),
                )),
                mapOf("type" to "contact", "title" to "Write to us", "text" to "Usually within a day", "items" to emptyList<Any>()),
            ),
            plain(value.getJSONArray("blocks")),
        )
        assertEquals(Appearance.Rotation.GAME, Appearance.Builder(custom).rotation(Appearance.Rotation.GAME).build().rotation)
        assertEquals("system", json(Appearance.Builder(custom).mode(Appearance.Mode.SYSTEM).build()).getString("mode"))
        assertEquals("dark", json(Appearance.Builder().mode(Appearance.Mode.DARK).build()).getString("mode"))
    }

    @Test
    fun setterInputIsCopied() {
        val items = mutableListOf(topic())
        val builder = Appearance.Builder().topics("Topics", items)
        items.add(topic("Other", "other"))
        assertEquals(1, blocks(builder.build())[1].getJSONArray("items").length())
    }

    @Test
    fun settersReplaceTheirBlockOrInsertItInOrder() {
        val contactOnly = Appearance.Builder(theme("""[{"type":"contact","title":"C","text":"","items":[]}]"""))
        assertEquals(listOf("welcome", "contact"), types(contactOnly.welcome("W", "").build()))
        assertEquals(listOf("welcome", "topics", "contact"), types(contactOnly.topics("T", listOf(topic())).build()))

        val empty = Appearance.Builder(theme("[]"))
        assertEquals(listOf("contact"), types(empty.contact("C", "").build()))
        assertEquals(listOf("topics", "contact"), types(empty.topics("T", emptyList()).build()))
        assertEquals(listOf("welcome", "topics", "contact"), types(empty.welcome("W", "").build()))

        // A file's own order stays: a replaced block keeps its place.
        val reordered = Appearance.Builder(custom).welcome("New", "Text").topics("New topics", emptyList()).build()
        assertEquals(listOf("topics", "welcome"), types(reordered))
        assertEquals("New", blocks(reordered)[1].getString("title"))
        assertEquals(0, blocks(reordered)[0].getJSONArray("items").length())
        assertEquals(listOf("topics", "welcome", "contact"), types(Appearance.Builder(custom).contact("C", "").build()))

        val replaced = Appearance.Builder().contact("Mail us", "Fast").build()
        assertEquals(listOf("welcome", "topics", "contact"), types(replaced))
        assertEquals("Mail us", blocks(replaced)[2].getString("title"))
        assertEquals("Fast", blocks(replaced)[2].getString("text"))
    }

    @Test
    fun theBuilderRejectsInvalidJson() {
        for (bad in listOf("", " ", "{", "{\"version\":2,}", "{'version':2}", "{version:2}", "{\"version\":2} x",
            "{\"version\":02}", "{\"version\":2.}", "{\"title\":\"a\u0001\"}", "{\"title\":\"\\x\"}", "{\"title\":\"\\u12G4\"}",
            "{\"version\":2 \"title\":\"x\"}", "[".repeat(40) + "]".repeat(40), "nul", "{\"version\":2}/*c*/")) {
            fails("Invalid appearance JSON: ") { Appearance.Builder(bad) }
        }
    }

    @Test
    fun theBuilderRejectsAnythingButAVersion2Object() {
        for (bad in listOf("[]", "2", "\"text\"", "null", "true")) fails("must be an object") { Appearance.Builder(bad) }
        for (version in listOf("1", "3", "\"2\"", "2.5", "null")) {
            fails("Appearance version 2 is required") { Appearance.Builder(AppearanceDefaults.JSON.replace("\"version\":2", "\"version\":$version")) }
        }
        fails("Appearance version 2 is required") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { remove("version") }.toString()) }
        // 2.0 is the number 2, as JSON.parse reads it.
        Appearance.Builder(AppearanceDefaults.JSON.replace("\"version\":2", "\"version\":2.0")).build()
    }

    @Test
    fun theBuilderRejectsKeysOutsideTheSchema() {
        val default = JSONObject(AppearanceDefaults.JSON)
        fails("Unknown key \"appearance.logo\"") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("logo", "x").toString()) }
        fails("Unknown key \"appearance.colors.sepia\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONObject("colors").put("sepia", default.getJSONObject("colors").get("light")) }.toString())
        }
        fails("Unknown key \"appearance.colors.light.border\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONObject("colors").getJSONObject("light").put("border", "#000000") }.toString())
        }
        fails("Unknown key \"appearance.blocks[0].hidden\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONArray("blocks").getJSONObject(0).put("hidden", true) }.toString())
        }
        fails("Unknown key \"appearance.blocks[1].items[2].url\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply {
                getJSONArray("blocks").getJSONObject(1).getJSONArray("items").getJSONObject(2).put("url", "https://example.com")
            }.toString())
        }
        for (key in listOf("title", "mode", "colors", "blocks")) {
            fails("Missing key \"appearance.$key\"") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { remove(key) }.toString()) }
        }
        fails("Missing key \"appearance.colors.dark\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONObject("colors").remove("dark") }.toString())
        }
        fails("Missing key \"appearance.colors.dark.text\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONObject("colors").getJSONObject("dark").remove("text") }.toString())
        }
        fails("Missing key \"appearance.blocks[2].items\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONArray("blocks").getJSONObject(2).remove("items") }.toString())
        }
        fails("Missing key \"appearance.blocks[1].items[0].icon\"") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply {
                getJSONArray("blocks").getJSONObject(1).getJSONArray("items").getJSONObject(0).remove("icon")
            }.toString())
        }
    }

    @Test
    fun theBuilderRejectsWrongTypesAndChoices() {
        fails("appearance.title must be a string") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("title", 5).toString()) }
        fails("appearance.mode must be system or light or dark") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("mode", "auto").toString()) }
        fails("appearance.mode must be system or light or dark") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("mode", "SYSTEM").toString()) }
        fails("appearance.rotation must be game or free") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("rotation", "portrait").toString()) }
        fails("appearance.rotation must be a string") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("rotation", JSONObject.NULL).toString()) }
        fails("appearance.colors must be an object") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("colors", JSONArray()).toString()) }
        fails("appearance.colors.light.accent must be a string") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONObject("colors").getJSONObject("light").put("accent", 0x183e31) }.toString())
        }
        fails("appearance.blocks must be an array") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("blocks", JSONObject()).toString()) }
        fails("appearance.blocks[0] must be an object") { Appearance.Builder(theme("[\"welcome\"]")) }
        fails("appearance.blocks[0].items must be an array") { Appearance.Builder(theme("""[{"type":"welcome","title":"","text":"","items":null}]""")) }
        fails("appearance.blocks[0].items[0] must be an object") { Appearance.Builder(theme("""[{"type":"topics","title":"","text":"","items":[1]}]""")) }
        fails("appearance.blocks[0].items[0].label must be a string") {
            Appearance.Builder(theme("""[{"type":"topics","title":"","text":"","items":[{"label":1,"icon":"","category":"a"}]}]"""))
        }
    }

    @Test
    fun buildChecksTheTitle() {
        Appearance.Builder().title("t".repeat(100)).build()
        fails("The title is longer than 100 characters") { Appearance.Builder().title("t".repeat(101)).build() }
        for (blank in listOf("", " ", "\t\n", "\u00A0\u3000\uFEFF\u2007")) fails("The title must not be blank") { Appearance.Builder().title(blank).build() }
        fails("The title must not be blank") { Appearance.Builder(JSONObject(AppearanceDefaults.JSON).put("title", " ").toString()).build() }
    }

    @Test
    fun buildChecksColors() {
        Appearance.Builder().lightColors("#ABCDEF", "#abcdef", "#000000", "#FFFFFF", "#0a0B0c").build()
        for (bad in listOf("#12345", "123456", "#12345g", "#1234567", "#fff", "", "#123456\n", " #123456")) {
            fails("The light accent color must be #RRGGBB") { Appearance.Builder().lightColors(bad, "#000000", "#000000", "#000000", "#000000").build() }
        }
        fails("The light text color must be #RRGGBB") { Appearance.Builder().lightColors("#000000", "#000000", "#000000", "#000000", "red").build() }
        fails("The dark surface color must be #RRGGBB") { Appearance.Builder().darkColors("#000000", "#000000", "#000000", "#00000", "#000000").build() }
        fails("The dark action color must be #RRGGBB") {
            Appearance.Builder(JSONObject(AppearanceDefaults.JSON).apply { getJSONObject("colors").getJSONObject("dark").put("action", "blue") }.toString()).build()
        }
    }

    @Test
    fun buildChecksBlocks() {
        val welcome = """{"type":"welcome","title":"","text":"","items":[]}"""
        val contact = """{"type":"contact","title":"","text":"","items":[]}"""
        fails("At most 4 blocks are allowed") { Appearance.Builder(theme("[$welcome,$welcome,$welcome,$welcome,$welcome]")).build() }
        fails("The welcome block appears more than once") { Appearance.Builder(theme("[$welcome,$contact,$welcome]")).build() }
        fails("Block type \"faq\" is not welcome, topics or contact") { Appearance.Builder(theme("""[{"type":"faq","title":"","text":"","items":[]}]""")).build() }
        Appearance.Builder().welcome("t".repeat(100), "x".repeat(1000)).contact("t".repeat(100), "x".repeat(1000)).build()
        fails("The welcome title is longer than 100 characters") { Appearance.Builder().welcome("t".repeat(101), "").build() }
        fails("The contact text is longer than 1000 characters") { Appearance.Builder().contact("", "x".repeat(1001)).build() }
        fails("The topics title is longer than 100 characters") { Appearance.Builder().topics("t".repeat(101), emptyList()).build() }
        Appearance.Builder().topics("", List(12) { topic() }).build()
        fails("At most 12 topics are allowed") { Appearance.Builder().topics("", List(13) { topic() }).build() }
        fails("Only the topics block has items") {
            Appearance.Builder(theme("""[{"type":"contact","title":"","text":"","items":[{"label":"A","icon":"","category":"a"}]}]""")).build()
        }
        fails("The topics text is longer than 1000 characters") {
            Appearance.Builder(theme("""[{"type":"topics","title":"","text":"${"x".repeat(1001)}","items":[]}]""")).build()
        }
    }

    @Test
    fun buildChecksTopics() {
        fun build(vararg items: Appearance.Topic) = Appearance.Builder().topics("Topics", items.toList()).build()
        build(topic(label = "l".repeat(80)), topic(category = "a"), topic(category = "a" + "_9".repeat(24) + "z"), topic(icon = ""), topic(icon = "i".repeat(32)))
        fails("Topic 2 label must not be blank") { build(topic(), topic(label = " \u2028")) }
        fails("Topic 1 label is longer than 80 characters") { build(topic(label = "l".repeat(81))) }
        for (bad in listOf("", "Account", "1st", "_a", "a-b", "a".repeat(51), "a\n")) {
            fails("Topic 1 category must be a category key") { build(topic(category = bad)) }
        }
        fails("Topic 1 icon must be at most 32 characters without < or >") { build(topic(icon = "i".repeat(33))) }
        fails("Topic 1 icon must be at most 32 characters without < or >") { build(topic(icon = "<b>")) }
        fails("Topic 1 icon must be at most 32 characters without < or >") { build(topic(icon = "a>")) }
    }

    @Test
    fun buildChecksImageIcons() {
        fun build(icon: String) = Appearance.Builder().topics("Topics", listOf(topic(icon = icon))).build()
        build(tinyPNG)
        build(tinyJPEG)
        build(png(size = 525_000))
        for (bad in listOf("data:image/gif;base64,R0lGOD", "data:image/png;base64,", "data:image/png;base64,abc def", "data:image/svg+xml;base64,PHN2Zz4=",
            "data:image/png;base64,abc=d", "data:image/png,abc", "data:", png(width = 16_384, height = 16_384), png(size = 525_001),
            png(chunkLength = 12), png(chunkType = "IDAT"))) {
            fails("Topic 1 icon must be") { build(bad) }
        }
    }

    @Test
    fun jsonInputsAreBoundedBeforeParsing() {
        fails("appearance JSON is larger") { Appearance.Builder(" ".repeat(1_500_001)) }
        fails("appearance JSON is larger") { Appearance.Builder("é".repeat(750_001)) }
        fails("appearance overrides JSON is larger") { Appearance.Builder().overrides(" ".repeat(1_500_001)) }
        assertEquals("{}", boundedJSON("{}", 2, "too large"))
        fails("attributes JSON is larger") { boundedJSON("\u00e9", 1, "The attributes JSON is larger than 15 KB") }
    }

    @Test
    fun buildRejectsAThemeOverTheWidgetLimit() {
        val icon = png(size = 390_000)
        Appearance.Builder().topics("Topics", List(2) { topic(icon = icon) }).build()
        fails("The appearance is larger than 1.5 MB") { Appearance.Builder().topics("Topics", List(3) { topic(icon = icon) }).build() }
    }

    @Test
    fun overridesApplyTheSetters() {
        val overrides = """{"title":"Support","mode":"light","rotation":"free",
            "colors":{"light":{"accent":"#000001","action":"#000002","background":"#000003","surface":"#000004","text":"#000005"},
                      "dark":{"accent":"#00000A","action":"#00000B","background":"#00000C","surface":"#00000D","text":"#00000E"}},
            "welcome":{"title":"Hello","text":"Hi"},
            "topics":{"title":"Topics","items":[{"label":"Account","category":"account","icon":"\ud83d\udc64"}]},
            "contact":{"title":"Write","text":"Soon"}}"""
        val expected = Appearance.Builder(custom)
            .title("Support").mode(Appearance.Mode.LIGHT).rotation(Appearance.Rotation.FREE)
            .lightColors("#000001", "#000002", "#000003", "#000004", "#000005")
            .darkColors("#00000A", "#00000B", "#00000C", "#00000D", "#00000E")
            .welcome("Hello", "Hi").topics("Topics", listOf(topic("Account", "account", "\uD83D\uDC64"))).contact("Write", "Soon")
            .build()
        val applied = Appearance.Builder(custom).overrides(overrides).build()
        assertEquals(expected.json, applied.json)
        assertEquals(Appearance.Rotation.FREE, applied.rotation)
        assertEquals(listOf("topics", "welcome", "contact"), types(applied))

        assertEquals(Appearance.Builder().build().json, Appearance.Builder().overrides("{}").build().json)
        assertEquals(Appearance.Builder(custom).build().json, Appearance.Builder(custom).overrides(" {} ").build().json)
        val partial = Appearance.Builder().overrides("""{"rotation":"free","colors":{"dark":{"accent":"#123456","action":"#123456","background":"#123456","surface":"#123456","text":"#123456"}}}""").build()
        assertEquals(Appearance.Rotation.FREE, partial.rotation)
        assertEquals(plain(JSONObject(AppearanceDefaults.JSON).getJSONObject("colors").getJSONObject("light")), plain(json(partial).getJSONObject("colors").getJSONObject("light")))
        assertEquals("#123456", json(partial).getJSONObject("colors").getJSONObject("dark").getString("text"))
    }

    @Test
    fun badOverridesAreRejected() {
        fun apply(overrides: String) = Appearance.Builder().overrides(overrides).build()
        for (bad in listOf("", "{", "{\"title\":\"x\",}", "{} {}")) fails("Invalid appearance overrides JSON: ") { apply(bad) }
        for (bad in listOf("[]", "null", "\"title\"")) fails("The appearance overrides must be an object") { apply(bad) }
        fails("Unknown key \"overrides.blocks\"") { apply("{\"blocks\":[]}") }
        fails("Unknown key \"overrides.colors.sepia\"") { apply("{\"colors\":{\"sepia\":{}}}") }
        fails("Missing key \"overrides.colors.light.text\"") {
            apply("""{"colors":{"light":{"accent":"#000000","action":"#000000","background":"#000000","surface":"#000000"}}}""")
        }
        fails("Missing key \"overrides.welcome.text\"") { apply("{\"welcome\":{\"title\":\"W\"}}") }
        fails("Unknown key \"overrides.contact.items\"") { apply("{\"contact\":{\"title\":\"C\",\"text\":\"\",\"items\":[]}}") }
        fails("Missing key \"overrides.topics.items\"") { apply("{\"topics\":{\"title\":\"T\"}}") }
        fails("Missing key \"overrides.topics.items[0].category\"") { apply("{\"topics\":{\"title\":\"T\",\"items\":[{\"label\":\"A\",\"icon\":\"\"}]}}") }
        fails("overrides.title must be a string") { apply("{\"title\":null}") }
        fails("overrides.mode must be system or light or dark") { apply("{\"mode\":\"night\"}") }
        fails("overrides.rotation must be game or free") { apply("{\"rotation\":\"FREE\"}") }
        fails("overrides.topics.items must be an array") { apply("{\"topics\":{\"title\":\"T\",\"items\":{}}}") }
        // Overrides pass through the same checks as setters.
        fails("The title must not be blank") { apply("{\"title\":\"  \"}") }
        fails("Topic 1 category must be a category key") { apply("{\"topics\":{\"title\":\"T\",\"items\":[{\"label\":\"A\",\"category\":\"A\",\"icon\":\"\"}]}}") }
        fails("The dark accent color must be #RRGGBB") {
            apply("""{"colors":{"dark":{"accent":"red","action":"#000000","background":"#000000","surface":"#000000","text":"#000000"}}}""")
        }
        // A bad base fails before overrides are read.
        fails("Appearance version 2 is required") { Appearance.Builder("{\"version\":1}").overrides("{}") }
    }

    @Test
    fun freeRotationFromTheThemeTurnsSupportFreely() {
        val game = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        fun orientation(appearance: Appearance?) = SupportSettings.orientation(appearance?.rotation == Appearance.Rotation.FREE, game)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, orientation(Appearance.Builder(custom).build()))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, orientation(Appearance.Builder().rotation(Appearance.Rotation.FREE).build()))
        assertEquals(game, orientation(Appearance.Builder().build()))
        assertEquals(game, orientation(Appearance.Builder(custom).rotation(Appearance.Rotation.GAME).build()))
        assertEquals(game, orientation(null))
    }
}
