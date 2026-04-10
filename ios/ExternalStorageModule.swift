import ExpoModulesCore
import Foundation

/// iOS implementation of the ExternalStorage native module.
/// Provides filesystem operations, TiddlyWiki batch parsing,
/// git status/index building, tar extraction, and streaming HTTP.
///
/// All path arguments are plain filesystem paths — NOT file:// URIs.
public class ExternalStorageModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExternalStorage")

    // ─── Basic queries ─────────────────────────────────────────────

    AsyncFunction("exists") { (path: String) -> Bool in
      FileManager.default.fileExists(atPath: path)
    }

    AsyncFunction("getInfo") { (path: String) -> [String: Any] in
      let fm = FileManager.default
      guard fm.fileExists(atPath: path) else {
        return ["exists": false, "isDirectory": false, "size": 0, "modificationTime": 0]
      }
      let attrs = try fm.attributesOfItem(atPath: path)
      let isDir = (attrs[.type] as? FileAttributeType) == .typeDirectory
      let size = (attrs[.size] as? UInt64) ?? 0
      let mtime = (attrs[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
      return [
        "exists": true,
        "isDirectory": isDir,
        "size": size,
        "modificationTime": Int(mtime * 1000),
      ]
    }

    // ─── Directory operations ──────────────────────────────────────

    AsyncFunction("mkdir") { (path: String) in
      try FileManager.default.createDirectory(
        atPath: path,
        withIntermediateDirectories: true,
        attributes: nil
      )
    }

    AsyncFunction("readDir") { (path: String) -> [String] in
      let fm = FileManager.default
      guard fm.fileExists(atPath: path) else {
        throw NSError(domain: "ENOENT", code: 2, userInfo: [NSLocalizedDescriptionKey: "Directory does not exist: \(path)"])
      }
      return try fm.contentsOfDirectory(atPath: path)
    }

    AsyncFunction("readDirRecursive") { (path: String) -> [String] in
      Self.readDirRecursive(root: path)
    }

    AsyncFunction("rmdir") { (path: String) in
      let fm = FileManager.default
      if fm.fileExists(atPath: path) {
        try fm.removeItem(atPath: path)
      }
    }

    // ─── File read/write ───────────────────────────────────────────

    AsyncFunction("readFileUtf8") { (path: String) -> String in
      guard FileManager.default.fileExists(atPath: path) else {
        throw NSError(domain: "ENOENT", code: 2, userInfo: [NSLocalizedDescriptionKey: "File does not exist: \(path)"])
      }
      return try String(contentsOfFile: path, encoding: .utf8)
    }

    AsyncFunction("readFileBase64") { (path: String) -> String in
      guard FileManager.default.fileExists(atPath: path) else {
        throw NSError(domain: "ENOENT", code: 2, userInfo: [NSLocalizedDescriptionKey: "File does not exist: \(path)"])
      }
      let data = try Data(contentsOf: URL(fileURLWithPath: path))
      return data.base64EncodedString()
    }

    AsyncFunction("writeFileUtf8") { (path: String, content: String) in
      let url = URL(fileURLWithPath: path)
      try FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(),
        withIntermediateDirectories: true,
        attributes: nil
      )
      try content.write(toFile: path, atomically: true, encoding: .utf8)
    }

    AsyncFunction("writeFileBase64") { (path: String, base64Content: String) in
      guard let data = Data(base64Encoded: base64Content) else {
        throw NSError(domain: "InvalidBase64", code: 1, userInfo: nil)
      }
      let url = URL(fileURLWithPath: path)
      try FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(),
        withIntermediateDirectories: true,
        attributes: nil
      )
      try data.write(to: url)
    }

    AsyncFunction("appendFileBase64") { (path: String, base64Content: String, truncateFirst: Bool) in
      guard let data = Data(base64Encoded: base64Content) else {
        throw NSError(domain: "InvalidBase64", code: 1, userInfo: nil)
      }
      let url = URL(fileURLWithPath: path)
      if truncateFirst {
        try FileManager.default.createDirectory(
          at: url.deletingLastPathComponent(),
          withIntermediateDirectories: true,
          attributes: nil
        )
        try data.write(to: url)
      } else {
        let handle = try FileHandle(forWritingTo: url)
        defer { handle.closeFile() }
        handle.seekToEndOfFile()
        handle.write(data)
      }
    }

    AsyncFunction("writeFilesBase64") { (paths: [String], base64Contents: [String]) -> [String: Int] in
      guard paths.count == base64Contents.count else {
        throw NSError(domain: "InvalidArgs", code: 1, userInfo: [NSLocalizedDescriptionKey: "paths and contents must have same length"])
      }
      var written = 0
      for (path, b64) in zip(paths, base64Contents) {
        guard let data = Data(base64Encoded: b64) else { continue }
        let url = URL(fileURLWithPath: path)
        try FileManager.default.createDirectory(
          at: url.deletingLastPathComponent(),
          withIntermediateDirectories: true,
          attributes: nil
        )
        try data.write(to: url)
        written += 1
      }
      return ["writtenCount": written]
    }

    AsyncFunction("deleteFile") { (path: String) in
      let fm = FileManager.default
      if fm.fileExists(atPath: path) {
        try fm.removeItem(atPath: path)
      }
    }

    // ─── Storage queries (iOS equivalents) ─────────────────────────

    AsyncFunction("isExternalStorageWritable") { () -> Bool in
      // iOS always has writable app sandbox
      true
    }

    AsyncFunction("getExternalStorageDirectory") { () -> String in
      // Return the Documents directory
      NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first ?? ""
    }

    AsyncFunction("isExternalStorageManager") { () -> Bool in
      // iOS doesn't have this concept — always true within sandbox
      true
    }

    // ─── Streaming HTTP operations ─────────────────────────────────

    AsyncFunction("httpPostToFile") { (url: String, headersMap: [String: String], bodyBase64: String, destPath: String, contentType: String) -> [String: Any] in
      try await Self.httpPostToFile(
        url: url,
        headers: headersMap,
        bodyBase64: bodyBase64,
        destPath: destPath,
        contentType: contentType
      )
    }

    AsyncFunction("downloadFileResumable") { (url: String, headersMap: [String: String], destPath: String) -> [String: Any] in
      try await Self.downloadFileResumable(url: url, headers: headersMap, destPath: destPath)
    }

    // ─── Chunked file reading ──────────────────────────────────────

    AsyncFunction("readFileChunk") { (path: String, offset: Int, length: Int) -> [String: Any] in
      guard FileManager.default.fileExists(atPath: path) else {
        throw NSError(domain: "ENOENT", code: 2, userInfo: nil)
      }
      let handle = try FileHandle(forReadingFrom: URL(fileURLWithPath: path))
      defer { handle.closeFile() }
      handle.seek(toFileOffset: UInt64(offset))
      let data = handle.readData(ofLength: length)
      return [
        "data": data.base64EncodedString(),
        "bytesRead": data.count,
      ]
    }

    // ─── Tar extraction ────────────────────────────────────────────

    AsyncFunction("extractTar") { (tarPath: String, destDir: String) -> [String: Int] in
      let count = try TarExtractor.extract(tarPath: tarPath, destDir: destDir)
      return ["filesExtracted": count]
    }

    // ─── TiddlyWiki batch parsing ──────────────────────────────────

    AsyncFunction("batchParseTidFiles") { (filePaths: [String], quickLoadMode: Bool) -> String in
      TiddlerParser.batchParse(filePaths: filePaths, quickLoadMode: quickLoadMode)
    }

    // ─── Git operations ────────────────────────────────────────────

    AsyncFunction("gitStatus") { (gitRootDir: String) -> String in
      try GitHelper.gitStatus(rootDir: gitRootDir)
    }

    AsyncFunction("gitStatusDebug") { (gitRootDir: String) -> String in
      try GitHelper.gitStatusDebug(rootDir: gitRootDir)
    }

    AsyncFunction("buildGitIndex") { (gitRootDir: String) -> String in
      try GitHelper.buildGitIndex(rootDir: gitRootDir)
    }

    AsyncFunction("gitPush") { (gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String, force: Bool, headers: String?) -> String in
      try GitHelper.gitPush(gitRootDir: gitRootDir, remoteName: remoteName, localBranch: localBranch, remoteBranch: remoteBranch, force: force, headers: headers)
    }

    AsyncFunction("gitFetch") { (gitRootDir: String, remoteName: String, branch: String, headers: String?) -> String in
      try GitHelper.gitFetch(gitRootDir: gitRootDir, remoteName: remoteName, branch: branch, headers: headers)
    }

    AsyncFunction("gitCheckoutChangedFiles") { (gitRootDir: String, oldOid: String, newOid: String) -> String in
      try GitHelper.gitCheckoutChangedFiles(gitRootDir: gitRootDir, oldOid: oldOid, newOid: newOid)
    }

    AsyncFunction("gitAddAndCommit") { (gitRootDir: String, message: String, authorName: String, authorEmail: String) -> String in
      try GitHelper.gitAddAndCommit(gitRootDir: gitRootDir, message: message, authorName: authorName, authorEmail: authorEmail)
    }

    AsyncFunction("gitReset") { (gitRootDir: String, ref: String, mode: String) -> String in
      try GitHelper.gitReset(gitRootDir: gitRootDir, ref: ref, mode: mode)
    }

    AsyncFunction("gitClone") { (url: String, directory: String, branch: String?, depth: Int, singleBranch: Bool, noTags: Bool, headers: String?) -> String in
      try GitHelper.gitClone(url: url, directory: directory, branch: branch, depth: depth, singleBranch: singleBranch, noTags: noTags, headers: headers)
    }

    AsyncFunction("gitLog") { (gitRootDir: String, ref: String?, maxCount: Int) -> String in
      try GitHelper.gitLog(gitRootDir: gitRootDir, ref: ref, maxCount: maxCount)
    }

    AsyncFunction("gitResolveRef") { (gitRootDir: String, ref: String) -> String in
      try GitHelper.gitResolveRef(gitRootDir: gitRootDir, ref: ref)
    }

    AsyncFunction("gitCurrentBranch") { (gitRootDir: String) -> String in
      try GitHelper.gitCurrentBranch(gitRootDir: gitRootDir)
    }

    AsyncFunction("gitInit") { (directory: String, defaultBranch: String) -> String in
      try GitHelper.gitInit(directory: directory, defaultBranch: defaultBranch)
    }

    AsyncFunction("gitSetConfig") { (gitRootDir: String, section: String, subsection: String?, name: String, value: String) -> String in
      try GitHelper.gitSetConfig(gitRootDir: gitRootDir, section: section, subsection: subsection, name: name, value: value)
    }

    AsyncFunction("gitAddRemote") { (gitRootDir: String, remoteName: String, url: String) -> String in
      try GitHelper.gitAddRemote(gitRootDir: gitRootDir, remoteName: remoteName, url: url)
    }

    AsyncFunction("gitReadBlob") { (gitRootDir: String, ref: String, filepath: String, asBase64: Bool) -> String in
      try GitHelper.gitReadBlob(gitRootDir: gitRootDir, ref: ref, filepath: filepath, asBase64: asBase64)
    }

    AsyncFunction("gitDiffTrees") { (gitRootDir: String, oldRef: String, newRef: String) -> String in
      try GitHelper.gitDiffTrees(gitRootDir: gitRootDir, oldRef: oldRef, newRef: newRef)
    }

    AsyncFunction("gitDiscardFileChanges") { (gitRootDir: String, filepath: String) -> String in
      try GitHelper.gitDiscardFileChanges(gitRootDir: gitRootDir, filepath: filepath)
    }
  }

  // ─── Static helpers ──────────────────────────────────────────────

  private static let skipDirs: Set<String> = [".git", "node_modules", ".DS_Store", "output"]

  static func readDirRecursive(root: String) -> [String] {
    var results = [String]()
    walkDir(base: root, prefix: "", results: &results)
    return results
  }

  private static func walkDir(base: String, prefix: String, results: inout [String]) {
    let fm = FileManager.default
    guard let children = try? fm.contentsOfDirectory(atPath: base) else { return }
    for child in children {
      if skipDirs.contains(child) { continue }
      let fullPath = (base as NSString).appendingPathComponent(child)
      let relPath = prefix.isEmpty ? child : "\(prefix)/\(child)"
      var isDir: ObjCBool = false
      if fm.fileExists(atPath: fullPath, isDirectory: &isDir), isDir.boolValue {
        walkDir(base: fullPath, prefix: relPath, results: &results)
      } else {
        results.append(relPath)
      }
    }
  }

  // ─── Streaming HTTP helpers ──────────────────────────────────────

  private static func httpPostToFile(
    url: String,
    headers: [String: String],
    bodyBase64: String,
    destPath: String,
    contentType: String
  ) async throws -> [String: Any] {
    guard let requestUrl = URL(string: url) else {
      throw NSError(domain: "InvalidURL", code: 1, userInfo: nil)
    }
    guard let bodyData = Data(base64Encoded: bodyBase64) else {
      throw NSError(domain: "InvalidBase64", code: 1, userInfo: nil)
    }

    let destUrl = URL(fileURLWithPath: destPath)
    try FileManager.default.createDirectory(
      at: destUrl.deletingLastPathComponent(),
      withIntermediateDirectories: true,
      attributes: nil
    )

    var request = URLRequest(url: requestUrl)
    request.httpMethod = "POST"
    request.setValue(contentType, forHTTPHeaderField: "Content-Type")
    for (key, value) in headers {
      request.setValue(value, forHTTPHeaderField: key)
    }
    request.httpBody = bodyData
    request.timeoutInterval = 300  // 5 min read timeout

    let (data, response) = try await URLSession.shared.data(for: request)
    let httpResponse = response as? HTTPURLResponse
    let statusCode = httpResponse?.statusCode ?? 0
    let responseHeaders = (httpResponse?.allHeaderFields as? [String: String]) ?? [:]

    // Write response to file
    try data.write(to: destUrl)

    return [
      "statusCode": statusCode,
      "headers": responseHeaders,
      "bytesWritten": data.count,
    ]
  }

  private static func downloadFileResumable(
    url: String,
    headers: [String: String],
    destPath: String
  ) async throws -> [String: Any] {
    guard let requestUrl = URL(string: url) else {
      throw NSError(domain: "InvalidURL", code: 1, userInfo: nil)
    }

    let destUrl = URL(fileURLWithPath: destPath)
    try FileManager.default.createDirectory(
      at: destUrl.deletingLastPathComponent(),
      withIntermediateDirectories: true,
      attributes: nil
    )

    var request = URLRequest(url: requestUrl)
    request.httpMethod = "GET"
    for (key, value) in headers {
      request.setValue(value, forHTTPHeaderField: key)
    }
    request.timeoutInterval = 600  // 10 min

    // Check existing file for resume
    var existingSize: UInt64 = 0
    if FileManager.default.fileExists(atPath: destPath) {
      let attrs = try FileManager.default.attributesOfItem(atPath: destPath)
      existingSize = (attrs[.size] as? UInt64) ?? 0
      if existingSize > 0 {
        request.setValue("bytes=\(existingSize)-", forHTTPHeaderField: "Range")
      }
    }

    let (data, response) = try await URLSession.shared.data(for: request)
    let httpResponse = response as? HTTPURLResponse
    let statusCode = httpResponse?.statusCode ?? 0
    let resumed = statusCode == 206

    if resumed {
      // Append to existing file
      let handle = try FileHandle(forWritingTo: destUrl)
      defer { handle.closeFile() }
      handle.seekToEndOfFile()
      handle.write(data)
    } else {
      // Overwrite
      try data.write(to: destUrl)
    }

    let finalAttrs = try FileManager.default.attributesOfItem(atPath: destPath)
    let totalBytes = (finalAttrs[.size] as? UInt64) ?? 0

    return [
      "statusCode": statusCode,
      "totalBytes": totalBytes,
      "resumed": resumed,
    ]
  }
}
