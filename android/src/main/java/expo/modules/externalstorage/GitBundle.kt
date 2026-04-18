package expo.modules.externalstorage

import android.util.Base64
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.pack.PackConfig
import org.eclipse.jgit.transport.BundleWriter
import org.eclipse.jgit.transport.RefSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

internal object GitBundle {

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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        GitRepository.ensureProtocolV0(repo)
        val localRef = repo.resolve("refs/heads/$localBranch")
          ?: throw Exception("Local branch $localBranch not found")
        val remoteRef = repo.resolve("refs/remotes/$remoteName/$remoteBranch")

        val revWalk = RevWalk(repo)
        try {
          val localCommit = revWalk.parseCommit(localRef)

          val bundleWriter = BundleWriter(repo)

          // Include the local branch tip
          bundleWriter.include("refs/heads/$localBranch", localRef)

          // If we have a remote tracking ref, mark it as assumed (prerequisite).
          // The receiving end must have this commit.
          if (remoteRef != null) {
            val remoteCommit = revWalk.parseCommit(remoteRef)
            bundleWriter.assume(remoteCommit)
          }

          // Configure pack for low memory (Android)
          val packConfig = PackConfig(repo)
          bundleWriter.setPackConfig(packConfig)

          // Write bundle to memory
          val baos = ByteArrayOutputStream()
          bundleWriter.writeBundle(NullProgressMonitor.INSTANCE, baos)

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
      val repo = GitRepository.openRepo(gitRootDir)
      try {
        GitRepository.ensureProtocolV0(repo)
        val bundlePath = File(repo.directory, bundleFileName)
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
}
