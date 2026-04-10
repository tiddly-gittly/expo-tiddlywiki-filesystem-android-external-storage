import CommonCrypto
import Foundation

/// Git operations for iOS — direct port of GitHelper.kt.
/// Parses .git/index, reads git objects from loose and pack files,
/// applies pack deltas, and builds git index files.
enum GitHelper {

  // ─── Public API ──────────────────────────────────────────────────

  /// Compare working directory against git index, returning JSON array of changes.
  static func gitStatus(rootDir: String) throws -> String {
    let root = URL(fileURLWithPath: rootDir)
    let gitDir = root.appendingPathComponent(".git")

    guard FileManager.default.fileExists(atPath: gitDir.path) else {
      throw NSError(domain: "Git", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not a git repository: \(rootDir)"])
    }

    let indexFile = gitDir.appendingPathComponent("index")
    guard FileManager.default.fileExists(atPath: indexFile.path) else {
      return "[]"
    }

    let indexEntries = try parseGitIndex(path: indexFile.path)

    let skipDirs: Set<String> = [".git", "node_modules", "output"]
    var workdirFiles = Set<String>()
    walkWorkDir(dir: root, prefix: "", skipDirs: skipDirs, files: &workdirFiles)

    var changes = [[String: String]]()
    var indexPaths = Set<String>()

    for entry in indexEntries {
      indexPaths.insert(entry.path)
      let workFile = root.appendingPathComponent(entry.path)
      let fm = FileManager.default

      if !fm.fileExists(atPath: workFile.path) {
        changes.append(["path": entry.path, "type": "delete"])
      } else {
        let attrs = try? fm.attributesOfItem(atPath: workFile.path)
        let diskSize = (attrs?[.size] as? UInt64) ?? 0
        let diskMtimeMs = ((attrs?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0)
        let diskMtimeS = Int(diskMtimeMs)

        if diskSize != entry.size || diskMtimeS != entry.mtimeSeconds {
          changes.append(["path": entry.path, "type": "modify"])
        }
      }
    }

    for path in workdirFiles {
      if !indexPaths.contains(path) {
        changes.append(["path": path, "type": "add"])
      }
    }

    let jsonData = try JSONSerialization.data(withJSONObject: changes, options: [])
    return String(data: jsonData, encoding: .utf8) ?? "[]"
  }

  /// Debug information about git repository state.
  static func gitStatusDebug(rootDir: String) throws -> String {
    let root = URL(fileURLWithPath: rootDir)
    let gitDir = root.appendingPathComponent(".git")
    let indexFile = gitDir.appendingPathComponent("index")
    let fm = FileManager.default

    var result = [String: Any]()
    result["rootExists"] = fm.fileExists(atPath: root.path)
    var isDir: ObjCBool = false
    fm.fileExists(atPath: root.path, isDirectory: &isDir)
    result["rootIsDir"] = isDir.boolValue
    result["gitDirExists"] = fm.fileExists(atPath: gitDir.path)
    result["indexFileExists"] = fm.fileExists(atPath: indexFile.path)
    result["rootPath"] = root.path
    result["gitDirPath"] = gitDir.path
    result["indexPath"] = indexFile.path

    let rootChildren = (try? fm.contentsOfDirectory(atPath: root.path))?.sorted().prefix(10).map { $0 } ?? []
    result["rootChildren"] = rootChildren

    if fm.fileExists(atPath: gitDir.path) {
      let gitChildren = (try? fm.contentsOfDirectory(atPath: gitDir.path))?.sorted() ?? []
      result["gitDirChildren"] = gitChildren
    }

    if fm.fileExists(atPath: indexFile.path) {
      let entries = try parseGitIndex(path: indexFile.path)
      result["indexEntryCount"] = entries.count

      let skipDirs: Set<String> = [".git", "node_modules", "output"]
      var workdirFiles = Set<String>()
      walkWorkDir(dir: root, prefix: "", skipDirs: skipDirs, files: &workdirFiles)
      result["workdirFileCount"] = workdirFiles.count
      result["tiddlerFileCount"] = workdirFiles.filter { $0.hasPrefix("tiddlers/") }.count

      let indexPaths = Set(entries.map { $0.path })
      let addCount = workdirFiles.filter { !indexPaths.contains($0) }.count
      result["potentialAddCount"] = addCount
    }

    let jsonData = try JSONSerialization.data(withJSONObject: result, options: [])
    return String(data: jsonData, encoding: .utf8) ?? "{}"
  }

  /// Build .git/index from HEAD tree, stat'ing all files on disk.
  static func buildGitIndex(rootDir: String) throws -> String {
    let root = URL(fileURLWithPath: rootDir)
    let gitDir = root.appendingPathComponent(".git")

    guard FileManager.default.fileExists(atPath: gitDir.path) else {
      throw NSError(domain: "Git", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not a git repository: \(rootDir)"])
    }

    do {
      let headFile = gitDir.appendingPathComponent("HEAD")
      let headContent = try String(contentsOfFile: headFile.path, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)

      let commitSha: String
      if headContent.hasPrefix("ref: ") {
        let refPath = String(headContent.dropFirst("ref: ".count))
        let refFile = gitDir.appendingPathComponent(refPath)
        if FileManager.default.fileExists(atPath: refFile.path) {
          commitSha = try String(contentsOfFile: refFile.path, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
          guard let resolved = resolvePackedRef(gitDir: gitDir, refPath: refPath) else {
            throw NSError(domain: "Git", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot resolve HEAD ref: \(refPath)"])
          }
          commitSha = resolved
        }
      } else {
        commitSha = headContent
      }

      guard let commitBytes = readGitObject(gitDir: gitDir, sha: commitSha) else {
        throw NSError(domain: "Git", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cannot read commit: \(commitSha)"])
      }
      guard let treeSha = parseCommitTreeSha(commitData: commitBytes) else {
        throw NSError(domain: "Git", code: 4, userInfo: [NSLocalizedDescriptionKey: "Cannot find tree in commit: \(commitSha)"])
      }

      var entries = [GitTreeEntry]()
      try walkTreeRecursive(gitDir: gitDir, treeSha: treeSha, prefix: "", entries: &entries)
      entries.sort { $0.path < $1.path }

      let indexData = buildIndexBinary(root: root, entries: entries)
      let indexFile = gitDir.appendingPathComponent("index")
      try indexData.write(to: indexFile)

      let result: [String: Any] = [
        "ok": true,
        "entries": entries.count,
        "indexSize": indexData.count,
      ]
      let jsonData = try JSONSerialization.data(withJSONObject: result, options: [])
      return String(data: jsonData, encoding: .utf8) ?? "{}"
    } catch {
      let result: [String: Any] = ["ok": false, "error": error.localizedDescription]
      let jsonData = try JSONSerialization.data(withJSONObject: result, options: [])
      return String(data: jsonData, encoding: .utf8) ?? "{}"
    }
  }

  // ─── Git index parsing ──────────────────────────────────────────

  struct IndexEntry {
    let path: String
    let size: UInt64
    let mtimeSeconds: Int
  }

  private static func parseGitIndex(path: String) throws -> [IndexEntry] {
    let data = try Data(contentsOf: URL(fileURLWithPath: path))
    guard data.count >= 12 else { return [] }

    // Verify "DIRC" signature
    let sig = String(data: data[0..<4], encoding: .ascii)
    guard sig == "DIRC" else { return [] }

    let version = readInt32(data: data, offset: 4)
    let entryCount = readInt32(data: data, offset: 8)

    var entries = [IndexEntry]()
    var offset = 12

    for _ in 0..<entryCount {
      guard offset + 62 <= data.count else { break }

      let mtimeS = readInt32(data: data, offset: offset)
      // Skip ctime (8 bytes), dev (4 bytes), ino (4 bytes) → offset+8 is ctime_s, +16 is dev
      let fileSize = UInt64(readInt32(data: data, offset: offset + 36))
      // SHA is at offset+40, 20 bytes
      let flags = readInt16(data: data, offset: offset + 60)

      let nameLen = flags & 0xFFF

      // For v4, path names use prefix compression — handle v2/v3 simple case
      let pathStart = offset + 62
      let path: String
      let entryEnd: Int

      if version < 4 {
        // v2/v3: null-terminated path, padded to 8-byte boundary
        if nameLen > 0 && pathStart + nameLen <= data.count {
          path = String(data: data[pathStart..<(pathStart + nameLen)], encoding: .utf8) ?? ""
        } else {
          // Find null terminator
          var end = pathStart
          while end < data.count && data[end] != 0 { end += 1 }
          path = String(data: data[pathStart..<end], encoding: .utf8) ?? ""
        }
        let entrySize = 62 + path.utf8.count + 1
        let padding = (8 - (entrySize % 8)) % 8
        entryEnd = pathStart + path.utf8.count + 1 + padding
      } else {
        // v4: prefix-compressed paths — for now, find null terminator
        var end = pathStart
        while end < data.count && data[end] != 0 { end += 1 }
        path = String(data: data[pathStart..<end], encoding: .utf8) ?? ""
        entryEnd = end + 1
      }

      entries.append(IndexEntry(path: path, size: fileSize, mtimeSeconds: mtimeS))
      offset = entryEnd
    }

    return entries
  }

  // ─── Working directory walk ─────────────────────────────────────

  private static func walkWorkDir(dir: URL, prefix: String, skipDirs: Set<String>, files: inout Set<String>) {
    let fm = FileManager.default
    guard let children = try? fm.contentsOfDirectory(atPath: dir.path) else { return }
    for child in children {
      if skipDirs.contains(child) { continue }
      let fullPath = dir.appendingPathComponent(child)
      let relPath = prefix.isEmpty ? child : "\(prefix)/\(child)"
      var isDir: ObjCBool = false
      if fm.fileExists(atPath: fullPath.path, isDirectory: &isDir), isDir.boolValue {
        walkWorkDir(dir: fullPath, prefix: relPath, skipDirs: skipDirs, files: &files)
      } else {
        files.insert(relPath)
      }
    }
  }

  // ─── Git object reading ─────────────────────────────────────────

  private static func readGitObject(gitDir: URL, sha: String) -> Data? {
    // Try loose object first
    let prefix = String(sha.prefix(2))
    let suffix = String(sha.dropFirst(2))
    let loosePath = gitDir.appendingPathComponent("objects/\(prefix)/\(suffix)")
    if FileManager.default.fileExists(atPath: loosePath.path) {
      return readLooseObject(path: loosePath.path)
    }

    // Try pack files
    let packDir = gitDir.appendingPathComponent("objects/pack")
    guard let idxFiles = try? FileManager.default.contentsOfDirectory(atPath: packDir.path).filter({ $0.hasSuffix(".idx") }) else {
      return nil
    }
    for idxFilename in idxFiles {
      let idxPath = packDir.appendingPathComponent(idxFilename).path
      let packPath = idxPath.replacingOccurrences(of: ".idx", with: ".pack")
      guard FileManager.default.fileExists(atPath: packPath) else { continue }
      if let offset = findObjectInPackIndex(idxPath: idxPath, sha: sha) {
        return readObjectFromPack(packPath: packPath, offset: offset, gitDir: gitDir)
      }
    }
    return nil
  }

  private static func readLooseObject(path: String) -> Data? {
    guard let compressed = try? Data(contentsOf: URL(fileURLWithPath: path)) else { return nil }
    guard let inflated = inflate(data: compressed) else { return nil }
    // Skip "type size\0" header
    if let nullIndex = inflated.firstIndex(of: 0) {
      return inflated.subdata(in: inflated.index(after: nullIndex)..<inflated.endIndex)
    }
    return inflated
  }

  private static func findObjectInPackIndex(idxPath: String, sha: String) -> UInt64? {
    guard let handle = FileHandle(forReadingAtPath: idxPath) else { return nil }
    defer { handle.closeFile() }

    let shaBytes = hexToBytes(sha)

    // Read magic + version
    let magic = handle.readData(ofLength: 4)
    guard magic.count == 4,
      magic[0] == 0xFF, magic[1] == 0x74,
      magic[2] == 0x4F, magic[3] == 0x63
    else { return nil }

    _ = handle.readData(ofLength: 4)  // version

    // Read fanout table
    var fanout = [Int](repeating: 0, count: 256)
    for i in 0..<256 {
      let bytes = handle.readData(ofLength: 4)
      guard bytes.count == 4 else { return nil }
      fanout[i] = readInt32FromData(bytes, offset: 0)
    }
    let totalObjects = fanout[255]

    let firstByte = Int(shaBytes[0])
    let lo = firstByte == 0 ? 0 : fanout[firstByte - 1]
    let hi = fanout[firstByte]

    let shaTableStart: UInt64 = 8 + 256 * 4
    var low = lo
    var high = hi - 1

    while low <= high {
      let mid = (low + high) / 2
      handle.seek(toFileOffset: shaTableStart + UInt64(mid) * 20)
      let entry = handle.readData(ofLength: 20)
      guard entry.count == 20 else { return nil }

      let cmp = compareSha(Array(entry), shaBytes)
      if cmp < 0 {
        low = mid + 1
      } else if cmp > 0 {
        high = mid - 1
      } else {
        // Found — read offset
        let offsetTableStart = shaTableStart + UInt64(totalObjects) * 20 + UInt64(totalObjects) * 4
        handle.seek(toFileOffset: offsetTableStart + UInt64(mid) * 4)
        let offsetBytes = handle.readData(ofLength: 4)
        guard offsetBytes.count == 4 else { return nil }
        let rawOffset = UInt64(readInt32FromData(offsetBytes, offset: 0)) & 0xFFFF_FFFF

        if rawOffset & 0x8000_0000 != 0 {
          // Large offset
          let largeOffsetTableStart = offsetTableStart + UInt64(totalObjects) * 4
          let idx = Int(rawOffset & 0x7FFF_FFFF)
          handle.seek(toFileOffset: largeOffsetTableStart + UInt64(idx) * 8)
          let bigBytes = handle.readData(ofLength: 8)
          guard bigBytes.count == 8 else { return nil }
          return readInt64FromData(bigBytes, offset: 0)
        }
        return rawOffset
      }
    }
    return nil
  }

  private static func readObjectFromPack(packPath: String, offset: UInt64, gitDir: URL) -> Data? {
    guard let handle = FileHandle(forReadingAtPath: packPath) else { return nil }
    defer { handle.closeFile() }

    handle.seek(toFileOffset: offset)
    guard let firstByte = handle.readData(ofLength: 1).first else { return nil }

    var byte = Int(firstByte)
    let type = (byte >> 4) & 0x07
    var size = Int64(byte & 0x0F)
    var shift = 4

    while byte & 0x80 != 0 {
      guard let nextByte = handle.readData(ofLength: 1).first else { return nil }
      byte = Int(nextByte)
      size = size | (Int64(byte & 0x7F) << shift)
      shift += 7
    }

    switch type {
    case 1, 2, 3, 4:
      return decompressFromHandle(handle, expectedSize: size)
    case 6:
      // OFS_DELTA
      guard var b = handle.readData(ofLength: 1).first.map({ Int($0) }) else { return nil }
      var deltaOffset = Int64(b & 0x7F)
      while b & 0x80 != 0 {
        guard let nextB = handle.readData(ofLength: 1).first.map({ Int($0) }) else { return nil }
        b = nextB
        deltaOffset = ((deltaOffset + 1) << 7) | Int64(b & 0x7F)
      }
      let baseOffset = Int64(offset) - deltaOffset
      guard baseOffset >= 0 else { return nil }
      guard let base = readObjectFromPack(packPath: packPath, offset: UInt64(baseOffset), gitDir: gitDir) else { return nil }
      guard let delta = decompressFromHandle(handle, expectedSize: size) else { return nil }
      return applyDelta(base: base, delta: delta)
    case 7:
      // REF_DELTA
      let baseShaData = handle.readData(ofLength: 20)
      guard baseShaData.count == 20 else { return nil }
      let baseSha = bytesToHex(Array(baseShaData))
      guard let base = readGitObject(gitDir: gitDir, sha: baseSha) else { return nil }
      guard let delta = decompressFromHandle(handle, expectedSize: size) else { return nil }
      return applyDelta(base: base, delta: delta)
    default:
      return nil
    }
  }

  // ─── Zlib decompression ─────────────────────────────────────────

  private static func inflate(data: Data) -> Data? {
    let bufferSize = max(data.count * 4, 65536)
    var buffer = [UInt8](repeating: 0, count: bufferSize)
    var stream = z_stream()

    stream.next_in = UnsafeMutablePointer<UInt8>(mutating: (data as NSData).bytes.assumingMemoryBound(to: UInt8.self))
    stream.avail_in = uInt(data.count)

    guard inflateInit2_(&stream, MAX_WBITS + 32, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
      return nil
    }

    var result = Data()
    repeat {
      stream.next_out = &buffer
      stream.avail_out = uInt(buffer.count)
      let status = Foundation.inflate(&stream, Z_NO_FLUSH)
      guard status == Z_OK || status == Z_STREAM_END else {
        inflateEnd(&stream)
        return nil
      }
      let written = buffer.count - Int(stream.avail_out)
      result.append(buffer, count: written)
      if status == Z_STREAM_END { break }
    } while stream.avail_in > 0 || stream.avail_out == 0

    inflateEnd(&stream)
    return result
  }

  private static func decompressFromHandle(_ handle: FileHandle, expectedSize: Int64) -> Data? {
    let pos = handle.offsetInFile
    let fileSize = handle.seekToEndOfFile()
    handle.seek(toFileOffset: pos)
    let remaining = min(Int(fileSize - pos), Int(expectedSize * 4 + 4096))
    let compressed = handle.readData(ofLength: remaining)
    return inflate(data: compressed)
  }

  // ─── Delta application ──────────────────────────────────────────

  private static func applyDelta(base: Data, delta: Data) -> Data? {
    let deltaBytes = Array(delta)
    var pos = 0

    // Read base size (variable-length int)
    var shift = 0
    var baseSize: Int64 = 0
    repeat {
      guard pos < deltaBytes.count else { return nil }
      let b = Int(deltaBytes[pos]); pos += 1
      baseSize |= Int64(b & 0x7F) << shift
      shift += 7
      if b & 0x80 == 0 { break }
    } while true

    // Read result size
    var resultSize: Int64 = 0
    shift = 0
    repeat {
      guard pos < deltaBytes.count else { return nil }
      let b = Int(deltaBytes[pos]); pos += 1
      resultSize |= Int64(b & 0x7F) << shift
      shift += 7
      if b & 0x80 == 0 { break }
    } while true

    let baseBytes = Array(base)
    var result = [UInt8](repeating: 0, count: Int(resultSize))
    var resultPos = 0

    while pos < deltaBytes.count {
      let cmd = Int(deltaBytes[pos]); pos += 1

      if cmd & 0x80 != 0 {
        // Copy from base
        var copyOffset = 0
        var copySize = 0
        if cmd & 0x01 != 0 { copyOffset = Int(deltaBytes[pos]); pos += 1 }
        if cmd & 0x02 != 0 { copyOffset |= Int(deltaBytes[pos]) << 8; pos += 1 }
        if cmd & 0x04 != 0 { copyOffset |= Int(deltaBytes[pos]) << 16; pos += 1 }
        if cmd & 0x08 != 0 { copyOffset |= Int(deltaBytes[pos]) << 24; pos += 1 }
        if cmd & 0x10 != 0 { copySize = Int(deltaBytes[pos]); pos += 1 }
        if cmd & 0x20 != 0 { copySize |= Int(deltaBytes[pos]) << 8; pos += 1 }
        if cmd & 0x40 != 0 { copySize |= Int(deltaBytes[pos]) << 16; pos += 1 }
        if copySize == 0 { copySize = 0x10000 }

        guard copyOffset + copySize <= baseBytes.count, resultPos + copySize <= result.count else { return nil }
        result.replaceSubrange(resultPos..<(resultPos + copySize), with: baseBytes[copyOffset..<(copyOffset + copySize)])
        resultPos += copySize
      } else if cmd != 0 {
        // Insert from delta
        guard pos + cmd <= deltaBytes.count, resultPos + cmd <= result.count else { return nil }
        result.replaceSubrange(resultPos..<(resultPos + cmd), with: deltaBytes[pos..<(pos + cmd)])
        pos += cmd
        resultPos += cmd
      }
    }

    return Data(result)
  }

  // ─── Tree/commit parsing ────────────────────────────────────────

  private static func parseCommitTreeSha(commitData: Data) -> String? {
    guard let str = String(data: commitData, encoding: .utf8) else { return nil }
    for line in str.components(separatedBy: .newlines) {
      if line.hasPrefix("tree ") {
        return String(line.dropFirst("tree ".count)).trimmingCharacters(in: .whitespaces)
      }
      if line.isEmpty { break }
    }
    return nil
  }

  private struct GitTreeEntry {
    let path: String
    let mode: Int
    let sha: [UInt8]
  }

  private static func parseTreeEntries(treeData: Data) -> [(name: String, mode: Int, sha: [UInt8])] {
    let bytes = Array(treeData)
    var entries = [(String, Int, [UInt8])]()
    var pos = 0

    while pos < bytes.count {
      // Find space (separates mode from name)
      var spaceIdx = pos
      while spaceIdx < bytes.count && bytes[spaceIdx] != 0x20 { spaceIdx += 1 }
      guard spaceIdx < bytes.count else { break }
      let modeStr = String(bytes: Array(bytes[pos..<spaceIdx]), encoding: .ascii) ?? ""
      guard let mode = Int(modeStr, radix: 8) else { break }
      pos = spaceIdx + 1

      // Find null (end of name)
      var nullIdx = pos
      while nullIdx < bytes.count && bytes[nullIdx] != 0 { nullIdx += 1 }
      guard nullIdx < bytes.count else { break }
      let name = String(bytes: Array(bytes[pos..<nullIdx]), encoding: .utf8) ?? ""
      pos = nullIdx + 1

      // Read 20-byte SHA
      guard pos + 20 <= bytes.count else { break }
      let sha = Array(bytes[pos..<(pos + 20)])
      pos += 20

      entries.append((name, mode, sha))
    }
    return entries
  }

  private static func walkTreeRecursive(
    gitDir: URL, treeSha: String, prefix: String, entries: inout [GitTreeEntry]
  ) throws {
    guard let treeData = readGitObject(gitDir: gitDir, sha: treeSha) else {
      throw NSError(domain: "Git", code: 5, userInfo: [NSLocalizedDescriptionKey: "Cannot read tree: \(treeSha)"])
    }
    for (name, mode, sha) in parseTreeEntries(treeData: treeData) {
      let fullPath = prefix.isEmpty ? name : "\(prefix)/\(name)"
      if mode == 0o40000 {
        // Directory — recurse
        try walkTreeRecursive(gitDir: gitDir, treeSha: bytesToHex(sha), prefix: fullPath, entries: &entries)
      } else {
        entries.append(GitTreeEntry(path: fullPath, mode: mode, sha: sha))
      }
    }
  }

  // ─── Build git index binary ─────────────────────────────────────

  private static func buildIndexBinary(root: URL, entries: [GitTreeEntry]) -> Data {
    var data = Data()

    // Header: "DIRC", version 2, entry count
    data.append("DIRC".data(using: .ascii)!)
    appendInt32(&data, 2)
    appendInt32(&data, Int(entries.count))

    let fm = FileManager.default

    for entry in entries {
      let workFile = root.appendingPathComponent(entry.path)
      let attrs = try? fm.attributesOfItem(atPath: workFile.path)
      let mtimeMs = ((attrs?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0) * 1000
      let mtimeS = Int(mtimeMs / 1000)
      let mtimeNs = Int(mtimeMs.truncatingRemainder(dividingBy: 1000)) * 1_000_000
      let fileSize = Int((attrs?[.size] as? UInt64) ?? 0)

      appendInt32(&data, mtimeS)
      appendInt32(&data, mtimeNs)
      appendInt32(&data, mtimeS)  // ctime = mtime
      appendInt32(&data, mtimeNs)
      appendInt32(&data, 0)  // dev
      appendInt32(&data, 0)  // ino
      appendInt32(&data, entry.mode)
      appendInt32(&data, 0)  // uid
      appendInt32(&data, 0)  // gid
      appendInt32(&data, fileSize)
      data.append(contentsOf: entry.sha)

      let pathBytes = Array(entry.path.utf8)
      let flags = min(pathBytes.count, 0xFFF)
      data.append(UInt8((flags >> 8) & 0xFF))
      data.append(UInt8(flags & 0xFF))
      data.append(contentsOf: pathBytes)
      data.append(0)  // null terminator

      let entrySize = 62 + pathBytes.count + 1
      let padding = (8 - (entrySize % 8)) % 8
      for _ in 0..<padding { data.append(0) }
    }

    // SHA-1 checksum
    var hash = [UInt8](repeating: 0, count: Int(CC_SHA1_DIGEST_LENGTH))
    data.withUnsafeBytes { ptr in
      _ = CC_SHA1(ptr.baseAddress, CC_LONG(data.count), &hash)
    }
    data.append(contentsOf: hash)

    return data
  }

  // ─── Packed ref resolution ──────────────────────────────────────

  private static func resolvePackedRef(gitDir: URL, refPath: String) -> String? {
    let packedRefsPath = gitDir.appendingPathComponent("packed-refs").path
    guard let content = try? String(contentsOfFile: packedRefsPath, encoding: .utf8) else { return nil }
    for line in content.components(separatedBy: .newlines) {
      if line.hasPrefix("#") || line.isEmpty { continue }
      let parts = line.split(separator: " ", maxSplits: 1)
      if parts.count == 2, String(parts[1]).trimmingCharacters(in: .whitespaces) == refPath {
        return String(parts[0]).trimmingCharacters(in: .whitespaces)
      }
    }
    return nil
  }

  // ─── Utility functions ──────────────────────────────────────────

  private static func hexToBytes(_ hex: String) -> [UInt8] {
    let chars = Array(hex)
    var bytes = [UInt8]()
    for i in stride(from: 0, to: chars.count - 1, by: 2) {
      let str = String(chars[i]) + String(chars[i + 1])
      if let byte = UInt8(str, radix: 16) {
        bytes.append(byte)
      }
    }
    return bytes
  }

  private static func bytesToHex(_ bytes: [UInt8]) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
  }

  private static func compareSha(_ a: [UInt8], _ b: [UInt8]) -> Int {
    for i in 0..<min(a.count, b.count) {
      if a[i] != b[i] { return Int(a[i]) - Int(b[i]) }
    }
    return 0
  }

  private static func readInt32(data: Data, offset: Int) -> Int {
    guard offset + 4 <= data.count else { return 0 }
    return (Int(data[offset]) << 24) | (Int(data[offset + 1]) << 16) |
      (Int(data[offset + 2]) << 8) | Int(data[offset + 3])
  }

  private static func readInt16(data: Data, offset: Int) -> Int {
    guard offset + 2 <= data.count else { return 0 }
    return (Int(data[offset]) << 8) | Int(data[offset + 1])
  }

  private static func readInt32FromData(_ data: Data, offset: Int) -> Int {
    readInt32(data: data, offset: offset)
  }

  private static func readInt64FromData(_ data: Data, offset: Int) -> UInt64 {
    guard offset + 8 <= data.count else { return 0 }
    var result: UInt64 = 0
    for i in 0..<8 {
      result = (result << 8) | UInt64(data[offset + i])
    }
    return result
  }

  private static func appendInt32(_ data: inout Data, _ value: Int) {
    data.append(UInt8((value >> 24) & 0xFF))
    data.append(UInt8((value >> 16) & 0xFF))
    data.append(UInt8((value >> 8) & 0xFF))
    data.append(UInt8(value & 0xFF))
  }

  // ─── Stubs for operations requiring libgit2 (not yet available on iOS) ───

  static func gitPush(
    gitRootDir: String, remoteName: String, localBranch: String,
    remoteBranch: String, force: Bool, headers: String?
  ) throws -> String {
    return "{\"ok\":false,\"error\":\"Native git push not available on iOS (no libgit2). Use isomorphic-git fallback.\"}"
  }

  static func gitFetch(
    gitRootDir: String, remoteName: String, branch: String, headers: String?
  ) throws -> String {
    return "{\"ok\":false,\"error\":\"Native git fetch not available on iOS (no libgit2). Use isomorphic-git fallback.\"}"
  }

  static func gitCheckoutChangedFiles(
    gitRootDir: String, oldOid: String, newOid: String
  ) throws -> String {
    return "{\"ok\":false,\"error\":\"Native git checkout not available on iOS (no libgit2). Use isomorphic-git fallback.\"}"
  }

  static func gitAddAndCommit(
    gitRootDir: String, message: String, authorName: String, authorEmail: String
  ) throws -> String {
    return "{\"ok\":false,\"error\":\"Native git commit not available on iOS (no libgit2). Use isomorphic-git fallback.\"}"
  }

  static func gitReset(
    gitRootDir: String, ref: String, mode: String
  ) throws -> String {
    return "{\"ok\":false,\"error\":\"Native git reset not available on iOS (no libgit2). Use isomorphic-git fallback.\"}"
  }
}
