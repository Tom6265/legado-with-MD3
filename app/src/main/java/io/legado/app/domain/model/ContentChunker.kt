package io.legado.app.domain.model

object ContentChunker {

    private val sentenceDelimiters = listOf('。', '！', '？', '.', '!', '?', '；', ';', '\n')

    fun chunk(text: String, maxCharsPerChunk: Int = 3000): List<TextChunk> {
        if (text.isBlank()) return emptyList()

        val normalizedText = text.replace("\r\n", "\n")
        val paragraphs = normalizedText.split("\n\n").filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<TextChunk>()
        var currentChunk = StringBuilder()
        var currentParagraphIndices = mutableListOf<Int>()
        var currentChunkParagraphCount = 0

        for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
            if (currentChunk.isEmpty()) {
                currentChunk.append(paragraph)
                currentParagraphIndices.add(paragraphIndex)
                currentChunkParagraphCount = 1
            } else if (currentChunk.length + paragraph.length + 2 <= maxCharsPerChunk) {
                currentChunk.append("\n\n").append(paragraph)
                currentParagraphIndices.add(paragraphIndex)
                currentChunkParagraphCount++
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(
                        TextChunk(
                            chunks.size,
                            currentChunk.toString(),
                            currentParagraphIndices.toList()
                        )
                    )
                }
                currentChunk = StringBuilder(paragraph)
                currentParagraphIndices = mutableListOf(paragraphIndex)
                currentChunkParagraphCount = 1
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(
                TextChunk(
                    chunks.size,
                    currentChunk.toString(),
                    currentParagraphIndices.toList()
                )
            )
        }

        val result = mutableListOf<TextChunk>()
        for (chunk in chunks) {
            if (chunk.content.length > maxCharsPerChunk) {
                result.addAll(splitOversizedChunk(chunk, maxCharsPerChunk))
            } else {
                result.add(chunk)
            }
        }

        return result.mapIndexed { index, chunk -> chunk.copy(index = index) }
    }

    private fun splitOversizedChunk(chunk: TextChunk, maxCharsPerChunk: Int): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        val paragraphs = chunk.content.split("\n\n")
        var currentSubChunk = StringBuilder()
        var currentParagraphIndices = mutableListOf<Int>()
        var subChunkIndex = chunk.index * 100

        for ((idx, paragraph) in paragraphs.withIndex()) {
            if (paragraph.length > maxCharsPerChunk) {
                if (currentSubChunk.isNotEmpty()) {
                    result.add(
                        TextChunk(
                            subChunkIndex++,
                            currentSubChunk.toString(),
                            currentParagraphIndices.toList()
                        )
                    )
                    currentSubChunk = StringBuilder()
                    currentParagraphIndices = mutableListOf()
                }
                result.addAll(
                    splitOversizedParagraph(
                        paragraph,
                        chunk.paragraphIndices.getOrElse(idx) { idx },
                        subChunkIndex,
                        maxCharsPerChunk
                    )
                )
                subChunkIndex += 100
            } else if (currentSubChunk.length + paragraph.length + 2 <= maxCharsPerChunk) {
                currentParagraphIndices.add(chunk.paragraphIndices.getOrElse(idx) { idx })
                if (currentSubChunk.isNotEmpty()) {
                    currentSubChunk.append("\n\n")
                }
                currentSubChunk.append(paragraph)
            } else {
                if (currentSubChunk.isNotEmpty()) {
                    result.add(
                        TextChunk(
                            subChunkIndex++,
                            currentSubChunk.toString(),
                            currentParagraphIndices.toList()
                        )
                    )
                }
                currentSubChunk = StringBuilder(paragraph)
                currentParagraphIndices =
                    mutableListOf(chunk.paragraphIndices.getOrElse(idx) { idx })
            }
        }

        if (currentSubChunk.isNotEmpty()) {
            result.add(
                TextChunk(
                    subChunkIndex,
                    currentSubChunk.toString(),
                    currentParagraphIndices.toList()
                )
            )
        }

        return result
    }

    private fun splitOversizedParagraph(
        paragraph: String,
        paragraphIndex: Int,
        startIndex: Int,
        maxCharsPerChunk: Int
    ): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        val sentences = mutableListOf<String>()
        var currentSentence = StringBuilder()

        for (char in paragraph) {
            currentSentence.append(char)
            if (char in sentenceDelimiters) {
                sentences.add(currentSentence.toString())
                currentSentence = StringBuilder()
            }
        }
        if (currentSentence.isNotEmpty()) {
            sentences.add(currentSentence.toString())
        }

        var currentChunk = StringBuilder()
        var currentChunkSentences = 0
        var subIndex = startIndex

        for (sentence in sentences) {
            if (currentChunk.length + sentence.length <= maxCharsPerChunk) {
                currentChunk.append(sentence)
                currentChunkSentences++
            } else {
                if (currentChunk.isNotEmpty()) {
                    result.add(
                        TextChunk(
                            subIndex++,
                            currentChunk.toString(),
                            listOf(paragraphIndex)
                        )
                    )
                    currentChunkSentences = 0
                }
                if (sentence.length > maxCharsPerChunk) {
                    var remaining = sentence
                    while (remaining.length > maxCharsPerChunk) {
                        result.add(
                            TextChunk(
                                subIndex++,
                                remaining.substring(0, maxCharsPerChunk),
                                listOf(paragraphIndex)
                            )
                        )
                        remaining = remaining.substring(maxCharsPerChunk)
                    }
                    currentChunk = StringBuilder(remaining)
                } else {
                    currentChunk = StringBuilder(sentence)
                    currentChunkSentences = 1
                }
            }
        }

        if (currentChunk.isNotEmpty()) {
            result.add(TextChunk(subIndex, currentChunk.toString(), listOf(paragraphIndex)))
        }

        return result
    }

    /**
     * Paragraph mode: each paragraph is an independent chunk (no char limit).
     */
    fun chunkByParagraphs(text: String): List<TextChunk> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n")
        return normalized.split("\n\n")
            .filter { it.isNotBlank() }
            .mapIndexed { index, para -> TextChunk(index, para.trim(), listOf(index)) }
    }

    /**
     * Sentence mode: split by paragraphs, then by sentence punctuation; each sentence is a chunk.
     */
    fun chunkBySentences(text: String): List<TextChunk> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n")
        val paragraphs = normalized.split("\n\n").filter { it.isNotBlank() }
        val chunks = mutableListOf<TextChunk>()
        for ((paraIdx, para) in paragraphs.withIndex()) {
            val sentences = splitIntoSentences(para.trim())
            for (sentence in sentences) {
                if (sentence.isNotBlank()) {
                    chunks.add(TextChunk(chunks.size, sentence.trim(), listOf(paraIdx)))
                }
            }
        }
        return chunks
    }

    private fun splitIntoSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch in sentenceDelimiters) {
                sentences.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) sentences.add(current.toString())
        return sentences
    }

    fun merge(chunks: List<TextChunk>): String {
        return chunks.sortedBy { it.index }.joinToString("\n\n") { it.content }
    }
}
