package io.legado.app.domain.model.settings

import io.legado.app.domain.model.TranslationConstants

data class TranslationSettings(
    val provider: String = TranslationConstants.PROVIDER_GOOGLE,
    val targetLanguage: String = "zh",
    val maxCharsPerChunk: Int = 10000,
    val granularity: String = TranslationConstants.DEFAULT_GRANULARITY,
)
