package dev.notifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule-list diff used by the Mini App sync (miniapp/test/rules_test.dart checks the Dart side the same way). */
class RulesDiffTest {

    private fun r(id: String, word: String = "x") = Rule(id = id, kind = Rule.Kind.MUTE, words = listOf(word))

    @Test
    fun applyingTheDiffGivesTheNewList() {
        val old = listOf(r("a"), r("b"), r("c"))
        val new = listOf(r("c"), r("a", "changed"), r("d"))
        val diff = RulesDiff.between(old, new)
        assertEquals(listOf("b"), diff.del)
        assertEquals(listOf("a", "d"), diff.up.map { it.id })
        assertEquals(new, diff.applyTo(old))
    }

    @Test
    fun appendingNeedsNoOrder() = assertNull(RulesDiff.between(listOf(r("a")), listOf(r("a"), r("b"))).ord)

    @Test
    fun nothingChanged() = assertTrue(RulesDiff.between(listOf(r("a"), r("b")), listOf(r("a"), r("b"))).isEmpty)

    @Test
    fun survivesJson() {
        val diff = RulesDiff.between(listOf(r("a"), r("b")), listOf(r("b"), r("a")))
        val back = RulesDiff.fromJson(org.json.JSONObject(diff.toJson().toString()))
        assertEquals(listOf("b", "a"), back.applyTo(listOf(r("a"), r("b"))).map { it.id })
    }
}
