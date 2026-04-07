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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream

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

    // ─── TiddlyWiki batch file parsing ─────────────────────────────────

    /**
     * Lightweight native git status — orders of magnitude faster than
     * isomorphic-git's statusMatrix which must cross the JS↔Native bridge
     * for every file read AND compute SHA-1 hashes in JavaScript.
     *
     * Strategy:
     * 1. Parse `.git/index` to get the list of tracked files with their
     *    stat-cache entries (size, mtime).
     * 2. Walk the working directory in parallel using Java NIO.
     * 3. Compare stat-cache: if size+mtime match → file is clean.
     *    If they differ → mark as modified (we skip re-hashing since
     *    the user only needs to know *which* files changed, not the
     *    exact content delta).
     * 4. Files in the index but missing from disk → deleted.
     * 5. Files on disk but not in the index → added (untracked).
     *
     * @param gitRootDir  The root directory of the git repository (parent of .git/)
     * @return JSON string: `[{"path":"tiddlers/foo.tid","type":"add"}, ...]`
     */
    AsyncFunction("gitStatus") { gitRootDir: String ->
      val root = File(gitRootDir)
      val gitDir = File(root, ".git")
      if (!gitDir.exists()) {
        throw Exception("Not a git repository: $gitRootDir (no .git directory)")
      }

      val indexFile = File(gitDir, "index")
      if (!indexFile.exists()) {
        // No index means no tracked files — everything is untracked
        return@AsyncFunction "[]"
      }

      // 1. Parse the git index
      val indexEntries = parseGitIndex(indexFile)
      android.util.Log.i("GitStatus", "Parsed ${indexEntries.size} entries from git index at $gitRootDir")
      if (indexEntries.size <= 5) {
        indexEntries.forEach { e -> android.util.Log.i("GitStatus", "  index: ${e.path} size=${e.size} mtime=${e.mtimeSeconds}") }
      } else {
        indexEntries.take(3).forEach { e -> android.util.Log.i("GitStatus", "  index: ${e.path} size=${e.size} mtime=${e.mtimeSeconds}") }
        android.util.Log.i("GitStatus", "  ... and ${indexEntries.size - 3} more entries")
      }

      // 2. Walk the working directory (skip .git, node_modules, etc.)
      val skipDirs = setOf(".git", "node_modules", "output")
      val workdirFiles = mutableSetOf<String>()
      fun walkDir(dir: File, prefix: String) {
        val children = dir.listFiles() ?: return
        for (child in children) {
          val relPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
          if (child.isDirectory) {
            if (child.name !in skipDirs) {
              walkDir(child, relPath)
            }
          } else {
            workdirFiles.add(relPath)
          }
        }
      }
      walkDir(root, "")
      android.util.Log.i("GitStatus", "Found ${workdirFiles.size} files on disk")
      // Log files in tiddlers/ dir specifically
      val tiddlerFiles = workdirFiles.filter { it.startsWith("tiddlers/") }
      android.util.Log.i("GitStatus", "  tiddlers/ count: ${tiddlerFiles.size}, sample: ${tiddlerFiles.take(5).joinToString()}")

      // 3. Compare index vs working directory
      val changes = JSONArray()
      val indexPaths = mutableSetOf<String>()

      var modifiedCount = 0
      var deletedCount = 0
      for (entry in indexEntries) {
        indexPaths.add(entry.path)
        val workFile = File(root, entry.path)
        if (!workFile.exists()) {
          // Tracked file missing from disk → deleted
          val obj = JSONObject()
          obj.put("path", entry.path)
          obj.put("type", "delete")
          changes.put(obj)
          deletedCount++
        } else {
          // Check stat cache: size and mtime
          val diskSize = workFile.length()
          val diskMtime = workFile.lastModified() / 1000  // git index uses seconds
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

      // 4. Files on disk but not in index → added
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
      changes.toString()
    }

    /**
     * Return diagnostic info about git index vs working directory.
     * Used to debug why gitStatus returns 0 when changes exist.
     */
    AsyncFunction("gitStatusDebug") { gitRootDir: String ->
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

      // List contents of root (first 10)
      val rootChildren = root.listFiles()?.map { it.name }?.sorted()?.take(10) ?: emptyList()
      result.put("rootChildren", JSONArray(rootChildren))

      // List contents of .git if exists
      if (gitDir.exists() && gitDir.isDirectory) {
        val gitChildren = gitDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        result.put("gitDirChildren", JSONArray(gitChildren))
      }

      if (!indexFile.exists()) {
        return@AsyncFunction result.toString()
      }

      val indexEntries = parseGitIndex(indexFile)
      result.put("indexEntryCount", indexEntries.size)

      // Sample a few index entries with their stats
      val indexSamples = JSONArray()
      // Find $__StoryList.tid or similar common tiddler in the index
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

      // Check 新条目.tid
      val newEntryFile = File(root, "tiddlers/新条目.tid")
      val newObj = JSONObject()
      newObj.put("path", "tiddlers/新条目.tid")
      newObj.put("diskExists", newEntryFile.exists())
      if (newEntryFile.exists()) {
        newObj.put("diskSize", newEntryFile.length())
        newObj.put("diskMtime", newEntryFile.lastModified() / 1000)
      }
      val inIndex = indexEntries.any { it.path == "tiddlers/新条目.tid" }
      newObj.put("inIndex", inIndex)
      indexSamples.put(newObj)

      // Walk working dir and count
      val skipDirs = setOf(".git", "node_modules", "output")
      val workdirFiles = mutableSetOf<String>()
      fun walkDir(dir: File, prefix: String) {
        val children = dir.listFiles() ?: return
        for (child in children) {
          val relPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
          if (child.isDirectory) {
            if (child.name !in skipDirs) walkDir(child, relPath)
          } else {
            workdirFiles.add(relPath)
          }
        }
      }
      walkDir(root, "")

      result.put("workdirFileCount", workdirFiles.size)
      result.put("tiddlerFileCount", workdirFiles.count { it.startsWith("tiddlers/") })
      result.put("newEntryInWorkdir", workdirFiles.contains("tiddlers/新条目.tid"))
      result.put("samples", indexSamples)

      // Count files in workdir not in index (potential adds)
      val indexPaths = indexEntries.map { it.path }.toSet()
      val addCount = workdirFiles.count { it !in indexPaths }
      result.put("potentialAddCount", addCount)
      // List first 5 potential adds
      val addSamples = JSONArray()
      workdirFiles.filter { it !in indexPaths }.take(5).forEach { addSamples.put(it) }
      result.put("potentialAddSamples", addSamples)

      result.toString()
    }

    /**
     * Build a .git/index file from scratch by:
     * 1. Resolving HEAD → commit SHA → tree SHA
     * 2. Walking the tree recursively to enumerate (path, mode, blobSHA)
     * 3. Stat'ing each file on disk natively
     * 4. Writing a v2 binary .git/index file
     *
     * This is MUCH faster than isomorphic-git checkout because everything
     * runs natively without JS↔Kotlin bridge per-file overhead.
     *
     * @param gitRootDir  The repo root (parent of .git/)
     * @return JSON string with status: {"ok":true,"entries":N} or {"ok":false,"error":"..."}
     */
    AsyncFunction("buildGitIndex") { gitRootDir: String ->
      val root = File(gitRootDir)
      val gitDir = File(root, ".git")
      if (!gitDir.isDirectory) throw Exception("Not a git repository: $gitRootDir")

      try {
        // 1. Resolve HEAD to a commit SHA
        val headFile = File(gitDir, "HEAD")
        val headContent = headFile.readText(Charsets.UTF_8).trim()
        val commitSha: String = if (headContent.startsWith("ref: ")) {
          val refPath = headContent.removePrefix("ref: ")
          val refFile = File(gitDir, refPath)
          if (refFile.exists()) {
            refFile.readText(Charsets.UTF_8).trim()
          } else {
            // Try packed-refs
            resolvePackedRef(gitDir, refPath)
              ?: throw Exception("Cannot resolve HEAD ref: $refPath")
          }
        } else {
          headContent // Detached HEAD — already a SHA
        }
        android.util.Log.i("BuildGitIndex", "HEAD commit: $commitSha")

        // 2. Read the commit object → get tree SHA
        val commitBytes = readGitObject(gitDir, commitSha)
          ?: throw Exception("Cannot read commit object: $commitSha")
        val treeSha = parseCommitTreeSha(commitBytes)
          ?: throw Exception("Cannot find tree SHA in commit: $commitSha")
        android.util.Log.i("BuildGitIndex", "Root tree: $treeSha")

        // 3. Walk the tree recursively → collect all entries
        val entries = mutableListOf<GitTreeEntry>()
        fun walkTree(sha: String, prefix: String) {
          val treeBytes = readGitObject(gitDir, sha)
            ?: throw Exception("Cannot read tree object: $sha")
          parseTreeEntries(treeBytes).forEach { (name, entryMode, entrySha) ->
            val fullPath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (entryMode == 0x4000 || entryMode == 0o40000) {
              // Directory — recurse
              walkTree(bytesToHex(entrySha), fullPath)
            } else {
              entries.add(GitTreeEntry(fullPath, entryMode, entrySha))
            }
          }
        }
        walkTree(treeSha, "")
        android.util.Log.i("BuildGitIndex", "Tree walk found ${entries.size} entries")

        // 4. Sort entries by path (git index requires sorted order)
        entries.sortBy { it.path }

        // 5. Build the binary index
        val indexBytes = buildIndexBinary(root, entries)

        // 6. Write to .git/index
        val indexFile = File(gitDir, "index")
        indexFile.writeBytes(indexBytes)
        android.util.Log.i("BuildGitIndex", "Wrote index: ${indexBytes.size} bytes, ${entries.size} entries")

        val result = JSONObject()
        result.put("ok", true)
        result.put("entries", entries.size)
        result.put("indexSize", indexBytes.size)
        result.toString()
      } catch (e: Exception) {
        android.util.Log.e("BuildGitIndex", "Failed: ${e.message}", e)
        val result = JSONObject()
        result.put("ok", false)
        result.put("error", e.message ?: "Unknown error")
        result.toString()
      }
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

  // ─── Git byte-conversion helpers ─────────────────────────────────

  /** Convert a byte array to a lowercase hex string */
  private fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it) }
  }

  /** Convert a hex string to a byte array */
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

  /** Compare two 20-byte SHA-1 arrays lexicographically (unsigned) */
  private fun compareSha(a: ByteArray, b: ByteArray): Int {
    for (i in a.indices) {
      val av = a[i].toInt() and 0xFF
      val bv = b[i].toInt() and 0xFF
      if (av != bv) return av - bv
    }
    return 0
  }

  // ─── Git object reading helpers ──────────────────────────────────

  /** Resolve a ref from .git/packed-refs */
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

  /**
   * Read a git object by its SHA-1 hex string.
   * Tries loose objects first (.git/objects/ab/cdef...),
   * then falls back to pack files (.git/objects/pack/*.pack).
   * Returns the raw object content (after the "type size\0" header is stripped).
   */
  private fun readGitObject(gitDir: File, sha: String): ByteArray? {
    // Try loose object first
    val looseFile = File(gitDir, "objects/${sha.substring(0, 2)}/${sha.substring(2)}")
    if (looseFile.exists()) {
      return readLooseObject(looseFile)
    }
    // Try pack files
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

  /** Read and decompress a loose git object, returning raw content after header. */
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
    // Skip "type size\0" header
    val nullIdx = inflated.indexOf(0.toByte())
    return if (nullIdx >= 0) inflated.copyOfRange(nullIdx + 1, inflated.size) else inflated
  }

  /**
   * Find an object's offset in a pack index file (v2 format).
   * Returns the byte offset within the .pack file, or null if not found.
   */
  private fun findObjectInPackIndex(idxFile: File, sha: String): Long? {
    val shaBytes = hexToBytes(sha)
    RandomAccessFile(idxFile, "r").use { raf ->
      // V2 index starts with 0xff744f63 magic + 4-byte version
      val magic = ByteArray(4)
      raf.readFully(magic)
      if (magic[0] != 0xFF.toByte() || magic[1] != 0x74.toByte() ||
        magic[2] != 0x4F.toByte() || magic[3] != 0x63.toByte()) {
        return null // Not a v2 index
      }
      raf.readInt() // version (should be 2)

      // Fanout table: 256 entries of 4-byte big-endian counts
      val fanout = IntArray(256)
      for (i in 0 until 256) {
        fanout[i] = raf.readInt()
      }
      val totalObjects = fanout[255]

      // Binary search for the SHA in the sorted SHA table
      val firstByte = shaBytes[0].toInt() and 0xFF
      val lo = if (firstByte == 0) 0 else fanout[firstByte - 1]
      val hi = fanout[firstByte]

      // SHA table starts at offset 8 + 256*4 = 1032
      val shaTableStart = 8L + 256 * 4
      // Each SHA entry is 20 bytes
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
            // Found! Read the offset from the offset table
            // CRC table: after SHA table, totalObjects * 4 bytes
            // Offset table: after CRC table, totalObjects * 4 bytes
            val offsetTableStart = shaTableStart + totalObjects * 20L + totalObjects * 4L
            raf.seek(offsetTableStart + mid * 4L)
            val offset = raf.readInt().toLong() and 0xFFFFFFFFL
            return if (offset and 0x80000000L != 0L) {
              // Large offset — read from 8-byte table
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

  /**
   * Read a single object from a .pack file at the given byte offset.
   * Handles types: commit, tree, blob, and OFS_DELTA / REF_DELTA.
   */
  private fun readObjectFromPack(packFile: File, offset: Long, gitDir: File): ByteArray? {
    RandomAccessFile(packFile, "r").use { raf ->
      raf.seek(offset)
      // Read variable-length object header
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
        1, 2, 3, 4 -> { // commit, tree, blob, tag — just decompress
          decompressFromRaf(raf, size)
        }
        6 -> { // OFS_DELTA
          // Read negative offset
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
        7 -> { // REF_DELTA
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

  /** Decompress zlib data from current RAF position */
  private fun decompressFromRaf(raf: RandomAccessFile, expectedSize: Long): ByteArray {
    // Read remaining data from current position for decompression
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

  /** Apply a git delta to a base object */
  private fun applyDelta(base: ByteArray, delta: ByteArray): ByteArray {
    var pos = 0
    // Read base size (variable-length)
    var baseSize = 0L
    var shift = 0
    do {
      val b = delta[pos++].toInt() and 0xFF
      baseSize = baseSize or ((b and 0x7F).toLong() shl shift)
      shift += 7
    } while (b and 0x80 != 0)

    // Read result size (variable-length)
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
        // Copy from base
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
        // Insert literal data
        System.arraycopy(delta, pos, result, resultPos, cmd)
        pos += cmd
        resultPos += cmd
      }
    }
    return result
  }

  /** Parse a commit object to extract the tree SHA (first line: "tree <sha>\n") */
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

  /**
   * Parse a tree object into a list of (name, mode, sha-bytes) entries.
   *
   * Tree format: repeated entries of "mode name\0<20-byte SHA>"
   */
  private fun parseTreeEntries(treeData: ByteArray): List<Triple<String, Int, ByteArray>> {
    val entries = mutableListOf<Triple<String, Int, ByteArray>>()
    var pos = 0
    while (pos < treeData.size) {
      // Read "mode " (space-separated)
      var spaceIdx = pos
      while (spaceIdx < treeData.size && treeData[spaceIdx] != ' '.code.toByte()) spaceIdx++
      if (spaceIdx >= treeData.size) break
      val modeStr = String(treeData, pos, spaceIdx - pos, Charsets.US_ASCII)
      val mode = modeStr.toInt(8)
      pos = spaceIdx + 1

      // Read name until NUL
      var nullIdx = pos
      while (nullIdx < treeData.size && treeData[nullIdx] != 0.toByte()) nullIdx++
      if (nullIdx >= treeData.size) break
      val name = String(treeData, pos, nullIdx - pos, Charsets.UTF_8)
      pos = nullIdx + 1

      // Read 20-byte SHA
      if (pos + 20 > treeData.size) break
      val sha = treeData.copyOfRange(pos, pos + 20)
      pos += 20

      entries.add(Triple(name, mode, sha))
    }
    return entries
  }

  /** Data class for a tree entry (file path, mode, blob SHA bytes) */
  private data class GitTreeEntry(val path: String, val mode: Int, val sha: ByteArray)

  /**
   * Build a version 2 .git/index binary from tree entries and disk stats.
   *
   * Index v2 format:
   *   - 12-byte header: "DIRC" + version(4) + numEntries(4)
   *   - Sorted entries, each:
   *       ctime_s(4) + ctime_ns(4) + mtime_s(4) + mtime_ns(4) +
   *       dev(4) + ino(4) + mode(4) + uid(4) + gid(4) +
   *       file_size(4) + SHA-1(20) + flags(2) + path(variable) + NUL padding to 8-byte boundary
   *   - 20-byte SHA-1 checksum over the entire index (header + entries)
   */
  private fun buildIndexBinary(root: File, entries: List<GitTreeEntry>): ByteArray {
    val baos = ByteArrayOutputStream()

    fun writeInt32(value: Int) {
      baos.write((value shr 24) and 0xFF)
      baos.write((value shr 16) and 0xFF)
      baos.write((value shr 8) and 0xFF)
      baos.write(value and 0xFF)
    }

    // Header: "DIRC" + version 2 + entry count
    baos.write("DIRC".toByteArray(Charsets.US_ASCII))
    writeInt32(2) // version
    writeInt32(entries.size)

    for (entry in entries) {
      val workFile = File(root, entry.path)
      val mtimeMs = if (workFile.exists()) workFile.lastModified() else 0L
      val mtimeS = (mtimeMs / 1000).toInt()
      val mtimeNs = ((mtimeMs % 1000) * 1_000_000).toInt()
      val fileSize = if (workFile.exists()) workFile.length().toInt() else 0

      // ctime (same as mtime for simplicity)
      writeInt32(mtimeS)
      writeInt32(mtimeNs)
      // mtime
      writeInt32(mtimeS)
      writeInt32(mtimeNs)
      // dev, ino (0 — not available on Android)
      writeInt32(0)
      writeInt32(0)
      // mode: entry.mode is octal (e.g., 0o100644), needs to be stored as-is
      writeInt32(entry.mode)
      // uid, gid (0)
      writeInt32(0)
      writeInt32(0)
      // file size
      writeInt32(fileSize)
      // 20-byte SHA-1 of the blob
      baos.write(entry.sha)
      // flags: assume flag = (pathLen & 0xFFF)
      val pathBytes = entry.path.toByteArray(Charsets.UTF_8)
      val flags = pathBytes.size.coerceAtMost(0xFFF)
      baos.write((flags shr 8) and 0xFF)
      baos.write(flags and 0xFF)
      // path + NUL + padding to 8-byte boundary
      baos.write(pathBytes)
      baos.write(0) // NUL terminator
      // Pad to 8-byte boundary (entry starts at header end or previous entry end)
      // Total entry size so far = 62 + pathBytes.size + 1
      val entrySize = 62 + pathBytes.size + 1
      val padding = (8 - (entrySize % 8)) % 8
      for (i in 0 until padding) {
        baos.write(0)
      }
    }

    val content = baos.toByteArray()

    // Compute SHA-1 checksum over everything
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    val checksum = digest.digest(content)

    // Final result: content + checksum
    val result = ByteArrayOutputStream(content.size + 20)
    result.write(content)
    result.write(checksum)
    return result.toByteArray()
  }

  // ─── Git index parser ─────────────────────────────────────────────

  /**
   * Minimal representation of a git index entry — just what we need
   * for stat-cache comparison.
   */
  data class GitIndexEntry(
    val path: String,
    val size: Long,
    val mtimeSeconds: Long,
  )

  /**
   * Parse a git index file (versions 2, 3, 4).
   *
   * Format reference: https://git-scm.com/docs/index-format
   *
   * We only extract the fields needed for stat-cache comparison:
   * file path, file size, and mtime (seconds).
   */
  private fun parseGitIndex(indexFile: File): List<GitIndexEntry> {
    val bytes = indexFile.readBytes()
    if (bytes.size < 12) return emptyList()

    // Header: 4-byte signature "DIRC"
    val sig = String(bytes, 0, 4, Charsets.US_ASCII)
    if (sig != "DIRC") return emptyList()

    // 4-byte version number
    val version = readInt32(bytes, 4)
    if (version !in 2..4) return emptyList()

    // 4-byte number of entries
    val entryCount = readInt32(bytes, 8)
    val entries = ArrayList<GitIndexEntry>(entryCount)

    var offset = 12 // start of first entry

    for (i in 0 until entryCount) {
      if (offset + 62 > bytes.size) break // minimum entry size

      // Offset 0: 32-bit ctime seconds (skip)
      // Offset 4: 32-bit ctime nanoseconds (skip)
      // Offset 8: 32-bit mtime seconds
      val mtimeSeconds = readInt32(bytes, offset + 8).toLong() and 0xFFFFFFFFL
      // Offset 12: 32-bit mtime nanoseconds (skip)
      // Offset 16: 32-bit dev (skip)
      // Offset 20: 32-bit ino (skip)
      // Offset 24: 32-bit mode (skip)
      // Offset 28: 32-bit uid (skip)
      // Offset 32: 32-bit gid (skip)
      // Offset 36: 32-bit file size
      val fileSize = readInt32(bytes, offset + 36).toLong() and 0xFFFFFFFFL
      // Offset 40: 160-bit (20 bytes) SHA-1 (skip)
      // Offset 60: 16-bit flags
      val flags = readInt16(bytes, offset + 60)
      val nameLength = flags and 0xFFF

      // The path starts at offset 62
      val pathStart = offset + 62
      val pathEnd: Int
      if (nameLength == 0xFFF) {
        // Name is longer than 0xFFF — find the NUL terminator
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

      // Entry is padded to a multiple of 8 bytes (from the start of the entry).
      // Total entry bytes = 62 + pathLength + 1 (NUL), rounded up to 8.
      if (version < 4) {
        val entryLength = 62 + (pathEnd - pathStart) + 1
        val paddedLength = (entryLength + 7) and 7.inv()
        offset += paddedLength
      } else {
        // Version 4 uses prefix compression — path is stored differently.
        // For simplicity, fall back to NUL scanning.
        var nextOffset = pathEnd + 1
        // No padding in v4
        offset = nextOffset
      }
    }

    return entries
  }

  /** Read a big-endian 32-bit integer from a byte array. */
  private fun readInt32(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
      (bytes[offset + 3].toInt() and 0xFF)
  }

  /** Read a big-endian 16-bit integer from a byte array. */
  private fun readInt16(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or
      (bytes[offset + 1].toInt() and 0xFF)
  }
}
