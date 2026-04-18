package expo.modules.externalstorage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TiddlyWiki tiddler file parser.
 * Supports .tid, .json, and .meta formats with skinny/full text logic.
 */
internal object TiddlyWikiParser {

  /**
   * Parse a batch of TiddlyWiki tiddler files in parallel.
   * Returns a JSON array string ready for injection into TiddlyWiki boot store.
   */
  fun batchParseTidFiles(filePaths: List<String>, quickLoadMode: Boolean): String {
    val results = filePaths.parallelStream().map { path ->
      try {
        parseTiddlerFile(path, quickLoadMode)
      } catch (e: Exception) {
        null
      }
    }.toList()

    val jsonArray = JSONArray()
    for (result in results) {
      if (result == null) continue
      when (result) {
        is JSONObject -> jsonArray.put(result)
        is JSONArray -> {
          for (i in 0 until result.length()) {
            jsonArray.put(result.getJSONObject(i))
          }
        }
      }
    }
    return jsonArray.toString()
  }

  /**
   * Parse a single tiddler file based on extension.
   */
  private fun parseTiddlerFile(path: String, quickLoadMode: Boolean): Any? {
    val file = File(path)
    if (!file.exists()) return null
    val name = file.name

    return when {
      name.endsWith(".tid") -> parseDotTid(file, quickLoadMode)
      name.endsWith(".json") -> parseDotJson(file, quickLoadMode)
      name.endsWith(".meta") -> parseDotMeta(file, quickLoadMode)
      else -> null
    }
  }

  /**
   * Parse a .tid file (TiddlyWiki native format).
   * Format: `key: value\n` headers, blank line, then body text.
   */
  private fun parseDotTid(file: File, quickLoadMode: Boolean): JSONObject? {
    val content = file.readText(Charsets.UTF_8)
    val json = JSONObject()

    val blankLineRegex = Regex("\r?\n\r?\n")
    val match = blankLineRegex.find(content)
    val headerText = if (match != null) content.substring(0, match.range.first) else content
    val bodyOffset = match?.let { it.range.last + 1 } ?: -1
    val estimatedBodyLength = if (bodyOffset >= 0) content.length - bodyOffset else 0

    for (line in headerText.split(Regex("\r?\n"))) {
      val colonIndex = line.indexOf(':')
      if (colonIndex != -1) {
        val fieldName = line.substring(0, colonIndex).trim()
        val fieldValue = line.substring(colonIndex + 1).trim()
        if (fieldName.isNotEmpty()) {
          json.put(fieldName, fieldValue)
        }
      }
    }

    if (!json.has("title")) {
      json.put("title", getTitleFromFilename(file.name))
    }

    val title = json.optString("title", "")
    val type = json.optString("type", "")
    val hasModuleType = json.has("module-type")
    val hasPluginType = json.has("plugin-type")

    val shouldIncludeText = if (quickLoadMode) {
      shouldPreserveFullTextInQuickLoad(title, type, hasModuleType, hasPluginType)
    } else {
      shouldSaveFullTiddler(title, type, hasModuleType, hasPluginType, estimatedBodyLength)
    }

    if (shouldIncludeText && bodyOffset >= 0 && estimatedBodyLength > 0) {
      json.put("text", content.substring(bodyOffset))
    } else if (!shouldIncludeText) {
      json.remove("text")
      json.put("_is_skinny", "yes")
    }

    return json
  }

  /**
   * Parse a .json tiddler file.
   * Can be: single tiddler, array of tiddlers, or plugin bundle.
   */
  private fun parseDotJson(file: File, quickLoadMode: Boolean): Any? {
    val content = file.readText(Charsets.UTF_8)
    val fallbackTitle = getTitleFromFilename(file.name)
    return try {
      if (content.trimStart().startsWith("[")) {
        val array = JSONArray(content)
        val result = JSONArray()
        for (i in 0 until array.length()) {
          val obj = array.optJSONObject(i)
          if (obj != null && obj.has("title")) {
            result.put(obj)
          }
        }
        if (result.length() > 0) {
          result
        } else {
          createStandaloneJsonTiddler(fallbackTitle, content, quickLoadMode)
        }
      } else {
        val obj = JSONObject(content)
        if (obj.has("title")) {
          if (!obj.has("type")) {
            obj.put("type", "application/json")
          }
          obj
        } else if (obj.has("tiddlers")) {
          null
        } else {
          createStandaloneJsonTiddler(fallbackTitle, content, quickLoadMode)
        }
      }
    } catch (_: Exception) {
      createStandaloneJsonTiddler(fallbackTitle, content, quickLoadMode)
    }
  }

  private fun createStandaloneJsonTiddler(title: String, content: String, quickLoadMode: Boolean): JSONObject {
    val json = JSONObject()
    json.put("title", title)
    json.put("type", "application/json")
    if (quickLoadMode) {
      json.put("_is_skinny", "yes")
    } else {
      json.put("text", content)
    }
    return json
  }

  /**
   * Parse a .meta companion file.
   * The .meta has field definitions; actual content is in the companion file.
   */
  private fun parseDotMeta(metaFile: File, quickLoadMode: Boolean): JSONObject? {
    val metaContent = metaFile.readText(Charsets.UTF_8)
    val json = JSONObject()

    for (line in metaContent.split(Regex("\r?\n"))) {
      val colonIndex = line.indexOf(':')
      if (colonIndex != -1) {
        val fieldName = line.substring(0, colonIndex).trim()
        val fieldValue = line.substring(colonIndex + 1).trim()
        if (fieldName.isNotEmpty()) {
          json.put(fieldName, fieldValue)
        }
      }
    }

    if (!json.has("title")) {
      val metaName = metaFile.name
      json.put("title", getTitleFromFilename(metaName.removeSuffix(".meta")))
    }

    val companionPath = metaFile.absolutePath.removeSuffix(".meta")
    val companionFile = File(companionPath)

    if (companionFile.exists()) {
      val tiddlerType = json.optString("type", "text/vnd.tiddlywiki")
      val hasModuleType = json.has("module-type")
      val hasPluginType = json.has("plugin-type")

      val isTextCompanion = companionPath.endsWith(".json") ||
        companionPath.endsWith(".js") ||
        companionPath.endsWith(".css") ||
        companionPath.endsWith(".svg") ||
        companionPath.endsWith(".txt") ||
        companionPath.endsWith(".html") ||
        companionPath.endsWith(".htm") ||
        tiddlerType.startsWith("text/") ||
        tiddlerType == "application/javascript" ||
        tiddlerType == "application/json" ||
        tiddlerType == "application/x-tiddler-dictionary"

      if (isTextCompanion) {
        val shouldIncludeText = if (quickLoadMode) {
          shouldPreserveFullTextInQuickLoad(
            json.optString("title", ""),
            tiddlerType,
            hasModuleType,
            hasPluginType,
          )
        } else {
          true
        }
        if (shouldIncludeText) {
          val textContent = companionFile.readText(Charsets.UTF_8)
          json.put("text", textContent)
        } else {
          json.put("_is_skinny", "yes")
        }
      }
    }

    return if (json.has("title")) json else null
  }

  /**
   * Decide whether a tiddler's full text should be included in the boot store.
   */
  private fun shouldSaveFullTiddler(
    title: String,
    type: String,
    hasModuleType: Boolean,
    hasPluginType: Boolean,
    estimatedTextLength: Int,
  ): Boolean {
    if (shouldPreserveFullTextInQuickLoad(title, type, hasModuleType, hasPluginType)) return true
    if (estimatedTextLength < 10000) return true
    return false
  }

  /**
   * Boot-critical tiddlers that must have full text even in quick-load mode.
   */
  private fun shouldPreserveFullTextInQuickLoad(
    title: String,
    type: String,
    hasModuleType: Boolean,
    hasPluginType: Boolean,
  ): Boolean {
    if (title.startsWith("\$:/")) return true
    if (type == "application/json" && hasPluginType) return true
    if (hasModuleType) return true
    return false
  }

  private fun getTitleFromFilename(filename: String): String {
    return filename
      .removeSuffix(".tid")
      .removeSuffix(".json")
      .removeSuffix(".meta")
  }
}
