# Portainer Android App

This sample Android application demonstrates how to connect to a Portainer server via its HTTP API and list Docker Swarm nodes. It uses Retrofit for networking and is structured as a basic Android project.

## Building

Ensure the Android SDK is installed, `ANDROID_HOME` is configured, and Gradle is available on your PATH, then run:

```
gradle assembleDebug
```

## Releases

Publishing a GitHub Release will automatically build the project and upload a signed APK as a release asset. The signing step expects a file named `test.keystore` at the repository root. This keystore is excluded from version control and must be added manually (for example via the GitHub web interface) before running release builds.

## Configuration

By default the app points to `http://localhost:9000/`. Update the base URL or credentials in `MainActivity` for your environment.

## Excluded binary files

The repository does not track the following binaries. Add them manually via the GitHub web UI if you need them:

- `test.keystore` for signing release builds
- `gradlew`, `gradlew.bat`, and `gradle/wrapper/` for the Gradle wrapper
