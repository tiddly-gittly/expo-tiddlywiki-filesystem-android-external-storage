package expo.modules.externalstorage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.json.JSONArray
import org.json.JSONObject

internal object GitNetwork {

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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        GitRepository.ensureProtocolV0(repo)
        val git = Git(repo)
        val pushCommand = git.push()
          .setRemote(remoteName)
          .setRefSpecs(RefSpec("refs/heads/$localBranch:$remoteBranch"))
          .setForce(force)

        GitTransport.applyHeaders(pushCommand, headers)

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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        GitRepository.ensureProtocolV0(repo)
        val git = Git(repo)
        val fetchCommand = git.fetch()
          .setRemote(remoteName)
          .setRefSpecs(RefSpec("+refs/heads/$branch:refs/remotes/$remoteName/$branch"))

        GitTransport.applyHeaders(fetchCommand, headers)

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
      val targetDir = java.io.File(directory)
      val cloneCommand = Git.cloneRepository()
        .setURI(url)
        .setDirectory(targetDir)
        .setCloneAllBranches(!singleBranch)

      if (singleBranch && branch != null) {
        cloneCommand.setBranch("refs/heads/$branch")
        cloneCommand.setBranchesToClone(listOf("refs/heads/$branch"))
      }

      if (noTags) {
        cloneCommand.setNoTags()
      }

      GitTransport.applyHeaders(cloneCommand, headers)

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
}
