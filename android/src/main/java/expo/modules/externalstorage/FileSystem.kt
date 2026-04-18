package expo.modules.externalstorage

import android.os.Build
import android.os.Environment
import android.util.Base64
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
 * Filesystem operations: basic I/O, HTTP streaming, tar extraction.
 * Pure filesystem utilities with no Git or TiddlyWiki dependencies.
 */
internal object FileSystem {

  // ─── Basic File Operations ─────────────────────────────────────

  fun exists(path: String): Boolean = File(path).exists()

  fun getInfo(path: String): Map<String, Any> {
    val file = File(path)
    if (!file.exists()) {
      return mapOf(
        "exists" to false,
        "isDirectory" to false,
        "size" to 0L,
        "modificationTime" to 0L,
      )
    }
    return mapOf(
      "exists" to true,
      "isDirectory" to file.isDirectory,
      "size" to file.length(),
      "modificationTime" to file.lastModified(),
    )
  }

  fun mkdir(path: String) {
    val dir = File(path)
    if (!dir.exists()) {
      val ok = dir.mkdirs()
      if (!ok && !dir.exists()) {
        throw Exception("Failed to create directory: $path")
      }
    }
  }

  fun readDir(path: String): List<String> {
    val dir = File(path)
    if (!dir.exists() || !dir.isDirectory) {
      throw Exception("ENOENT: no such directory: $path")
    }
    return dir.list()?.toList() ?: emptyList()
  }

  fun readDirRecursive(path: String): List<String> {
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
    return result
  }

  fun rmdir(path: String) {
    val dir = File(path)
    if (dir.exists()) {
      dir.deleteRecursively()
    }
  }

  fun readFileUtf8(path: String): String {
    val file = File(path)
    if (!file.exists()) {
      throw Exception("ENOENT: no such file: $path")
    }
    return file.readText(Charsets.UTF_8)
  }

  fun readFileBase64(path: String): String {
    val file = File(path)
    if (!file.exists()) {
      throw Exception("ENOENT: no such file: $path")
    }
    return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
  }

  fun writeFileUtf8(path: String, content: String) {
    val file = File(path)
    file.parentFile?.let { parent ->
      if (!parent.exists()) parent.mkdirs()
    }
    file.writeText(content, Charsets.UTF_8)
  }

  fun writeFileBase64(path: String, base64Content: String) {
    val file = File(path)
    file.parentFile?.let { parent ->
      if (!parent.exists()) parent.mkdirs()
    }
    val bytes = Base64.decode(base64Content, Base64.DEFAULT)
    file.writeBytes(bytes)
  }

  fun appendFileBase64(path: String, base64Content: String, truncateFirst: Boolean) {
    val file = File(path)
    file.parentFile?.let { parent ->
      if (!parent.exists()) parent.mkdirs()
    }
    val bytes = Base64.decode(base64Content, Base64.DEFAULT)
    FileOutputStream(file, !truncateFirst).use { fos ->
      fos.write(bytes)
    }
  }

  fun writeFilesBase64(paths: List<String>, base64Contents: List<String>): Map<String, Int> {
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

    return mapOf("writtenCount" to paths.size)
  }

  fun deleteFile(path: String) {
    val file = File(path)
    if (file.exists()) {
      file.delete()
    }
  }

  // ─── Storage Permission Helpers ────────────────────────────────

  fun isExternalStorageWritable(): Boolean {
    return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
  }

  fun getExternalStorageDirectory(): String {
    return Environment.getExternalStorageDirectory()?.absolutePath ?: ""
  }

  fun isExternalStorageManager(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Environment.isExternalStorageManager()
    } else {
      true
    }
  }

  // ─── HTTP Streaming Operations ─────────────────────────────────

  fun httpPostToFile(
    url: String,
    headersMap: Map<String, String>,
    bodyBase64: String,
    destPath: String,
    contentType: String
  ): Map<String, Any> {
    val client = OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(5, TimeUnit.MINUTES)
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
          val buffer = ByteArray(64 * 1024)
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

    return mapOf(
      "statusCode" to response.code,
      "headers" to responseHeaders,
      "bytesWritten" to bytesWritten,
    )
  }

  fun readFileChunk(path: String, offset: Long, length: Int): Map<String, Any> {
    val file = RandomAccessFile(path, "r")
    file.use { raf ->
      val fileLength = raf.length()
      if (offset >= fileLength) {
        return mapOf(
          "data" to "",
          "bytesRead" to 0,
        )
      }
      raf.seek(offset)
      val toRead = minOf(length.toLong(), fileLength - offset).toInt()
      val buffer = ByteArray(toRead)
      val bytesRead = raf.read(buffer, 0, toRead)
      if (bytesRead <= 0) {
        return mapOf(
          "data" to "",
          "bytesRead" to 0,
        )
      }
      val actual = if (bytesRead < toRead) buffer.copyOf(bytesRead) else buffer
      return mapOf(
        "data" to Base64.encodeToString(actual, Base64.NO_WRAP),
        "bytesRead" to bytesRead,
      )
    }
  }

  fun downloadFileResumable(
    url: String,
    headersMap: Map<String, String>,
    destPath: String
  ): Map<String, Any> {
    val destFile = File(destPath)
    destFile.parentFile?.let { parent ->
      if (!parent.exists()) parent.mkdirs()
    }

    val existingBytes = if (destFile.exists()) destFile.length() else 0L

    val client = OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build()

    val requestBuilder = Request.Builder()
      .url(url)
      .headers(headersMap.toHeaders())

    if (existingBytes > 0) {
      requestBuilder.addHeader("Range", "bytes=$existingBytes-")
    }

    val request = requestBuilder.build()
    val response = client.newCall(request).execute()

    val statusCode = response.code
    var resumed = false

    response.body?.let { body ->
      if (statusCode == 206) {
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

    return mapOf(
      "statusCode" to statusCode,
      "totalBytes" to destFile.length(),
      "resumed" to resumed,
    )
  }

  // ─── Tar Extraction ────────────────────────────────────────────

  fun extractTar(tarPath: String, destDir: String): Map<String, Int> {
    val tarFile = File(tarPath)
    if (!tarFile.exists()) {
      throw Exception("ENOENT: tar file not found: $tarPath")
    }

    val dest = File(destDir)
    if (!dest.exists()) dest.mkdirs()

    val canonicalDest = dest.canonicalPath

    var filesExtracted = 0
    var longName: String? = null

    BufferedInputStream(FileInputStream(tarFile), 256 * 1024).use { bis ->
      val headerBuf = ByteArray(512)

      while (true) {
        val headerRead = readFully(bis, headerBuf)
        if (headerRead < 512) break

        if (headerBuf.all { it == 0.toByte() }) break

        val rawName = extractString(headerBuf, 0, 100)
        val sizeStr = extractString(headerBuf, 124, 12).trim()
        val fileSize = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
        val typeFlag = headerBuf[156].toInt().toChar()
        val prefix = extractString(headerBuf, 345, 155)

        if (typeFlag == 'L') {
          val nameBuf = ByteArray(fileSize.toInt())
          readFully(bis, nameBuf)
          longName = String(nameBuf, Charsets.UTF_8).trimEnd('\u0000')
          val remainder = (512 - (fileSize % 512).toInt()) % 512
          if (remainder > 0) bis.skip(remainder.toLong())
          continue
        }

        if (typeFlag == 'x' || typeFlag == 'g') {
          val paxBuf = ByteArray(fileSize.toInt())
          readFully(bis, paxBuf)
          val paxStr = String(paxBuf, Charsets.UTF_8)
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
          val remainder = (512 - (fileSize % 512).toInt()) % 512
          if (remainder > 0) bis.skip(remainder.toLong())
          continue
        }

        val fileName = longName ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
        longName = null

        if (fileName.isEmpty()) {
          skipDataBlocks(bis, fileSize)
          continue
        }

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

            val remainder = (512 - (fileSize % 512).toInt()) % 512
            if (remainder > 0) bis.skip(remainder.toLong())

            filesExtracted++
          }
          else -> {
            skipDataBlocks(bis, fileSize)
          }
        }
      }
    }

    return mapOf("filesExtracted" to filesExtracted)
  }

  // ─── Tar Helper Functions ──────────────────────────────────────

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
    val totalBytes = ((fileSize + 511) / 512) * 512
    var skipped = 0L
    while (skipped < totalBytes) {
      val n = stream.skip(totalBytes - skipped)
      if (n <= 0) break
      skipped += n
    }
  }
}
