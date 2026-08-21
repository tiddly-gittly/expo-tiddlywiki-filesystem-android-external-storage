package expo.modules.externalstorage

/**
 * Facade for all Git operations.
 * Delegates to specialized modules: GitRepository, GitTransport, GitNetwork, etc.
 * Maintains backward compatibility with ExternalStorageModule.
 */
internal object GitHelper {

  // ─── Status & Diff Operations (GitStatus) ──────────────────────

  fun gitStatus(gitRootDir: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitStatus.gitStatus(gitRootDir) }

  fun gitStatusDebug(gitRootDir: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitStatus.gitStatusDebug(gitRootDir) }

  fun gitDiffTrees(gitRootDir: String, oldRef: String, newRef: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitStatus.gitDiffTrees(gitRootDir, oldRef, newRef) }

  fun gitDiscardFileChanges(gitRootDir: String, filepath: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitStatus.gitDiscardFileChanges(gitRootDir, filepath) }

  // ─── Local Operations (GitLocal) ────────────────────────────────

  fun buildGitIndex(gitRootDir: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitLocal.buildGitIndex(gitRootDir) }

  fun gitAddAndCommit(gitRootDir: String, message: String, authorName: String, authorEmail: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitLocal.gitAddAndCommit(gitRootDir, message, authorName, authorEmail) }

  fun gitReset(gitRootDir: String, ref: String, mode: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitLocal.gitReset(gitRootDir, ref, mode) }

  fun gitCheckoutChangedFiles(gitRootDir: String, oldOid: String, newOid: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitLocal.gitCheckoutChangedFiles(gitRootDir, oldOid, newOid) }

  // ─── Network Operations (GitNetwork) ────────────────────────────

  fun gitPush(gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String, force: Boolean, headers: String?): String =
    GitRepositoryLock.withLock(gitRootDir) { GitNetwork.gitPush(gitRootDir, remoteName, localBranch, remoteBranch, force, headers) }

  fun gitFetch(gitRootDir: String, remoteName: String, branch: String, headers: String?): String =
    GitRepositoryLock.withLock(gitRootDir) { GitNetwork.gitFetch(gitRootDir, remoteName, branch, headers) }

  fun gitClone(url: String, directory: String, branch: String?, depth: Int, singleBranch: Boolean, noTags: Boolean, headers: String?): String =
    GitRepositoryLock.withLock(directory) { GitNetwork.gitClone(url, directory, branch, depth, singleBranch, noTags, headers) }

  // ─── Bundle Operations (GitBundle) ──────────────────────────────

  fun gitCreateBundle(gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitBundle.gitCreateBundle(gitRootDir, remoteName, localBranch, remoteBranch) }

  fun gitFetchFromBundle(gitRootDir: String, bundleFileName: String, branch: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitBundle.gitFetchFromBundle(gitRootDir, bundleFileName, branch) }

  // ─── History & Reference Operations (GitHistory) ────────────────

  fun gitLog(gitRootDir: String, ref: String?, maxCount: Int): String =
    GitRepositoryLock.withLock(gitRootDir) { GitHistory.gitLog(gitRootDir, ref, maxCount) }

  fun gitResolveRef(gitRootDir: String, ref: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitHistory.gitResolveRef(gitRootDir, ref) }

  fun gitCurrentBranch(gitRootDir: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitHistory.gitCurrentBranch(gitRootDir) }

  fun gitReadBlob(gitRootDir: String, ref: String, filepath: String, asBase64: Boolean): String =
    GitRepositoryLock.withLock(gitRootDir) { GitHistory.gitReadBlob(gitRootDir, ref, filepath, asBase64) }

  // ─── Repository Configuration (GitRepository) ───────────────────

  fun gitInit(directory: String, defaultBranch: String): String =
    GitRepositoryLock.withLock(directory) { GitRepository.gitInit(directory, defaultBranch) }

  fun gitSetConfig(gitRootDir: String, section: String, subsection: String?, name: String, value: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitRepository.gitSetConfig(gitRootDir, section, subsection, name, value) }

  fun gitAddRemote(gitRootDir: String, remoteName: String, url: String): String =
    GitRepositoryLock.withLock(gitRootDir) { GitRepository.gitAddRemote(gitRootDir, remoteName, url) }
}
