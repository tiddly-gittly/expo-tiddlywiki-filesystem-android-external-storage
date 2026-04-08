package expo.modules.externalstorage

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * All git-related operations extracted from ExternalStorageModule
 * to avoid Kotlin K2 compiler scoping issues in large classes.
 */
internal object GitHelper {

  // ─── Git status ──────────────────────────────────────────────────

  fun gitStatus(gitRootDir: String): String {
    val root = File(gitRootDir)
    val gitDir = File(root, ".git")
    if (!gitDir.exists()) {
      throw Exception("Not a git repository: $gitRootDir (no .git directory)")
    }

    val indexFile = File(gitDir, "index")
    if (!indexFile.exists()) {
      return "[]"
    }

    val indexEntries = parseGitIndex(indexFile)
    android.util.Log.i("GitStatus", "Parsed ${indexEntries.size} entries from git index at $gitRootDir")
    if (indexEntries.size <= 5) {
      indexEntries.forEach { e -> android.util.Log.i("GitStatus", "  index: ${e.path} size=${e.size} mtime=${e.mtimeSeconds}") }
    } else {
      indexEntries.take(3).forEach { e -> android.util.Log.i("GitStatus", "  index: ${e.path} size=${e.size} mtime=${e.mtimeSeconds}") }
      android.util.Log.i("GitStatus", "  ... and ${indexEntries.size - 3} more entries")
    }

    val skipDirs = setOf(".git", "node_modules", "output")
    val workdirFiles = mutableSetOf<String>()
    walkWorkDir(root, "", skipDirs, workdirFiles)
    android.util.Log.i("GitStatus", "Found ${workdirFiles.size} files on disk")
    val tiddlerFiles = workdirFiles.filter { it.startsWith("tiddlers/") }
    android.util.Log.i("GitStatus", "  tiddlers/ count: ${tiddlerFiles.size}, sample: ${tiddlerFiles.take(5).joinToString()}")

    val changes = JSONArray()
    val indexPaths = mutableSetOf<String>()

    var modifiedCount = 0
    var deletedCount = 0
    for (entry in indexEntries) {
      indexPaths.add(entry.path)
      val workFile = File(root, entry.path)
      if (!workFile.exists()) {
        val obj = JSONObject()
        obj.put("path", entry.path)
        obj.put("type", "delete")
        changes.put(obj)
        deletedCount++
      } else {
        val diskSize = workFile.length()
        val diskMtime = workFile.lastModified() / 1000
        if (diskSize != entry.size || diskMtime != entry.mtimeSeconds) {
          val obj = JSONObject()
          obj.put("path", entry.path)
          obj.put("type", "modify")
          changes.put(obj)
          modifiedCount++
          if (modifiedCount <= 3) {
            android.util.Log.i("GitStatus", "  modify: ${entry.path} disk(size=$diskSize,mtime=$diskMtime) vs index(size=${entry.size},mtime=${entry.mtimeSeconds})")
          }
        }
      }
    }

    var addedCount = 0
    for (path in workdirFiles) {
      if (path !in indexPaths) {
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("type", "add")
        changes.put(obj)
        addedCount++
        if (addedCount <= 5) {
          android.util.Log.i("GitStatus", "  add: $path")
        }
      }
    }

    android.util.Log.i("GitStatus", "Result: ${changes.length()} changes (add=$addedCount, modify=$modifiedCount, delete=$deletedCount), indexEntries=${indexEntries.size}, workdirFiles=${workdirFiles.size}")
    return changes.toString()
  }

  fun gitStatusDebug(gitRootDir: String): String {
    val root = File(gitRootDir)
    val gitDir = File(root, ".git")
    val indexFile = File(gitDir, "index")

    val result = JSONObject()
    result.put("rootExists", root.exists())
    result.put("rootIsDir", root.isDirectory)
    result.put("gitDirExists", gitDir.exists())
    result.put("gitDirIsDir", gitDir.isDirectory)
    result.put("indexFileExists", indexFile.exists())
    result.put("rootPath", root.absolutePath)
    result.put("gitDirPath", gitDir.absolutePath)
    result.put("indexPath", indexFile.absolutePath)

    val rootChildren = root.listFiles()?.map { it.name }?.sorted()?.take(10) ?: emptyList()
    result.put("rootChildren", JSONArray(rootChildren))

    if (gitDir.exists() && gitDir.isDirectory) {
      val gitChildren = gitDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
      result.put("gitDirChildren", JSONArray(gitChildren))
    }

    if (!indexFile.exists()) {
      return result.toString()
    }

    val indexEntries = parseGitIndex(indexFile)
    result.put("indexEntryCount", indexEntries.size)

    val indexSamples = JSONArray()
    val storyListEntry = indexEntries.find { it.path == "tiddlers/\$__StoryList.tid" }
    if (storyListEntry != null) {
      val obj = JSONObject()
      obj.put("path", storyListEntry.path)
      obj.put("indexSize", storyListEntry.size)
      obj.put("indexMtime", storyListEntry.mtimeSeconds)
      val workFile = File(root, storyListEntry.path)
      obj.put("diskExists", workFile.exists())
      if (workFile.exists()) {
        obj.put("diskSize", workFile.length())
        obj.put("diskMtime", workFile.lastModified() / 1000)
        obj.put("diskMtimeMs", workFile.lastModified())
        obj.put("sizeMatch", workFile.length() == storyListEntry.size)
        obj.put("mtimeMatch", workFile.lastModified() / 1000 == storyListEntry.mtimeSeconds)
      }
      indexSamples.put(obj)
    }

    val newEntryFile = File(root, "tiddlers/\u65b0\u6761\u76ee.tid")
    val newObj = JSONObject()
    newObj.put("path", "tiddlers/\u65b0\u6761\u76ee.tid")
    newObj.put("diskExists", newEntryFile.exists())
    if (newEntryFile.exists()) {
      newObj.put("diskSize", newEntryFile.length())
      newObj.put("diskMtime", newEntryFile.lastModified() / 1000)
    }
    val inIndex = indexEntries.any { it.path == "tiddlers/\u65b0\u6761\u76ee.tid" }
    newObj.put("inIndex", inIndex)
    indexSamples.put(newObj)

    val skipDirs = setOf(".git", "node_modules", "output")
    val workdirFiles = mutableSetOf<String>()
    walkWorkDir(root, "", skipDirs, workdirFiles)

    result.put("workdirFileCount", workdirFiles.size)
    result.put("tiddlerFileCount", workdirFiles.count { it.startsWith("tiddlers/") })
    result.put("newEntryInWorkdir", workdirFiles.contains("tiddlers/\u65b0\u6761\u76ee.tid"))
    result.put("samples", indexSamples)

    val indexPaths = indexEntries.map { it.path }.toSet()
    val addCount = workdirFiles.count { it !in indexPaths }
    result.put("potentialAddCount", addCount)
    val addSamples = JSONArray()
    workdirFiles.filter { it !in indexPaths }.take(5).forEach { addSamples.put(it) }
    result.put("potentialAddSamples", addSamples)

    return result.toString()
  }

  // ─── Build git index from HEAD tree ──────────────────────────────

  fun buildGitIndex(gitRootDir: String): String {
    val root = File(gitRootDir)
    val gitDir = File(root, ".git")
    if (!gitDir.isDirectory) throw Exception("Not a git repository: $gitRootDir")

    try {
      val headFile = File(gitDir, "HEAD")
      val headContent = headFile.readText(Charsets.UTF_8).trim()
      val commitSha: String = if (headContent.startsWith("ref: ")) {
        val refPath = headContent.removePrefix("ref: ")
        val refFile = File(gitDir, refPath)
        if (refFile.exists()) {
          refFile.readText(Charsets.UTF_8).trim()
        } else {
          resolvePackedRef(gitDir, refPath)
            ?: throw Exception("Cannot resolve HEAD ref: $refPath")
        }
      } else {
        headContent
      }
      android.util.Log.i("BuildGitIndex", "HEAD commit: $commitSha")

      val commitBytes = readGitObject(gitDir, commitSha)
        ?: throw Exception("Cannot read commit object: $commitSha")
      val treeSha = parseCommitTreeSha(commitBytes)
        ?: throw Exception("Cannot find tree SHA in commit: $commitSha")
      android.util.Log.i("BuildGitIndex", "Root tree: $treeSha")

      val entries = mutableListOf<GitTreeEntry>()
      walkTreeRecursive(gitDir, treeSha, "", entries)
      android.util.Log.i("BuildGitIndex", "Tree walk found ${entries.size} entries")

      entries.sortBy { it.path }

      val indexBytes = buildIndexBinary(root, entries)

      val indexFile = File(gitDir, "index")
      indexFile.writeBytes(indexBytes)
      android.util.Log.i("BuildGitIndex", "Wrote index: ${indexBytes.size} bytes, ${entries.size} entries")

      val result = JSONObject()
      result.put("ok", true)
      result.put("entries", entries.size)
      result.put("indexSize", indexBytes.size)
      return result.toString()
    } catch (e: Exception) {
      android.util.Log.e("BuildGitIndex", "Failed: ${e.message}", e)
      val result = JSONObject()
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
      return result.toString()
    }
  }

  // ─── Private helpers ─────────────────────────────────────────────

  private fun walkWorkDir(dir: File, prefix: String, skipDirs: Set<String>, files: MutableSet<String>) {
    val children = dir.listFiles() ?: return
    for (child in children) {
      val relPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
      if (child.isDirectory) {
        if (child.name !in skipDirs) walkWorkDir(child, relPath, skipDirs, files)
      } else {
        files.add(relPath)
      }
    }
  }

  private fun walkTreeRecursive(
    gitDir: File, treeSha: String, prefix: String, entries: MutableList<GitTreeEntry>
  ) {
    val treeBytes = readGitObject(gitDir, treeSha)
      ?: throw Exception("Cannot read tree object: $treeSha")
    for (triple in parseTreeEntries(treeBytes)) {
      val name = triple.first
      val entryMode = triple.second
      val entrySha = triple.third
      val fullPath = if (prefix.isEmpty()) name else "$prefix/$name"
      if (entryMode == 16384) {
        walkTreeRecursive(gitDir, bytesToHex(entrySha), fullPath, entries)
      } else {
        entries.add(GitTreeEntry(fullPath, entryMode, entrySha))
      }
    }
  }

  private fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun hexToBytes(hex: String): ByteArray {
    val len = hex.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
      data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
      i += 2
    }
    return data
  }

  private fun compareSha(a: ByteArray, b: ByteArray): Int {
    for (i in a.indices) {
      val av = a[i].toInt() and 0xFF
      val bv = b[i].toInt() and 0xFF
      if (av != bv) return av - bv
    }
    return 0
  }

  private fun resolvePackedRef(gitDir: File, refPath: String): String? {
    val packedRefs = File(gitDir, "packed-refs")
    if (!packedRefs.exists()) return null
    for (line in packedRefs.readLines(Charsets.UTF_8)) {
      if (line.startsWith("#") || line.isBlank()) continue
      val parts = line.split(" ", limit = 2)
      if (parts.size == 2 && parts[1].trim() == refPath) {
        return parts[0].trim()
      }
    }
    return null
  }

  private fun readGitObject(gitDir: File, sha: String): ByteArray? {
    val looseFile = File(gitDir, "objects/${sha.substring(0, 2)}/${sha.substring(2)}")
    if (looseFile.exists()) {
      return readLooseObject(looseFile)
    }
    val packDir = File(gitDir, "objects/pack")
    if (!packDir.isDirectory) return null
    val idxFiles = packDir.listFiles { _, name -> name.endsWith(".idx") } ?: return null
    for (idxFile in idxFiles) {
      val packFile = File(idxFile.absolutePath.replace(".idx", ".pack"))
      if (!packFile.exists()) continue
      val offset = findObjectInPackIndex(idxFile, sha)
      if (offset != null) {
        return readObjectFromPack(packFile, offset, gitDir)
      }
    }
    return null
  }

  private fun readLooseObject(file: File): ByteArray {
    val compressed = file.readBytes()
    val inflated = java.util.zip.Inflater().let { inflater ->
      inflater.setInput(compressed)
      val buf = ByteArray(8192)
      val baos = ByteArrayOutputStream()
      while (!inflater.finished()) {
        val n = inflater.inflate(buf)
        baos.write(buf, 0, n)
      }
      inflater.end()
      baos.toByteArray()
    }
    val nullIdx = inflated.indexOf(0.toByte())
    return if (nullIdx >= 0) inflated.copyOfRange(nullIdx + 1, inflated.size) else inflated
  }

  private fun findObjectInPackIndex(idxFile: File, sha: String): Long? {
    val shaBytes = hexToBytes(sha)
    RandomAccessFile(idxFile, "r").use { raf ->
      val magic = ByteArray(4)
      raf.readFully(magic)
      if (magic[0] != 0xFF.toByte() || magic[1] != 0x74.toByte() ||
        magic[2] != 0x4F.toByte() || magic[3] != 0x63.toByte()) {
        return null
      }
      raf.readInt()

      val fanout = IntArray(256)
      for (i in 0 until 256) {
        fanout[i] = raf.readInt()
      }
      val totalObjects = fanout[255]

      val firstByte = shaBytes[0].toInt() and 0xFF
      val lo = if (firstByte == 0) 0 else fanout[firstByte - 1]
      val hi = fanout[firstByte]

      val shaTableStart = 8L + 256 * 4
      var low = lo
      var high = hi - 1
      while (low <= high) {
        val mid = (low + high) / 2
        raf.seek(shaTableStart + mid * 20L)
        val entry = ByteArray(20)
        raf.readFully(entry)
        val cmp = compareSha(entry, shaBytes)
        when {
          cmp < 0 -> low = mid + 1
          cmp > 0 -> high = mid - 1
          else -> {
            val offsetTableStart = shaTableStart + totalObjects * 20L + totalObjects * 4L
            raf.seek(offsetTableStart + mid * 4L)
            val offset = raf.readInt().toLong() and 0xFFFFFFFFL
            return if (offset and 0x80000000L != 0L) {
              val largeOffsetTableStart = offsetTableStart + totalObjects * 4L
              val idx = (offset and 0x7FFFFFFFL).toInt()
              raf.seek(largeOffsetTableStart + idx * 8L)
              raf.readLong()
            } else {
              offset
            }
          }
        }
      }
    }
    return null
  }

  private fun readObjectFromPack(packFile: File, offset: Long, gitDir: File): ByteArray? {
    RandomAccessFile(packFile, "r").use { raf ->
      raf.seek(offset)
      var byte = raf.read()
      val type = (byte shr 4) and 0x07
      var size = (byte and 0x0F).toLong()
      var shift = 4
      while (byte and 0x80 != 0) {
        byte = raf.read()
        size = size or ((byte and 0x7F).toLong() shl shift)
        shift += 7
      }

      return when (type) {
        1, 2, 3, 4 -> decompressFromRaf(raf, size)
        6 -> {
          var b = raf.read()
          var deltaOffset = (b and 0x7F).toLong()
          while (b and 0x80 != 0) {
            b = raf.read()
            deltaOffset = ((deltaOffset + 1) shl 7) or (b and 0x7F).toLong()
          }
          val baseOffset = offset - deltaOffset
          val base = readObjectFromPack(packFile, baseOffset, gitDir) ?: return null
          val delta = decompressFromRaf(raf, size)
          applyDelta(base, delta)
        }
        7 -> {
          val baseSha = ByteArray(20)
          raf.readFully(baseSha)
          val base = readGitObject(gitDir, bytesToHex(baseSha)) ?: return null
          val delta = decompressFromRaf(raf, size)
          applyDelta(base, delta)
        }
        else -> null
      }
    }
  }

  private fun decompressFromRaf(raf: RandomAccessFile, expectedSize: Long): ByteArray {
    val pos = raf.filePointer
    val remaining = (raf.length() - pos).coerceAtMost(expectedSize * 4 + 4096)
    val compressed = ByteArray(remaining.toInt())
    raf.readFully(compressed)
    val inflater = java.util.zip.Inflater()
    inflater.setInput(compressed)
    val baos = ByteArrayOutputStream(expectedSize.toInt())
    val buf = ByteArray(8192)
    while (!inflater.finished()) {
      val n = inflater.inflate(buf)
      if (n == 0 && inflater.needsInput()) break
      baos.write(buf, 0, n)
    }
    inflater.end()
    return baos.toByteArray()
  }

  private fun applyDelta(base: ByteArray, delta: ByteArray): ByteArray {
    var pos = 0
    var shift = 0
    var baseSize = 0L
    do {
      val b = delta[pos++].toInt() and 0xFF
      baseSize = baseSize or ((b and 0x7F).toLong() shl shift)
      shift += 7
    } while (b and 0x80 != 0)

    var resultSize = 0L
    shift = 0
    do {
      val b = delta[pos++].toInt() and 0xFF
      resultSize = resultSize or ((b and 0x7F).toLong() shl shift)
      shift += 7
    } while (b and 0x80 != 0)

    val result = ByteArray(resultSize.toInt())
    var resultPos = 0

    while (pos < delta.size) {
      val cmd = delta[pos++].toInt() and 0xFF
      if (cmd and 0x80 != 0) {
        var copyOffset = 0
        var copySize = 0
        if (cmd and 0x01 != 0) copyOffset = delta[pos++].toInt() and 0xFF
        if (cmd and 0x02 != 0) copyOffset = copyOffset or ((delta[pos++].toInt() and 0xFF) shl 8)
        if (cmd and 0x04 != 0) copyOffset = copyOffset or ((delta[pos++].toInt() and 0xFF) shl 16)
        if (cmd and 0x08 != 0) copyOffset = copyOffset or ((delta[pos++].toInt() and 0xFF) shl 24)
        if (cmd and 0x10 != 0) copySize = delta[pos++].toInt() and 0xFF
        if (cmd and 0x20 != 0) copySize = copySize or ((delta[pos++].toInt() and 0xFF) shl 8)
        if (cmd and 0x40 != 0) copySize = copySize or ((delta[pos++].toInt() and 0xFF) shl 16)
        if (copySize == 0) copySize = 0x10000
        System.arraycopy(base, copyOffset, result, resultPos, copySize)
        resultPos += copySize
      } else if (cmd != 0) {
        System.arraycopy(delta, pos, result, resultPos, cmd)
        pos += cmd
        resultPos += cmd
      }
    }
    return result
  }

  private fun parseCommitTreeSha(commitData: ByteArray): String? {
    val str = String(commitData, Charsets.UTF_8)
    for (line in str.lineSequence()) {
      if (line.startsWith("tree ")) {
        return line.removePrefix("tree ").trim()
      }
      if (line.isBlank()) break
    }
    return null
  }

  private fun parseTreeEntries(treeData: ByteArray): List<Triple<String, Int, ByteArray>> {
    val entries = mutableListOf<Triple<String, Int, ByteArray>>()
    var pos = 0
    while (pos < treeData.size) {
      var spaceIdx = pos
      while (spaceIdx < treeData.size && treeData[spaceIdx] != ' '.code.toByte()) spaceIdx++
      if (spaceIdx >= treeData.size) break
      val modeStr = String(treeData, pos, spaceIdx - pos, Charsets.US_ASCII)
      val mode = modeStr.toInt(8)
      pos = spaceIdx + 1

      var nullIdx = pos
      while (nullIdx < treeData.size && treeData[nullIdx] != 0.toByte()) nullIdx++
      if (nullIdx >= treeData.size) break
      val name = String(treeData, pos, nullIdx - pos, Charsets.UTF_8)
      pos = nullIdx + 1

      if (pos + 20 > treeData.size) break
      val sha = treeData.copyOfRange(pos, pos + 20)
      pos += 20

      entries.add(Triple(name, mode, sha))
    }
    return entries
  }

  private data class GitTreeEntry(val path: String, val mode: Int, val sha: ByteArray)

  private fun buildIndexBinary(root: File, entries: List<GitTreeEntry>): ByteArray {
    val baos = ByteArrayOutputStream()

    fun writeInt32(value: Int) {
      baos.write((value shr 24) and 0xFF)
      baos.write((value shr 16) and 0xFF)
      baos.write((value shr 8) and 0xFF)
      baos.write(value and 0xFF)
    }

    baos.write("DIRC".toByteArray(Charsets.US_ASCII))
    writeInt32(2)
    writeInt32(entries.size)

    for (entry in entries) {
      val workFile = File(root, entry.path)
      val mtimeMs = if (workFile.exists()) workFile.lastModified() else 0L
      val mtimeS = (mtimeMs / 1000).toInt()
      val mtimeNs = ((mtimeMs % 1000) * 1_000_000).toInt()
      val fileSize = if (workFile.exists()) workFile.length().toInt() else 0

      writeInt32(mtimeS); writeInt32(mtimeNs)
      writeInt32(mtimeS); writeInt32(mtimeNs)
      writeInt32(0); writeInt32(0)
      writeInt32(entry.mode)
      writeInt32(0); writeInt32(0)
      writeInt32(fileSize)
      baos.write(entry.sha)
      val pathBytes = entry.path.toByteArray(Charsets.UTF_8)
      val flags = pathBytes.size.coerceAtMost(0xFFF)
      baos.write((flags shr 8) and 0xFF)
      baos.write(flags and 0xFF)
      baos.write(pathBytes)
      baos.write(0)
      val entrySize = 62 + pathBytes.size + 1
      val padding = (8 - (entrySize % 8)) % 8
      for (i in 0 until padding) { baos.write(0) }
    }

    val content = baos.toByteArray()
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    val checksum = digest.digest(content)
    val result = ByteArrayOutputStream(content.size + 20)
    result.write(content)
    result.write(checksum)
    return result.toByteArray()
  }

  // ─── Git index parser ─────────────────────────────────────────────

  data class GitIndexEntry(
    val path: String,
    val size: Long,
    val mtimeSeconds: Long,
  )

  private fun parseGitIndex(indexFile: File): List<GitIndexEntry> {
    val bytes = indexFile.readBytes()
    if (bytes.size < 12) return emptyList()

    val sig = String(bytes, 0, 4, Charsets.US_ASCII)
    if (sig != "DIRC") return emptyList()

    val version = readInt32(bytes, 4)
    if (version !in 2..4) return emptyList()

    val entryCount = readInt32(bytes, 8)
    val entries = ArrayList<GitIndexEntry>(entryCount)
    var offset = 12

    for (i in 0 until entryCount) {
      if (offset + 62 > bytes.size) break

      val mtimeSeconds = readInt32(bytes, offset + 8).toLong() and 0xFFFFFFFFL
      val fileSize = readInt32(bytes, offset + 36).toLong() and 0xFFFFFFFFL
      val flags = readInt16(bytes, offset + 60)
      val nameLength = flags and 0xFFF

      val pathStart = offset + 62
      val pathEnd: Int
      if (nameLength == 0xFFF) {
        var nullPos = pathStart
        while (nullPos < bytes.size && bytes[nullPos] != 0.toByte()) nullPos++
        pathEnd = nullPos
      } else {
        pathEnd = pathStart + nameLength
      }

      val path = if (pathEnd <= bytes.size) {
        String(bytes, pathStart, pathEnd - pathStart, Charsets.UTF_8)
      } else {
        break
      }

      entries.add(GitIndexEntry(path = path, size = fileSize, mtimeSeconds = mtimeSeconds))

      if (version < 4) {
        val entryLength = 62 + (pathEnd - pathStart) + 1
        val paddedLength = (entryLength + 7) and 7.inv()
        offset += paddedLength
      } else {
        offset = pathEnd + 1
      }
    }

    return entries
  }

  private fun readInt32(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
      (bytes[offset + 3].toInt() and 0xFF)
  }

  private fun readInt16(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or
      (bytes[offset + 1].toInt() and 0xFF)
  }
}
