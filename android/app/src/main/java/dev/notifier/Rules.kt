package dev.notifier

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Smart mute and mapper rules: one list, three kinds.
 * - ALWAYS (exception): a matching notification is forwarded even if a mute rule matches it.
 * - MUTE: drop it, send it silently, or keep it for the next digest; the strictest matching mute wins
 *   (drop > digest > silent).
 * - MAP: rewrite what is sent — cut phrases, a regex and links, or parse the text with a regex and fill a
 *   template. Only the first matching map rule applies.
 * Order: exceptions → mute (on the original text) → mapper.
 *
 * Conditions are ANDed; a list condition matches if any of its entries does. Mute and exception rules need
 * at least one condition (an empty one would silence or let through everything); a map rule without
 * conditions applies to all notifications.
 *
 * The Mini App edits the same JSON and previews with its own engine (miniapp/lib/rules.dart). Both run the
 * cases in spec/rules-cases.json, so they behave the same: change both, and the cases, together.
 */
data class Rule(
    val id: String = newId(),
    val kind: Kind,
    val on: Boolean = true,
    val name: String = "",
    // Conditions
    val apps: List<String> = emptyList(),
    val chats: List<String> = emptyList(),
    val words: List<String> = emptyList(),
    val regex: String = "",
    // Mute
    val action: Action = Action.DROP,
    // Map
    val cut: List<String> = emptyList(),
    val cutRegex: String = "",
    val links: Boolean = false,
    val parse: String = "",
    val out: String = "",
    val outTitle: String = "",
) {
    enum class Kind(val key: String) { ALWAYS("always"), MUTE("mute"), MAP("map") }

    /** [severity]: the strictest matching mute rule decides. */
    enum class Action(val key: String, val severity: Int) { SILENT("silent", 1), DIGEST("digest", 2), DROP("drop", 3) }

    val hasConditions get() = apps.isNotEmpty() || chats.isNotEmpty() || words.isNotEmpty() || regex.isNotBlank()

    /** "Mute this app" as the per-app switches make it: drop, and the app is the only condition. */
    val isAppMute get() = kind == Kind.MUTE && action == Action.DROP && apps.isNotEmpty() &&
        chats.isEmpty() && words.isEmpty() && regex.isBlank()

    /** The name, or a summary of the conditions: Сбербанк · «900» · “акция”, “скидк”. */
    fun label(appName: (String) -> String, t: Lang = Lang.EN): String = name.ifBlank {
        listOfNotNull(
            apps.takeIf { it.isNotEmpty() }?.joinToString { appName(it) },
            chats.takeIf { it.isNotEmpty() }?.joinToString { "«$it»" },
            words.takeIf { it.isNotEmpty() }?.joinToString { "“$it”" },
            regex.takeIf { it.isNotBlank() }?.let { "/$it/" },
        ).joinToString(" · ").ifEmpty { t("all notifications", "все уведомления") }
    }

    /** Compact JSON (it travels in the Mini App link): defaults are left out. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("k", kind.key)
        if (!on) put("on", false)
        if (name.isNotBlank()) put("n", name)
        if (apps.isNotEmpty()) put("apps", JSONArray(apps))
        if (chats.isNotEmpty()) put("chats", JSONArray(chats))
        if (words.isNotEmpty()) put("words", JSONArray(words))
        if (regex.isNotBlank()) put("re", regex)
        if (kind == Kind.MUTE) put("a", action.key)
        if (cut.isNotEmpty()) put("cut", JSONArray(cut))
        if (cutRegex.isNotBlank()) put("cutRe", cutRegex)
        if (links) put("links", true)
        if (parse.isNotBlank()) put("parse", parse)
        if (out.isNotBlank()) put("out", out)
        if (outTitle.isNotBlank()) put("outTitle", outTitle)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)

        /** Null for an unknown kind (a newer Mini App), so it's skipped rather than misread. */
        fun fromJson(o: JSONObject): Rule? {
            val kind = Kind.entries.firstOrNull { it.key == o.optString("k") } ?: return null
            fun strings(key: String) = o.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
            }.orEmpty()
            return Rule(
                id = o.optString("id").ifBlank { newId() },
                kind = kind,
                on = o.optBoolean("on", true),
                name = o.optString("n"),
                apps = strings("apps"),
                chats = strings("chats"),
                words = strings("words"),
                regex = o.optString("re"),
                action = Action.entries.firstOrNull { it.key == o.optString("a") } ?: Action.DROP,
                cut = strings("cut"),
                cutRegex = o.optString("cutRe"),
                links = o.optBoolean("links"),
                parse = o.optString("parse"),
                out = o.optString("out"),
                outTitle = o.optString("outTitle"),
            )
        }

        fun listFromJson(json: String): List<Rule> {
            val a = try {
                JSONArray(json)
            } catch (e: Exception) {
                return emptyList()
            }
            return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
        }

        fun listToJson(rules: List<Rule>): String = JSONArray(rules.map { it.toJson() }).toString()
    }
}

/**
 * Changes from one rule list to another, for the Mini App sync (MiniAppState): [up] = new or changed rules,
 * [del] = removed ids, [ord] = the full id order, only when applying [up]/[del] alone wouldn't give it.
 * The Mini App has the same two functions (miniapp/lib/rules.dart).
 */
data class RulesDiff(val up: List<Rule>, val del: List<String>, val ord: List<String>?) {

    val isEmpty get() = up.isEmpty() && del.isEmpty() && ord == null

    fun toJson(): JSONObject = JSONObject()
        .put("up", JSONArray(up.map { it.toJson() }))
        .put("del", JSONArray(del))
        .apply { if (ord != null) put("ord", JSONArray(ord)) }

    fun applyTo(base: List<Rule>): List<Rule> {
        val changed = up.associateBy { it.id }
        val out = base.filter { it.id !in del }.map { changed[it.id] ?: it }.toMutableList()
        val have = out.map { it.id }.toSet()
        out += up.filter { it.id !in have }
        if (ord != null) {
            val index = ord.withIndex().associate { it.value to it.index }
            out.sortBy { index[it.id] ?: Int.MAX_VALUE }  // stable: rules missing from ord keep their order, last
        }
        return out
    }

    companion object {
        fun between(old: List<Rule>, new: List<Rule>): RulesDiff {
            val before = old.associateBy { it.id }
            val newIds = new.map { it.id }.toSet()
            val up = new.filter { before[it.id] != it }
            val del = old.map { it.id }.filter { it !in newIds }
            val order = new.map { it.id }
            val plain = RulesDiff(up, del, null)
            return if (plain.applyTo(old).map { it.id } == order) plain else plain.copy(ord = order)
        }

        fun fromJson(o: JSONObject?): RulesDiff {
            fun ids(key: String) = o?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            val up = o?.optJSONArray("up")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Rule::fromJson) } }
            return RulesDiff(up.orEmpty(), ids("del").orEmpty(), ids("ord"))
        }
    }
}

/** A notification as the rules see it: [title] is the chat or sender, [text] the message. */
data class Incoming(val pkg: String, val title: String, val text: String, val app: String = "")

/** What to do with a notification: [action] null = forward normally. [title]/[text] are after the mapper. */
data class Verdict(
    val action: Rule.Action?,
    val title: String,
    val text: String,
    val mute: Rule? = null,
    val exception: Rule? = null,
    val map: Rule? = null,
)

object RuleEngine {

    /** Compiled patterns by source; null = invalid (such a condition never matches). */
    private val patterns = ConcurrentHashMap<String, Pattern>()
    private val INVALID: Pattern = Pattern.compile("(?!)")

    private val LINKS = pattern("(?:https?://|www\\.)\\S+|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}/\\S*")
    private val PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*|\\d+)\\}")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val SPACE_BEFORE_PUNCT = Regex(" +([,.!?:;])")

    fun evaluate(rules: List<Rule>, n: Incoming): Verdict {
        val active = rules.filter { it.on }
        val exception = active.firstOrNull { it.kind == Rule.Kind.ALWAYS && it.hasConditions && matches(it, n) }
        val mute = if (exception != null) null else active
            .filter { it.kind == Rule.Kind.MUTE && it.hasConditions && matches(it, n) }
            .maxByOrNull { it.action.severity }
        if (mute?.action == Rule.Action.DROP) return Verdict(Rule.Action.DROP, n.title, n.text, mute = mute)
        val map = active.firstOrNull { it.kind == Rule.Kind.MAP && matches(it, n) }
        val (title, text) = map?.let { apply(it, n) } ?: (n.title to n.text)
        return Verdict(mute?.action, title, text, mute, exception, map)
    }

    fun matches(rule: Rule, n: Incoming): Boolean {
        if (rule.apps.isNotEmpty() && n.pkg !in rule.apps) return false
        if (rule.chats.isNotEmpty() && rule.chats.none { fold(it.trim()) == fold(n.title.trim()) }) return false
        val all = n.title + "\n" + n.text
        if (rule.words.isNotEmpty()) {
            val haystack = fold(all)
            if (rule.words.none { it.isNotBlank() && fold(it.trim()) in haystack }) return false
        }
        if (rule.regex.isNotBlank() && !pattern(rule.regex).matcher(all).find()) return false
        return true
    }

    /** The map rule's result: (title, text). A template whose regex doesn't match leaves the cleaned original. */
    fun apply(rule: Rule, n: Incoming): Pair<String, String> {
        var text = n.text
        for (phrase in rule.cut) if (phrase.isNotBlank()) {
            text = pattern(Pattern.quote(phrase.trim())).matcher(text).replaceAll("")
        }
        if (rule.cutRegex.isNotBlank()) text = pattern(rule.cutRegex).matcher(text).replaceAll("")
        if (rule.links) text = LINKS.matcher(text).replaceAll("")
        text = tidy(text)

        var title = n.title
        if (rule.parse.isNotBlank()) {
            val p = pattern(rule.parse)
            val m = p.matcher(n.text).takeIf { it.find() } ?: p.matcher(n.title + "\n" + n.text).takeIf { it.find() }
            if (m != null) {
                val cleaned = text
                text = tidy(fill(rule.out.ifBlank { "{text}" }, m, n, cleaned))
                if (rule.outTitle.isNotBlank()) title = tidy(fill(rule.outTitle, m, n, cleaned))
            }
        }
        return title to text
    }

    /** Whether [source] compiles (for the editors). */
    fun isValidRegex(source: String): Boolean = source.isBlank() || pattern(source) !== INVALID

    /** {name} = a named group, {1} = a numbered one; {title}, {text} (cleaned) and {app} are always there. */
    private fun fill(template: String, m: Matcher, n: Incoming, cleaned: String): String {
        val out = StringBuffer()
        val p = PLACEHOLDER.matcher(template)
        while (p.find()) {
            val key = p.group(1)!!
            val value = when {
                key.all { it.isDigit() } -> key.toInt().takeIf { it <= m.groupCount() }?.let { m.group(it) }
                else -> try {
                    m.group(key)
                } catch (e: IllegalArgumentException) {
                    when (key) {
                        "title" -> n.title
                        "text" -> cleaned
                        "app" -> n.app
                        else -> null
                    }
                }
            }
            p.appendReplacement(out, Matcher.quoteReplacement(value?.trim().orEmpty()))
        }
        p.appendTail(out)
        return out.toString()
    }

    /** Single spaces, no space before punctuation, no leading/trailing blank lines, at most one blank line. */
    fun tidy(s: String): String {
        val lines = s.split('\n').map { it.replace(SPACES, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim() }
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    /** Case-insensitive comparison that also treats ё as е. */
    private fun fold(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е')

    private fun pattern(source: String): Pattern = patterns.getOrPut(source) {
        try {
            Pattern.compile(source, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        } catch (e: Exception) {
            INVALID
        }
    }
}
