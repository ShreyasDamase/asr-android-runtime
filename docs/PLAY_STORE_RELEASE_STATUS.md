# Play Store release readiness

This branch contains UI, app reliability, and release-preparation changes. Play publication is intentionally deferred until the separate 16 KB page-size work is complete.

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
- Listing copy and a privacy policy draft are in `PLAY_STORE_LISTING.md` and `PRIVACY_POLICY_DRAFT.md`.
- A static public-facing site is prepared under `website/` with privacy, license, and notices pages. GitHub Pages must be enabled for GitHub Actions, then the site must deploy from `main` before using its URL in Play Console.

## Reported complete

- Maintainer reports build/platform validation on macOS, Ubuntu, and Windows is complete.
- Maintainer reports the Play Console Data Safety form passed. Recheck its saved answers against the final policy and binary before publication.

## Still required before publication

- **Security blocker:** current TCP and inspected DDS transports do not use modern cryptography. Google Play's User Data policy requires modern cryptography when transmitting personal or sensitive data. A clear disclosure is now present, but it does not replace transport security. TCP requires coordinated Android/ASR Studio work; DDS security requires supported secure configurations and native-library validation in both backend variants. Do not publish until this is resolved and verified.
- Create or provide the long-term release keystore, back it up securely, and configure its path and credentials locally. Never commit the keystore or credentials.
- Publish and review the privacy policy at a stable public URL, then link that URL in Play Console and the app.
- Confirm which DDS variant(s) will use the listing and complete the content rating and target audience declarations in Play Console.
- Capture final phone screenshots and prepare the 1024 × 500 feature graphic.
- Complete the remaining store listing and publication steps in Play Console.
- Re-audit and update the Data Safety declaration for each Play package against the actual data and transports using `PLAY_DATA_SAFETY_AUDIT.md`. A prior pass is not a guarantee for a changed package or binary.
- Resolve whether the optional in-app Buy Me a Coffee link qualifies under the current Play payments policy, or remove it from the Play build. Keep the website support link separate from the app's Play payment-policy assessment.
- Resolve 16 KB compatibility for every packaged native library in the separate native-library repository, then build and validate a new APK/AAB before upload.

## Signing configuration

Set all four values in the ignored `local.properties` file or as environment variables:

```properties
ASR_RELEASE_STORE_FILE=/absolute/path/to/asr-release.jks
ASR_RELEASE_STORE_PASSWORD=...
ASR_RELEASE_KEY_ALIAS=...
ASR_RELEASE_KEY_PASSWORD=...
```

The environment variable names match the property names. If no values are set, the release variant remains unsigned. If only some values are set or the keystore path does not exist, Gradle stops with a configuration error. `isMinifyEnabled` remains `false` by design; the ProGuard rules are present for JNI safety if minification is intentionally enabled in a future milestone.

## Out of scope here

Rebuilding/replacing ROS 2 and DDS native libraries for 16 KB ELF and APK alignment is deferred to the separate repository, as requested. Do not upload a build from this branch until that work is complete.
