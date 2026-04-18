package expo.modules.externalstorage

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Expo native module coordinator for external storage operations.
 * Delegates to specialized helpers: FileSystem, TiddlyWikiParser, GitHelper.
 */
class ExternalStorageModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExternalStorage")

    // ─── Basic File Operations (FileSystem) ────────────────────────

    AsyncFunction("exists") { path: String ->
      FileSystem.exists(path)
    }

    AsyncFunction("getInfo") { path: String ->
      FileSystem.getInfo(path)
    }

    AsyncFunction("mkdir") { path: String ->
      FileSystem.mkdir(path)
    }

    AsyncFunction("readDir") { path: String ->
      FileSystem.readDir(path)
    }

    AsyncFunction("readDirRecursive") { path: String ->
      FileSystem.readDirRecursive(path)
    }

    AsyncFunction("rmdir") { path: String ->
      FileSystem.rmdir(path)
    }

    AsyncFunction("readFileUtf8") { path: String ->
      FileSystem.readFileUtf8(path)
    }

    AsyncFunction("readFileBase64") { path: String ->
      FileSystem.readFileBase64(path)
    }

    AsyncFunction("writeFileUtf8") { path: String, content: String ->
      FileSystem.writeFileUtf8(path, content)
    }

    AsyncFunction("writeFileBase64") { path: String, base64Content: String ->
      FileSystem.writeFileBase64(path, base64Content)
    }

    AsyncFunction("appendFileBase64") { path: String, base64Content: String, truncateFirst: Boolean ->
      FileSystem.appendFileBase64(path, base64Content, truncateFirst)
    }

    AsyncFunction("writeFilesBase64") { paths: List<String>, base64Contents: List<String> ->
      FileSystem.writeFilesBase64(paths, base64Contents)
    }

    AsyncFunction("deleteFile") { path: String ->
      FileSystem.deleteFile(path)
    }

    // ─── Storage Permission Helpers (FileSystem) ────────────────────

    AsyncFunction("isExternalStorageWritable") {
      FileSystem.isExternalStorageWritable()
    }

    AsyncFunction("getExternalStorageDirectory") {
      FileSystem.getExternalStorageDirectory()
    }

    AsyncFunction("isExternalStorageManager") {
      FileSystem.isExternalStorageManager()
    }

    // ─── HTTP Streaming (FileSystem) ────────────────────────────────

    AsyncFunction("httpPostToFile") { url: String, headersMap: Map<String, String>, bodyBase64: String, destPath: String, contentType: String ->
      FileSystem.httpPostToFile(url, headersMap, bodyBase64, destPath, contentType)
    }

    AsyncFunction("readFileChunk") { path: String, offset: Long, length: Int ->
      FileSystem.readFileChunk(path, offset, length)
    }

    AsyncFunction("downloadFileResumable") { url: String, headersMap: Map<String, String>, destPath: String ->
      FileSystem.downloadFileResumable(url, headersMap, destPath)
    }

    // ─── Tar Extraction (FileSystem) ────────────────────────────────

    AsyncFunction("extractTar") { tarPath: String, destDir: String ->
      FileSystem.extractTar(tarPath, destDir)
    }

    // ─── TiddlyWiki Parsing (TiddlyWikiParser) ──────────────────────

    AsyncFunction("batchParseTidFiles") { filePaths: List<String>, quickLoadMode: Boolean ->
      TiddlyWikiParser.batchParseTidFiles(filePaths, quickLoadMode)
    }

    // ─── Git Operations (GitHelper facade) ──────────────────────────

    AsyncFunction("gitStatus") { gitRootDir: String ->
      GitHelper.gitStatus(gitRootDir)
    }

    AsyncFunction("gitStatusDebug") { gitRootDir: String ->
      GitHelper.gitStatusDebug(gitRootDir)
    }

    AsyncFunction("buildGitIndex") { gitRootDir: String ->
      GitHelper.buildGitIndex(gitRootDir)
    }

    AsyncFunction("gitPush") { gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String, force: Boolean, headers: String? ->
      GitHelper.gitPush(gitRootDir, remoteName, localBranch, remoteBranch, force, headers)
    }

    AsyncFunction("gitCreateBundle") { gitRootDir: String, remoteName: String, localBranch: String, remoteBranch: String ->
      GitHelper.gitCreateBundle(gitRootDir, remoteName, localBranch, remoteBranch)
    }

    AsyncFunction("gitFetchFromBundle") { gitRootDir: String, bundleFileName: String, branch: String ->
      GitHelper.gitFetchFromBundle(gitRootDir, bundleFileName, branch)
    }

    AsyncFunction("gitFetch") { gitRootDir: String, remoteName: String, branch: String, headers: String? ->
      GitHelper.gitFetch(gitRootDir, remoteName, branch, headers)
    }

    AsyncFunction("gitCheckoutChangedFiles") { gitRootDir: String, oldOid: String, newOid: String ->
      GitHelper.gitCheckoutChangedFiles(gitRootDir, oldOid, newOid)
    }

    AsyncFunction("gitAddAndCommit") { gitRootDir: String, message: String, authorName: String, authorEmail: String ->
      GitHelper.gitAddAndCommit(gitRootDir, message, authorName, authorEmail)
    }

    AsyncFunction("gitReset") { gitRootDir: String, ref: String, mode: String ->
      GitHelper.gitReset(gitRootDir, ref, mode)
    }

    AsyncFunction("gitClone") { url: String, directory: String, branch: String?, depth: Int, singleBranch: Boolean, noTags: Boolean, headers: String? ->
      GitHelper.gitClone(url, directory, branch, depth, singleBranch, noTags, headers)
    }

    AsyncFunction("gitLog") { gitRootDir: String, ref: String?, maxCount: Int ->
      GitHelper.gitLog(gitRootDir, ref, maxCount)
    }

    AsyncFunction("gitResolveRef") { gitRootDir: String, ref: String ->
      GitHelper.gitResolveRef(gitRootDir, ref)
    }

    AsyncFunction("gitCurrentBranch") { gitRootDir: String ->
      GitHelper.gitCurrentBranch(gitRootDir)
    }

    AsyncFunction("gitInit") { directory: String, defaultBranch: String ->
      GitHelper.gitInit(directory, defaultBranch)
    }

    AsyncFunction("gitSetConfig") { gitRootDir: String, section: String, subsection: String?, name: String, value: String ->
      GitHelper.gitSetConfig(gitRootDir, section, subsection, name, value)
    }

    AsyncFunction("gitAddRemote") { gitRootDir: String, remoteName: String, url: String ->
      GitHelper.gitAddRemote(gitRootDir, remoteName, url)
    }

    AsyncFunction("gitReadBlob") { gitRootDir: String, ref: String, filepath: String, asBase64: Boolean ->
      GitHelper.gitReadBlob(gitRootDir, ref, filepath, asBase64)
    }

    AsyncFunction("gitDiffTrees") { gitRootDir: String, oldRef: String, newRef: String ->
      GitHelper.gitDiffTrees(gitRootDir, oldRef, newRef)
    }

    AsyncFunction("gitDiscardFileChanges") { gitRootDir: String, filepath: String ->
      GitHelper.gitDiscardFileChanges(gitRootDir, filepath)
    }
  }
}
