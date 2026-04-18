package expo.modules.externalstorage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.json.JSONArray
import org.json.JSONObject

internal object GitHistory {

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
      val repo = GitRepository.openRepo(gitRootDir)
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

  /**
   * Resolve a git reference to its SHA-1 hash.
   */
  fun gitResolveRef(
    gitRootDir: String,
    ref: String
  ): String {
    val result = JSONObject()
    try {
      val repo = GitRepository.openRepo(gitRootDir)
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

  /**
   * Get the current branch name.
   * Returns the branch name or "HEAD" if detached.
   */
  fun gitCurrentBranch(gitRootDir: String): String {
    val result = JSONObject()
    try {
      val repo = GitRepository.openRepo(gitRootDir)
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
        for (ref in git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()) {
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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        val commitId = repo.resolve(ref)
          ?: throw Exception("Cannot resolve ref: $ref")
        val walk = RevWalk(repo)
        val commit = walk.parseCommit(commitId)
        val tree = commit.tree

        val treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, filepath, tree)
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
          result.put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
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
}
