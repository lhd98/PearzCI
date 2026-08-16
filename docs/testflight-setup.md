# TestFlight setup runbook

End-to-end steps to go from *"the Apple ID was just added to the
organization"* to *"the build is on TestFlight and installed on an iPhone"*,
using PearzCI's shared pipeline.

This runbook assumes the shared graph is used
(`pearzUnityPipeline(platform: 'iOS')` or
`pearzUnityAndroidPipeline(mobilePlatform: 'iOS')`), which is the only entry
point that contains the `Upload to TestFlight` stage. The standalone
`pearzUnityIosPipeline()` job uploads to Google Drive only and does not submit
to App Store Connect.

TestFlight is not a cable install. The pipeline's job ends at *uploading the
IPA to App Store Connect*; getting the build onto a phone is done afterwards in
App Store Connect and the TestFlight app (section F). For a direct
cable install to one connected iPhone, use `IOS_BUILD_TO_DEVICE=true` instead;
that mode does not produce an IPA and does not use TestFlight.

## A. Apple Developer Portal (developer.apple.com)

1. **Accept the invite and confirm the role.** Under **People**, the account
   must be **Admin** or **App Manager** — a plain Developer role cannot create a
   distribution certificate or profile.
2. **Get the Team ID.** **Membership** → record the 10-character **Team ID**
   (for example `AB12CD34EF`). Used for `IOS_DEVELOPMENT_TEAM` and the
   `teamID` in `ExportOptions.plist`.
3. **Register the App ID (bundle id).** **Certificates, IDs & Profiles →
   Identifiers → +** → App IDs → enter the exact **bundle id from Unity Project
   Settings** (for example `com.pg.sushi.sort`). Enable the capabilities the app
   needs (In-App Purchase, Push, …).
4. **Create the Apple Distribution certificate.** On the **Mac agent itself**,
   open Keychain Access → *Certificate Assistant → Request a Certificate From a
   Certificate Authority* → save the `.certSigningRequest` (CSR). In the portal:
   **Certificates → + → Apple Distribution** → upload the CSR → download the
   `.cer` → double-click to install it into the **Keychain of the macOS user
   that runs the Jenkins agent**. Doing this on the Mac agent keeps the private
   key in that Keychain.
5. **Create the App Store provisioning profile.** **Profiles → + → App Store**
   → select the App ID from step 3 and the certificate from step 4 → give it a
   clear name and **record that name** (for example `Sushi Sort AppStore`).
   Download the `.mobileprovision` and double-click to install it on the Mac
   agent. This name is `IOS_PROVISIONING_PROFILE_SPECIFIER`.

## B. Mac agent (as the Jenkins user)

1. **Verify the certificate is installed:**

   ```sh
   security find-identity -v -p codesigning
   ```

   The output must include `Apple Distribution: <Org Name> (TEAMID)`.

2. **Create `ExportOptions.plist`** outside the workspace, for example
   `/Users/jenkins/ci/ExportOptions.plist`:

   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
     "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
   <plist version="1.0">
   <dict>
       <key>method</key>
       <string>app-store</string>
       <key>teamID</key>
       <string>AB12CD34EF</string>
       <key>signingStyle</key>
       <string>manual</string>
       <key>provisioningProfiles</key>
       <dict>
           <key>com.pg.sushi.sort</key>
           <string>Sushi Sort AppStore</string>
       </dict>
       <key>uploadSymbols</key>
       <true/>
   </dict>
   </plist>
   ```

   - `method` **must** be `app-store`; a development or ad-hoc export is
     rejected by App Store Connect.
   - The key in `provisioningProfiles` is the **bundle id**; the value is the
     **profile name** from A5.
   - `chmod 600` the file so only the Jenkins user can read it. Do not commit it
     to the game repository.

## C. App Store Connect (appstoreconnect.apple.com)

1. **Create the app record.** **My Apps → + → New App** → iOS platform → pick
   the **Bundle ID** registered in A3 → set a name and SKU. Without an app
   record, the `altool` upload fails even when the IPA is correct.
2. **Create the App Store Connect API key.** **Users and Access → Integrations
   → App Store Connect API → +** → role **App Manager** → **Download** the
   `.p8` file (Apple allows this download only once). Record the **Key ID** and
   the **Issuer ID** shown on that page.

## D. Jenkins

1. **Store the `.p8` as a credential.** **Manage Jenkins → Credentials** → add a
   **Secret file** credential holding the `.p8` → **ID: `appstore-connect-api-key`**
   (the default the pipeline looks for; if you use another ID, set
   `appStoreConnectApiKeyCredentialsId`).
2. **Use the shared graph** in the job's Pipeline script — for example:

   ```groovy
   pearzUnityPipeline(
       // existing Mac / Drive options...
   )
   ```

   (or `pearzUnityAndroidPipeline(mobilePlatform: 'iOS')` — anything except
   `pearzUnityIosPipeline()`).
3. **Fill the build parameters:**

   | Param | Value |
   |---|---|
   | `BUILD_PLATFORM` | `iOS` |
   | `IOS_BUILD_TO_DEVICE` | `false` |
   | `IOS_EXPORT_OPTIONS_PLIST_PATH` | `/Users/jenkins/ci/ExportOptions.plist` |
   | `IOS_DEVELOPMENT_TEAM` | Team ID (A2) |
   | `IOS_PROVISIONING_PROFILE_SPECIFIER` | profile name (A5) |
   | `XCODE_CONFIGURATION` | `Release` |
   | `UPLOAD_TO_TESTFLIGHT` | `true` |
   | `APP_STORE_CONNECT_KEY_ID` | Key ID (C2) |
   | `APP_STORE_CONNECT_ISSUER_ID` | Issuer ID (C2) |
   | `IOS_BUILD_NUMBER` | empty (uses Jenkins `BUILD_NUMBER`) or an increasing number |
   | `APP_VERSION` | **empty** (keeps the Unity project version; if set, must be dotted numbers such as `1.2.3`) |

   Tip: put the stable values (`iosExportOptionsPlistPath`,
   `iosDevelopmentTeam`, `iosProvisioningProfileSpecifier`,
   `appStoreConnectKeyId`, `appStoreConnectIssuerId`) directly in the
   `pearzUnityPipeline(...)` script so they need not be typed on every build; the
   parameters still override per build.

## E. First run and verification

1. Run **Build with Parameters** manually once (before enabling the webhook) so
   you can read the log.
2. Common failure points:
   - **Archive and Export IPA** — usually a certificate / profile / team
     mismatch, or a wrong `method` / `provisioningProfiles` mapping in
     `ExportOptions.plist`. Read `xcodebuild.log`.
   - **Upload to TestFlight** — usually a missing app record (C1), a wrong
     Key ID / Issuer ID, or a `CFBundleVersion` that matches a build already
     uploaded; bump `IOS_BUILD_NUMBER`.
3. On success the Telegram message shows
   `TestFlight: Uploaded; App Store Connect is still processing the build.`
   plus the Drive download link for the IPA.

## F. Getting the build onto an iPhone (App Store Connect, not Jenkins)

1. In App Store Connect open the app's **TestFlight** tab and wait for the build
   to move from *Processing* to ready (5–30 minutes).
2. If prompted for **Export Compliance**, answer it (typically "does not use
   non-exempt encryption"). To stop being asked every time, add
   `ITSAppUsesNonExemptEncryption = false` to the game's `Info.plist`.
3. Assign testers:
   - **Internal testers** (up to 100 people in the org) can install
     immediately, with no review.
   - **External testers** require **Beta App Review** for the first build
     (a few hours to a day).
4. On the iPhone: install the **TestFlight** app from the App Store → open the
   invitation → **Install**. No cable and no UDID are needed; only the invited
   Apple ID.

## Short checklist

```
[ ] Accept invite, role Admin/App Manager, record Team ID
[ ] Register the App ID (bundle id matches Unity)
[ ] Create the Apple Distribution certificate (CSR on the Mac agent) → install in Keychain
[ ] Create the App Store provisioning profile → install on the Mac agent
[ ] Write ExportOptions.plist (method=app-store, teamID, profile mapping)
[ ] Create the app record in App Store Connect
[ ] Create the API key (App Manager), download .p8, record Key ID + Issuer ID
[ ] Jenkins: Secret file credential id = appstore-connect-api-key
[ ] Job uses the shared graph + fill the parameters (table D3)
[ ] Build manually once, read the log, fix any failure
[ ] TestFlight: wait for processing → export compliance → assign testers → install on iPhone
```

## Related documentation

- README sections *Upload to TestFlight* and *Signing setup on the Mac agent*.
- `CHANGELOG.md` for the pipeline version that introduced the TestFlight stage.
