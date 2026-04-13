package expo.modules.externalstorage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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

  /** Apply custom HTTP headers and credentials to a JGit transport command. */
  private fun applyHeaders(
    command: org.eclipse.jgit.api.TransportCommand<*, *>,
    headers: String?
  ) {
    // Parse custom headers JSON
    val headerMap = mutableMapOf<String, String>()
    if (headers != null) {
      try {
        val headerObj = JSONObject(headers)
        for (key in headerObj.keys()) {
          headerMap[key] = headerObj.getString(key)
        }
      } catch (e: Exception) {
        android.util.Log.w("GitHelper", "Failed to parse headers: ${e.message}")
      }
    }

    // Extract Basic Auth from Authorization header and set as CredentialsProvider.
    val authHeader = headerMap["Authorization"] ?: headerMap["authorization"]
    if (authHeader != null && authHeader.startsWith("Basic ", ignoreCase = true)) {
      try {
        val decoded = String(Base64.decode(authHeader.substring(6), Base64.DEFAULT))
        val colonIndex = decoded.indexOf(':')
        val username = if (colonIndex >= 0) decoded.substring(0, colonIndex) else ""
        val password = if (colonIndex >= 0) decoded.substring(colonIndex + 1) else decoded
        command.setCredentialsProvider(UsernamePasswordCredentialsProvider(username, password))
      } catch (e: Exception) {
        android.util.Log.w("GitHelper", "Failed to decode Basic auth: ${e.message}")
      }
    }

    command.setTransportConfigCallback { transport ->
      if (transport is TransportHttp) {
        transport.setAdditionalHeaders(headerMap)
      }
    }
  }

  /**
   * Ensure repository git config forces protocol.version=0 and
   * constrains pack memory for Android's limited heap.
   *
   * TidGi Desktop's git server uses `git --stateless-rpc` which only speaks V0/V1.
   * JGit defaults to V2 negotiation, which causes the error:
   *   "Starting read stage without written request data pending is not supported"
   * because the server doesn't handle V2 capability advertisements.
   *
   * Pack memory limits prevent OOM on large repos (Android heap is ~268MB).
   * Default JGit settings: deltaCacheSize=50MB, windowMemory=unlimited,
   * bigFileThreshold=50MB — far too much for a mobile device.
   */
  private fun ensureProtocolV0(repo: Repository) {
    val config = repo.config
    var dirty = false
    val current = config.getString("protocol", null, "version")
    if (current != "0") {
      config.setString("protocol", null, "version", "0")
      dirty = true
    }
    // Limit pack memory to avoid OOM on push
    // pack.windowMemory: max bytes for delta search window (per thread), default unlimited
    if (config.getLong("pack", "windowmemory", 0) == 0L) {
      config.setLong("pack", null, "windowmemory", 10L * 1024 * 1024) // 10MB
      dirty = true
    }
    // pack.deltaCacheSize: total delta cache, default 50MB
    if (config.getLong("pack", "deltacachesize", 50L * 1024 * 1024) >= 50L * 1024 * 1024) {
      config.setLong("pack", null, "deltacachesize", 5L * 1024 * 1024) // 5MB
      dirty = true
    }
    // pack.threads: limit to 1 to reduce memory pressure
    if (config.getInt("pack", "threads", 0) == 0) {
      config.setInt("pack", null, "threads", 1)
      dirty = true
    }
    // pack.window: reduce from 10 to 5
    if (config.getInt("pack", "window", 10) > 5) {
      config.setInt("pack", null, "window", 5)
      dirty = true
    }
    if (dirty) {
      config.save()
    }
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

  // ─── Git push (JGit) ────────────────────────────────────────────

  /**
   * Push local branch to remote using JGit (efficient native pack building).
   * JGit handles pack construction in Java with bounded memory usage,
   * avoiding the OOM that isomorphic-git causes on large repos.
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
        ensureProtocolV0(repo)
        val git = Git(repo)
        val pushCommand = git.push()
          .setRemote(remoteName)
          .setRefSpecs(RefSpec("refs/heads/$localBranch:$remoteBranch"))
          .setForce(force)

        applyHeaders(pushCommand, headers)

        val pushResults = pushCommand.call()

        val resultsArray = JSONArray()
        for (pushResult in pushResults) {
          for (update in pushResult.remoteUpdates) {
            val updateObj = JSONObject()
            updateObj.put("remoteName", update.remoteName)
            updateObj.put("status", update.status.name)
            updateObj.put("message", update.message ?: "")
            resultsArray.put(updateObj)
          }
        }

        result.put("ok", true)
        result.put("updates", resultsArray)
        android.util.Log.i("GitPush", "Push completed: ${resultsArray.length()} updates")
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

  // ─── Git bundle creation (for push via HTTP POST) ───────────────

  /**
   * Create a git bundle containing commits on localBranch that are ahead of
   * remoteName/remoteBranch. Returns a base64-encoded bundle string, or
   * a JSON error if there's nothing to bundle.
   *
   * This avoids JGit's broken SmartHttpPushConnection (which throws
   * "Starting read stage without written request data pending is not supported"
   * due to MultiRequestService not marking finalRequest=true for push).
   *
   * The bundle is sent by the JS layer via HTTP POST to the desktop's
   * /receive-bundle endpoint, which runs `git fetch <bundle> master:mobile-incoming`.
   */
  fun gitCreateBundle(
    gitRootDir: String,
    remoteName: String,
    localBranch: String,
    remoteBranch: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        ensureProtocolV0(repo)
        val localRef = repo.resolve("refs/heads/$localBranch")
          ?: throw Exception("Local branch $localBranch not found")
        val remoteRef = repo.resolve("refs/remotes/$remoteName/$remoteBranch")

        val revWalk = RevWalk(repo)
        try {
          val localCommit = revWalk.parseCommit(localRef)

          val bundleWriter = org.eclipse.jgit.transport.BundleWriter(repo)

          // Include the local branch tip
          bundleWriter.include("refs/heads/$localBranch", localRef)

          // If we have a remote tracking ref, mark it as assumed (prerequisite).
          // The receiving end must have this commit.
          if (remoteRef != null) {
            val remoteCommit = revWalk.parseCommit(remoteRef)
            bundleWriter.assume(remoteCommit)
          }

          // Configure pack for low memory (Android)
          val packConfig = org.eclipse.jgit.storage.pack.PackConfig(repo)
          bundleWriter.setPackConfig(packConfig)

          // Write bundle to memory
          val baos = ByteArrayOutputStream()
          bundleWriter.writeBundle(org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE, baos)

          val bundleBytes = baos.toByteArray()
          val base64Bundle = Base64.encodeToString(bundleBytes, Base64.NO_WRAP)

          result.put("ok", true)
          result.put("bundle", base64Bundle)
          result.put("bundleSize", bundleBytes.size)
          android.util.Log.i("GitBundle", "Bundle created: ${bundleBytes.size} bytes, local=$localBranch remote=$remoteName/$remoteBranch")
        } finally {
          revWalk.close()
        }
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown bundle error")
      android.util.Log.e("GitBundle", "Bundle creation failed: ${e.message}", e)
    }
    return result.toString()
  }

  // ─── Git fetch (JGit) ───────────────────────────────────────────

  /**
   * Fetch from remote using JGit (efficient native pack handling).
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
        ensureProtocolV0(repo)
        val git = Git(repo)
        val fetchCommand = git.fetch()
          .setRemote(remoteName)
          .setRefSpecs(RefSpec("+refs/heads/$branch:refs/remotes/$remoteName/$branch"))

        applyHeaders(fetchCommand, headers)

        val fetchResult = fetchCommand.call()

        val updatesArray = JSONArray()
        for (update in fetchResult.trackingRefUpdates) {
          val updateObj = JSONObject()
          updateObj.put("ref", update.localName)
          updateObj.put("oldObjectId", update.oldObjectId?.name ?: "")
          updateObj.put("newObjectId", update.newObjectId?.name ?: "")
          updatesArray.put(updateObj)
        }

        result.put("ok", true)
        result.put("updates", updatesArray)
        android.util.Log.i("GitFetch", "Fetch completed: ${updatesArray.length()} updates")
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

  // ─── Git fetch from local bundle file (JGit) ───────────────────

  /**
   * Fetch commits from a local git bundle file into origin/<branch>.
   * This avoids JGit's broken HTTP multi-request transport.
   *
   * The bundle file is expected to be at `<gitRootDir>/.git/<bundleFileName>`.
   * After fetching, the bundle file is deleted.
   *
   * @param gitRootDir  path to the git working tree
   * @param bundleFileName  name of the bundle file inside .git/
   * @param branch  local branch name (e.g. "master")
   * @return JSON: {"ok":true,"updates":[...]} or {"ok":false,"error":"..."}
   */
  fun gitFetchFromBundle(
    gitRootDir: String,
    bundleFileName: String,
    branch: String
  ): String {
    val result = JSONObject()
    try {
      val repo = openRepo(gitRootDir)
      try {
        ensureProtocolV0(repo)
        val bundlePath = java.io.File(repo.directory, bundleFileName)
        if (!bundlePath.exists()) {
          throw Exception("Bundle file not found: ${bundlePath.absolutePath}")
        }

        val git = Git(repo)
        // JGit's FetchCommand supports local file URIs including bundle files
        val fetchCommand = git.fetch()
          .setRemote(bundlePath.absolutePath)
          .setRefSpecs(RefSpec("+refs/heads/$branch:refs/remotes/origin/$branch"))

        val fetchResult = fetchCommand.call()

        val updatesArray = JSONArray()
        for (update in fetchResult.trackingRefUpdates) {
          val updateObj = JSONObject()
          updateObj.put("ref", update.localName)
          updateObj.put("oldObjectId", update.oldObjectId?.name ?: "")
          updateObj.put("newObjectId", update.newObjectId?.name ?: "")
          updatesArray.put(updateObj)
        }

        result.put("ok", true)
        result.put("updates", updatesArray)
        android.util.Log.i("GitFetchBundle", "Bundle fetch completed: ${updatesArray.length()} updates")

        // Clean up bundle file
        try { bundlePath.delete() } catch (_: Exception) { /* ignore */ }
      } finally {
        repo.close()
      }
    } catch (e: Exception) {
      result.put("ok", false)
      result.put("error", e.message ?: "Unknown bundle fetch error")
      android.util.Log.e("GitFetchBundle", "Bundle fetch failed: ${e.message}", e)
    }
    return result.toString()
  }

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

      applyHeaders(cloneCommand, headers)

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
