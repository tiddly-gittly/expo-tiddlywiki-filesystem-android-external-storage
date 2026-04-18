package expo.modules.externalstorage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object GitLocal {

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
      val repo = GitRepository.openRepo(gitRootDir)
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
      val repo = GitRepository.openRepo(gitRootDir)
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
      val repo = GitRepository.openRepo(gitRootDir)
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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        val root = File(gitRootDir)
        val reader = repo.newObjectReader()
        try {
          val walk = RevWalk(repo)
          val oldCommit = walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(oldOid))
          val newCommit = walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(newOid))
          val oldTree = oldCommit.tree
          val newTree = newCommit.tree

          // Diff the two trees
          val diffFormatter = org.eclipse.jgit.diff.DiffFormatter(java.io.ByteArrayOutputStream())
          diffFormatter.setRepository(repo)
          val diffs = diffFormatter.scan(oldTree, newTree)

          val updatedFiles = JSONArray()
          var checkedOutCount = 0

          for (diff in diffs) {
            when (diff.changeType) {
              org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD,
              org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY,
              org.eclipse.jgit.diff.DiffEntry.ChangeType.COPY -> {
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
              org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> {
                val targetFile = File(root, diff.oldPath)
                if (targetFile.exists()) targetFile.delete()
                updatedFiles.put("-${diff.oldPath}")
                checkedOutCount++
              }
              org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME -> {
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
}
