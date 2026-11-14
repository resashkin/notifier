package dev.notifier

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Edits one rule on the phone (the Mini App has the same editor). Opened from Settings → Rules, or with
 * a long press on a notification in the Notifications tab (EXTRA_EXAMPLE_*: start from that notification).
 * The preview below the form runs the rules on the recent log, so it shows what would happen.
 */
class RuleEditActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var rule: Rule
    private var isNew = true
    private var example: LogEntry? = null

    private val kinds = listOf(Rule.Kind.MUTE to "Mute", Rule.Kind.ALWAYS to "Always forward", Rule.Kind.MAP to "Rewrite")
    private val actions = listOf(
        Rule.Action.DROP to "Drop: not sent at all",
        Rule.Action.DIGEST to "Digest: sent together at the digest times",
        Rule.Action.SILENT to "Silent: sent without a sound",
    )

    private lateinit var kindGroup: RadioGroup
    private lateinit var actionGroup: RadioGroup
    private lateinit var actionBox: View
    private lateinit var mapBox: View
    private lateinit var appsButton: Button
    private lateinit var chats: EditText
    private lateinit var words: EditText
    private lateinit var regex: EditText
    private lateinit var cut: EditText
    private lateinit var cutRegex: EditText
    private lateinit var links: CheckBox
    private lateinit var parse: EditText
    private lateinit var out: EditText
    private lateinit var outTitle: EditText
    private lateinit var name: EditText
    private lateinit var on: Switch
    private lateinit var problem: TextView
    private lateinit var preview: TextView
    private lateinit var save: Button
    private var apps = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val id = intent.getStringExtra(EXTRA_RULE_ID)
        val existing = id?.let { rid -> prefs.rules().firstOrNull { it.id == rid } }
        isNew = existing == null
        intent.getStringExtra(EXTRA_EXAMPLE_PKG)?.let { pkg ->
            example = LogEntry(
                intent.getLongExtra(EXTRA_EXAMPLE_TIME, System.currentTimeMillis()), appLabel(this, pkg),
                intent.getStringExtra(EXTRA_EXAMPLE_TITLE).orEmpty(), intent.getStringExtra(EXTRA_EXAMPLE_TEXT).orEmpty(),
                "", pkg = pkg,
            )
        }
        rule = existing ?: example?.let { ex ->
            Rule(kind = Rule.Kind.MUTE, apps = listOf(ex.pkg), chats = listOfNotNull(ex.title.trim().ifEmpty { null }))
        } ?: Rule(kind = Rule.Kind.MUTE)
        title = if (isNew) "New rule" else "Rule"
        setContentView(buildForm())
        fill(rule)
        refresh()
    }

    private fun buildForm(): View {
        val pad = dp(16)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun section(text: String) = form.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            setPadding(0, dp(20), 0, dp(4))
        })
        fun hint(text: String) = form.addView(TextView(this).apply {
            this.text = text
            alpha = 0.7f
            textSize = 13f
        })
        fun field(label: String, multiLine: Boolean = false, mono: Boolean = false): EditText {
            hint(label)
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                if (mono) typeface = android.graphics.Typeface.MONOSPACE
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                addTextChangedListener(watcher)
                form.addView(this)
            }
        }

        kindGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            kinds.forEachIndexed { i, (_, label) -> addView(RadioButton(this@RuleEditActivity).apply { this.id = 100 + i; text = label }) }
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        form.addView(kindGroup)

        actionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@RuleEditActivity).apply {
                text = "Action"
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                setPadding(0, dp(20), 0, dp(4))
            })
            actionGroup = RadioGroup(this@RuleEditActivity).apply {
                actions.forEachIndexed { i, (_, label) -> addView(RadioButton(this@RuleEditActivity).apply { this.id = 200 + i; text = label }) }
                setOnCheckedChangeListener { _, _ -> refresh() }
            }
            addView(actionGroup)
        }
        form.addView(actionBox)

        section("When")
        hint("All filled conditions must match; within a list, any entry. Letter case and ё/е don't matter.")
        appsButton = Button(this).apply {
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { pickApps() }
        }
        form.addView(appsButton)
        chats = field("Chat or sender, exactly (one per line)", multiLine = true)
        words = field("Words or phrases, any of them (comma-separated)")
        regex = field("Regex", mono = true)

        mapBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(mapBox)
        // Fields below go into mapBox: build them in the form, then move.
        val before = form.childCount
        section("Clean up")
        cut = field("Cut phrases (one per line)", multiLine = true)
        cutRegex = field("Cut regex", mono = true)
        links = CheckBox(this).apply {
            text = "Remove links"
            setOnCheckedChangeListener { _, _ -> refresh() }
            form.addView(this)
        }
        section("Template")
        hint("Name parts of the text with (?<name>…) and use {name} in the text below; also {title}, {text}, {app}, {1}. If the regex doesn't match, the cleaned text is sent.")
        parse = field("Regex for the text", mono = true)
        out = field("Text")
        outTitle = field("Title (empty = keep)")
        val mapViews = (before until form.childCount).map { form.getChildAt(it) }
        mapViews.forEach { form.removeView(it); (mapBox as LinearLayout).addView(it) }

        section("Name")
        name = field("Optional")
        on = Switch(this).apply {
            text = "Rule is on"
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        form.addView(on)
        problem = TextView(this).apply { setTextColor(0xFFC62828.toInt()) }
        form.addView(problem)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        save = Button(this).apply {
            text = if (isNew) "Add rule" else "Save"
            setOnClickListener { save() }
        }
        buttons.addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!isNew) buttons.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener { confirmDelete() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(buttons)

        section("Preview")
        preview = TextView(this).apply { setTextIsSelectable(true) }
        form.addView(preview)

        return ScrollView(this).apply { addView(form) }
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = refresh()
    }

    private var filling = false

    private fun fill(r: Rule) {
        filling = true
        kindGroup.check(100 + kinds.indexOfFirst { it.first == r.kind })
        actionGroup.check(200 + actions.indexOfFirst { it.first == r.action })
        apps = r.apps
        chats.setText(r.chats.joinToString("\n"))
        words.setText(r.words.joinToString(", "))
        regex.setText(r.regex)
        cut.setText(r.cut.joinToString("\n"))
        cutRegex.setText(r.cutRegex)
        links.isChecked = r.links
        parse.setText(r.parse)
        out.setText(r.out)
        outTitle.setText(r.outTitle)
        name.setText(r.name)
        on.isChecked = r.on
        filling = false
    }

    /** The rule as the form describes it. */
    private fun current(): Rule {
        fun lines(e: EditText) = e.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return rule.copy(
            kind = kinds.getOrNull(kindGroup.checkedRadioButtonId - 100)?.first ?: Rule.Kind.MUTE,
            on = on.isChecked,
            name = name.text.toString().trim(),
            apps = apps,
            chats = lines(chats),
            words = words.text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            regex = regex.text.toString().trim(),
            action = actions.getOrNull(actionGroup.checkedRadioButtonId - 200)?.first ?: Rule.Action.DROP,
            cut = lines(cut),
            cutRegex = cutRegex.text.toString().trim(),
            links = links.isChecked,
            parse = parse.text.toString().trim(),
            out = out.text.toString().trim(),
            outTitle = outTitle.text.toString().trim(),
        )
    }

    private fun problemOf(r: Rule): String? = when {
        r.kind != Rule.Kind.MAP && !r.hasConditions -> "Add at least one condition: an app, a chat, a word or a regex."
        listOf(r.regex, r.cutRegex, r.parse).any { !RuleEngine.isValidRegex(it) } -> "A regex has an error."
        else -> null
    }

    private fun refresh() {
        if (filling || !::preview.isInitialized) return
        val r = current()
        actionBox.visibility = if (r.kind == Rule.Kind.MUTE) View.VISIBLE else View.GONE
        mapBox.visibility = if (r.kind == Rule.Kind.MAP) View.VISIBLE else View.GONE
        appsButton.text = "Apps: " + if (apps.isEmpty()) "any" else apps.joinToString { appLabel(this, it) }
        val p = problemOf(r)
        problem.text = p.orEmpty()
        problem.visibility = if (p == null) View.GONE else View.VISIBLE
        save.isEnabled = p == null
        preview.text = previewText(r)
    }

    /** The example first, then the recent notifications this rule matches, with what all the rules would do. */
    private fun previewText(r: Rule): String {
        val all = prefs.rules().let { list ->
            if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else list + r
        }
        val log = prefs.recent().filter { it.pkg.isNotEmpty() && it.pkg != packageName }
        fun describe(e: LogEntry): String {
            val n = Incoming(e.pkg, e.title, e.text, e.app)
            val v = RuleEngine.evaluate(all, n)
            val outcome = when (v.action) {
                Rule.Action.DROP -> "🔕 dropped"
                Rule.Action.DIGEST -> "📋 into the digest"
                Rule.Action.SILENT -> "🤫 sent silently"
                null -> "✅ forwarded"
            }
            val changed = v.action != Rule.Action.DROP && (v.title != n.title || v.text != n.text)
            return buildString {
                append(e.app).append(" · ").append(clock(this@RuleEditActivity, e.time)).append('\n')
                if (e.title.isNotBlank()) append(e.title).append('\n')
                append(e.text.take(300)).append('\n')
                append(outcome)
                if (changed) append(" as:\n").append(listOf(v.title, v.text).filter { it.isNotBlank() }.joinToString("\n"))
            }
        }
        val counts = r.on && (r.hasConditions || r.kind == Rule.Kind.MAP)
        val matching = if (counts) log.filter { RuleEngine.matches(r, Incoming(it.pkg, it.title, it.text, it.app)) } else emptyList()
        return buildString {
            example?.let { ex ->
                val matches = counts && RuleEngine.matches(r, Incoming(ex.pkg, ex.title, ex.text, ex.app))
                append("Example — rule ").append(if (matches) "matches" else "doesn't match").append(":\n")
                append(describe(ex)).append("\n\n")
            }
            if (log.isEmpty()) append("No recent notifications to try the rule on yet.")
            else {
                append("Matches ${matching.size} of the last ${log.size}")
                matching.take(8).forEach { append("\n\n").append(describe(it)) }
            }
        }
    }

    private fun pickApps() {
        val seen = prefs.seenApps()
        val known = linkedMapOf<String, String>()
        seen.keys().forEach { known[it] = seen.getJSONObject(it).optString("l", it) }
        prefs.recent().filter { it.pkg.isNotEmpty() && it.pkg != packageName }.forEach { known.putIfAbsent(it.pkg, it.app) }
        example?.let { known.putIfAbsent(it.pkg, it.app) }
        apps.forEach { known.putIfAbsent(it, appLabel(this, it)) }
        val entries = known.entries.sortedBy { it.value.lowercase() }
        val checked = entries.map { it.key in apps }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Apps")
            .setMultiChoiceItems(entries.map { it.value }.toTypedArray(), checked) { _, i, value -> checked[i] = value }
            .setPositiveButton("OK") { _, _ ->
                apps = entries.filterIndexed { i, _ -> checked[i] }.map { it.key }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val r = current()
        if (problemOf(r) != null) return
        // Through JSON so it compares equal to what the Mini App sends back (the sync diffs by value).
        val normalized = Rule.fromJson(r.toJson()) ?: return
        prefs.editRules { list ->
            if (list.any { it.id == normalized.id }) list.map { if (it.id == normalized.id) normalized else it } else list + normalized
        }
        Toast.makeText(this, if (isNew) "Rule added" else "Rule saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage("Delete this rule?")
            .setPositiveButton("Delete") { _, _ ->
                prefs.editRules { list -> list.filter { it.id != rule.id } }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_EXAMPLE_PKG = "example_pkg"
        const val EXTRA_EXAMPLE_TITLE = "example_title"
        const val EXTRA_EXAMPLE_TEXT = "example_text"
        const val EXTRA_EXAMPLE_TIME = "example_time"
    }
}
