<p align="center">
  <img src="assets/repo-banner.png" alt="kpal" width="720" />
</p>

## Publishing

This repository ships its Maven Central release flow through a dedicated Gradle publishing build under `publishing/` that reads the existing Amper module sources.

Release tags use the format `v<version>`, for example `v0.1.0`.

Required GitHub secrets:

- `SONATYPE_B64`: Base64 of `username:password` for your Sonatype Central user token.
- `GPG_PRIVATE_KEY`: ASCII-armored private key used to sign release artifacts.
- `GPG_PASSPHRASE`: Passphrase for `GPG_PRIVATE_KEY`.

Local bundle generation:

```bash
cd publishing
./gradlew prepareCentralBundle -PreleaseVersion=0.1.0
```

The signed upload bundle is written to `publishing/build/central-bundle/central-bundle.zip`.

The current default POM license metadata is `Proprietary`. If you want an open-source release, set `pomLicenseName` and `pomLicenseUrl` in `~/.gradle/gradle.properties` or pass them with `-P...` when publishing.
