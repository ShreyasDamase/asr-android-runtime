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
- Both flavors now use package ID `org.asr.runtime`; the Cyclone version name is `1.0.1-cyclone` (versionCode `2`). Build and upload the Cyclone flavor for the intended Play listing. Play fixes a new listing's package ID at its first app-bundle upload.
- Current Cyclone APK and AAB outputs passed static ELF LOAD alignment checks (65 packaged ARM64 libraries), APK `zipalign -P 16` checks, and AAB `PAGE_ALIGNMENT_16K` inspection. These checks establish artifact alignment only, not device runtime behavior.
- **AAB currently available:** `app/build/outputs/bundle/cycloneRelease/app-cyclone-release.aab` (about 11 MB), but it is **unsigned** and is not the Play upload artifact yet.
- **APK currently available:** `app/build/outputs/apk/cyclone/release/app-cyclone-release-unsigned.apk` (about 21 MB), also unsigned. For Play distribution, use the signed AAB; keep debug APKs for local development only.
- **Store icon:** `app/src/main/ic_launcher-playstore.png` is 512 × 512 PNG, the required Play icon dimensions. Recheck the file-size and icon-content rules in Console before submission.
- **Not prepared yet:** phone screenshots and the 1024 × 500 feature graphic. No Play screenshot/feature-graphic assets are currently in this repository.
- Listing copy and a privacy policy draft are in `PLAY_STORE_LISTING.md` and `PRIVACY_POLICY_DRAFT.md`.
- A static public-facing site is prepared under `website/` with privacy, license, and notices pages. GitHub Pages must be enabled for GitHub Actions, then the site must deploy from `main` before using its URL in Play Console.

## Reported complete

- Maintainer reports build/platform validation on macOS, Ubuntu, and Windows is complete.
- Maintainer reports the Play Console Data Safety form passed. Recheck its saved answers against the final policy and binary before publication.

## Internal testing checklist

- [x] Package ID selected: `org.asr.runtime`. Build and upload the Cyclone flavor for this listing.
- [ ] Configure the existing Play upload key locally; verify it matches the key already registered in Play Console. Do not create a replacement key for an existing listing.
- [ ] Increment `versionCode` above `2` and choose the next version name before generating the upload AAB.
- [ ] Build the signed Cyclone release AAB from the reviewed commit and verify its package ID, version, signature, native libraries, and 16 KB packaging alignment.
- [ ] Upload to the Internal testing track, add the maintainer and friend as testers, and share the opt-in link.
- [ ] Run functional checks on the testers' devices, including startup defaults, sensor selection, camera restart, DDS discovery, TCP, and clean Exit App shutdown; capture logs for failures.
- [ ] Run at least one install and runtime check on an actual 16 KB page-size device or a configured 16 KB Android emulator. The currently connected 4 KB Samsung cannot satisfy this item.
- [ ] Review Play Console pre-launch report and resolve any crashes, policy warnings, or device compatibility failures before expanding testing.

## Play Console forms and proposed answers to review

These are a preparation guide based on the current app, not pre-filled legal declarations. Answer for the exact binary and the actual destinations you configure.

- [ ] **App category:** `Tools` is the best fit for a ROS 2 sensor and connectivity utility. `Education` would fit only if the app's main purpose changes to delivering educational lessons or courses.
- [ ] **Target audience / age groups:** ASR is aimed at robotics developers and makers, not designed for children. Select only the age ranges you truly intend to support. If selecting any child age group, review the Families requirements first. Complete the content-rating questionnaire; don't infer a rating from the audience choice.
- [ ] **Contains ads:** declare `No` if the release has no ad content or advertising SDKs.
- [ ] **App access:** declare that no account or sign-in is required, if still true. Give the reviewer concise steps for starting the runtime and explain that ROS 2 peers are optional for inspecting local UI. Do not provide credentials that the app does not use.
- [ ] **Data Safety:** complete/recheck every data type and sharing answer against `PLAY_DATA_SAFETY_AUDIT.md` and the signed AAB. In particular account for user-selected camera, microphone, GPS, motion/environment sensor streams, device/capability handshake, and user-configured ROS 2 / ASR Studio destinations. Do not describe the transfers as encrypted: current TCP is plain TCP and DDS Security is not enabled.
- [ ] **Privacy policy:** publish the reviewed policy at a public HTTPS URL and enter the same URL in Play Console. Confirm it opens without sign-in, is not a PDF, identifies ASR/developer and contact method, and explains data use, destinations, security, retention, and deletion. The policy must agree with the in-app disclosure and Data Safety answers.
- [ ] **Content rating (IARC):** answer the questionnaire accurately for app content and functionality. No entertainment or user-generated content is evident in ASR; confirm in the Console questionnaire.
- [ ] **Health apps declaration:** complete it even if the answer is that there are no health features. Based on the current robotics purpose, the expected answer is no health functionality; confirm that sensor readings are not presented for health, fitness, diagnosis, or treatment.
- [ ] **Financial features declaration:** complete it even if the answer is no financial features. ASR does not provide banking, loans, investing, wallets, or transfers. Separately review whether the external Buy Me a Coffee link is permitted for this Play build and the target markets; if uncertain, remove that link from the Play build while keeping the website link.
- [ ] **Government apps / government information:** if Console shows the declaration, answer no government affiliation or official government information if that remains true. Do not imply government endorsement. No government authorization certificate should be needed for an independent robotics tool unless the app later represents or distributes government information.
- [ ] **Education, medical, financial-service, and other regulated certificates:** the current app is a robotics tool, not an educational credential, medical device, financial service, or government app. No such certificate is identified as applicable from the current functionality. If Console flags a declaration, inspect the exact prompt and do not claim a certificate you do not hold.
- [ ] **Permissions and foreground services:** review the permissions listed from the final AAB. Ensure each sensor permission is optional and explained in context; complete any Console permission/foreground-service declaration that is actually presented.
- [ ] **Developer account details:** make sure account identity, public developer name/contact details, and personal-versus-organization account type are accurate and verified. Check whether this account has any additional testing requirement before production access.

## Store listing assets and copy checklist

- [x] App icon: 512 × 512 PNG exists at `app/src/main/ic_launcher-playstore.png`.
- [x] Short and full description draft: `PLAY_STORE_LISTING.md`.
- [ ] Final app title, category, contact email, and public support/website links in Play Console.
- [ ] At least two accurate phone screenshots. Capture the released app UI (suggested set: home/connectivity, sensor selection expanded, and one each of light and dark mode). Play accepts JPEG or 24-bit PNG screenshots from 320 to 3840 px; keep the longest side no more than twice the shortest.
- [ ] Feature graphic: JPEG or 24-bit PNG, 1024 × 500 px, no alpha channel. Show ASR's real robotics use; avoid misleading claims and text that may be cropped.
- [ ] Add tablet/large-screen screenshots only if those device types are part of the supported experience and the listing calls for them.
- [ ] Review all text and images for accurate functionality, permission behavior, supported DDS flavor, privacy statements, and no promises of encryption or compatibility that the build has not demonstrated.

Official references: [Play preview assets](https://support.google.com/googleplay/android-developer/answer/9866151), [User Data and privacy policy](https://support.google.com/googleplay/android-developer/answer/10144311), [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469), [Prepare app for review](https://support.google.com/googleplay/android-developer/answer/9859455), [Health apps declaration](https://support.google.com/googleplay/android-developer/answer/14738291), [Financial features declaration](https://support.google.com/googleplay/android-developer/answer/13849271), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [16 KB technical requirement](https://support.google.com/googleplay/android-developer/answer/17492799).

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

Keep the upload keystore backed up in encrypted storage and its passwords in a password manager such as macOS Keychain. Do not put passwords in a plain Documents text file, the repository, screenshots, or chat. The Play app-signing key and the developer's upload key are separate; confirm the upload certificate in Play Console before signing an AAB. The keystore and credentials were not present in this checkout, so no credential file was copied or created.

## Current 16 KB evidence and limits

The static Cyclone artifact audit is recorded in `ANDROID_16KB_RESEARCH_EXPORT.md`. It passed ELF segment alignment and APK/AAB packaging checks for the built Cyclone artifacts. Fast DDS remains deferred as requested. Do not claim full 16 KB device compatibility until runtime validation succeeds on a genuine 16 KB environment.
