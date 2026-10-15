# Rafolio updates and signing — 0.9.0

## User behavior

Settings → App updates checks the latest public GitHub Release (`rafolio-update.json`). A signed release with a larger version code is offered, showing version, notes and download size. A smaller delta is chosen only for the exact SHA-256 of the currently installed single APK. Otherwise the full size is shown. Delta failure does not silently spend the full download: the user must choose Download full update. The UI displays progress, cancellation, preparation, verification and ready states. Android asks for unknown-source permission when needed and for installation confirmation; silent installation is not promised.

Reading and library access remain offline. Only this updater uses INTERNET. No documents or library metadata are sent. Update requests go to GitHub and its asset CDN using HTTPS with bounded redirects, connect/read timeouts and byte limits. REQUEST_INSTALL_PACKAGES is used only for the user-selected signed APK. The private FileProvider exposes the updates directory, and the app grants read access only to the verified `ready.apk` URI.

The process-scoped downloader survives activity recreation. Cancellation removes partial files; it may take up to the current network read timeout to finish. A complete verified APK and its signed feed survive process restart and are rechecked before use. Partial downloads after process death restart from zero. No background service or automatic unattended installation is used. Split/store installations must use their original store. Google Play distribution requires a separate update mechanism and review of install-permission policy.

## Trust and byte-exact deltas

The embedded X.509 public certificate verifies an RSA SHA-256 signature over the exact metadata payload. The payload binds package, monotonic version, minimum Android version, full APK SHA-256/length, and optional patch hashes/lengths and base APK hashes. Asset origins are restricted to this repository's public release assets. The release certificate SHA-256 is:

`f81569437e31be6e14b0cdd3ce91b0e03c28684418e0a9b9598d13eb30d1d7f3`

RFD1 contains an 8-byte target size and two 32-byte SHA-256 hashes after its 4-byte magic, followed by a gzip command stream. COPY uses an 8-byte base offset and 4-byte length; ADD uses a 4-byte length and literal bytes; END is 127. All integers are big endian. Limits: 300 MiB target/patch, 16 MiB per command, 100,000 commands, valid base ranges and exact target length. Overflow, truncation, extra commands, wrong bases, cancellation and wrong hashes fail closed. The installed APK is only read. ZIP records that match are copied; changed records, ZIP metadata and the complete target APK signing block are emitted exactly. No APK entries are recompressed on the device.

The complete APK hash is authenticated by the signed feed, then package/version and Android-parsed signing certificates are checked against the retained identity before sharing a URI. The Android installer performs platform APK signature enforcement. Build/release packaging also runs Google's `apksigner verify` outside the Android app. Runtime reading does not bundle a desktop signature tool or Office engine.

## Retained key and recovery

RSA-3072 / SHA256withRSA, PKCS12 alias `rafolio-release`, 36,525-day certificate validity. The private recovery archive contains the encrypted keystore **and its password**, so anyone with the archive can sign releases. Keep two private offline copies; never attach it to an issue, commit it, or publish it as a release asset. Only the public PEM certificate belongs in source. Never generate a new key for routine releases.

0.8.0 and earlier used ephemeral debug keys. Android will reject replacing them with this release certificate. The one-time migration requires uninstalling the debug copy and installing the release APK, which clears app-private library copies/settings. Later updates signed with this identity and larger version codes retain those data. Retain original documents before uninstalling.

## Releasing from CI artifacts without sharing the key

1. Require passing JVM/lint/Android CI on the exact source commit. Download `rafolio-unsigned-release` from that run.
2. Sign locally with the retained PKCS12 key, using the official Android build-tools `apksigner.jar`; use password files/environment secrets rather than command-line plaintext:

```sh
java -jar /path/to/apksigner.jar sign --ks /private/rafolio-release.p12 \
  --ks-key-alias rafolio-release --ks-pass file:/private/store-password.txt \
  --v4-signing-enabled false \
  --out /output/Rafolio-0.9.0.apk /input/app-release-unsigned.apk
java -jar /path/to/apksigner.jar verify --verbose --print-certs /output/Rafolio-0.9.0.apk
```

3. Run `tools/package_release.py` with that signed APK, its exact version code/name, output directory, apksigner JAR, keystore and password-file paths. Add `--previous /path/to/previous-signed.apk` for retained stable releases. It verifies certificate/v2 signatures, reconstructs every delta and compares bytes, then signs the update feed with the same retained identity.
4. Publish only the output APK, `.rfd` patches and `rafolio-update.json` as GitHub Release assets under `v<versionName>` at the tested commit. Keep prior full APKs published so they can supply bases. Never publish private-signing, recovery files or password files. Releases must use increasing Android version codes. Update the Gradle version for the next release.

CI produces unsigned release artifacts deliberately. It never creates replacement release keys and does not need access to the retained private key. Future publishing can be automated using protected GitHub secrets after the owner authorizes storing the private signing material there; until then signing occurs locally. This does not prevent installed release builds from checking/downloading published updates automatically through the in-app flow.
