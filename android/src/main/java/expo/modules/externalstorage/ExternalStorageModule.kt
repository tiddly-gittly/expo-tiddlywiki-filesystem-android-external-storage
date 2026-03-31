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
import java.io.BufferedInputStream
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
