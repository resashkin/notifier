package dev.notifier

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs the shared cases in spec/rules-cases.json; the Mini App's engine runs the same file. */
class RulesSpecTest {

    @Test
    fun sharedCases() {
        val spec = JSONObject(File("../../spec/rules-cases.json").readText())
        val cases = spec.getJSONArray("cases")
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val rules = Rule.listFromJson(c.getJSONArray("rules").toString())
            val input = c.getJSONObject("in")
            val n = Incoming(input.getString("pkg"), input.getString("title"), input.getString("text"), input.optString("app"))
            val v = RuleEngine.evaluate(rules, n)
            val expect = c.getJSONObject("expect")
            val action = if (expect.isNull("action")) null else expect.getString("action")
            val gotTitle = expect.optString("title", n.title)
            if (v.action?.key != action) failures += "${c.getString("name")}: action ${v.action?.key}, expected $action"
            if (expect.has("title") && v.title != gotTitle) failures += "${c.getString("name")}: title «${v.title}», expected «$gotTitle»"
            if (expect.has("text") && v.text != expect.getString("text")) {
                failures += "${c.getString("name")}: text «${v.text}», expected «${expect.getString("text")}»"
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun jsonRoundTrip() {
        val rule = Rule(kind = Rule.Kind.MAP, name = "Bank", chats = listOf("900"), cut = listOf("ПАО"), links = true,
            parse = "(?<a>\\d+)", out = "{a}", outTitle = "B")
        assertEquals(rule, Rule.fromJson(JSONObject(rule.toJson().toString())))
        val mute = Rule(kind = Rule.Kind.MUTE, apps = listOf("x"), action = Rule.Action.DIGEST, on = false)
        assertEquals(mute, Rule.fromJson(JSONObject(mute.toJson().toString())))
    }
}
