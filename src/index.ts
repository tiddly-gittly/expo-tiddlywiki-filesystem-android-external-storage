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
  if (Platform.OS !== 'android') {
    throw new Error('ExternalStorage native module is only available on Android');
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
