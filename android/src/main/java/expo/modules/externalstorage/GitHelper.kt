package expo.modules.externalstorage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.internal.storage.pack.PackWriter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.PacketLineIn
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

/**
 * All git-related operations using JGit — a pure Java implementation of Git.
 * This replaces the previous hand-written git index/object parsing with JGit's
 * high-level API, providing reliable git status, push, fetch, checkout, and
 * index building.
 */
internal object GitHelper {

  // ─── Helpers ─────────────────────────────────────────────────────

  /** Open a JGit Repository from a working directory path. */
  private fun openRepo(gitRootDir: String): Repository {
    val root = File(gitRootDir)
    val gitDir = File(root, ".git")
    if (!gitDir.exists()) {
      throw Exception("Not a git repository: $gitRootDir (no .git directory)")
    }
    return FileRepositoryBuilder()
      .setGitDir(gitDir)
      .setWorkTree(root)
      .readEnvironment()
      .build()
  }

  // ─── Raw HTTP helpers for Git Smart HTTP protocol ─────────────────

  /**
   * Parse headers JSON into a map.
   */
  private fun parseHeaders(headers: String?): Map<String, String> {
    if (headers == null) return emptyMap()
    return try {
      val obj = JSONObject(headers)
      val map = mutableMapOf<String, String>()
      for (key in obj.keys()) map[key] = obj.getString(key)
      map
    } catch (e: Exception) {
      emptyMap()
    }
  }

  /**
   * Open an HTTP connection with custom headers applied.
   */
  private fun openHttpConnection(url: String, method: String, headers: Map<String, String>, contentType: String? = null): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 30_000
    conn.readTimeout = 120_000
    conn.instanceFollowRedirects = true
    for ((k, v) in headers) conn.setRequestProperty(k, v)
    if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
    return conn
  }

  /**
   * Parse pkt-line formatted /info/refs response into a map of ref→oid.
   * Also returns the set of capabilities advertised by the server.
   */
  private data class ServerRefs(
    val refs: Map<String, String>,
    val capabilities: Set<String>
  )

  private fun parseInfoRefs(input: InputStream, service: String): ServerRefs {
    val pktIn = PacketLineIn(input)
    val refs = mutableMapOf<String, String>()
    val capabilities = mutableSetOf<String>()

    // First: skip service announcement line(s) and flush
    // Format: "# service=git-upload-pack\n" + "0000"
    // The server may also send the line as a pkt-line.
    var firstRef = true
    while (true) {
      val line = pktIn.readString()
      if (PacketLineIn.isEnd(line)) break // flush packet after service announcement
      // Some servers include "# service=..." in pkt-line format
      if (line.startsWith("# ")) continue
      // If we got here without a flush, it's the first ref line
      // (shouldn't happen with standard servers)
    }

    // Now read refs
    while (true) {
      val line = pktIn.readString()
      if (PacketLineIn.isEnd(line)) break
      if (line.isEmpty()) continue

      val parts = line.split(" ", limit = 2)
      if (parts.size < 2) continue
      val oid = parts[0]
      val refWithCaps = parts[1]

      if (firstRef && refWithCaps.contains('\u0000')) {
        // First ref line contains capabilities after NUL
        val refParts = refWithCaps.split('\u0000', limit = 2)
        refs[refParts[0]] = oid
        if (refParts.size > 1) {
          capabilities.addAll(refParts[1].trim().split(" "))
        }
        firstRef = false
      } else {
        refs[refWithCaps.trim()] = oid
        firstRef = false
      }
    }

    return ServerRefs(refs, capabilities)
  }

  // ─── Git push via raw HTTP ──────────────────────────────────────

  /**
   * Push local branch to remote using raw Git Smart HTTP protocol.
   *
   * This bypasses JGit's TransportHttp (which has issues with some servers)
   * and directly implements the stateless-rpc push protocol:
   *
   * 1. GET /info/refs?service=git-receive-pack → parse server refs + capabilities
   * 2. Build pack data containing objects the server is missing
   * 3. POST /git-receive-pack with ref-update command + pack data
   * 4. Parse server response for success/failure
   */
  fun gitPush(
    gitRootDir: String,
    remoteName: String,
    localBranch: String,
    remoteBranch: String,
    force: Boolean,
    headers: String?
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val headerMap = parseHeaders(headers)
        val remoteUrl = repo.config.getString("remote", remoteName, "url")
          ?: throw Exception("Remote '$remoteName' not configured")

        // Resolve local branch to its SHA
        val localRef = repo.resolve("refs/heads/$localBranch")
          ?: throw Exception("Cannot resolve local branch: $localBranch")

        // Step 1: GET /info/refs?service=git-receive-pack
        val infoUrl = "$remoteUrl/info/refs?service=git-receive-pack"
        val infoConn = openHttpConnection(infoUrl, "GET", headerMap)
        infoConn.setRequestProperty("Accept", "application/x-git-receive-pack-advertisement, */*")
        val serverRefs: ServerRefs
        try {
          if (infoConn.responseCode != 200) {
            throw Exception("info/refs failed: ${infoConn.responseCode} ${infoConn.responseMessage}")
          }
          serverRefs = parseInfoRefs(infoConn.inputStream, "git-receive-pack")
        } finally {
          infoConn.disconnect()
        }

        // Check if push is needed
        val remoteOid = serverRefs.refs[remoteBranch] ?: ObjectId.zeroId().name
        if (remoteOid == localRef.name) {
          result.put("ok", true)
          result.put("updates", JSONArray().put(JSONObject().apply {
            put("remoteName", remoteBranch)
            put("status", "UP_TO_DATE")
            put("message", "")
          }))
          return result.toString()
        }

        // Step 2: Build the pack data
        val packBuf = ByteArrayOutputStream()

        // Write the ref-update command line in pkt-line format
        // Format: <old-oid> <new-oid> <ref-name>\0<capabilities>\n
        val caps = mutableListOf("report-status", "side-band-64k")
        if (serverRefs.capabilities.contains("ofs-delta")) caps.add("ofs-delta")
        val commandLine = "$remoteOid ${localRef.name} $remoteBranch\u0000${caps.joinToString(" ")}\n"
        writePktLine(packBuf, commandLine)
        // Flush packet (0000)
        packBuf.write("0000".toByteArray())

        // Build pack with objects missing on server
        val remoteObjectId = if (remoteOid == ObjectId.zeroId().name) null else ObjectId.fromString(remoteOid)
        val localObjectId = localRef

        val packData = ByteArrayOutputStream()
        val writer = PackWriter(repo)
        try {
          writer.setUseBitmaps(true)
          writer.setThin(true)
          writer.setDeltaBaseAsOffset(serverRefs.capabilities.contains("ofs-delta"))

          // Determine which objects to send
          val want = setOf(localObjectId)
          val have = if (remoteObjectId != null) setOf(remoteObjectId) else emptySet<ObjectId>()
          writer.preparePack(NullProgressMonitor.INSTANCE, want, have)
          writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, packData)
        } finally {
          writer.close()
        }

        packBuf.write(packData.toByteArray())

        // Step 3: POST /git-receive-pack
        val postUrl = "$remoteUrl/git-receive-pack"
        val postConn = openHttpConnection(postUrl, "POST", headerMap, "application/x-git-receive-pack-request")
        postConn.setRequestProperty("Accept", "application/x-git-receive-pack-result")
        postConn.doOutput = true
        val requestBody = packBuf.toByteArray()
        postConn.setFixedLengthStreamingMode(requestBody.size)

        try {
          postConn.outputStream.use { it.write(requestBody) }

          if (postConn.responseCode != 200) {
            throw Exception("git-receive-pack failed: ${postConn.responseCode} ${postConn.responseMessage}")
          }

          // Step 4: Parse response
          val responseBytes = postConn.inputStream.readBytes()
          val responseStr = tryParseReceivePackResponse(responseBytes)

          val updatesArray = JSONArray()
          val updateObj = JSONObject()
          updateObj.put("remoteName", remoteBranch)
          updateObj.put("status", if (responseStr.contains("unpack ok")) "OK" else "REJECTED")
          updateObj.put("message", responseStr)
          updatesArray.put(updateObj)

          if (!responseStr.contains("unpack ok")) {
            throw Exception("Push rejected by server: $responseStr")
          }

          result.put("ok", true)
          result.put("updates", updatesArray)
          android.util.Log.i("GitPush", "Raw HTTP push completed: ${localRef.name.take(8)} → $remoteBranch")
        } finally {
          postConn.disconnect()
        }
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown push error")
      android.util.Log.e("GitPush", "Push failed: ${e.message}", e)
    }
    return result.toString()
  }

  private fun writePktLine(out: ByteArrayOutputStream, data: String) {
    val bytes = data.toByteArray()
    val length = bytes.size + 4
    val hex = String.format("%04x", length)
    out.write(hex.toByteArray())
    out.write(bytes)
  }

  /**
   * Try to parse receive-pack response, handling side-band encoding.
   */
  private fun tryParseReceivePackResponse(responseBytes: ByteArray): String {
    // The response may be plain pkt-line or side-band encoded.
    // Try to extract meaningful text.
    val text = StringBuilder()
    try {
      val pktIn = PacketLineIn(ByteArrayInputStream(responseBytes))
      while (true) {
        val line = pktIn.readString()
        if (PacketLineIn.isEnd(line)) break
        text.append(line).append("\n")
      }
    } catch (e: Exception) {
      // Fallback: just decode as UTF-8
      text.append(String(responseBytes, Charsets.UTF_8))
    }
    return text.toString().trim()
  }

  // ─── Git fetch via raw HTTP ─────────────────────────────────────

  /**
   * Fetch from remote using raw Git Smart HTTP protocol.
   *
   * This bypasses JGit's TransportHttp and directly implements:
   *
   * 1. GET /info/refs?service=git-upload-pack → parse server refs
   * 2. Build wants/haves negotiation message
   * 3. POST /git-upload-pack with wants/haves + done → receive pack data
   * 4. Parse and apply pack to local repo, update tracking refs
   */
  fun gitFetch(
    gitRootDir: String,
    remoteName: String,
    branch: String,
    headers: String?
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val headerMap = parseHeaders(headers)
        val remoteUrl = repo.config.getString("remote", remoteName, "url")
          ?: throw Exception("Remote '$remoteName' not configured")

        // Step 1: GET /info/refs?service=git-upload-pack
        val infoUrl = "$remoteUrl/info/refs?service=git-upload-pack"
        val infoConn = openHttpConnection(infoUrl, "GET", headerMap)
        infoConn.setRequestProperty("Accept", "application/x-git-upload-pack-advertisement, */*")
        val serverRefs: ServerRefs
        try {
          if (infoConn.responseCode != 200) {
            throw Exception("info/refs failed: ${infoConn.responseCode} ${infoConn.responseMessage}")
          }
          serverRefs = parseInfoRefs(infoConn.inputStream, "git-upload-pack")
        } finally {
          infoConn.disconnect()
        }

        // Find the ref we want
        val remoteRef = "refs/heads/$branch"
        val wantOid = serverRefs.refs[remoteRef]
        if (wantOid == null) {
          // Branch doesn't exist on remote — nothing to fetch
          result.put("ok", true)
          result.put("updates", JSONArray())
          return result.toString()
        }

        // Check if we already have this object
        val wantObjectId = ObjectId.fromString(wantOid)
        val trackingRef = "refs/remotes/$remoteName/$branch"
        val localTrackingOid = repo.resolve(trackingRef)

        if (localTrackingOid != null && localTrackingOid == wantObjectId) {
          // Already up to date
          result.put("ok", true)
          result.put("updates", JSONArray())
          return result.toString()
        }

        // Also check if we already have the object in our object database
        if (repo.objectDatabase.has(wantObjectId)) {
          // We have the object, just update the tracking ref
          updateRef(repo, trackingRef, wantObjectId)
          val updatesArray = JSONArray()
          updatesArray.put(JSONObject().apply {
            put("ref", trackingRef)
            put("oldObjectId", localTrackingOid?.name ?: ObjectId.zeroId().name)
            put("newObjectId", wantOid)
          })
          result.put("ok", true)
          result.put("updates", updatesArray)
          return result.toString()
        }

        // Step 2: Build the upload-pack request
        val requestBuf = ByteArrayOutputStream()

        // Write "want" lines
        // First want line includes capabilities
        val caps = mutableListOf("no-progress", "report-status", "side-band-64k")
        if (serverRefs.capabilities.contains("ofs-delta")) caps.add("ofs-delta")
        if (serverRefs.capabilities.contains("thin-pack")) caps.add("thin-pack")
        writePktLine(requestBuf, "want $wantOid ${caps.joinToString(" ")}\n")

        // Additional wants: also fetch any other refs we might need
        // (for now, just the one branch)
        // Flush after wants
        requestBuf.write("0000".toByteArray())

        // Write "have" lines — tell server what we already have
        // Send recent commits so server can compute a thin pack
        val haveOids = collectHaveOids(repo, 256)
        for (have in haveOids) {
          writePktLine(requestBuf, "have ${have.name}\n")
        }

        // "done" — single-round stateless negotiation
        writePktLine(requestBuf, "done\n")
        // Flush
        requestBuf.write("0000".toByteArray())

        // Step 3: POST /git-upload-pack
        val postUrl = "$remoteUrl/git-upload-pack"
        val postConn = openHttpConnection(postUrl, "POST", headerMap, "application/x-git-upload-pack-request")
        postConn.setRequestProperty("Accept", "application/x-git-upload-pack-result")
        postConn.doOutput = true
        val requestBody = requestBuf.toByteArray()
        postConn.setFixedLengthStreamingMode(requestBody.size)

        try {
          postConn.outputStream.use { it.write(requestBody) }

          if (postConn.responseCode != 200) {
            throw Exception("git-upload-pack failed: ${postConn.responseCode} ${postConn.responseMessage}")
          }

          // Step 4: Parse response and apply pack
          val responseStream = postConn.inputStream
          val responseBytes = responseStream.readBytes()

          // The response is pkt-line encoded. It starts with NAK or ACK lines,
          // then contains pack data (possibly side-band encoded).
          val packData = extractPackData(responseBytes)

          if (packData != null && packData.isNotEmpty()) {
            // Parse and index the pack
            val inserter = repo.newObjectInserter()
            try {
              val parser = inserter.newPackParser(ByteArrayInputStream(packData))
              parser.setAllowThin(true)
              parser.parse(NullProgressMonitor.INSTANCE)
              inserter.flush()
            } finally {
              inserter.close()
            }
          }

          // Update tracking ref
          updateRef(repo, trackingRef, wantObjectId)

          val updatesArray = JSONArray()
          updatesArray.put(JSONObject().apply {
            put("ref", trackingRef)
            put("oldObjectId", localTrackingOid?.name ?: ObjectId.zeroId().name)
            put("newObjectId", wantOid)
          })

          result.put("ok", true)
          result.put("updates", updatesArray)
          android.util.Log.i("GitFetch", "Raw HTTP fetch completed: $wantOid → $trackingRef")
        } finally {
          postConn.disconnect()
        }
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown fetch error")
      android.util.Log.e("GitFetch", "Fetch failed: ${e.message}", e)
    }
    return result.toString()
  }

  /**
   * Collect recent commit OIDs that we can tell the server we "have".
   * This helps the server send a minimal pack.
   */
  private fun collectHaveOids(repo: Repository, maxCount: Int): List<ObjectId> {
    val oids = mutableListOf<ObjectId>()
    try {
      val walk = RevWalk(repo)
      // Add all refs as starting points
      for (ref in repo.refDatabase.refs) {
        try {
          val peeled = repo.refDatabase.peel(ref)
          val target = peeled.peeledObjectId ?: peeled.objectId ?: continue
          walk.markStart(walk.parseCommit(target))
        } catch (e: Exception) {
          // Skip non-commit refs
        }
      }
      var count = 0
      for (commit in walk) {
        oids.add(commit.id)
        if (++count >= maxCount) break
      }
      walk.close()
    } catch (e: Exception) {
      android.util.Log.w("GitFetch", "collectHaveOids error: ${e.message}")
    }
    return oids
  }

  /**
   * Update a ref to point to a new object ID.
   */
  private fun updateRef(repo: Repository, refName: String, newId: ObjectId) {
    val refUpdate = repo.updateRef(refName)
    refUpdate.setNewObjectId(newId)
    refUpdate.isForceUpdate = true
    val updateResult = refUpdate.update()
    android.util.Log.i("GitFetch", "Updated ref $refName → ${newId.name.take(8)}: $updateResult")
  }

  /**
   * Extract pack data from a git-upload-pack response.
   *
   * The response format is:
   * - pkt-line with "NAK\n" or "ACK <oid>\n" lines
   * - Then pack data, possibly side-band encoded
   *
   * Side-band encoding: each pkt-line starts with a channel byte:
   * - 1 = pack data
   * - 2 = progress messages  
   * - 3 = error messages
   *
   * We need to handle both side-band and non-side-band responses.
   */
  private fun extractPackData(responseBytes: ByteArray): ByteArray? {
    if (responseBytes.isEmpty()) return null

    val packBytes = ByteArrayOutputStream()
    val input = ByteArrayInputStream(responseBytes)

    try {
      // Read pkt-lines
      while (input.available() > 0) {
        // Read 4-byte hex length
        val hexBytes = ByteArray(4)
        val read = input.read(hexBytes)
        if (read < 4) break

        val hexStr = String(hexBytes)
        if (hexStr == "0000") continue // flush packet
        if (hexStr == "0001") continue // delimiter packet
        if (hexStr == "0002") continue // response-end packet

        val length = try { hexStr.toInt(16) } catch (e: Exception) {
          // Not a valid pkt-line, might be raw pack data
          // Check if the first 4 bytes are "PACK" signature
          if (hexStr == "PACK") {
            packBytes.write(hexBytes)
            val remaining = ByteArray(input.available())
            input.read(remaining)
            packBytes.write(remaining)
            break
          }
          break
        }

        if (length <= 4) continue // empty line

        val dataLen = length - 4
        val data = ByteArray(dataLen)
        var totalRead = 0
        while (totalRead < dataLen) {
          val n = input.read(data, totalRead, dataLen - totalRead)
          if (n < 0) break
          totalRead += n
        }

        // Check the content
        val text = String(data, Charsets.UTF_8).trim()
        if (text == "NAK" || text.startsWith("ACK ")) continue

        // Check for side-band encoding
        if (data.isNotEmpty()) {
          val channel = data[0].toInt()
          when (channel) {
            1 -> {
              // Pack data
              packBytes.write(data, 1, data.size - 1)
            }
            2 -> {
              // Progress - log and skip
              android.util.Log.d("GitFetch", "progress: ${String(data, 1, data.size - 1).trim()}")
            }
            3 -> {
              // Error
              val errorMsg = String(data, 1, data.size - 1).trim()
              android.util.Log.e("GitFetch", "server error: $errorMsg")
              throw Exception("Server error during fetch: $errorMsg")
            }
            else -> {
              // No side-band: check if this is raw pack data
              if (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'A'.code.toByte()
                && data[2] == 'C'.code.toByte() && data[3] == 'K'.code.toByte()) {
                packBytes.write(data)
              } else {
                // Unknown data — might be text response, skip
                android.util.Log.d("GitFetch", "Unknown pkt-line: ${text.take(100)}")
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      android.util.Log.w("GitFetch", "extractPackData: ${e.message}")
    }

    val result = packBytes.toByteArray()
    return if (result.isEmpty()) null else result
  }

  // ─── Git status (JGit) ──────────────────────────────────────────

  /**
   * Compute git status using JGit's Status API.
   *
   * JGit internally uses the DirCache (git index) and compares it against
   * the working tree and HEAD tree, producing added/modified/deleted sets.
   * This is much more reliable than our previous hand-written index parser
   * and avoids the OOM issues of isomorphic-git's statusMatrix.
   */
  fun gitStatus(gitRootDir: String): String {
    val repo = openRepo(gitRootDir)
    try {
      val git = Git(repo)
      val status = git.status().call()

      val changes = JSONArray()

      // Untracked files = "add"
      for (path in status.untracked) {
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("type", "add")
        changes.put(obj)
      }

      // Modified files = "modify"
      for (path in status.modified) {
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("type", "modify")
        changes.put(obj)
      }

      // Missing files (in index but not on disk) = "delete"
      for (path in status.missing) {
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("type", "delete")
        changes.put(obj)
      }

      // Also include staged changes that differ from HEAD
      for (path in status.added) {
        // Only if not already reported
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("type", "add")
        changes.put(obj)
      }
      for (path in status.changed) {
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("type", "modify")
        changes.put(obj)
      }
      for (path in status.removed) {
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("type", "delete")
        changes.put(obj)
      }

      android.util.Log.i("GitStatus", "JGit status: ${changes.length()} changes " +
        "(untracked=${status.untracked.size}, modified=${status.modified.size}, " +
        "missing=${status.missing.size}, added=${status.added.size}, " +
        "changed=${status.changed.size}, removed=${status.removed.size})")
      return changes.toString()
    } finally {
      repo.close()
    }
  }

  /**
   * Debug information about git repository state using JGit.
   */
  fun gitStatusDebug(gitRootDir: String): String {
    val root = File(gitRootDir)
    val gitDir = File(root, ".git")

    val result = JSONObject()
    result.put("rootExists", root.exists())
    result.put("rootIsDir", root.isDirectory)
    result.put("gitDirExists", gitDir.exists())
    result.put("rootPath", root.absolutePath)

    if (!gitDir.exists()) return result.toString()

    try {
      val repo = openRepo(gitRootDir)
      try {
        val git = Git(repo)

        // HEAD info
        val head = repo.resolve(Constants.HEAD)
        result.put("headCommit", head?.name ?: "null")

        val branch = repo.branch
        result.put("currentBranch", branch ?: "detached")

        // Index entry count
        val dirCache = repo.readDirCache()
        result.put("indexEntryCount", dirCache.entryCount)

        // Status summary
        val status = git.status().call()
        result.put("untrackedCount", status.untracked.size)
        result.put("modifiedCount", status.modified.size)
        result.put("missingCount", status.missing.size)
        result.put("addedCount", status.added.size)
        result.put("changedCount", status.changed.size)
        result.put("removedCount", status.removed.size)

        // Remote info
        val config = repo.config
        val remoteNames = config.getSubsections("remote")
        val remotes = JSONArray()
        for (name in remoteNames) {
          val url = config.getString("remote", name, "url")
          val obj = JSONObject()
          obj.put("name", name)
          obj.put("url", url ?: "")
          remotes.put(obj)
        }
        result.put("remotes", remotes)
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("error", e.message)
    }

    return result.toString()
  }

  // ─── Build git index from HEAD tree (JGit) ──────────────────────

  /**
   * Rebuild .git/index from HEAD using JGit's DirCacheCheckout.
   *
   * This replaces the hand-written index builder with JGit's built-in
   * mechanism. It reads the HEAD tree and creates a proper index file,
   * stat'ing all files on disk for the cache entries.
   */
  fun buildGitIndex(gitRootDir: String): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val git = Git(repo)

        // Resolve HEAD commit and its tree
        val headId = repo.resolve(Constants.HEAD)
          ?: throw Exception("Cannot resolve HEAD")
        val walk = RevWalk(repo)
        val commit = walk.parseCommit(headId)
        val tree = commit.tree
        walk.close()

        // Use reset --mixed to rebuild the index from HEAD tree
        // This reads HEAD tree into the index without changing the working tree
        git.reset()
          .setMode(ResetCommand.ResetType.MIXED)
          .setRef(headId.name)
          .call()

        // Read back the index to report stats
        val dirCache = repo.readDirCache()
        val entryCount = dirCache.entryCount

        android.util.Log.i("BuildGitIndex", "JGit rebuilt index: $entryCount entries from HEAD ${headId.name}")
        result.put("ok", true)
        result.put("entries", entryCount)
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      android.util.Log.e("BuildGitIndex", "Failed: ${e.message}", e)
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
    }
    return result.toString()
  }

  // ─── Git push (raw HTTP - see above) ──────────────────────────────
  // gitPush() is defined earlier in this file using raw HTTP transport.

  // ─── Git fetch (raw HTTP - see above) ───────────────────────────
  // gitFetch() is defined earlier in this file using raw HTTP transport.

  // ─── Git checkout changed files (JGit) ──────────────────────────

  /**
   * Compare two commits and checkout only changed/new files to the working tree.
   * Uses JGit's DiffFormatter to find changed files, then reads their content
   * from the new tree and writes them to disk.
   */
  fun gitCheckoutChangedFiles(
    gitRootDir: String,
    oldOid: String,
    newOid: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val root = File(gitRootDir)
        val reader = repo.newObjectReader()
        try {
          val walk = RevWalk(repo)
          val oldCommit = walk.parseCommit(ObjectId.fromString(oldOid))
          val newCommit = walk.parseCommit(ObjectId.fromString(newOid))
          val oldTree = oldCommit.tree
          val newTree = newCommit.tree

          // Diff the two trees
          val diffFormatter = DiffFormatter(ByteArrayOutputStream())
          diffFormatter.setRepository(repo)
          val diffs = diffFormatter.scan(oldTree, newTree)

          val updatedFiles = JSONArray()
          var checkedOutCount = 0

          for (diff in diffs) {
            when (diff.changeType) {
              DiffEntry.ChangeType.ADD,
              DiffEntry.ChangeType.MODIFY,
              DiffEntry.ChangeType.COPY -> {
                val treeWalk = TreeWalk.forPath(repo, diff.newPath, newTree)
                if (treeWalk != null) {
                  val objectId = treeWalk.getObjectId(0)
                  val loader = reader.open(objectId)
                  val targetFile = File(root, diff.newPath)
                  targetFile.parentFile?.mkdirs()
                  targetFile.outputStream().use { out -> loader.copyTo(out) }
                  updatedFiles.put(diff.newPath)
                  checkedOutCount++
                }
              }
              DiffEntry.ChangeType.DELETE -> {
                val targetFile = File(root, diff.oldPath)
                if (targetFile.exists()) targetFile.delete()
                updatedFiles.put("-${diff.oldPath}")
                checkedOutCount++
              }
              DiffEntry.ChangeType.RENAME -> {
                File(root, diff.oldPath).let { if (it.exists()) it.delete() }
                val treeWalk = TreeWalk.forPath(repo, diff.newPath, newTree)
                if (treeWalk != null) {
                  val objectId = treeWalk.getObjectId(0)
                  val loader = reader.open(objectId)
                  val targetFile = File(root, diff.newPath)
                  targetFile.parentFile?.mkdirs()
                  targetFile.outputStream().use { out -> loader.copyTo(out) }
                }
                updatedFiles.put("${diff.oldPath}->${diff.newPath}")
                checkedOutCount++
              }
            }
          }

          diffFormatter.close()
          walk.close()

          result.put("ok", true)
          result.put("count", checkedOutCount)
          result.put("files", updatedFiles)
          android.util.Log.i("GitCheckout", "Checked out $checkedOutCount changed files between ${oldOid.take(8)}..${newOid.take(8)}")
        } finally {
          reader.close()
        }
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown checkout error")
      android.util.Log.e("GitCheckout", "Checkout changed files failed: ${e.message}", e)
    }
    return result.toString()
  }

  // ─── Git add + commit (JGit) ────────────────────────────────────

  /**
   * Stage all changes and commit using JGit.
   * This replaces isomorphic-git's statusMatrix + add/remove loop which
   * OOMs on large repos.
   *
   * @param gitRootDir  The git working directory
   * @param message     Commit message
   * @param authorName  Author name
   * @param authorEmail Author email
   * @return JSON: {"ok":true,"commitId":"abc123"} or {"ok":false,"error":"..."}
   */
  fun gitAddAndCommit(
    gitRootDir: String,
    message: String,
    authorName: String,
    authorEmail: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val git = Git(repo)

        // Stage all changes: add new/modified, remove deleted
        val status = git.status().call()

        if (status.untracked.isEmpty() && status.modified.isEmpty() &&
          status.missing.isEmpty() && status.added.isEmpty() &&
          status.changed.isEmpty() && status.removed.isEmpty()) {
          result.put("ok", true)
          result.put("commitId", "")
          result.put("message", "nothing to commit")
          return result.toString()
        }

        // git add . (stages new and modified files)
        git.add().addFilepattern(".").call()
        // git add -u (stages deletions)
        git.add().setUpdate(true).addFilepattern(".").call()

        // Commit
        val commitResult = git.commit()
          .setMessage(message)
          .setAuthor(authorName, authorEmail)
          .setCommitter(authorName, authorEmail)
          .call()

        result.put("ok", true)
        result.put("commitId", commitResult.id.name)
        android.util.Log.i("GitCommit", "Committed: ${commitResult.id.name} - $message")
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown commit error")
      android.util.Log.e("GitCommit", "Commit failed: ${e.message}", e)
    }
    return result.toString()
  }

  // ─── Git reset (JGit) ───────────────────────────────────────────

  /**
   * Hard reset the current branch to a specific ref (e.g. origin/main).
   * This is the JGit equivalent of `git reset --hard origin/main`.
   *
   * For large repos this is safer than isomorphic-git's checkout because
   * JGit manages memory more efficiently.
   */
  fun gitReset(
    gitRootDir: String,
    ref: String,
    mode: String  // "hard", "mixed", or "soft"
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val git = Git(repo)
        val resetMode = when (mode.lowercase()) {
          "hard" -> ResetCommand.ResetType.HARD
          "mixed" -> ResetCommand.ResetType.MIXED
          "soft" -> ResetCommand.ResetType.SOFT
          else -> ResetCommand.ResetType.HARD
        }

        val resetResult = git.reset()
          .setMode(resetMode)
          .setRef(ref)
          .call()

        result.put("ok", true)
        result.put("ref", resetResult?.objectId?.name ?: ref)
        android.util.Log.i("GitReset", "Reset ($mode) to $ref -> ${resetResult?.objectId?.name}")
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown reset error")
      android.util.Log.e("GitReset", "Reset failed: ${e.message}", e)
    }
    return result.toString()
  }

  // ─── Git clone (JGit) ───────────────────────────────────────────

  /**
   * Clone a remote repository into the specified directory.
   * Uses JGit's CloneCommand for efficient native pack handling.
   */
  fun gitClone(
    url: String,
    directory: String,
    branch: String?,
    depth: Int,
    singleBranch: Boolean,
    noTags: Boolean,
    headers: String?
  ): String {
    val result = JSONObject()
    try {
      val targetDir = File(directory)
      val cloneCommand = Git.cloneRepository()
        .setURI(url)
        .setDirectory(targetDir)
        .setCloneAllBranches(!singleBranch)

      if (singleBranch && branch != null) {
        cloneCommand.setBranch("refs/heads/$branch")
        cloneCommand.setBranchesToClone(listOf("refs/heads/$branch"))
      }

      if (depth > 0) {
        cloneCommand.setDepth(depth)
      }

      if (noTags) {
        cloneCommand.setNoTags()
      }

      // Apply headers for clone (JGit transport is used here since clone
      // is typically done via archive download; this is just a fallback)
      if (headers != null) {
        try {
          val headerObj = JSONObject(headers)
          val headerMap = mutableMapOf<String, String>()
          for (key in headerObj.keys()) headerMap[key] = headerObj.getString(key)

          val authHeader = headerMap["Authorization"] ?: headerMap["authorization"]
          if (authHeader != null && authHeader.startsWith("Basic ", ignoreCase = true)) {
            try {
              val decoded = String(Base64.decode(authHeader.substring(6), Base64.DEFAULT))
              val colonIndex = decoded.indexOf(':')
              val username = if (colonIndex >= 0) decoded.substring(0, colonIndex) else ""
              val password = if (colonIndex >= 0) decoded.substring(colonIndex + 1) else decoded
              cloneCommand.setCredentialsProvider(UsernamePasswordCredentialsProvider(username, password))
            } catch (e: Exception) {
              android.util.Log.w("GitHelper", "Failed to decode Basic auth: ${e.message}")
            }
          }

          cloneCommand.setTransportConfigCallback { transport ->
            if (transport is TransportHttp) {
              transport.setAdditionalHeaders(headerMap)
            }
          }
        } catch (e: Exception) {
          android.util.Log.w("GitHelper", "Failed to parse clone headers: ${e.message}")
        }
      }

      val git = cloneCommand.call()
      val headId = git.repository.resolve(Constants.HEAD)

      result.put("ok", true)
      result.put("head", headId?.name ?: "")
      android.util.Log.i("GitClone", "Cloned $url to $directory, HEAD=${headId?.name?.take(8)}")
      git.repository.close()
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown clone error")
      android.util.Log.e("GitClone", "Clone failed: ${e.message}", e)
    }
    return result.toString()
  }

  // ─── Git log (JGit) ─────────────────────────────────────────────

  /**
   * Get commit history using JGit's LogCommand.
   * Returns JSON array of commit objects.
   */
  fun gitLog(
    gitRootDir: String,
    ref: String?,
    maxCount: Int
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val git = Git(repo)
        val logCommand = git.log()

        if (ref != null && ref.isNotEmpty()) {
          val resolved = repo.resolve(ref)
          if (resolved != null) {
            logCommand.add(resolved)
          } else {
            result.put("ok", true)
            result.put("commits", JSONArray())
            return result.toString()
          }
        } else {
          logCommand.all()
        }

        logCommand.setMaxCount(maxCount)

        val commits = JSONArray()
        for (commit in logCommand.call()) {
          val obj = JSONObject()
          obj.put("oid", commit.id.name)
          obj.put("message", commit.fullMessage)
          obj.put("authorName", commit.authorIdent.name)
          obj.put("authorEmail", commit.authorIdent.emailAddress)
          obj.put("timestamp", commit.authorIdent.`when`.time)
          val parents = JSONArray()
          for (parent in commit.parents) {
            parents.put(parent.id.name)
          }
          obj.put("parentOids", parents)
          commits.put(obj)
        }

        result.put("ok", true)
        result.put("commits", commits)
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown log error")
    }
    return result.toString()
  }

  // ─── Git resolve ref (JGit) ─────────────────────────────────────

  /**
   * Resolve a git reference to its SHA-1 hash.
   */
  fun gitResolveRef(
    gitRootDir: String,
    ref: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val objectId = repo.resolve(ref)
        if (objectId != null) {
          result.put("ok", true)
          result.put("oid", objectId.name)
        } else {
          result.put("ok", false)
          result.put("error", "Cannot resolve ref: $ref")
        }
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
    }
    return result.toString()
  }

  // ─── Git current branch (JGit) ──────────────────────────────────

  /**
   * Get the current branch name.
   * Returns the branch name or "HEAD" if detached.
   */
  fun gitCurrentBranch(gitRootDir: String): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val branch = repo.branch
        val fullBranch = repo.fullBranch
        val isDetached = fullBranch == null || !fullBranch.startsWith("refs/heads/")

        result.put("ok", true)
        result.put("branch", branch ?: "")
        result.put("isDetached", isDetached)

        // Also list all branches
        val git = Git(repo)
        val localBranches = JSONArray()
        for (ref in git.branchList().call()) {
          localBranches.put(ref.name.removePrefix("refs/heads/"))
        }
        result.put("localBranches", localBranches)

        val remoteBranches = JSONArray()
        for (ref in git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call()) {
          remoteBranches.put(ref.name.removePrefix("refs/remotes/"))
        }
        result.put("remoteBranches", remoteBranches)
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
    }
    return result.toString()
  }

  // ─── Git init (JGit) ────────────────────────────────────────────

  /**
   * Initialize a new git repository.
   */
  fun gitInit(
    directory: String,
    defaultBranch: String
  ): String {
    val result = JSONObject()
    try {
      val targetDir = File(directory)
      targetDir.mkdirs()
      val git = Git.init()
        .setDirectory(targetDir)
        .setInitialBranch(defaultBranch)
        .call()

      result.put("ok", true)
      android.util.Log.i("GitInit", "Initialized repo at $directory with branch $defaultBranch")
      git.repository.close()
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown init error")
    }
    return result.toString()
  }

  // ─── Git config (JGit) ──────────────────────────────────────────

  /**
   * Set a git config value. Primarily for remote.origin.url.
   */
  fun gitSetConfig(
    gitRootDir: String,
    section: String,
    subsection: String?,
    name: String,
    value: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val config = repo.config
        config.setString(section, subsection, name, value)
        config.save()
        result.put("ok", true)
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown config error")
    }
    return result.toString()
  }

  /**
   * Add a remote to the repository.
   */
  fun gitAddRemote(
    gitRootDir: String,
    remoteName: String,
    url: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val config = repo.config
        config.setString("remote", remoteName, "url", url)
        config.setString("remote", remoteName, "fetch", "+refs/heads/*:refs/remotes/$remoteName/*")
        config.save()
        result.put("ok", true)
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
    }
    return result.toString()
  }

  // ─── Git read blob (JGit) ───────────────────────────────────────

  /**
   * Read a file (blob) at a specific commit reference.
   * Returns base64-encoded content for binary, or utf8 text.
   */
  fun gitReadBlob(
    gitRootDir: String,
    ref: String,
    filepath: String,
    asBase64: Boolean
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val commitId = repo.resolve(ref)
          ?: throw Exception("Cannot resolve ref: $ref")
        val walk = RevWalk(repo)
        val commit = walk.parseCommit(commitId)
        val tree = commit.tree

        val treeWalk = TreeWalk.forPath(repo, filepath, tree)
        if (treeWalk == null) {
          result.put("ok", false)
          result.put("error", "File not found in tree: $filepath at $ref")
          walk.close()
          return result.toString()
        }

        val objectId = treeWalk.getObjectId(0)
        val loader = repo.newObjectReader().open(objectId)
        val bytes = loader.bytes

        result.put("ok", true)
        if (asBase64) {
          result.put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
          result.put("encoding", "base64")
        } else {
          result.put("content", String(bytes, Charsets.UTF_8))
          result.put("encoding", "utf8")
        }
        result.put("size", bytes.size)

        walk.close()
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
    }
    return result.toString()
  }

  // ─── Git diff trees (JGit) ──────────────────────────────────────

  /**
   * Diff two commits and return the list of changed files.
   * This is the native equivalent of diffCommitTrees in JS.
   */
  fun gitDiffTrees(
    gitRootDir: String,
    oldRef: String,
    newRef: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val walk = RevWalk(repo)
        val oldCommit = walk.parseCommit(repo.resolve(oldRef)
          ?: throw Exception("Cannot resolve ref: $oldRef"))
        val newCommit = walk.parseCommit(repo.resolve(newRef)
          ?: throw Exception("Cannot resolve ref: $newRef"))

        val diffFormatter = DiffFormatter(ByteArrayOutputStream())
        diffFormatter.setRepository(repo)
        val diffs = diffFormatter.scan(oldCommit.tree, newCommit.tree)

        val files = JSONArray()
        for (diff in diffs) {
          val obj = JSONObject()
          when (diff.changeType) {
            DiffEntry.ChangeType.ADD -> {
              obj.put("path", diff.newPath)
              obj.put("type", "add")
            }
            DiffEntry.ChangeType.DELETE -> {
              obj.put("path", diff.oldPath)
              obj.put("type", "delete")
            }
            DiffEntry.ChangeType.MODIFY -> {
              obj.put("path", diff.newPath)
              obj.put("type", "modify")
            }
            DiffEntry.ChangeType.RENAME -> {
              obj.put("path", diff.newPath)
              obj.put("oldPath", diff.oldPath)
              obj.put("type", "modify")
            }
            DiffEntry.ChangeType.COPY -> {
              obj.put("path", diff.newPath)
              obj.put("type", "add")
            }
          }
          files.put(obj)
        }

        diffFormatter.close()
        walk.close()

        result.put("ok", true)
        result.put("files", files)
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
    }
    return result.toString()
  }

  // ─── Git discard file changes (JGit) ────────────────────────────

  /**
   * Discard changes to a specific file by checking out the HEAD version.
   * If the file doesn't exist in HEAD (new file), delete it from working tree.
   */
  fun gitDiscardFileChanges(
    gitRootDir: String,
    filepath: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        val git = Git(repo)
        val headId = repo.resolve(Constants.HEAD)

        if (headId != null) {
          // Check if file exists in HEAD
          val walk = RevWalk(repo)
          val commit = walk.parseCommit(headId)
          val treeWalk = TreeWalk.forPath(repo, filepath, commit.tree)
          walk.close()

          if (treeWalk != null) {
            // File exists in HEAD — checkout it
            git.checkout()
              .addPath(filepath)
              .call()
            result.put("ok", true)
            result.put("action", "checkout")
          } else {
            // File doesn't exist in HEAD — it's untracked, delete it
            val targetFile = File(File(gitRootDir), filepath)
            if (targetFile.exists()) {
              targetFile.delete()
            }
            result.put("ok", true)
            result.put("action", "delete")
          }
        } else {
          result.put("ok", false)
          result.put("error", "Cannot resolve HEAD")
        }
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown error")
    }
    return result.toString()
  }
}
