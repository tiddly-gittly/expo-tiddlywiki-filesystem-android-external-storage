import Foundation

/// TiddlyWiki batch file parser — iOS equivalent of the Kotlin implementation.
/// Parses .tid, .json, and .meta files and returns a JSON array string.
enum TiddlerParser {

  /// Parse a batch of tiddler files and return a JSON array string.
  static func batchParse(filePaths: [String], quickLoadMode: Bool) -> String {
    let fm = FileManager.default
    var results = [[String: Any]]()

    for path in filePaths {
      guard fm.fileExists(atPath: path) else { continue }
      let filename = (path as NSString).lastPathComponent
      let ext = (filename as NSString).pathExtension.lowercased()

      switch ext {
      case "tid":
        if let tiddler = parseDotTid(path: path, quickLoadMode: quickLoadMode) {
          results.append(tiddler)
        }
      case "json":
        if let parsed = parseDotJson(path: path) {
          if let array = parsed as? [[String: Any]] {
            results.append(contentsOf: array)
          } else if let single = parsed as? [String: Any] {
            results.append(single)
          }
        }
      case "meta":
        if let tiddler = parseDotMeta(path: path, quickLoadMode: quickLoadMode) {
          results.append(tiddler)
        }
      default:
        break
      }
    }

    guard let jsonData = try? JSONSerialization.data(withJSONObject: results, options: []) else {
      return "[]"
    }
    return String(data: jsonData, encoding: .utf8) ?? "[]"
  }

  // ─── .tid parser ─────────────────────────────────────────────────

  private static func parseDotTid(path: String, quickLoadMode: Bool) -> [String: Any]? {
    guard let content = try? String(contentsOfFile: path, encoding: .utf8) else { return nil }

    var fields = [String: Any]()

    // Find the first blank line separating headers from body
    let blankLinePattern = try? NSRegularExpression(pattern: "\\r?\\n\\r?\\n")
    let range = NSRange(content.startIndex..<content.endIndex, in: content)
    let match = blankLinePattern?.firstMatch(in: content, range: range)

    let headerText: String
    let bodyOffset: Int
    let estimatedBodyLength: Int

    if let match = match {
      let headerRange = content.startIndex..<content.index(content.startIndex, offsetBy: match.range.location)
      headerText = String(content[headerRange])
      bodyOffset = match.range.location + match.range.length
      estimatedBodyLength = content.utf8.count - bodyOffset
    } else {
      headerText = content
      bodyOffset = -1
      estimatedBodyLength = 0
    }

    // Parse header lines
    for line in headerText.components(separatedBy: .newlines) {
      guard let colonIndex = line.firstIndex(of: ":") else { continue }
      let fieldName = String(line[line.startIndex..<colonIndex]).trimmingCharacters(in: .whitespaces)
      let fieldValue = String(line[line.index(after: colonIndex)...]).trimmingCharacters(in: .whitespaces)
      if !fieldName.isEmpty {
        fields[fieldName] = fieldValue
      }
    }

    // Use filename as title fallback
    if fields["title"] == nil {
      fields["title"] = getTitleFromFilename((path as NSString).lastPathComponent)
    }

    let title = fields["title"] as? String ?? ""
    let type = fields["type"] as? String ?? ""
    let hasModuleType = fields["module-type"] != nil
    let hasPluginType = fields["plugin-type"] != nil

    // Determine if we should include full text
    let shouldIncludeText: Bool
    if quickLoadMode {
      shouldIncludeText = shouldPreserveFullTextInQuickLoad(
        title: title, type: type, hasModuleType: hasModuleType, hasPluginType: hasPluginType
      )
    } else {
      shouldIncludeText = shouldSaveFullTiddler(
        title: title, type: type, hasModuleType: hasModuleType, hasPluginType: hasPluginType,
        estimatedTextLength: estimatedBodyLength
      )
    }

    if shouldIncludeText, bodyOffset >= 0, estimatedBodyLength > 0 {
      let bodyStartIndex = content.index(content.startIndex, offsetBy: bodyOffset)
      fields["text"] = String(content[bodyStartIndex...])
    } else if !shouldIncludeText {
      fields.removeValue(forKey: "text")
      fields["_is_skinny"] = "yes"
    }

    return fields
  }

  // ─── .json parser ────────────────────────────────────────────────

  private static func parseDotJson(path: String) -> Any? {
    guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)) else { return nil }

    guard let json = try? JSONSerialization.jsonObject(with: data) else { return nil }

    if let array = json as? [[String: Any]] {
      let filtered = array.filter { $0["title"] != nil }
      return filtered.isEmpty ? nil : filtered
    } else if let obj = json as? [String: Any] {
      if obj["title"] != nil {
        return obj
      }
      // Plugin bundle format — skip
      return nil
    }
    return nil
  }

  // ─── .meta parser ───────────────────────────────────────────────

  private static let textCompanionExtensions: Set<String> = [
    "json", "js", "css", "svg", "txt", "html", "htm"
  ]

  private static func parseDotMeta(path: String, quickLoadMode: Bool) -> [String: Any]? {
    guard let metaContent = try? String(contentsOfFile: path, encoding: .utf8) else { return nil }

    var fields = [String: Any]()

    for line in metaContent.components(separatedBy: .newlines) {
      guard let colonIndex = line.firstIndex(of: ":") else { continue }
      let fieldName = String(line[line.startIndex..<colonIndex]).trimmingCharacters(in: .whitespaces)
      let fieldValue = String(line[line.index(after: colonIndex)...]).trimmingCharacters(in: .whitespaces)
      if !fieldName.isEmpty {
        fields[fieldName] = fieldValue
      }
    }

    if fields["title"] == nil {
      let metaFilename = (path as NSString).lastPathComponent
      let baseName = (metaFilename as NSString).deletingPathExtension  // removes .meta
      fields["title"] = getTitleFromFilename(baseName)
    }

    // Find companion file
    let companionPath = (path as NSString).deletingPathExtension  // removes .meta
    let fm = FileManager.default

    if fm.fileExists(atPath: companionPath) {
      let companionExt = (companionPath as NSString).pathExtension.lowercased()

      if companionExt == "json" {
        // .meta + .json pair
        let title = fields["title"] as? String ?? ""
        let type = fields["type"] as? String ?? ""
        let hasModuleType = fields["module-type"] != nil
        let hasPluginType = fields["plugin-type"] != nil

        let shouldIncludeText: Bool
        if quickLoadMode {
          shouldIncludeText = shouldPreserveFullTextInQuickLoad(
            title: title, type: type, hasModuleType: hasModuleType, hasPluginType: hasPluginType
          )
        } else {
          shouldIncludeText = true
        }

        if shouldIncludeText {
          if let jsonContent = try? String(contentsOfFile: companionPath, encoding: .utf8) {
            fields["text"] = jsonContent
          }
        } else {
          fields["_is_skinny"] = "yes"
        }
      } else if textCompanionExtensions.contains(companionExt) {
        // Text companion — include if not quick-load-skinny
        let title = fields["title"] as? String ?? ""
        let type = fields["type"] as? String ?? ""
        let hasModuleType = fields["module-type"] != nil
        let hasPluginType = fields["plugin-type"] != nil

        let shouldIncludeText: Bool
        if quickLoadMode {
          shouldIncludeText = shouldPreserveFullTextInQuickLoad(
            title: title, type: type, hasModuleType: hasModuleType, hasPluginType: hasPluginType
          )
        } else {
          shouldIncludeText = true
        }

        if shouldIncludeText {
          if let textContent = try? String(contentsOfFile: companionPath, encoding: .utf8) {
            fields["text"] = textContent
          }
        } else {
          fields["_is_skinny"] = "yes"
        }
      }
      // Binary companions (images etc.) — no text set
    }

    return fields["title"] != nil ? fields : nil
  }

  // ─── Helpers ─────────────────────────────────────────────────────

  static func getTitleFromFilename(_ filename: String) -> String {
    var name = filename
    for ext in [".tid", ".json", ".meta"] {
      if name.lowercased().hasSuffix(ext) {
        name = String(name.dropLast(ext.count))
        break
      }
    }
    return name
  }

  static func shouldSaveFullTiddler(
    title: String, type: String, hasModuleType: Bool, hasPluginType: Bool,
    estimatedTextLength: Int
  ) -> Bool {
    if shouldPreserveFullTextInQuickLoad(title: title, type: type, hasModuleType: hasModuleType, hasPluginType: hasPluginType) {
      return true
    }
    if estimatedTextLength < 10000 { return true }
    return false
  }

  static func shouldPreserveFullTextInQuickLoad(
    title: String, type: String, hasModuleType: Bool, hasPluginType: Bool
  ) -> Bool {
    if title.hasPrefix("$:/") { return true }
    if type == "application/json" && hasPluginType { return true }
    if hasModuleType { return true }
    return false
  }
}
