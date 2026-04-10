package expo.modules.externalstorage

import android.os.Build
import android.os.Environment
import android.util.Base64
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Expo native module that performs raw java.io.File I/O on external storage.
 *
 * Expo's built-in FileSystem module restricts writes to its own directory
 * whitelist, blocking access to shared storage even when MANAGE_EXTERNAL_STORAGE
 * is granted. This module bypasses that restriction.
 *
 * All paths are plain filesystem paths (no file:// prefix).
 */
class ExternalStorageModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExternalStorage")

    // --- Basic queries ---

    AsyncFunction("exists") { path: String ->
      File(path).exists()
    }

    AsyncFunction("getInfo") { path: String ->
      val file = File(path)
      if (!file.exists()) {
        return@AsyncFunction mapOf(
          "exists" to false,
          "isDirectory" to false,
          "size" to 0L,
          "modificationTime" to 0L,
        )
      }
      mapOf(
        "exists" to true,
        "isDirectory" to file.isDirectory,
        "size" to file.length(),
        "modificationTime" to file.lastModified(),
      )
    }

    // --- Directory operations ---

    AsyncFunction("mkdir") { path: String ->
      val dir = File(path)
      if (!dir.exists()) {
        val ok = dir.mkdirs()
        if (!ok && !dir.exists()) {
          throw Exception("Failed to create directory: $path")
        }
      }
    }

    AsyncFunction("readDir") { path: String ->
      val dir = File(path)
      if (!dir.exists() || !dir.isDirectory) {
        throw Exception("ENOENT: no such directory: $path")
      }
      dir.list()?.toList() ?: emptyList<String>()
    }

    // Recursively list all files under a directory, returning paths relative to `path`.
    // Skips .git, node_modules, .DS_Store, output directories.
    AsyncFunction("readDirRecursive") { path: String ->
      val root = File(path)
      if (!root.exists() || !root.isDirectory) {
        throw Exception("ENOENT: no such directory: $path")
      }
      val skipNames = setOf(".git", "node_modules", ".DS_Store", "output")
      val result = mutableListOf<String>()
      fun walk(dir: File, prefix: String) {
        val children = dir.listFiles() ?: return
        for (child in children) {
          val relativePath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
          if (child.isDirectory) {
            if (child.name !in skipNames) {
              walk(child, relativePath)
            }
          } else {
            result.add(relativePath)
          }
        }
      }
      walk(root, "")
      result
    }

    AsyncFunction("rmdir") { path: String ->
      val dir = File(path)
      if (dir.exists()) {
        dir.deleteRecursively()
      }
    }

    // --- File read/write ---

    AsyncFunction("readFileUtf8") { path: String ->
      val file = File(path)
      if (!file.exists()) {
        throw Exception("ENOENT: no such file: $path")
      }
      file.readText(Charsets.UTF_8)
    }

    AsyncFunction("readFileBase64") { path: String ->
      val file = File(path)
      if (!file.exists()) {
        throw Exception("ENOENT: no such file: $path")
      }
      Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    AsyncFunction("writeFileUtf8") { path: String, content: String ->
      val file = File(path)
      file.parentFile?.let { parent ->
        if (!parent.exists()) parent.mkdirs()
      }
      file.writeText(content, Charsets.UTF_8)
    }

    AsyncFunction("writeFileBase64") { path: String, base64Content: String ->
      val file = File(path)
      file.parentFile?.let { parent ->
        if (!parent.exists()) parent.mkdirs()
      }
      val bytes = Base64.decode(base64Content, Base64.DEFAULT)
      file.writeBytes(bytes)
    }

    /**
     * Append a Base64-encoded chunk to a file, optionally truncating it first.
     *
     * This is designed for **streaming large writes from JS in bounded-memory
     * chunks** (e.g. 512 KB per call).  By keeping each chunk small the JVM
     * never needs to allocate the full file content at once, avoiding OOM on
     * 50+ MB git pack files.
     *
     * @param path           Plain filesystem path
     * @param base64Content  Chunk of data encoded as Base64
     * @param truncateFirst  If true the file is created / truncated before
     *                       writing; pass true for the first chunk only.
     */
    AsyncFunction("appendFileBase64") { path: String, base64Content: String, truncateFirst: Boolean ->
      val file = File(path)
      file.parentFile?.let { parent ->
        if (!parent.exists()) parent.mkdirs()
      }
      val bytes = Base64.decode(base64Content, Base64.DEFAULT)
      // truncateFirst=true  → overwrite (new file or truncate existing)
      // truncateFirst=false → append to existing file
      FileOutputStream(file, !truncateFirst).use { fos ->
        fos.write(bytes)
      }
    }

    AsyncFunction("writeFilesBase64") { paths: List<String>, base64Contents: List<String> ->
      if (paths.size != base64Contents.size) {
        throw Exception("paths/base64Contents length mismatch: ${paths.size} vs ${base64Contents.size}")
      }

      for (index in paths.indices) {
        val file = File(paths[index])
        file.parentFile?.let { parent ->
          if (!parent.exists()) parent.mkdirs()
        }
        val bytes = Base64.decode(base64Contents[index], Base64.DEFAULT)
        file.writeBytes(bytes)
      }

      mapOf("writtenCount" to paths.size)
    }

    AsyncFunction("deleteFile") { path: String ->
      val file = File(path)
      if (file.exists()) {
        file.delete()
      }
    }

    // --- Helper: check if external storage is available and MANAGE permission effective ---

    AsyncFunction("isExternalStorageWritable") {
      Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    AsyncFunction("getExternalStorageDirectory") {
      Environment.getExternalStorageDirectory()?.absolutePath ?: ""
    }

    /**
     * Check if this app has MANAGE_EXTERNAL_STORAGE ("All files access") granted.
     * On Android < 11 (API 30), returns true (not needed).
     */
    AsyncFunction("isExternalStorageManager") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
      } else {
        true
      }
    }

    // --- Streaming HTTP → disk ---

    /**
     * Make an HTTP POST request and stream the response body directly to a file
     * on disk, **never buffering the full body in JVM heap**.
     *
     * This is critical for git-upload-pack responses which can be 100+ MB.
     * React Native's built-in `fetch()` goes through OkHttp but buffers the
     * entire response in the JVM before handing it to Hermes, causing OOM.
     *
     * @param url         Target URL
     * @param headersMap  HTTP headers as { key: value }
     * @param bodyBase64  Request body encoded as Base64 (git protocol binary data)
     * @param destPath    Plain filesystem path for the response file
     * @param contentType MIME type for the request body
     * @return Map with "statusCode", "headers" (Map<String,String>), "bytesWritten"
     */
    AsyncFunction("httpPostToFile") { url: String, headersMap: Map<String, String>, bodyBase64: String, destPath: String, contentType: String ->
      val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)    // large packs take time
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

      val requestBody = Base64.decode(bodyBase64, Base64.DEFAULT)
        .toRequestBody(contentType.toMediaType())

      val request = Request.Builder()
        .url(url)
        .post(requestBody)
        .headers(headersMap.toHeaders())
        .build()

      val response = client.newCall(request).execute()

      val destFile = File(destPath)
      destFile.parentFile?.let { parent ->
        if (!parent.exists()) parent.mkdirs()
      }

      var bytesWritten = 0L
      response.body?.let { body ->
        body.byteStream().use { inputStream ->
          FileOutputStream(destFile).use { outputStream ->
            val buffer = ByteArray(64 * 1024) // 64 KB chunks
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
              outputStream.write(buffer, 0, read)
              bytesWritten += read
            }
          }
        }
      }

      val responseHeaders = mutableMapOf<String, String>()
      for (i in 0 until response.headers.size) {
        responseHeaders[response.headers.name(i)] = response.headers.value(i)
      }

      mapOf(
        "statusCode" to response.code,
        "headers" to responseHeaders,
        "bytesWritten" to bytesWritten,
      )
    }

    // --- Chunked file reading ---

    /**
     * Read a chunk of a file as Base64, starting at `offset` for up to `length`
     * bytes.  Returns `{ data: string, bytesRead: number }`.
     *
     * This lets JS consume a large temp file in bounded-memory chunks without
     * ever holding the full content in the Hermes heap.
     */
    AsyncFunction("readFileChunk") { path: String, offset: Long, length: Int ->
      val file = RandomAccessFile(path, "r")
      file.use { raf ->
        val fileLength = raf.length()
        if (offset >= fileLength) {
          return@AsyncFunction mapOf(
            "data" to "",
            "bytesRead" to 0,
          )
        }
        raf.seek(offset)
        val toRead = minOf(length.toLong(), fileLength - offset).toInt()
        val buffer = ByteArray(toRead)
        val bytesRead = raf.read(buffer, 0, toRead)
        if (bytesRead <= 0) {
          return@AsyncFunction mapOf(
            "data" to "",
            "bytesRead" to 0,
          )
        }
        val actual = if (bytesRead < toRead) buffer.copyOf(bytesRead) else buffer
        mapOf(
          "data" to Base64.encodeToString(actual, Base64.NO_WRAP),
          "bytesRead" to bytesRead,
        )
      }
    }

    // --- Resumable HTTP download → disk ---

    /**
     * Download a file via HTTP GET with support for resumable downloads.
     *
     * If `destPath` already exists, sends a `Range: bytes=<existingSize>-`
     * header to resume the download from where it left off.
     *
     * The server must respond with 206 Partial Content and the correct
     * Content-Range header for resume to work.  If the server responds
     * with 200, the file is overwritten from the start (full download).
     *
     * @param url       Target URL
     * @param headers   Extra HTTP headers (e.g. Authorization, ETag)
     * @param destPath  Plain filesystem path for the downloaded file
     * @return Map with "statusCode", "totalBytes" (final file size), "resumed" (boolean)
     */
    AsyncFunction("downloadFileResumable") { url: String, headersMap: Map<String, String>, destPath: String ->
      val destFile = File(destPath)
      destFile.parentFile?.let { parent ->
        if (!parent.exists()) parent.mkdirs()
      }

      val existingBytes = if (destFile.exists()) destFile.length() else 0L

      val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // large archives
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

      val requestBuilder = Request.Builder()
        .url(url)
        .headers(headersMap.toHeaders())

      // Request resume if we have partial data
      if (existingBytes > 0) {
        requestBuilder.addHeader("Range", "bytes=$existingBytes-")
      }

      val request = requestBuilder.build()
      val response = client.newCall(request).execute()

      val statusCode = response.code
      var resumed = false

      response.body?.let { body ->
        if (statusCode == 206) {
          // Server supports Range — append to existing file
          resumed = true
          FileOutputStream(destFile, true).use { outputStream ->
            body.byteStream().use { inputStream ->
              val buffer = ByteArray(64 * 1024)
              var read: Int
              while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
              }
            }
          }
        } else {
          // Full download (200 or other) — overwrite
          FileOutputStream(destFile, false).use { outputStream ->
            body.byteStream().use { inputStream ->
              val buffer = ByteArray(64 * 1024)
              var read: Int
              while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
              }
            }
          }
        }
      }

      mapOf(
        "statusCode" to statusCode,
        "totalBytes" to destFile.length(),
        "resumed" to resumed,
      )
    }

    // --- Tar extraction ---

    /**
     * Extract a tar archive (uncompressed) to a destination directory.
     *
     * Uses a minimal tar parser: reads 512-byte headers, extracts file
     * name and size, writes content.  Supports POSIX ustar long paths
     * via the "L" (LongLink) type extension.
     *
     * This avoids any third-party dependency while handling the tar
     * files generated by `git archive` + system tar.
     *
     * @param tarPath  Path to the .tar file
     * @param destDir  Destination directory (will be created if needed)
     * @return Map with "filesExtracted" count
     */
    AsyncFunction("extractTar") { tarPath: String, destDir: String ->
      val tarFile = File(tarPath)
      if (!tarFile.exists()) {
        throw Exception("ENOENT: tar file not found: $tarPath")
      }

      val dest = File(destDir)
      if (!dest.exists()) dest.mkdirs()

      // Resolve the canonical dest to prevent path traversal
      val canonicalDest = dest.canonicalPath

      var filesExtracted = 0
      var longName: String? = null

      BufferedInputStream(FileInputStream(tarFile), 256 * 1024).use { bis ->
        val headerBuf = ByteArray(512)

        while (true) {
          // Read 512-byte tar header
          val headerRead = readFully(bis, headerBuf)
          if (headerRead < 512) break

          // Check for end-of-archive (two zero blocks)
          if (headerBuf.all { it == 0.toByte() }) break

          // Parse file name (bytes 0-99, null-terminated)
          val rawName = extractString(headerBuf, 0, 100)
          // Parse size (bytes 124-135, octal)
          val sizeStr = extractString(headerBuf, 124, 12).trim()
          val fileSize = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
          // Parse type flag (byte 156)
          val typeFlag = headerBuf[156].toInt().toChar()
          // Parse prefix (bytes 345-499, POSIX ustar)
          val prefix = extractString(headerBuf, 345, 155)

          // Handle GNU tar long-name extension (type 'L')
          if (typeFlag == 'L') {
            val nameBuf = ByteArray(fileSize.toInt())
            readFully(bis, nameBuf)
            longName = String(nameBuf, Charsets.UTF_8).trimEnd('\u0000')
            // Skip padding to 512-byte boundary
            val remainder = (512 - (fileSize % 512).toInt()) % 512
            if (remainder > 0) bis.skip(remainder.toLong())
            continue
          }

          // Handle POSIX pax extended header (type 'x' or 'g')
          // pax headers contain key=value pairs, including "path" for long filenames
          if (typeFlag == 'x' || typeFlag == 'g') {
            val paxBuf = ByteArray(fileSize.toInt())
            readFully(bis, paxBuf)
            val paxStr = String(paxBuf, Charsets.UTF_8)
            // Parse pax records: each record is "<length> <key>=<value>\n"
            var paxPos = 0
            while (paxPos < paxStr.length) {
              val spaceAt = paxStr.indexOf(' ', paxPos)
              if (spaceAt < 0) break
              val recLen = paxStr.substring(paxPos, spaceAt).toIntOrNull() ?: break
              val record = paxStr.substring(spaceAt + 1, minOf(paxPos + recLen, paxStr.length)).trimEnd('\n')
              val eqAt = record.indexOf('=')
              if (eqAt >= 0) {
                val key = record.substring(0, eqAt)
                val value = record.substring(eqAt + 1)
                if (key == "path") {
                  longName = value
                }
              }
              paxPos += recLen
            }
            // Skip padding to 512-byte boundary
            val remainder = (512 - (fileSize % 512).toInt()) % 512
            if (remainder > 0) bis.skip(remainder.toLong())
            continue
          }

          // Determine the file name
          val fileName = longName ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
          longName = null

          if (fileName.isEmpty()) {
            // Skip data blocks for this entry
            skipDataBlocks(bis, fileSize)
            continue
          }

          // Type '5' = directory, '0' or '\0' = regular file
          when (typeFlag) {
            '5' -> {
              val dir = File(dest, fileName)
              if (!dir.canonicalPath.startsWith(canonicalDest)) {
                throw Exception("Path traversal detected: $fileName")
              }
              dir.mkdirs()
              skipDataBlocks(bis, fileSize)
            }
            '0', '\u0000' -> {
              val outFile = File(dest, fileName)
              if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                throw Exception("Path traversal detected: $fileName")
              }
              outFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
              }

              FileOutputStream(outFile).use { fos ->
                var remaining = fileSize
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                  val toRead = minOf(remaining, buf.size.toLong()).toInt()
                  val read = bis.read(buf, 0, toRead)
                  if (read <= 0) break
                  fos.write(buf, 0, read)
                  remaining -= read
                }
              }

              // Skip padding to 512-byte boundary
              val remainder = (512 - (fileSize % 512).toInt()) % 512
              if (remainder > 0) bis.skip(remainder.toLong())

              filesExtracted++
            }
            else -> {
              // Skip unknown entry types (symlinks, etc.)
              skipDataBlocks(bis, fileSize)
            }
          }
        }
      }

      mapOf("filesExtracted" to filesExtracted)
    }

    // ─── Git operations (all via JGit) ─────────────────────────────────

    AsyncFunction("gitStatus") { gitRootDir: String ->
      GitHelper.gitStatus(gitRootDir)
    }

    AsyncFunction("gitStatusDebug") { gitRootDir: String ->
      GitHelper.gitStatusDebug(gitRootDir)
    }

    AsyncFunction("buildGitIndex") { gitRootDir: String ->
      GitHelper.buildGitIndex(gitRootDir)
    }

    AsyncFunction("gitPush") { gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String, force: Boolean, headers: String? ->
      GitHelper.gitPush(gitRootDir, remoteName, localBranch, remoteBranch, force, headers)
    }

    AsyncFunction("gitFetch") { gitRootDir: String, remoteName: String, branch: String, headers: String? ->
      GitHelper.gitFetch(gitRootDir, remoteName, branch, headers)
    }

    AsyncFunction("gitCheckoutChangedFiles") { gitRootDir: String, oldOid: String, newOid: String ->
      GitHelper.gitCheckoutChangedFiles(gitRootDir, oldOid, newOid)
    }

    AsyncFunction("gitAddAndCommit") { gitRootDir: String, message: String, authorName: String, authorEmail: String ->
      GitHelper.gitAddAndCommit(gitRootDir, message, authorName, authorEmail)
    }

    AsyncFunction("gitReset") { gitRootDir: String, ref: String, mode: String ->
      GitHelper.gitReset(gitRootDir, ref, mode)
    }

    // ─── TiddlyWiki batch file parsing ─────────────────────────────────

    /**
     * Parse a batch of TiddlyWiki tiddler files entirely in Kotlin.
     *
     * This is the critical performance optimization: instead of making
     * 100+ JS→Native bridge calls (one per file), a single call parses
     * an entire batch and returns a ready-to-inject JSON array string.
     *
     * Supports:
     * - .tid files: header + body, with skinny mode (omit text for large tiddlers)
     * - .json files: single tiddler or array of tiddlers (also plugin bundles)
     * - .meta files: metadata companion for binary/.json files
     *
     * @param filePaths     Array of absolute file paths to parse
     * @param quickLoadMode If true, always return skinny tiddlers (no text)
     * @return JSON string: array of tiddler objects, e.g. `[{"title":"...","text":"..."}, ...]`
     */
    AsyncFunction("batchParseTidFiles") { filePaths: List<String>, quickLoadMode: Boolean ->
      // Parse all files in parallel using a thread pool.
      // Expo's AsyncFunction already runs off the main thread, so we
      // use Java's ForkJoinPool (via parallelStream) for concurrent I/O.
      val results = filePaths.parallelStream().map { path ->
        try {
          parseTiddlerFile(path, quickLoadMode)
        } catch (e: Exception) {
          null
        }
      }.toList()

      // Build a JSON array string directly — avoids JS-side JSON.stringify
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
      jsonArray.toString()
    }
  }

  // ─── TiddlyWiki file parsing helpers ───────────────────────────────

  /**
   * Parse a single tiddler file. Returns JSONObject, JSONArray (for .json
   * arrays), or null if the file cannot be parsed.
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

    // Find the first blank line separating headers from body
    val blankLineRegex = Regex("\r?\n\r?\n")
    val match = blankLineRegex.find(content)
    val headerText = if (match != null) content.substring(0, match.range.first) else content
    val bodyOffset = match?.let { it.range.last + 1 } ?: -1
    val estimatedBodyLength = if (bodyOffset >= 0) content.length - bodyOffset else 0

    // Parse header lines
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

    // Use filename as title fallback
    if (!json.has("title")) {
      json.put("title", getTitleFromFilename(file.name))
    }

    val title = json.optString("title", "")
    val type = json.optString("type", "")
    val hasModuleType = json.has("module-type")
    val hasPluginType = json.has("plugin-type")

    // Quick load still needs full text for boot-critical tiddlers.
    val shouldIncludeText = if (quickLoadMode) {
      shouldPreserveFullTextInQuickLoad(title, type, hasModuleType, hasPluginType)
    } else {
      shouldSaveFullTiddler(title, type, hasModuleType, hasPluginType, estimatedBodyLength)
    }

    if (shouldIncludeText && bodyOffset >= 0 && estimatedBodyLength > 0) {
      json.put("text", content.substring(bodyOffset))
    } else if (!shouldIncludeText) {
      // Skinny tiddler — mark for lazy loading
      json.remove("text")
      json.put("_is_skinny", "yes")
    }

    return json
  }

  /**
   * Parse a .json tiddler file.
   * Can be: single tiddler `{title: ...}`, array of tiddlers, or
   * a plugin bundle `{tiddlers: {...}}` (returned as null — loaded via .meta).
   */
  private fun parseDotJson(file: File, quickLoadMode: Boolean): Any? {
    val content = file.readText(Charsets.UTF_8)
    val fallbackTitle = getTitleFromFilename(file.name)
    return try {
      // Try as JSON array first
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
          // Plugin bundle format {tiddlers: {...}} — skip here,
          // it's loaded via .meta companion file
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
   * Parse a .meta companion file. The .meta has only field definitions;
   * the actual content is in the companion file (same name without .meta).
   */
  private fun parseDotMeta(metaFile: File, quickLoadMode: Boolean): JSONObject? {
    val metaContent = metaFile.readText(Charsets.UTF_8)
    val json = JSONObject()

    // Parse key: value pairs
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

    // Find companion file
    val companionPath = metaFile.absolutePath.removeSuffix(".meta")
    val companionFile = File(companionPath)

    if (companionFile.exists()) {
      val tiddlerType = json.optString("type", "text/vnd.tiddlywiki")
      val hasModuleType = json.has("module-type")
      val hasPluginType = json.has("plugin-type")

      // Determine whether this companion is a text file whose content
      // should be loaded as the tiddler's "text" field.
      // JS modules, CSS, JSON, and other text-based companions need their content.
      // Binary companions (images, pdfs, etc.) should NOT have their content loaded;
      // they use _canonical_uri instead (handled later by JS).
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
      // For binary companions (images, etc.), we don't set _canonical_uri here —
      // that requires knowing the workspace base path. JS side handles it.
    }

    return if (json.has("title")) json else null
  }

  /**
   * Decide whether a tiddler's full text should be included in the boot store.
   * Mirrors the JS `shouldSaveFullTiddler()` logic.
   */
  private fun shouldSaveFullTiddler(
    title: String,
    type: String,
    hasModuleType: Boolean,
    hasPluginType: Boolean,
    estimatedTextLength: Int,
  ): Boolean {
    if (shouldPreserveFullTextInQuickLoad(title, type, hasModuleType, hasPluginType)) return true
    // Small tiddlers (< 10KB)
    if (estimatedTextLength < 10000) return true
    return false
  }

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

  // --- Tar helper functions ---

  private fun readFully(stream: BufferedInputStream, buf: ByteArray): Int {
    var offset = 0
    while (offset < buf.size) {
      val read = stream.read(buf, offset, buf.size - offset)
      if (read <= 0) return offset
      offset += read
    }
    return offset
  }

  private fun extractString(header: ByteArray, offset: Int, maxLen: Int): String {
    val end = (offset until minOf(offset + maxLen, header.size))
      .firstOrNull { header[it] == 0.toByte() }
      ?: (offset + maxLen)
    return String(header, offset, end - offset, Charsets.UTF_8)
  }

  private fun skipDataBlocks(stream: BufferedInputStream, fileSize: Long) {
    if (fileSize <= 0) return
    // Data occupies ceil(fileSize / 512) * 512 bytes
    val totalBytes = ((fileSize + 511) / 512) * 512
    var skipped = 0L
    while (skipped < totalBytes) {
      val n = stream.skip(totalBytes - skipped)
      if (n <= 0) break
      skipped += n
    }
  }
}
