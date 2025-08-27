# Repository Guidelines

## Project Structure & Module Organization
- `app/`: Android app module.
  - `src/main/java/com/example/portainerapp/`: Kotlin sources (activities, network).
  - `src/main/res/`: Layouts, drawables, strings.
  - `src/main/AndroidManifest.xml`: Permissions and entry activity.
  - `build.gradle.kts`: Module config (Retrofit, AndroidX, Material).
- Root Gradle files: `build.gradle.kts`, `settings.gradle.kts`.
- Tests: add unit tests in `app/src/test/` and UI tests in `app/src/androidTest/`.

## Local Setup (Android Studio & Emulator)
- Install Android Studio (latest) with Android SDK 34 and JDK 17.
- Open the repo in Android Studio; let Gradle sync.
- Create an AVD: `Pixel 6 • API 34` (prefer x86_64/arm64 with hardware acceleration).
- Run on emulator: select the AVD and click Run, or use `./gradlew installDebug` + launch from the app drawer.

## Build, Test, and Development Commands
- Build debug APK: `./gradlew assembleDebug`
- Install on device/emulator: `./gradlew installDebug`
- Lint checks: `./gradlew lint`
- Unit tests (when added): `./gradlew testDebugUnitTest`
- Instrumented tests: `./gradlew connectedDebugAndroidTest`

## Coding Style & Naming Conventions
- Kotlin, JVM target 17; 4‑space indent; soft limit 100–120 cols.
- Names: `camelCase` vars/functions, `PascalCase` classes, UPPER_SNAKE constants.
- XML: `snake_case` IDs (e.g., `@+id/node_list`); descriptive layout names (e.g., `activity_login.xml`).
- Networking: Retrofit interfaces in `network/`; data models lightweight (Gson).

## Testing Guidelines
- Frameworks: JUnit (unit), Espresso (UI).
- Location: unit → `app/src/test/...`; UI → `app/src/androidTest/...`.
- Conventions: test classes end with `Test` (e.g., `PortainerApiTest`). Target ≥80% coverage for auth and node flows.

## Commit & Pull Request Guidelines
- Commits: Conventional Commits (`feat:`, `fix:`, `chore:`); small, focused changes.
- PRs: clear description, screenshots for UI, steps to verify; link issues; ensure `assembleDebug` and `lint` pass.

## App Scope & Requirements
- Current: basic skeleton working.
- Goal: Android client for Portainer management with token‑only auth.
- Login: input domain + port and API token; persist securely on disk.
- Post‑login: list Portainer nodes; tapping a node navigates to a detailed stats screen.
- UI: polished visuals with graphs and vibrant colors for node stats.

## Security & Configuration Tips
- Never hardcode tokens/hosts. Use secure storage (e.g., EncryptedSharedPreferences) and runtime injection.
- Prefer HTTPS; avoid `usesCleartextTraffic=true` in production.
- Remove verbose logs and debug tooling in release builds.
