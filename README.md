# expo-tiddlywiki-filesystem-android-external-storage

An Expo Config Plugin and Native Module for accessing Android's External Storage (e.g. `/sdcard/Documents/`) directly using `java.io.File`, bypassing Expo FileSystem's scoped storage restrictions.

## Why this package?

We built this because we faced significant limitations with standard approaches when developing **TidGi-Mobile** (a TiddlyWiki app that needs to sync with desktop):

1.  **SAF (Storage Access Framework) is too slow**: We need to sync thousands of small `.tid` files. Using `DocumentFile` and SAF APIs for batch operations was incredibly slow compared to direct file access.
2.  **`expo-file-system` is restricted**: Even if you add `MANAGE_EXTERNAL_STORAGE` permission to your `app.json`, the standard `expo-file-system` APIs are scoped to the app's internal directory (or specific cache dirs). They generally cannot access arbitrary paths like `/sdcard/Documents/TidGi/` directly.
3.  **Permissions alone are insufficient**: Simply adding the permissions below to `app.json` does **not** grant `expo-file-system` access to external storage due to its internal "scoped" design.
    ```json
    "permissions": [
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.MANAGE_EXTERNAL_STORAGE"
    ]
    ```
4.  **Legacy API failures**: We tried using `expo-file-system`'s legacy storage flags, but they often failed or were inconsistent across Android versions.

Therefore, we implemented this **Native Module** which uses standard `java.io.File` APIs in Kotlin. This requires:
-   **Expo Development Client**: You cannot use Expo Go because this package contains custom native code. You must build a custom dev client or a production build (prebuild).
-   **`MANAGE_EXTERNAL_STORAGE` Permission**: The user must grant "All files access" in system settings.

## Features

- **Direct File Access**: Read and write to any path the app has permission for.
- **Bypass Scoped Storage**: Works with absolute paths (e.g. `/storage/emulated/0/...`).
- **Standard Operations**: `exists`, `read`, `write`, `mkdir`, `list` (recursive), `delete`.
- **Permission Helper**: Check for `MANAGE_EXTERNAL_STORAGE` status.

## Installation

```bash
npm install expo-tiddlywiki-filesystem-android-external-storage
# or
pnpm add expo-tiddlywiki-filesystem-android-external-storage
```

## Configuration

### Android Manifest

This module requires the `MANAGE_EXTERNAL_STORAGE` permission to be effective for broad access.

Add this to your `app.json` / `app.config.ts` if you want Expo to add the permission to `AndroidManifest.xml` (though you might need a custom config plugin to add `R.attr.requestLegacyExternalStorage` or similar attributes if targeting Android 10).

Actually, this package is primarily the *native implementation*. You should ensure your app requests the permission at runtime (e.g. using `Intent` to open Settings).

## Usage

```typescript
import { ExternalStorage } from 'expo-tiddlywiki-filesystem-android-external-storage';

async function example() {
  const path = '/sdcard/Documents/myfile.txt';

  // Check existence
  if (await ExternalStorage.exists(path)) {
    console.log('File exists');
    const content = await ExternalStorage.readFileUtf8(path);
  } else {
    // Write file
    await ExternalStorage.writeFileUtf8(path, 'Hello World');
  }

  // List directory
  const files = await ExternalStorage.readDir('/sdcard/Documents');
  console.log('Files:', files);
}
```

## Expo Config Plugin

This package includes a config plugin to automatically add the `MANAGE_EXTERNAL_STORAGE` permission to your `AndroidManifest.xml`.

In `app.json` or `app.config.ts`:

```json
{
  "expo": {
    "plugins": [
      "expo-filesystem-android-external-storage"
    ]
  }
}
```

## API

### `exists(path: string): Promise<boolean>`
Checks if a file or directory exists.

### `getInfo(path: string): Promise<FileInfo>`
Gets metadata (size, modification time, isDirectory).

### `readFileUtf8(path: string): Promise<string>`
Reads a file as a UTF-8 string.

### `writeFileUtf8(path: string, content: string): Promise<void>`
Writes a UTF-8 string to a file. Creates parent directories if needed.

### `readDir(path: string): Promise<string[]>`
Lists files in a directory (non-recursive).

### `readDirRecursive(path: string): Promise<string[]>`
Recursively lists all files, returning paths relative to the root. Skips `.git`, `node_modules`, etc.

### `mkdir(path: string): Promise<void>`
Creates a directory and necessary parent directories.

### `deleteFile(path: string): Promise<void>`
Deletes a file.

### `rmdir(path: string): Promise<void>`
Deletes a directory recursively.

### `isExternalStorageManager(): Promise<boolean>`
Checks if the `MANAGE_EXTERNAL_STORAGE` permission is granted (Android 11+).

## JGit Version Note

This module bundles **JGit 6.2.x** (not the latest 6.10.x) for Android compatibility.

Newer JGit versions (6.3+) call Java 9+ APIs (`InputStream.readNBytes`, `InputStream.transferTo`) that require `coreLibraryDesugaringEnabled` — Android's `desugar_jdk_libs` provides these at build time. The config plugin included in this package automatically enables desugaring in the generated `android/app/build.gradle`.

If you encounter `NoSuchMethodError` for these methods at runtime, verify that the config plugin ran during `expo prebuild` (check that `android/app/build.gradle` contains `coreLibraryDesugaringEnabled true` and `desugar_jdk_libs`).

JGit 6.2.x is the latest release that avoids these Java 9+ API calls while still providing all the Git primitives needed by this module (clone, fetch, push, status, diff, log, etc.). One API trade-off: `CloneCommand.setDepth()` (shallow clone) was introduced in JGit 6.3, so this module does not support `--depth` on clone.

If you need a newer JGit, ensure your app's `minSdkVersion` and desugaring configuration are verified on all target devices.

## License

MIT
