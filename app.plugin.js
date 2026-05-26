const { withAndroidManifest, withAppBuildGradle } = require('@expo/config-plugins');

const DESUGAR_DEPENDENCY = "coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'";

function applyCoreLibraryDesugaring(contents) {
  if (!contents.includes('coreLibraryDesugaringEnabled')) {
    if (contents.includes('compileOptions {')) {
      contents = contents.replace(
        /(compileOptions\s*\{)/,
        '$1\n        coreLibraryDesugaringEnabled true',
      );
    } else {
      contents = contents.replace(
        /^(android\s*\{)/m,
        '$1\n    compileOptions {\n        coreLibraryDesugaringEnabled true\n    }',
      );
    }
  }

  if (!contents.includes('desugar_jdk_libs')) {
    contents = contents.replace(
      /^(dependencies\s*\{)/m,
      `$1\n    ${DESUGAR_DEPENDENCY}`,
    );
  }

  return contents;
}

const withExternalStoragePermission = (config) => {
  config = withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults;
    
    if (!androidManifest.manifest['uses-permission']) {
      androidManifest.manifest['uses-permission'] = [];
    }

    // Add MANAGE_EXTERNAL_STORAGE permission
    if (!androidManifest.manifest['uses-permission'].find(p => p.$['android:name'] === 'android.permission.MANAGE_EXTERNAL_STORAGE')) {
      androidManifest.manifest['uses-permission'].push({
        $: {
          'android:name': 'android.permission.MANAGE_EXTERNAL_STORAGE',
          // Optionally add tools:ignore="ScopedStorage" if needed but usually standard permission is enough
        },
      });
    }

    return config;
  });

  config = withAppBuildGradle(config, (config) => {
    config.modResults.contents = applyCoreLibraryDesugaring(config.modResults.contents);
    return config;
  });

  return config;
};

module.exports = withExternalStoragePermission;
