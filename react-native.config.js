/**
 * The native Android Gradle project is at the repository root (not ./android).
 * Point the React Native CLI to this existing layout so autolinking can inspect
 * app/src/main/AndroidManifest.xml and the app Gradle module.
 */
module.exports = {
  project: {
    android: {
      sourceDir: '.',
    },
  },
};
