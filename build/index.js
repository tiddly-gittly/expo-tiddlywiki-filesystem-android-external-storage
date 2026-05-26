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
let _module;
/**
 * Lazily load the native module. Wrapped in a function so that the app does NOT
 * crash at import time if the native module is missing (e.g. on iOS or when the
 * binary was built without it).
 */
function getNativeModule() {
    if (_module)
        return _module;
    if (Platform.OS !== 'android' && Platform.OS !== 'ios') {
        throw new Error('ExternalStorage native module is only available on Android and iOS');
    }
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { requireNativeModule } = require('expo-modules-core');
    _module = requireNativeModule('ExternalStorage');
    return _module;
}
export const ExternalStorage = new Proxy({}, {
    get(_target, property) {
        const mod = getNativeModule();
        return mod[property];
    },
});
/**
 * Strip file:// prefix from a URI to produce a plain filesystem path.
 * Safe to call on paths that are already plain.
 */
export function toPlainPath(uriOrPath) {
    if (uriOrPath.startsWith('file://')) {
        return uriOrPath.slice('file://'.length);
    }
    return uriOrPath;
}
//# sourceMappingURL=index.js.map