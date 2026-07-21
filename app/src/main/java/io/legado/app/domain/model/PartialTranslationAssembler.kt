package io.legado.app.domain.model

/**
 * Assembles mixed original/translated content as translation progresses.
 * Uses original chunk order and replaces chunks with translations when available.
 */
object PartialTranslationAssembler {

    /**
     * Assemble mixed content from original chunks and translated portions (chapter mode).
     *
     * @param originalChunks The original text chunks in order
     * @param translatedMap Map of chunk index to translated content
     * @return Mixed content string with translated chunks replacing originals
     */
    fun assemble(originalChunks: List<TextChunk>, translatedMap: Map<Int, String>): String {
        if (originalChunks.isEmpty()) return ""

        val result = StringBuilder()
        for (chunk in originalChunks) {
            val translated = translatedMap[chunk.index]
            val content = translated ?: chunk.content

            if (result.isNotEmpty()) {
                result.append("\n\n")
            }
            result.append(content)
        }

        return result.toString()
    }

    /**
     * Paragraph bilingual assemble: original + "\n" + translation per paragraph, no blank lines between pairs.
     * Inserts LOADING_MARKER at the end of the currently translating paragraph.
     */
    fun assembleParagraph(
        originalChunks: List<TextChunk>,
        translatedMap: Map<Int, String>,
        translatingChunkIndex: Int? = null
    ): String {
        if (originalChunks.isEmpty()) return ""
        return originalChunks.joinToString("\n") { chunk ->
            val translation = translatedMap[chunk.index]
            when {
                translation != null -> "${chunk.content}\n$translation"
                chunk.index == translatingChunkIndex ->
                    "${chunk.content}${TranslationConstants.TRANSLATION_LOADING_MARKER}"
                else -> chunk.content
            }
        }
    }

    /**
     * Sentence bilingual assemble: translation appended directly after original (same line, no separator).
     * Inserts LOADING_MARKER at the end of the currently translating sentence.
     */
    fun assembleSentence(
        originalChunks: List<TextChunk>,
        translatedMap: Map<Int, String>,
        translatingChunkIndex: Int? = null
    ): String {
        if (originalChunks.isEmpty()) return ""
        return buildString {
            for (chunk in originalChunks) {
                val translation = translatedMap[chunk.index]
                when {
                    translation != null -> append(chunk.content).append(translation)
                    chunk.index == translatingChunkIndex ->
                        append(chunk.content).append(TranslationConstants.TRANSLATION_LOADING_MARKER)
                    else -> append(chunk.content)
                }
            }
        }
    }

    /**
     * Check if we have any translations yet.
     */
    fun hasPartialTranslation(translatedMap: Map<Int, String>): Boolean {
        return translatedMap.isNotEmpty()
    }

    /**
     * Get the count of translated chunks vs total.
     */
    fun progress(translatedMap: Map<Int, String>, totalChunks: Int): Pair<Int, Int> {
        return Pair(translatedMap.size, totalChunks)
    }
}
