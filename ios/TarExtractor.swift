import Foundation

/// Minimal tar archive extractor — iOS equivalent of the Kotlin implementation.
/// Supports POSIX ustar, GNU long-name (type 'L'), and POSIX pax (type 'x'/'g') extensions.
enum TarExtractor {

  private static let blockSize = 512

  /// Extract an uncompressed tar archive to a destination directory.
  /// Returns the number of files extracted.
  static func extract(tarPath: String, destDir: String) throws -> Int {
    let fm = FileManager.default
    try fm.createDirectory(atPath: destDir, withIntermediateDirectories: true, attributes: nil)

    guard let handle = FileHandle(forReadingAtPath: tarPath) else {
      throw NSError(domain: "ENOENT", code: 2, userInfo: [NSLocalizedDescriptionKey: "Tar file not found: \(tarPath)"])
    }
    defer { handle.closeFile() }

    let destURL = URL(fileURLWithPath: destDir).standardized
    var filesExtracted = 0
    var longName: String?

    while true {
      let headerData = handle.readData(ofLength: blockSize)
      if headerData.count < blockSize { break }

      // Check for end-of-archive (two consecutive zero blocks)
      if headerData.allSatisfy({ $0 == 0 }) { break }

      // Extract filename from header
      let rawName = extractString(from: headerData, offset: 0, maxLen: 100)
      let typeFlag = headerData.count > 156 ? headerData[156] : 0

      // Parse file size from octal
      let sizeString = extractString(from: headerData, offset: 124, maxLen: 12)
      let fileSize = UInt64(sizeString.trimmingCharacters(in: .whitespaces), radix: 8) ?? 0

      // POSIX ustar prefix
      let prefix = extractString(from: headerData, offset: 345, maxLen: 155)

      // Handle special entry types
      if typeFlag == UInt8(ascii: "L") {
        // GNU long name
        let nameData = readExactly(handle: handle, count: Int(fileSize))
        longName = String(data: nameData, encoding: .utf8)?.trimmingCharacters(in: CharacterSet(charactersIn: "\0"))
        skipToBlockBoundary(handle: handle, fileSize: fileSize)
        continue
      }

      if typeFlag == UInt8(ascii: "x") || typeFlag == UInt8(ascii: "g") {
        // POSIX pax extended header — parse for "path" keyword
        let paxData = readExactly(handle: handle, count: Int(fileSize))
        skipToBlockBoundary(handle: handle, fileSize: fileSize)
        if let paxStr = String(data: paxData, encoding: .utf8) {
          for line in paxStr.components(separatedBy: "\n") {
            // Format: "len key=value\n"
            if let eqIndex = line.firstIndex(of: "=") {
              let beforeEq = line[line.startIndex..<eqIndex]
              if let spaceIndex = beforeEq.lastIndex(of: " ") {
                let key = String(beforeEq[beforeEq.index(after: spaceIndex)...])
                if key == "path" {
                  longName = String(line[line.index(after: eqIndex)...])
                }
              }
            }
          }
        }
        continue
      }

      // Skip directories and non-regular files
      if typeFlag == UInt8(ascii: "5") || typeFlag == UInt8(ascii: "2") {
        skipDataBlocks(handle: handle, fileSize: fileSize)
        longName = nil
        continue
      }

      // Determine final filename
      let entryName: String
      if let ln = longName {
        entryName = ln
        longName = nil
      } else if !prefix.isEmpty {
        entryName = "\(prefix)/\(rawName)"
      } else {
        entryName = rawName
      }

      guard !entryName.isEmpty else {
        skipDataBlocks(handle: handle, fileSize: fileSize)
        continue
      }

      // Path traversal protection
      let destFile = destURL.appendingPathComponent(entryName).standardized
      guard destFile.path.hasPrefix(destURL.path) else {
        skipDataBlocks(handle: handle, fileSize: fileSize)
        continue
      }

      // Create parent directories
      try fm.createDirectory(
        at: destFile.deletingLastPathComponent(),
        withIntermediateDirectories: true,
        attributes: nil
      )

      // Read file content
      let contentData = readExactly(handle: handle, count: Int(fileSize))
      skipToBlockBoundary(handle: handle, fileSize: fileSize)
      try contentData.write(to: destFile)
      filesExtracted += 1
    }

    return filesExtracted
  }

  // ─── Helpers ─────────────────────────────────────────────────────

  private static func extractString(from data: Data, offset: Int, maxLen: Int) -> String {
    let end = min(offset + maxLen, data.count)
    guard offset < end else { return "" }
    let slice = data[offset..<end]
    // Find null terminator
    let nullIndex = slice.firstIndex(of: 0) ?? end
    let stringSlice = data[offset..<nullIndex]
    return String(data: stringSlice, encoding: .utf8) ?? ""
  }

  private static func readExactly(handle: FileHandle, count: Int) -> Data {
    guard count > 0 else { return Data() }
    var result = Data()
    var remaining = count
    while remaining > 0 {
      let chunk = handle.readData(ofLength: remaining)
      if chunk.isEmpty { break }
      result.append(chunk)
      remaining -= chunk.count
    }
    return result
  }

  private static func skipDataBlocks(handle: FileHandle, fileSize: UInt64) {
    guard fileSize > 0 else { return }
    let totalBytes = ((fileSize + UInt64(blockSize - 1)) / UInt64(blockSize)) * UInt64(blockSize)
    handle.seek(toFileOffset: handle.offsetInFile + totalBytes)
  }

  private static func skipToBlockBoundary(handle: FileHandle, fileSize: UInt64) {
    guard fileSize > 0 else { return }
    let remainder = fileSize % UInt64(blockSize)
    if remainder > 0 {
      let padding = UInt64(blockSize) - remainder
      handle.seek(toFileOffset: handle.offsetInFile + padding)
    }
  }
}
