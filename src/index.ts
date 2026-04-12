/**
 * TypeScript bindings for the ExternalStorage native module.
 *
 * This module uses raw java.io.File on Android to bypass Expo FileSystem's
 * directory whitelist. It allows reading/writing to shared external storage
 * when MANAGE_EXTERNAL_STORAGE permission is granted.
 *
 * All path arguments are plain filesystem paths (e.g. "/storage/emulated/0/Documents/TidGi/").
 * Do NOT pass file:// URIs — strip the scheme before calling.
 */
import { Platform } from 'react-native';

let _module: IExternalStorageModule | undefined;

/**
 * Lazily load the native module. Wrapped in a function so that the app does NOT
 * crash at import time if the native module is missing (e.g. on iOS or when the
 * binary was built without it).
 */
function getNativeModule(): IExternalStorageModule {
  if (_module) return _module;
  if (Platform.OS !== 'android' && Platform.OS !== 'ios') {
    throw new Error('ExternalStorage native module is only available on Android and iOS');
  }
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { requireNativeModule } = require('expo-modules-core') as { requireNativeModule: (name: string) => IExternalStorageModule };
  _module = requireNativeModule('ExternalStorage');
  return _module;
}

interface FileInfo {
  exists: boolean;
  isDirectory: boolean;
  size: number;
  /** Milliseconds since epoch */
  modificationTime: number;
}

interface BatchWriteResult {
  writtenCount: number;
}

interface HttpPostToFileResult {
  statusCode: number;
  headers: Record<string, string>;
  bytesWritten: number;
}

interface DownloadFileResumableResult {
  statusCode: number;
  /** Final size of the file on disk after download */
  totalBytes: number;
  /** true if the download resumed from a partial file (HTTP 206) */
  resumed: boolean;
}

interface ExtractTarResult {
  filesExtracted: number;
}

interface ReadFileChunkResult {
  /** Base64-encoded chunk data */
  data: string;
  bytesRead: number;
}

interface IExternalStorageModule {
  exists(path: string): Promise<boolean>;
  getInfo(path: string): Promise<FileInfo>;

  mkdir(path: string): Promise<void>;
  readDir(path: string): Promise<string[]>;
  /** Recursively list all files under a directory, returning relative paths. Skips .git etc. */
  readDirRecursive(path: string): Promise<string[]>;
  rmdir(path: string): Promise<void>;

  readFileUtf8(path: string): Promise<string>;
  readFileBase64(path: string): Promise<string>;
  writeFileUtf8(path: string, content: string): Promise<void>;
  writeFileBase64(path: string, base64Content: string): Promise<void>;
  /**
   * Append a Base64-encoded chunk to a file, optionally truncating first.
   *
   * Designed for streaming large writes from JS in bounded-memory chunks
   * (e.g. 512 KB each) so the JVM never allocates the full file content,
   * avoiding OOM on 50+ MB git pack files.
   *
   * @param truncateFirst Pass `true` for the first chunk to create/truncate
   *                      the file, then `false` for subsequent chunks.
   */
  appendFileBase64(path: string, base64Content: string, truncateFirst: boolean): Promise<void>;
  writeFilesBase64(paths: string[], base64Contents: string[]): Promise<BatchWriteResult>;
  deleteFile(path: string): Promise<void>;

  isExternalStorageWritable(): Promise<boolean>;
  getExternalStorageDirectory(): Promise<string>;
  /** Android 11+ (API 30): check if MANAGE_EXTERNAL_STORAGE is granted. Pre-30 returns true. */
  isExternalStorageManager(): Promise<boolean>;

  /**
   * HTTP POST with the response body streamed directly to a file on disk,
   * **never buffering the full response in JVM/Hermes heap**.
   *
   * Designed for git-upload-pack which can return 100+ MB packfiles.
   *
   * @param url         Target URL
   * @param headers     HTTP headers as `{ key: value }`
   * @param bodyBase64  Request body encoded as Base64 (binary git protocol data)
   * @param destPath    Plain filesystem path to write the response body to
   * @param contentType MIME type for the request body
   */
  httpPostToFile(
    url: string,
    headers: Record<string, string>,
    bodyBase64: string,
    destPath: string,
    contentType: string,
  ): Promise<HttpPostToFileResult>;

  /**
   * Read a chunk of a file starting at `offset` for up to `length` bytes.
   * Returns Base64-encoded data and actual bytes read.
   *
   * Use this to stream a large file into JS in bounded-memory chunks.
   */
  readFileChunk(path: string, offset: number, length: number): Promise<ReadFileChunkResult>;

  /**
   * Download a file via HTTP GET with resumable download support.
   *
   * If `destPath` already exists on disk (from a previous interrupted download),
   * sends `Range: bytes=<existingSize>-` to resume. The server must respond
   * with 206 Partial Content for resume to work; otherwise the file is
   * overwritten from scratch (200 response).
   *
   * @param url       Target URL
   * @param headers   Extra HTTP headers (e.g. Authorization, ETag)
   * @param destPath  Plain filesystem path for the downloaded file
   */
  downloadFileResumable(
    url: string,
    headers: Record<string, string>,
    destPath: string,
  ): Promise<DownloadFileResumableResult>;

  /**
   * Extract an uncompressed tar archive to a destination directory.
   * Uses a native tar parser — no third-party dependency.
   * Supports POSIX ustar and GNU long-name extensions.
   * Validates paths to prevent directory traversal attacks.
   *
   * @param tarPath  Path to the .tar file
   * @param destDir  Destination directory (created if needed)
   */
  extractTar(tarPath: string, destDir: string): Promise<ExtractTarResult>;

  /**
   * Parse a batch of TiddlyWiki tiddler files entirely in native Kotlin.
   *
   * This is the critical performance optimization for initial wiki loading:
   * a single bridge call processes 100+ files in parallel, returning a
   * ready-to-inject JSON array string. Eliminates per-file bridge round-trips.
   *
   * Supports .tid, .json, and .meta files. Applies skinny logic:
   * - System tiddlers ($:/) → always full text
   * - Plugins (application/json + plugin-type) → always full text
   * - Module tiddlers (module-type) → always full text
   * - Small tiddlers (< 10KB body) → full text
   * - Large user tiddlers → skinny (_is_skinny: "yes", text omitted)
   *
   * @param filePaths     Array of absolute filesystem paths
   * @param quickLoadMode If true, all tiddlers returned as skinny
   * @returns JSON string: serialized array of tiddler field objects
   */
  batchParseTidFiles(filePaths: string[], quickLoadMode: boolean): Promise<string>;

  /**
   * Lightweight native git status using direct git-index parsing.
   *
   * Parses `.git/index` to get tracked files and their stat-cache entries,
   * then compares against the working directory using file size and mtime.
   * Orders of magnitude faster than isomorphic-git's `statusMatrix` because:
   * - No JS↔Native bridge round-trips per file
   * - Uses stat-cache (size+mtime) instead of SHA-1 re-hashing
   * - Parallel file walking in Java
   *
   * @param gitRootDir The root directory of the git repository (parent of .git/)
   * @returns JSON string: `[{"path":"tiddlers/foo.tid","type":"add"|"modify"|"delete"}, ...]`
   */
  gitStatus(gitRootDir: string): Promise<string>;

  /**
   * Debug function returning diagnostic info about the git repository state.
   * @returns JSON string with root/gitDir/index existence and git dir children
   */
  gitStatusDebug(gitRootDir: string): Promise<string>;

  /**
   * Build `.git/index` natively by reading the HEAD tree from pack files,
   * stat'ing all files on disk, and writing a v2 index file.
   *
   * This is used after archive clone where TidGi Desktop's tar export
   * doesn't include `.git/index`.
   *
   * @param gitRootDir The root directory of the git repository (parent of .git/)
   * @returns JSON string: `{"ok":true,"entries":N,"indexSize":M}` or `{"ok":false,"error":"..."}`
   */
  buildGitIndex(gitRootDir: string): Promise<string>;

  /**
   * Push local branch to remote using native JGit (efficient pack building).
   * Avoids OOM from isomorphic-git's JS-based pack construction on large repos.
   *
   * @param gitRootDir   Absolute path to the git working directory
   * @param remoteName   Remote name (e.g. "origin")
   * @param localBranch  Local branch name (e.g. "main")
   * @param remoteBranch Remote branch ref (e.g. "refs/heads/mobile-incoming")
   * @param force        Whether to force push
   * @param headers      Optional HTTP headers as JSON string
   * @returns JSON string: `{"ok":true,"updates":[...]}` or `{"ok":false,"error":"..."}`
   */
  gitPush(gitRootDir: string, remoteName: string, localBranch: string, remoteBranch: string, force: boolean, headers?: string | null): Promise<string>;

  /**
   * Create a git bundle containing unpushed commits (local branch tip minus remote tracking branch).
   * Returns base64-encoded bundle data that can be HTTP-POSTed to the desktop for unbundling.
   * This avoids JGit's broken HTTP push (SmartHttpPushConnection) entirely.
   *
   * @param gitRootDir    Absolute path to the git working directory
   * @param remoteName    Remote name (e.g. "origin")
   * @param localBranch   Local branch name (e.g. "master")
   * @param remoteBranch  Remote branch name (e.g. "master")
   * @returns JSON string: `{"ok":true,"bundleBase64":"..."}` or `{"ok":false,"error":"..."}`
   */
  gitCreateBundle(gitRootDir: string, remoteName: string, localBranch: string, remoteBranch: string): Promise<string>;

  /**
   * Fetch from remote using native JGit (efficient pack handling).
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param remoteName  Remote name (e.g. "origin")
   * @param branch      Branch to fetch
   * @param headers     Optional HTTP headers as JSON string
   * @returns JSON string: `{"ok":true,"updates":[...]}` or `{"ok":false,"error":"..."}`
   */
  gitFetch(gitRootDir: string, remoteName: string, branch: string, headers?: string | null): Promise<string>;

  /**
   * Compare two commits and checkout only changed/new files to the working tree.
   * Avoids full checkout which OOMs on large repos.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param oldOid      Old commit SHA (before fetch)
   * @param newOid      New commit SHA (after fetch)
   * @returns JSON string: `{"ok":true,"count":N,"files":[...]}` or `{"ok":false,"error":"..."}`
   */
  gitCheckoutChangedFiles(gitRootDir: string, oldOid: string, newOid: string): Promise<string>;

  /**
   * Stage all changes and commit using native git (JGit on Android).
   * Replaces isomorphic-git's statusMatrix + add/remove loop which OOMs on large repos.
   *
   * @param gitRootDir   Absolute path to the git working directory
   * @param message      Commit message
   * @param authorName   Author name
   * @param authorEmail  Author email
   * @returns JSON string: `{"ok":true,"commitId":"abc123"}` or `{"ok":false,"error":"..."}`
   */
  gitAddAndCommit(gitRootDir: string, message: string, authorName: string, authorEmail: string): Promise<string>;

  /**
   * Reset the current branch to a specific ref using native git (JGit on Android).
   * Supports hard, mixed, and soft reset modes.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param ref         Target ref (e.g. "origin/main", commit SHA)
   * @param mode        Reset mode: "hard", "mixed", or "soft"
   * @returns JSON string: `{"ok":true,"ref":"abc123"}` or `{"ok":false,"error":"..."}`
   */
  gitReset(gitRootDir: string, ref: string, mode: string): Promise<string>;

  /**
   * Clone a remote repository using native JGit.
   *
   * @param url          Remote repository URL
   * @param directory    Destination directory
   * @param branch       Branch to clone (null for default)
   * @param depth        Depth for shallow clone (0 for full)
   * @param singleBranch Whether to clone only the specified branch
   * @param noTags       Whether to skip fetching tags
   * @param headers      Optional HTTP headers as JSON string
   * @returns JSON string: `{"ok":true,"head":"abc123"}` or `{"ok":false,"error":"..."}`
   */
  gitClone(url: string, directory: string, branch: string | null, depth: number, singleBranch: boolean, noTags: boolean, headers?: string | null): Promise<string>;

  /**
   * Get commit history using native git log.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param ref         Branch or ref to start from (null for all)
   * @param maxCount    Maximum number of commits to return
   * @returns JSON string: `{"ok":true,"commits":[{"oid","message","authorName","authorEmail","timestamp","parentOids"}]}`
   */
  gitLog(gitRootDir: string, ref: string | null, maxCount: number): Promise<string>;

  /**
   * Resolve a git reference to its SHA-1 hash.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param ref         Reference to resolve (e.g. "HEAD", "refs/heads/main", "origin/main")
   * @returns JSON string: `{"ok":true,"oid":"abc123"}` or `{"ok":false,"error":"..."}`
   */
  gitResolveRef(gitRootDir: string, ref: string): Promise<string>;

  /**
   * Get current branch name and branch listings.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @returns JSON string: `{"ok":true,"branch":"main","isDetached":false,"localBranches":[],"remoteBranches":[]}`
   */
  gitCurrentBranch(gitRootDir: string): Promise<string>;

  /**
   * Initialize a new git repository.
   *
   * @param directory     Directory to initialize
   * @param defaultBranch Default branch name (e.g. "main")
   * @returns JSON string: `{"ok":true}` or `{"ok":false,"error":"..."}`
   */
  gitInit(directory: string, defaultBranch: string): Promise<string>;

  /**
   * Set a git config value.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param section     Config section (e.g. "remote")
   * @param subsection  Config subsection (e.g. "origin"), null for no subsection
   * @param name        Config key (e.g. "url")
   * @param value       Config value
   * @returns JSON string: `{"ok":true}` or `{"ok":false,"error":"..."}`
   */
  gitSetConfig(gitRootDir: string, section: string, subsection: string | null, name: string, value: string): Promise<string>;

  /**
   * Add a remote to the repository.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param remoteName  Remote name (e.g. "origin")
   * @param url         Remote URL
   * @returns JSON string: `{"ok":true}` or `{"ok":false,"error":"..."}`
   */
  gitAddRemote(gitRootDir: string, remoteName: string, url: string): Promise<string>;

  /**
   * Read a file (blob) at a specific commit reference.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param ref         Commit ref to read from
   * @param filepath    Relative path within the repository
   * @param asBase64    If true, return content as base64; otherwise as utf8 string
   * @returns JSON string: `{"ok":true,"content":"...","encoding":"base64"|"utf8","size":N}`
   */
  gitReadBlob(gitRootDir: string, ref: string, filepath: string, asBase64: boolean): Promise<string>;

  /**
   * Diff two commits and return changed files list.
   * Native equivalent of tree-walking diff.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param oldRef      Old commit ref
   * @param newRef      New commit ref
   * @returns JSON string: `{"ok":true,"files":[{"path":"...","type":"add"|"modify"|"delete"}]}`
   */
  gitDiffTrees(gitRootDir: string, oldRef: string, newRef: string): Promise<string>;

  /**
   * Discard uncommitted changes for a specific file.
   * Checks out the HEAD version, or deletes the file if it's untracked.
   *
   * @param gitRootDir  Absolute path to the git working directory
   * @param filepath    Relative path of the file to discard
   * @returns JSON string: `{"ok":true,"action":"checkout"|"delete"}` or `{"ok":false,"error":"..."}`
   */
  gitDiscardFileChanges(gitRootDir: string, filepath: string): Promise<string>;
}

export const ExternalStorage: IExternalStorageModule = new Proxy({} as IExternalStorageModule, {
  get(_target, property) {
    const mod = getNativeModule();
    return (mod as unknown as Record<string | symbol, unknown>)[property];
  },
});

/**
 * Strip file:// prefix from a URI to produce a plain filesystem path.
 * Safe to call on paths that are already plain.
 */
export function toPlainPath(uriOrPath: string): string {
  if (uriOrPath.startsWith('file://')) {
    return uriOrPath.slice('file://'.length);
  }
  return uriOrPath;
}

export type { BatchWriteResult, DownloadFileResumableResult, ExtractTarResult, FileInfo, HttpPostToFileResult, IExternalStorageModule, ReadFileChunkResult };
