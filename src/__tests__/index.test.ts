type ExternalStorageModule = typeof import('../index');

function loadModuleWithPlatform(
  platformOS: string,
  nativeModule: Record<string, unknown> = {},
): { module: ExternalStorageModule; requireNativeModule: jest.Mock } {
  jest.resetModules();
  const requireNativeModule = jest.fn(() => nativeModule);

  jest.doMock('react-native', () => ({
    Platform: {
      OS: platformOS,
    },
  }));
  jest.doMock('expo-modules-core', () => ({
    requireNativeModule,
  }));

  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const module = require('../index') as ExternalStorageModule;
  return { module, requireNativeModule };
}

describe('toPlainPath', () => {
  it('strips only the file URI scheme from filesystem paths', () => {
    const { module } = loadModuleWithPlatform('android');

    expect(module.toPlainPath('file:///storage/emulated/0/TidGi/wiki')).toBe('/storage/emulated/0/TidGi/wiki');
    expect(module.toPlainPath('/storage/emulated/0/TidGi/wiki')).toBe('/storage/emulated/0/TidGi/wiki');
    expect(module.toPlainPath('content://com.android.externalstorage.documents/tree/primary%3ATidGi')).toBe('content://com.android.externalstorage.documents/tree/primary%3ATidGi');
  });
});

describe('ExternalStorage proxy', () => {
  it('loads the native module lazily when a method is accessed', () => {
    const nativeExists = jest.fn();
    const nativeMkdir = jest.fn();
    const { module, requireNativeModule } = loadModuleWithPlatform('android', {
      exists: nativeExists,
      mkdir: nativeMkdir,
    });

    expect(requireNativeModule).not.toHaveBeenCalled();
    expect(module.ExternalStorage.exists).toBe(nativeExists);
    expect(module.ExternalStorage.mkdir).toBe(nativeMkdir);
    expect(requireNativeModule).toHaveBeenCalledTimes(1);
    expect(requireNativeModule).toHaveBeenCalledWith('ExternalStorage');
  });

  it('throws only when the native proxy is accessed on unsupported platforms', () => {
    const { module, requireNativeModule } = loadModuleWithPlatform('web');

    expect(module.toPlainPath('file:///tmp/wiki')).toBe('/tmp/wiki');
    expect(requireNativeModule).not.toHaveBeenCalled();
    expect(() => module.ExternalStorage.exists).toThrow('ExternalStorage native module is only available on Android and iOS');
  });
});
