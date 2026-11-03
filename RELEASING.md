# Releasing

For maintainers. A release publishes `com.cluefolk:android-sdk` to Maven Central and tags this repository.

1. Embed the current support screen build (`sdk/src/main/assets/cluefolk-widget`, with its `VERSION`).
2. Set `version` in `sdk/build.gradle.kts` and add the release to `CHANGELOG.md`.
3. Check the build:

   ```sh
   ./gradlew :sdk:assembleRelease :sdk:lintRelease :sdk:testDebugUnitTest :sdk:publishToMavenLocal
   ```

   Dependency lock state and SHA-256 verification metadata are committed. When a
   plugin or dependency changes, regenerate both files with the full task graph,
   review every new checksum against the publisher, and repeat the check from an
   empty Gradle cache on Linux. Never use lenient or disabled verification:

   ```sh
   ./gradlew :sdk:dependencies --write-locks --write-verification-metadata sha256
   ./gradlew :sdk:assembleRelease :sdk:lintRelease :sdk:testDebugUnitTest \
     --write-locks --write-verification-metadata sha256
   ```

4. Credentials live outside the repository, in `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_*` environment variables:
   - `mavenCentralUsername`, `mavenCentralPassword`: a Central Portal user token for the `com.cluefolk` namespace;
   - `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`, or `signingInMemoryKey` with `signingInMemoryKeyPassword`: the release signing key, whose public key is on a public key server.
5. Publish and release:

   ```sh
   ./gradlew publishAndReleaseToMavenCentral
   ```

6. Tag the commit with the version (`0.3.0`, no `v`), push the tag and create a GitHub release with the changelog entry.

Then update the Unity package to the same version.
