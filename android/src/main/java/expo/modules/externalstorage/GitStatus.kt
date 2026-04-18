package expo.modules.externalstorage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

internal object GitStatus {

  /**
   * Compute git status using JGit's Status API.
   *
   * JGit internally uses the DirCache (git index) and compares it against
   * the working tree and HEAD tree, producing added/modified/deleted sets.
   * This is much more reliable than our previous hand-written index parser
   * and avoids the OOM issues of isomorphic-git's statusMatrix.
   */
  fun gitStatus(gitRootDir: String): String {
    val repo = GitRepository.openRepo(gitRootDir)
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
      val repo = GitRepository.openRepo(gitRootDir)
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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        val walk = org.eclipse.jgit.revwalk.RevWalk(repo)
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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        val git = Git(repo)
        val headId = repo.resolve(Constants.HEAD)

        if (headId != null) {
          // Check if file exists in HEAD
          val walk = org.eclipse.jgit.revwalk.RevWalk(repo)
          val commit = walk.parseCommit(headId)
          val treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, filepath, commit.tree)
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
