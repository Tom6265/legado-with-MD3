package io.legado.app.domain.model

enum class TranslationGranularity(val value: String) {
    CHAPTER("chapter"),
    PARAGRAPH("paragraph"),
    SENTENCE("sentence");

    companion object {
        fun fromValue(value: String) = entries.find { it.value == value } ?: CHAPTER
    }
}

object TranslationConstants {

    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_APP_AI = "app_ai"
    const val PROVIDER_GOOGLE = "google"
    const val MIN_TEMPERATURE = 0f
    const val MAX_TEMPERATURE = 2f
    const val DEFAULT_TEMPERATURE = 0.7f

    val providerDisplayNames = listOf("Google Translate", "应用 AI 接口")
    val providerValues = listOf(PROVIDER_GOOGLE, PROVIDER_APP_AI)

    val targetLanguages = listOf(
        "zh" to "简体中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
        "ar" to "العربية"
    )

    val granularityDisplayNames = listOf("逐章翻译", "逐段翻译", "逐句翻译")
    val granularityValues = listOf("chapter", "paragraph", "sentence")
    const val DEFAULT_GRANULARITY = "chapter"

    // Private-use tags so the marker never collides with chapter text; render layer swaps it for a spinner.
    const val TRANSLATION_LOADING_MARKER = "￹TRANSLATING￺"
    // Static fallback shown by the reader when animated spinner is not available.
    const val TRANSLATION_LOADING_DISPLAY = "⌛"

    const val PARAGRAPH_PROMPT =
        "You are a professional translator. Translate the following paragraph to {language}. " +
            "Return only the translation, no explanation, no commentary."

    const val SENTENCE_PROMPT =
        "You are a professional translator. Translate the following sentence to {language}. " +
            "Return only the translation, no explanation, no commentary."

    fun effectiveLanguage(targetLanguage: String, granularity: TranslationGranularity): String =
        when (granularity) {
            TranslationGranularity.CHAPTER -> targetLanguage
            TranslationGranularity.PARAGRAPH -> "${targetLanguage}_para"
            TranslationGranularity.SENTENCE -> "${targetLanguage}_sent"
        }

    fun replaceLoadingMarkerForDisplay(content: String): String =
        content.replace(TRANSLATION_LOADING_MARKER, TRANSLATION_LOADING_DISPLAY)

    const val DEFAULT_PROMPT =
        """You are a professional literary translator, please translate according to the following requirements:

1. Keep the original paragraph count and order unchanged
2. Maintain the literary style and tone of the original text
3. Do not summarize, condense, or omit any content
4. Only output the translation result, do not add comments or explanations
5. Keep name consistency across abbreviations/nicknames (e.g., Alexander → Alex → same name). Add nickname mapping to dictionary.

"""

    const val OUTPUT_FORMAT = """Output is divided into two parts:

**New** proper nouns, place names that need to be recorded for context, and the translation result.

Only select the most common and important terms (max 10) to include in the dictionary.

Output format as follows, IMPORTANT, **dictionary** part must begin with english word **[dictionary]**, MUST NOT start with any other words. **result** part must begin with english word **[result]**,  MUST NOT start with any other words:
<example>
[dictionary]
Jack -> 杰克
Harry Port -> 哈利波特

[result]
...
</example>
    """
}
