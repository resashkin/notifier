package dev.notifier

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Mini App's ready-made rules (miniapp/assets/presets.json) must work the same on the phone. */
class PresetsTest {

    @Test
    fun samples() {
        val presets = JSONObject(File("../../miniapp/assets/presets.json").readText()).getJSONArray("presets")
        val failures = mutableListOf<String>()
        for (i in 0 until presets.length()) {
            val p = presets.getJSONObject(i)
            val id = p.getString("id")
            val rule = Rule.fromJson(p.getJSONObject("rule")) ?: run { failures += "$id: rule not read"; continue }
            val samples = p.getJSONArray("samples")
            for (j in 0 until samples.length()) {
                val s = samples.getJSONObject(j)
                val input = s.getJSONObject("in")
                val n = Incoming(input.getString("pkg"), input.getString("title"), input.getString("text"))
                val v = RuleEngine.evaluate(listOf(rule), n)
                val expect = s.getJSONObject("expect")
                val action = if (expect.isNull("action")) null else expect.getString("action")
                if (v.action?.key != action) failures += "$id #$j: action ${v.action?.key}, expected $action"
                if (expect.has("title") && v.title != expect.getString("title")) {
                    failures += "$id #$j: title «${v.title}», expected «${expect.getString("title")}»"
                }
                if (expect.has("text") && v.text != expect.getString("text")) {
                    failures += "$id #$j: text «${v.text}», expected «${expect.getString("text")}»"
                }
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
