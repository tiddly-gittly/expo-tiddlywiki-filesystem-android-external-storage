package expo.modules.externalstorage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.json.JSONObject
import java.io.File

internal object GitRepository {

  /** Open a JGit Repository from a working directory path. */
  fun openRepo(gitRootDir: String): Repository {
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
  fun ensureProtocolV0(repo: Repository) {
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
}
