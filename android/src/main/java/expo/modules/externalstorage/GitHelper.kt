package expo.modules.externalstorage

/**
 * Facade for all Git operations.
 * Delegates to specialized modules: GitRepository, GitTransport, GitNetwork, etc.
 * Maintains backward compatibility with ExternalStorageModule.
 */
internal object GitHelper {

  // ─── Status & Diff Operations (GitStatus) ──────────────────────

  fun gitStatus(gitRootDir: String): String = GitStatus.gitStatus(gitRootDir)

  fun gitStatusDebug(gitRootDir: String): String = GitStatus.gitStatusDebug(gitRootDir)

  fun gitDiffTrees(gitRootDir: String, oldRef: String, newRef: String): String =
    GitStatus.gitDiffTrees(gitRootDir, oldRef, newRef)

  fun gitDiscardFileChanges(gitRootDir: String, filepath: String): String =
    GitStatus.gitDiscardFileChanges(gitRootDir, filepath)

  // ─── Local Operations (GitLocal) ────────────────────────────────

  fun buildGitIndex(gitRootDir: String): String = GitLocal.buildGitIndex(gitRootDir)

  fun gitAddAndCommit(gitRootDir: String, message: String, authorName: String, authorEmail: String): String =
    GitLocal.gitAddAndCommit(gitRootDir, message, authorName, authorEmail)

  fun gitReset(gitRootDir: String, ref: String, mode: String): String =
    GitLocal.gitReset(gitRootDir, ref, mode)

  fun gitCheckoutChangedFiles(gitRootDir: String, oldOid: String, newOid: String): String =
    GitLocal.gitCheckoutChangedFiles(gitRootDir, oldOid, newOid)

  // ─── Network Operations (GitNetwork) ────────────────────────────

  fun gitPush(gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String, force: Boolean, headers: String?): String =
    GitNetwork.gitPush(gitRootDir, remoteName, localBranch, remoteBranch, force, headers)

  fun gitFetch(gitRootDir: String, remoteName: String, branch: String, headers: String?): String =
    GitNetwork.gitFetch(gitRootDir, remoteName, branch, headers)

  fun gitClone(url: String, directory: String, branch: String?, depth: Int, singleBranch: Boolean, noTags: Boolean, headers: String?): String =
    GitNetwork.gitClone(url, directory, branch, depth, singleBranch, noTags, headers)

  // ─── Bundle Operations (GitBundle) ──────────────────────────────

  fun gitCreateBundle(gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String): String =
    GitBundle.gitCreateBundle(gitRootDir, remoteName, localBranch, remoteBranch)

  fun gitFetchFromBundle(gitRootDir: String, bundleFileName: String, branch: String): String =
    GitBundle.gitFetchFromBundle(gitRootDir, bundleFileName, branch)

  // ─── History & Reference Operations (GitHistory) ────────────────

  fun gitLog(gitRootDir: String, ref: String?, maxCount: Int): String =
    GitHistory.gitLog(gitRootDir, ref, maxCount)

  fun gitResolveRef(gitRootDir: String, ref: String): String =
    GitHistory.gitResolveRef(gitRootDir, ref)

  fun gitCurrentBranch(gitRootDir: String): String =
    GitHistory.gitCurrentBranch(gitRootDir)

  fun gitReadBlob(gitRootDir: String, ref: String, filepath: String, asBase64: Boolean): String =
    GitHistory.gitReadBlob(gitRootDir, ref, filepath, asBase64)

  // ─── Repository Configuration (GitRepository) ───────────────────

  fun gitInit(directory: String, defaultBranch: String): String =
    GitRepository.gitInit(directory, defaultBranch)

  fun gitSetConfig(gitRootDir: String, section: String, subsection: String?, name: String, value: String): String =
    GitRepository.gitSetConfig(gitRootDir, section, subsection, name, value)

  fun gitAddRemote(gitRootDir: String, remoteName: String, url: String): String =
    GitRepository.gitAddRemote(gitRootDir, remoteName, url)
}
