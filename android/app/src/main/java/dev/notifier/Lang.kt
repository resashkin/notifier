package dev.notifier

/**
 * Language of the bot's messages: Russian or English, following the Telegram app language of the chat owner
 * (message.from.language_code, saved in Prefs.lang). The phone app itself stays English.
 * Usage: `val t = Lang.of(prefs); t("Paused", "Пауза")`.
 */
class Lang private constructor(val ru: Boolean) {

    operator fun invoke(en: String, ru: String): String = if (this.ru) ru else en

    /** "1 notification" / "5 notifications"; Russian needs three forms ("уведомление", "уведомления", "уведомлений"). */
    fun count(n: Int, en1: String, enMany: String, ru1: String, ru2: String, ru5: String): String {
        if (!ru) return "$n " + if (n == 1) en1 else enMany
        val tens = n % 100
        val ones = n % 10
        val word = when {
            tens in 11..14 -> ru5
            ones == 1 -> ru1
            ones in 2..4 -> ru2
            else -> ru5
        }
        return "$n $word"
    }

    fun notifications(n: Int) = count(n, "notification", "notifications", "уведомление", "уведомления", "уведомлений")

    companion object {
        val EN = Lang(false)
        val RU = Lang(true)

        fun of(prefs: Prefs) = if (prefs.lang == "ru") RU else EN

        /** Telegram's language_code ("ru", "en-US", …) to what Prefs.lang stores. */
        fun fromTelegram(code: String): String = if (code.lowercase().startsWith("ru")) "ru" else "en"
    }
}
