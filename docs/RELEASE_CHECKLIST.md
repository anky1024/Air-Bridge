# AirBridgeTouch — Release Checklist

## Build verification (required)

- [ ] Gradle wrapper jar present (`gradle/wrapper/gradle-wrapper.jar`)
- [ ] `gradlew.bat testDebugUnitTest` passes on Windows CLI
- [ ] `gradlew.bat lint` passes
- [ ] `gradlew.bat assembleDebug` produces a debug APK
- [ ] CI workflow runs green on GitHub Actions and uploads APK artifact
- [ ] `ci-workflow.yml` at repo root removed after `.github/workflows/android-ci.yml` confirmed active

## Physical device verification (required)

- [ ] All tests in `docs/DEVICE_TEST_PLAN.md` executed on Pixel 10a and OPPO A78 5G
- [ ] Cross-direction transfers verified both ways
- [ ] Hash-verified transfer of a ≥100 MB file on both device pairs
- [ ] Documented OEM coexistence behavior for both devices

## Release engineering

- [ ] Version bumped from 0.1.0-dev to 0.1.0 (or appropriate)
- [ ] Release keystore created locally; NEVER committed
- [ ] Signing configured via env vars or git-ignored `keystore.properties`
- [ ] `gradlew.bat bundleRelease` produces an AAB
- [ ] AAB installed and smoke-tested via bundletool or internal testing track
- [ ] ProGuard/R8 release build verified on both test devices
- [ ] `versionCode` incremented for every release

## Store compliance

- [ ] Target SDK 36 confirmed (Play requirement as of Aug 31, 2026)
- [ ] Data safety form completed (no data collected; local transfers only)
- [ ] Permissions justified in listing: Wi-Fi state, Nearby Wi-Fi, notifications
- [ ] Foreground service use declared and justified (dataSync) if kept
- [ ] Privacy policy URL available

## Security review

- [ ] No secrets/keys in repo (verified with a repo history scan)
- [ ] Filename sanitization, size limits, hash verification tested
- [ ] Honest security documentation: link-layer encryption only, no app-layer E2E claim
- [ ] Error messages reviewed: no private file content leakage
