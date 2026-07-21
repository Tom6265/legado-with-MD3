# 翻译粒度功能扩展规划文档

## 一、现状分析

### 当前翻译逻辑：逐章翻译

项目现有翻译功能以**章节**为最小翻译单元，工作流程如下：

1. `TranslationManager.startTranslation(book, chapter)` 触发翻译任务
2. `TranslateChapterUseCase.execute()` 读取整章原文
3. `ContentChunker.chunk(text, maxCharsPerChunk)` 按**字数上限**将章节切成若干 Chunk（每个 Chunk 包含若干完整段落）
4. 各 Chunk 并发提交给翻译引擎（Google Translate 或 App AI）
5. `PartialTranslationAssembler.assemble()` 在翻译过程中将已完成的 Chunk 与未完成的原文混拼，实时呈现进度
6. 翻译完成后，合并所有 Chunk，将**纯译文**写入缓存文件（`.{language}.nb`），完全替换原文展示

### 关键文件清单

| 文件路径 | 职责 |
|---|---|
| `domain/model/TranslationConstants.kt` | 常量、提供商、语言列表 |
| `domain/model/settings/TranslationSettings.kt` | 翻译设置域模型 |
| `domain/gateway/TranslationSettingsGateway.kt` | 设置网关接口 + 更新密封类 |
| `domain/usecase/TranslateChapterUseCase.kt` | 核心翻译逻辑（分块、请求、重试、缓存） |
| `domain/model/ContentChunker.kt` | 文本分块策略（目前按字数分组段落） |
| `domain/model/PartialTranslationAssembler.kt` | 翻译中途的混合内容组装 |
| `domain/gateway/TranslationCacheGateway.kt` | 缓存读写接口 |
| `data/repository/TranslationCacheRepositoryImpl.kt` | 缓存文件命名与管理实现 |
| `data/repository/FeatureSettingsRepositories.kt` | `TranslationSettingsRepository` 实现 |
| `model/translation/TranslationManager.kt` | 翻译任务编排（单例） |
| `model/translation/TranslationChapterState.kt` | 章节翻译运行时状态 |
| `ui/config/translation/TranslationConfig.kt` | SharedPreferences 配置对象 |
| `ui/config/translation/TranslationConfigContract.kt` | MVI 契约（UiState / Intent / Effect） |
| `ui/config/translation/TranslationConfigScreen.kt` | 翻译设置 UI |
| `ui/config/translation/TranslationConfigViewModel.kt` | 翻译设置 ViewModel |
| `constant/PreferKey.kt` | SharedPreferences 键常量 |
| `data/entities/Book.kt` | `Book.ReadConfig.translationMode: Boolean` |
| `res/values/strings.xml` | 英文字符串资源 |
| `res/values-zh-rCN/strings.xml` | 中文字符串资源 |

---

## 二、需求描述

在翻译设置页面新增**翻译粒度**下拉选项，支持三种模式：

| 选项 | 内部值 | 含义 |
|---|---|---|
| 逐章翻译 | `chapter` | 现有行为：翻译整章，展示纯译文（默认） |
| 逐段翻译 | `paragraph` | 每段原文下紧跟对应译文，双语交错展示 |
| 逐句翻译 | `sentence` | 每句原文下紧跟对应译文，双语交错展示 |

### 各模式展示效果

**逐章模式**（现有行为不变）：
```
译文第一段

译文第二段
```

**逐段模式** — 原段落下一行紧跟译段落，段对之间无空行：
```
原文第一段
译文第一段
原文第二段
译文第二段
```

翻译进行中时，正在翻译的段落末尾显示进度小圆圈：
```
原文第一段
译文第一段
原文第二段⌛
原文第三段
```

**逐句模式** — 译文直接跟在原句后面，同行拼接，无任何分隔符：
```
原文第一句译文第一句原文第二句译文第二句
```

翻译进行中时，正在翻译的句子末尾显示进度小圆圈：
```
原文第一句译文第一句原文第二句⌛原文第三句
```

**逐章模式**（现有行为不变，进度以百分比显示，无需额外改动）

---

## 三、设计决策

### 3.1 粒度枚举放置位置

在 `TranslationConstants.kt` 中新增 `TranslationGranularity` 枚举（与现有常量同文件，保持一致性）：

```kotlin
enum class TranslationGranularity(val value: String) {
    CHAPTER("chapter"),
    PARAGRAPH("paragraph"),
    SENTENCE("sentence");

    companion object {
        fun fromValue(value: String) = entries.find { it.value == value } ?: CHAPTER
    }
}
```

同时在 `TranslationConstants` 中添加：
```kotlin
val granularityDisplayNames = listOf("逐章翻译", "逐段翻译", "逐句翻译")
val granularityValues = listOf("chapter", "paragraph", "sentence")
const val DEFAULT_GRANULARITY = "chapter"
```

### 3.2 缓存文件命名策略

为避免三种模式的缓存相互冲突，扩展缓存文件名后缀规则：

| 模式 | 缓存文件名 |
|---|---|
| CHAPTER（现有） | `{chapterFile}.{lang}.nb`（**向后兼容，不变**） |
| PARAGRAPH | `{chapterFile}.{lang}_para.nb` |
| SENTENCE | `{chapterFile}.{lang}_sent.nb` |

实现方式：在 `TranslationCacheGateway` 所有涉及 `targetLanguage` 的方法中，传入的 `targetLanguage` 字符串由调用方负责附加粒度后缀。调用方（`TranslationManager`）根据当前粒度设置计算 `effectiveLanguage`：

```kotlin
fun effectiveLanguage(targetLanguage: String, granularity: TranslationGranularity): String =
    when (granularity) {
        TranslationGranularity.CHAPTER -> targetLanguage
        TranslationGranularity.PARAGRAPH -> "${targetLanguage}_para"
        TranslationGranularity.SENTENCE -> "${targetLanguage}_sent"
    }
```

这种方式**无需修改 `TranslationCacheGateway` 接口签名**，改动最小。

### 3.3 分块策略

在 `ContentChunker` 中新增两个方法：

- `chunkByParagraphs(text: String): List<TextChunk>` — 以 `\n\n` 为分隔符，每个段落作为一个独立 Chunk
- `chunkBySentences(text: String): List<TextChunk>` — 先按段落分割，再对每个段落按句子标点（`。！？.!?；;`）分句，每句作为一个独立 Chunk

### 3.4 翻译请求策略

**逐段 / 逐句模式**的每个 Chunk 已经是单一段落或单句，无需字数限制分割，直接发送整个 Chunk。AI 提示词无需返回 `[dictionary]`/`[result]` 格式，使用简化提示：

```
Translate the following [paragraph/sentence] to {targetLanguage}. 
Return only the translation, no explanation.
```

Google Translate 提供商不受影响，直接翻译每个 Chunk。

**逐章模式**中的词典积累（`[dictionary]`格式）不适用于逐句/逐段（单句太短，词典意义不大），因此逐段/逐句模式跳过词典更新逻辑，简化处理。

### 3.5 `TranslateChapterUseCase` 修改方式

在 `execute()` 中新增参数 `granularity: TranslationGranularity`（默认 `CHAPTER`）：

```kotlin
suspend fun execute(
    book: Book,
    bookChapter: BookChapter,
    granularity: TranslationGranularity = TranslationGranularity.CHAPTER,
    onProgress: (TranslationProgress) -> Unit,
    onTranslateStarted: () -> Unit
): Result<String>
```

内部根据 `granularity` 分支：
- `CHAPTER`：现有逻辑不变
- `PARAGRAPH`：调用 `ContentChunker.chunkByParagraphs()`，组装时交错原文与译文
- `SENTENCE`：调用 `ContentChunker.chunkBySentences()`，组装时交错原文与译文

### 3.6 组装策略

在 `PartialTranslationAssembler` 中新增方法，所有新方法接收一个可选的 `translatingChunkIndex: Int?`，指示当前正在翻译的 Chunk 索引（用于插入进度标记）：

```kotlin
// 逐段：原段落 + "\n" + 译段落，段对之间 "\n"（无空行）
// 正在翻译的段落末尾插入 LOADING_MARKER
fun assembleParagraph(
    originalChunks: List<TextChunk>,
    translatedMap: Map<Int, String>,
    translatingChunkIndex: Int? = null
): String

// 逐句：原句末尾直接拼接译文（同行），句间无分隔
// 正在翻译的句子末尾插入 LOADING_MARKER
fun assembleSentence(
    originalChunks: List<TextChunk>,
    translatedMap: Map<Int, String>,
    translatingChunkIndex: Int? = null
): String
```

对于翻译进行中时的 partial 展示：已翻译的 Chunk 显示"原文 + 译文"，当前翻译中的 Chunk 在原文末尾插入进度标记，尚未开始的 Chunk 仅显示原文。

---

### 3.7 翻译进度标记（Loading Marker）

#### 标记常量

在 `TranslationConstants.kt` 中新增：

```kotlin
// 插入文本末尾表示"正在翻译"的占位标记
// 渲染层识别此标记并替换为动画 Spinner
const val TRANSLATION_LOADING_MARKER = "￹TRANSLATING￺"
```

使用 Unicode 私有标签区字符包裹，确保不与正文内容冲突（阅读器渲染时这两个字符不会显示为可见字形）。

#### 组装层处理

`assembleParagraph` 和 `assembleSentence` 方法在遇到 `translatingChunkIndex` 对应的 Chunk 时，在其原文末尾拼接 `TRANSLATION_LOADING_MARKER`：

```
// 逐段中正在翻译第2段：
原文第一段\n译文第一段\n原文第二段[MARKER]\n原文第三段

// 逐句中正在翻译第2句：
原文第一句译文第一句原文第二句[MARKER]原文第三句
```

#### 渲染层处理（`ReadBookActivity` 所在的文本渲染模块）

文本渲染流程中检测 `TRANSLATION_LOADING_MARKER`，将其替换为一个 `AnimatedImageSpan`（或等效的旋转动画 Drawable）。实现要点：

- 在 `SpannableStringBuilder` 中定位标记位置，用 `ImageSpan` 替换
- 动画：使用 `ObjectAnimator` 对 Drawable 旋转属性做 0°→360° 无限循环，或使用 `AnimationDrawable` 帧动画（帧：`◐ → ◓ → ◑ → ◒`）
- 渲染刷新：每次 `mixedContent` 更新时重新构建 Spannable，保证标记位置跟随翻译进度移动
- 若渲染模块难以支持动画 Drawable，可退而使用静态 Unicode 圆圈字符 `◌` 作为标记（实现简单，视觉上仍清晰可识别）

#### `translatingChunkIndex` 的确定

`TranslationChapterState` 已有 `currentChunk: Int` 字段，表示当前正在翻译的 Chunk 序号。`TranslationManager` 在调用 `assemble*` 方法时，将 `state.currentChunk` 作为 `translatingChunkIndex` 传入即可。

---

## 四、详细修改清单

### Step 1：领域常量与枚举
**文件：`domain/model/TranslationConstants.kt`**

- 新增 `TranslationGranularity` 枚举（嵌套在文件内）
- 新增 `granularityDisplayNames: List<String>`（`["逐章翻译", "逐段翻译", "逐句翻译"]`）
- 新增 `granularityValues: List<String>`（`["chapter", "paragraph", "sentence"]`）
- 新增 `DEFAULT_GRANULARITY = "chapter"` 常量
- 新增翻译进度标记常量：
  ```kotlin
  // 插入文本末尾、表示"正在翻译"的占位标记；渲染层将其替换为动画 Spinner
  const val TRANSLATION_LOADING_MARKER = "￹TRANSLATING￺"
  ```

---

### Step 2：设置键常量
**文件：`constant/PreferKey.kt`**

- 在翻译相关键末尾新增：
  ```kotlin
  const val llmTranslationGranularity = "llmTranslationGranularity"
  ```

---

### Step 3：域模型
**文件：`domain/model/settings/TranslationSettings.kt`**

- 新增字段：
  ```kotlin
  val granularity: String = TranslationConstants.DEFAULT_GRANULARITY
  ```

---

### Step 4：设置网关
**文件：`domain/gateway/TranslationSettingsGateway.kt`**

- 在 `TranslationSettingsUpdate` 密封接口中新增：
  ```kotlin
  data class Granularity(val value: String) : TranslationSettingsUpdate
  ```

---

### Step 5：设置仓库实现
**文件：`data/repository/FeatureSettingsRepositories.kt`**

- `TranslationSettingsRepository.settings` Flow 中读取新键 `PreferKey.llmTranslationGranularity`，写入 `TranslationSettings.granularity`
- `update()` 中处理 `TranslationSettingsUpdate.Granularity` 分支，写入 `PreferKey.llmTranslationGranularity`

---

### Step 6：Config 对象
**文件：`ui/config/translation/TranslationConfig.kt`**

- 新增属性：
  ```kotlin
  var llmTranslationGranularity by prefDelegate(
      PreferKey.llmTranslationGranularity,
      TranslationConstants.DEFAULT_GRANULARITY
  )
  ```
- 暴露常量代理（与现有 `providerDisplayNames` 等保持一致）：
  ```kotlin
  val granularityDisplayNames get() = TranslationConstants.granularityDisplayNames
  val granularityValues get() = TranslationConstants.granularityValues
  ```

---

### Step 7：MVI 契约
**文件：`ui/config/translation/TranslationConfigContract.kt`**

- `TranslationConfigUiState` 中的 `settings: TranslationSettings` 已隐式包含新字段，**无需修改 UiState**
- `TranslationConfigIntent` 新增：
  ```kotlin
  data class SetGranularity(val value: String) : TranslationConfigIntent
  ```

---

### Step 8：ViewModel
**文件：`ui/config/translation/TranslationConfigViewModel.kt`**

- `onIntent()` 中处理 `TranslationConfigIntent.SetGranularity`：
  ```kotlin
  is TranslationConfigIntent.SetGranularity ->
      TranslationSettingsUpdate.Granularity(intent.value)
  ```

---

### Step 9：翻译设置 UI
**文件：`ui/config/translation/TranslationConfigScreen.kt`**

- 在"翻译选项"（`R.string.translation_options`）`SplicedColumnGroup` 中，在**目标语言**下拉框之前或之后插入翻译粒度下拉框：
  ```kotlin
  DropdownListSettingItem(
      title = stringResource(R.string.translation_granularity),
      selectedValue = settings.granularity,
      displayEntries = TranslationConstants.granularityDisplayNames.toTypedArray(),
      entryValues = TranslationConstants.granularityValues.toTypedArray(),
      onValueChange = { onIntent(TranslationConfigIntent.SetGranularity(it)) },
  )
  ```
- 下拉框位置建议：放在目标语言下拉框**之后**、每块字数滑块**之前**

---

### Step 10：文本分块策略
**文件：`domain/model/ContentChunker.kt`**

新增两个方法：

```kotlin
/**
 * 逐段模式：每个段落作为独立 Chunk，忽略字数限制。
 */
fun chunkByParagraphs(text: String): List<TextChunk> {
    val normalized = text.replace("\r\n", "\n")
    return normalized.split("\n\n")
        .filter { it.isNotBlank() }
        .mapIndexed { index, para -> TextChunk(index, para.trim(), listOf(index)) }
}

/**
 * 逐句模式：先按段落分割，再对每段分句，每句作为独立 Chunk。
 */
fun chunkBySentences(text: String): List<TextChunk> {
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
```

---

### Step 11：双语内容组装
**文件：`domain/model/PartialTranslationAssembler.kt`**

新增两个方法，均接受 `translatingChunkIndex: Int?` 用于插入进度标记：

```kotlin
/**
 * 逐段双语组装：原段落 + "\n" + 译段落，段对之间 "\n"，无空行分隔。
 * 正在翻译的段落末尾插入 LOADING_MARKER；未开始的段落仅显示原文。
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
 * 逐句双语组装：译文直接拼接在原句末尾（同行，无分隔符）。
 * 正在翻译的句子末尾插入 LOADING_MARKER；未开始的句子仅显示原文。
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
```

---

### Step 12：核心翻译 Use Case
**文件：`domain/usecase/TranslateChapterUseCase.kt`**

修改 `execute()` 签名，新增 `granularity` 参数：

```kotlin
suspend fun execute(
    book: Book,
    bookChapter: BookChapter,
    granularity: TranslationGranularity = TranslationGranularity.CHAPTER,
    onProgress: (TranslationProgress) -> Unit,
    onTranslateStarted: () -> Unit
): Result<String>
```

内部主要分支逻辑（在 `execute()` 开头拿到 `granularity` 后）：

```kotlin
val chunks = when (granularity) {
    TranslationGranularity.CHAPTER -> ContentChunker.chunk(
        originalContent,
        TranslationConfig.llmMaxCharsPerChunk.coerceAtLeast(1000)
    )
    TranslationGranularity.PARAGRAPH -> ContentChunker.chunkByParagraphs(originalContent)
    TranslationGranularity.SENTENCE -> ContentChunker.chunkBySentences(originalContent)
}
```

组装 `mixedContent` 时，根据 `granularity` 调用对应的 Assembler 方法，并传入当前正在翻译的 Chunk 索引（用于插入进度标记）：

```kotlin
// currentlyTranslatingIndex = 当前批次中第一个尚未完成的 chunk 的 index
val currentlyTranslatingIndex = chunks.firstOrNull { it.index !in translatedChunks }?.index

val mixedContent = when (granularity) {
    TranslationGranularity.CHAPTER ->
        PartialTranslationAssembler.assemble(chunks, translatedChunks)
    TranslationGranularity.PARAGRAPH ->
        PartialTranslationAssembler.assembleParagraph(chunks, translatedChunks, currentlyTranslatingIndex)
    TranslationGranularity.SENTENCE ->
        PartialTranslationAssembler.assembleSentence(chunks, translatedChunks, currentlyTranslatingIndex)
}
```

对于逐段/逐句模式，跳过词典（dictionary）更新逻辑（因为单段/单句过短，词典积累意义不大），使用简化版提示词（见下方）。

**简化提示词（用于逐段/逐句）：**
在 `translateWithAiGateway()` 中根据 `granularity` 传入不同的系统提示构建逻辑。可以在 `TranslationConstants` 中增加：

```kotlin
const val PARAGRAPH_PROMPT =
    "You are a professional translator. Translate the following paragraph to {language}. " +
    "Return only the translation, no explanation, no commentary."

const val SENTENCE_PROMPT =
    "You are a professional translator. Translate the following sentence to {language}. " +
    "Return only the translation, no explanation, no commentary."
```

逐段/逐句模式下，AI 响应直接作为翻译结果（无需解析 `[dictionary]`/`[result]` 格式）。

---

### Step 13：翻译管理器
**文件：`model/translation/TranslationManager.kt`**

- `startTranslation()` 中读取当前粒度设置并传递给 `TranslateChapterUseCase`：
  ```kotlin
  val granularity = TranslationGranularity.fromValue(TranslationConfig.llmTranslationGranularity)
  ```

- `hasTranslatedCache()` 和 `getCachedTranslation()` 使用 `effectiveLanguage()` 计算语言键：
  ```kotlin
  private fun currentEffectiveLanguage(): String =
      effectiveLanguage(
          TranslationConfig.llmTargetLanguage,
          TranslationGranularity.fromValue(TranslationConfig.llmTranslationGranularity)
      )
  ```

- 新增私有辅助方法：
  ```kotlin
  private fun effectiveLanguage(lang: String, granularity: TranslationGranularity): String =
      when (granularity) {
          TranslationGranularity.CHAPTER -> lang
          TranslationGranularity.PARAGRAPH -> "${lang}_para"
          TranslationGranularity.SENTENCE -> "${lang}_sent"
      }
  ```

- `deleteTranslationCache()` 也需要基于当前粒度确定使用哪个缓存文件（同上）。

---

### Step 14：字符串资源

**`res/values/strings.xml`** 新增（建议在翻译相关字符串块内）：
```xml
<string name="translation_granularity">Translation Granularity</string>
<string name="translation_granularity_chapter">Per Chapter</string>
<string name="translation_granularity_paragraph">Per Paragraph</string>
<string name="translation_granularity_sentence">Per Sentence</string>
```

**`res/values-zh-rCN/strings.xml`** 新增：
```xml
<string name="translation_granularity">翻译粒度</string>
<string name="translation_granularity_chapter">逐章翻译</string>
<string name="translation_granularity_paragraph">逐段翻译</string>
<string name="translation_granularity_sentence">逐句翻译</string>
```

> 注：`TranslationConstants` 中的 `granularityDisplayNames` 目前使用硬编码中文，这在多语言环境下需要改为从字符串资源读取。但由于翻译设置 UI 只有在 Compose 环境下才能访问资源，简单处理方式是 `TranslationConfigScreen.kt` 中直接用 `stringArrayResource` 或分别列出 `stringResource`（而非在 Constants 里硬编码）。具体实现方式由接手的 Agent 选择最符合项目风格的方案。

---

### Step 15：翻译进度 Spinner 渲染
**文件：读书界面文本渲染模块（`ReadBookActivity` 相关的内容渲染代码）**

目标：检测文本中的 `TranslationConstants.TRANSLATION_LOADING_MARKER`，将其替换为旋转动画圆圈。

实现要点：

1. **定位渲染入口**：找到将章节 `mixedContent` 转为屏幕可见文字的代码路径（通常是自定义 `ReadView` / `ContentTextView` 或章节内容 Spannable 构建处）

2. **替换为 AnimatedSpan**：
   ```kotlin
   val spannable = SpannableStringBuilder(content)
   val markerStr = TranslationConstants.TRANSLATION_LOADING_MARKER
   var idx = spannable.indexOf(markerStr)
   while (idx >= 0) {
       val spinnerDrawable = buildSpinnerDrawable(textSize)  // 见下方
       spinnerDrawable.setBounds(0, 0, spinnerDrawable.intrinsicWidth, spinnerDrawable.intrinsicHeight)
       spannable.replace(idx, idx + markerStr.length, " ")
       spannable.setSpan(ImageSpan(spinnerDrawable, ImageSpan.ALIGN_BASELINE), idx, idx + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
       idx = spannable.indexOf(markerStr)
   }
   ```

3. **Spinner 动画实现**（推荐两种，二选一）：

   - **方案 A（帧动画，简单）**：使用 `AnimationDrawable`，帧序列为 `◐ → ◓ → ◑ → ◒`，每帧 150ms，无限循环
   - **方案 B（属性动画，流畅）**：用 `ProgressBar` 的 `indeterminateDrawable` 提取系统圆形进度 Drawable，或自绘一个旋转弧度 Drawable + `ObjectAnimator` 做 0°→360° 无限旋转

4. **动画刷新**：动画 Drawable 每帧需触发 `view.invalidate()`。在 `AnimationDrawable.setCallback(view)` 后调用 `start()`，或在 `ObjectAnimator` 的 `addUpdateListener` 中调用 `view.invalidate()`

5. **静态兜底**：若渲染模块难以支持动画 Drawable，可退而将 `TRANSLATION_LOADING_MARKER` 替换为静态 Unicode 字符 `⌛`（U+231B）或 `◌`（U+25CC），保证视觉上有明确的"翻译中"提示，待后续迭代再实现动画

---

## 五、修改文件汇总

| 优先级 | 文件 | 修改类型 |
|---|---|---|
| 1 | `domain/model/TranslationConstants.kt` | 新增枚举 + 常量（含 `TRANSLATION_LOADING_MARKER`） |
| 2 | `constant/PreferKey.kt` | 新增键常量 |
| 3 | `domain/model/settings/TranslationSettings.kt` | 新增字段 |
| 4 | `domain/gateway/TranslationSettingsGateway.kt` | 新增密封类变体 |
| 5 | `data/repository/FeatureSettingsRepositories.kt` | 更新读写逻辑 |
| 6 | `ui/config/translation/TranslationConfig.kt` | 新增属性 |
| 7 | `ui/config/translation/TranslationConfigContract.kt` | 新增 Intent |
| 8 | `ui/config/translation/TranslationConfigViewModel.kt` | 处理新 Intent |
| 9 | `ui/config/translation/TranslationConfigScreen.kt` | 新增下拉框 UI |
| 10 | `domain/model/ContentChunker.kt` | 新增分块方法 |
| 11 | `domain/model/PartialTranslationAssembler.kt` | 新增双语组装方法（含 loading marker 参数） |
| 12 | `domain/usecase/TranslateChapterUseCase.kt` | 新增 `granularity` 参数 + 分支逻辑 |
| 13 | `model/translation/TranslationManager.kt` | 读取粒度，传递给 Use Case |
| 14 | `res/values/strings.xml` | 新增字符串 |
| 15 | `res/values-zh-rCN/strings.xml` | 新增字符串 |
| 16 | 读书内容渲染模块（`ReadBookActivity` 相关） | 检测并渲染 loading marker 为动画 Spinner |

---

## 六、注意事项与边界情况

1. **向后兼容**：`CHAPTER` 模式的缓存文件命名保持不变（`.{lang}.nb`），已有缓存无需迁移。

2. **并发数限制**：逐段/逐句模式每个 Chunk 只有几十到几百字，可以适当提高并发数（如上限改为 8），但此处建议复用 `llmConcurrentChunks` 设置，不另立配置，保持界面简洁。

3. **Google Translate 对逐句的适用性**：Google Translate API 天然支持短文本，逐句模式下无需特殊处理，但要注意每个句子都会发起一次 HTTP 请求，对章节较长的书籍（可能几百句）会产生大量请求。可考虑对 Google Translate 提供商在逐句模式下自动将若干句合并为一次请求，但这属于优化项，初版可先不做。

4. **空段落过滤**：`chunkByParagraphs` 和 `chunkBySentences` 都需过滤空字符串，防止产生空 Chunk 并触发无意义的翻译请求。

5. **缓存删除范围**：用户在翻译设置中切换粒度后，旧粒度的缓存文件不自动删除（避免意外清除有效数据）。如需清除，用户通过"清除翻译缓存"功能操作。

6. **读书界面**：`ReadBookViewModel` 中的 `toggleTranslation()` 和 `retranslateCurrentChapter()` 通过 `TranslationManager` 间接调用，`TranslationManager` 内已读取粒度设置，**读书界面代码无需修改**。

7. **`TranslationConstants` 中的展示文本**：`granularityDisplayNames` 中的文本（如"逐章翻译"）在多语言项目中应当通过字符串资源提供。建议 `TranslationConfigScreen.kt` 中使用 `listOf(stringResource(R.string.translation_granularity_chapter), ...)` 构建 `displayEntries`，而非直接从 `TranslationConstants` 读取硬编码字符串。

