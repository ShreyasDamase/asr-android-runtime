# Play Store release readiness

The first distribution target is the Google Play internal testing track so the maintainer and invited testers can exercise the app before broader release. The current Cyclone build has passed static 16 KB ELF and packaging checks, but the connected Samsung device is 4 KB, so 16 KB runtime validation is still outstanding.

## In this repository

- Runtime sensor controls, lifecycle and camera restart fixes, text/status corrections, tactile controls, and Nothing-style UI milestones are committed through `55f1f8c`.
- App icon asset exists at `app/src/main/ic_launcher-playstore.png`.
- About screen and data-use explanation are implemented.
- About screen includes an optional Buy Me a Coffee link that opens the third-party site outside ASR; the app does not embed checkout or receive payment details. This third-party payment link still needs a Play payments-policy eligibility decision. Privacy wording alone does not establish eligibility; for the lowest review risk, omit the in-app link from a Play-distributed build until confirmed.
- First sensor opt-in now shows an inline disclosure naming live recipients, transport paths, and current lack of transport encryption; the user must accept before enabling sensor outputs. The app's About screen links to the repository privacy policy.
- First-run onboarding explains that sensor permissions are requested when modules are selected; sensitive permissions are not requested as a bundle.
- Camera is declared optional so Play does not filter out devices without camera hardware.
- Release build keeps minification disabled and references JNI-preserving ProGuard rules.
- Release signing can be configured with local properties or environment variables. No release keystore or signing credentials are configured in this checkout.
- The Cyclone flavor currently uses package ID `org.asr.runtime.cyclone` and version `1.0.1-cyclone` (versionCode `2`). Confirm this is the package registered in Play Console before preparing an upload; Play does not allow changing an existing listing's package ID.
- Current Cyclone APK and AAB outputs passed static ELF LOAD alignment checks (65 packaged ARM64 libraries), APK `zipalign -P 16` checks, and AAB `PAGE_ALIGNMENT_16K` inspection. These checks establish artifact alignment only, not device runtime behavior.
- Listing copy and a privacy policy draft are in `PLAY_STORE_LISTING.md` and `PRIVACY_POLICY_DRAFT.md`.
- A static public-facing site is prepared under `website/` with privacy, license, and notices pages. GitHub Pages must be enabled for GitHub Actions, then the site must deploy from `main` before using its URL in Play Console.

## Reported complete

- Maintainer reports build/platform validation on macOS, Ubuntu, and Windows is complete.
- Maintainer reports the Play Console Data Safety form passed. Recheck its saved answers against the final policy and binary before publication.

## Internal testing checklist

- [ ] Confirm the Play Console app package ID is `org.asr.runtime.cyclone` (or select/build the matching flavor).
- [ ] Configure the existing Play upload key locally; verify it matches the key already registered in Play Console. Do not create a replacement key for an existing listing.
- [ ] Increment `versionCode` above `2` and choose the next version name before generating the upload AAB.
- [ ] Build the signed Cyclone release AAB from the reviewed commit and verify its package ID, version, signature, native libraries, and 16 KB packaging alignment.
- [ ] Upload to the Internal testing track, add the maintainer and friend as testers, and share the opt-in link.
- [ ] Run functional checks on the testers' devices, including startup defaults, sensor selection, camera restart, DDS discovery, TCP, and clean Exit App shutdown; capture logs for failures.
- [ ] Run at least one install and runtime check on an actual 16 KB page-size device or a configured 16 KB Android emulator. The currently connected 4 KB Samsung cannot satisfy this item.
- [ ] Review Play Console pre-launch report and resolve any crashes, policy warnings, or device compatibility failures before expanding testing.

## Remaining before production publication

- **Security blocker:** current TCP and inspected DDS transports do not use modern cryptography. Google Play's User Data policy requires modern cryptography when transmitting personal or sensitive data. A clear disclosure is now present, but it does not replace transport security. TCP requires coordinated Android/ASR Studio work; DDS security requires supported secure configurations and native-library validation in both backend variants. Do not publish until this is resolved and verified.
- Create or provide the long-term release keystore, back it up securely, and configure its path and credentials locally. Never commit the keystore or credentials.
- Publish and review the privacy policy at a stable public URL, then link that URL in Play Console and the app.
- Confirm which DDS variant(s) will use the listing and complete the content rating and target audience declarations in Play Console.
- Capture final phone screenshots and prepare the 1024 × 500 feature graphic.
- Complete the remaining store listing and publication steps in Play Console.
- Re-audit and update the Data Safety declaration for each Play package against the actual data and transports using `PLAY_DATA_SAFETY_AUDIT.md`. A prior pass is not a guarantee for a changed package or binary.
- Resolve whether the optional in-app Buy Me a Coffee link qualifies under the current Play payments policy, or remove it from the Play build. Keep the website support link separate from the app's Play payment-policy assessment.
- Complete 16 KB runtime validation on a 16 KB environment and resolve any observed runtime failures before production rollout.

## Signing configuration

Set all four values in the ignored `local.properties` file or as environment variables:

```properties
ASR_RELEASE_STORE_FILE=/absolute/path/to/asr-release.jks
ASR_RELEASE_STORE_PASSWORD=...
ASR_RELEASE_KEY_ALIAS=...
ASR_RELEASE_KEY_PASSWORD=...
```

The environment variable names match the property names. If no values are set, the release variant remains unsigned. If only some values are set or the keystore path does not exist, Gradle stops with a configuration error. `isMinifyEnabled` remains `false` by design; the ProGuard rules are present for JNI safety if minification is intentionally enabled in a future milestone.

## Current 16 KB evidence and limits

The static Cyclone artifact audit is recorded in `ANDROID_16KB_RESEARCH_EXPORT.md`. It passed ELF segment alignment and APK/AAB packaging checks for the built Cyclone artifacts. Fast DDS remains deferred as requested. Do not claim full 16 KB device compatibility until runtime validation succeeds on a genuine 16 KB environment.
