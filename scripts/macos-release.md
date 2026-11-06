# Signed macOS releases

The release workflow signs ByteQuay and its bundled native code with a Developer
ID Application certificate. Forge submits the app to Apple and staples its
notarization ticket before making the ZIP and DMG. The workflow also signs,
notarizes, and staples the DMG. Signature, ticket, Gatekeeper, and bundled-runtime
checks must all pass before an installer is uploaded.

Normal development and pull-request builds remain unsigned. Release builds set
`BYTEQUAY_SIGN_RELEASE=1` and fail if signing or notarization credentials are missing.
Existing unsigned downloads must be replaced by a newly built release; enabling
signing does not change files users have already downloaded.

## Repository configuration

Set these GitHub Actions **secrets** on the release repository:

| Secret | Value |
| --- | --- |
| `APPLE_CERTIFICATE_P12_BASE64` | Base64 of a password-protected PKCS#12 export containing the Developer ID Application certificate and its private key |
| `APPLE_CERTIFICATE_PASSWORD` | Password for that export |
| `APPLE_API_KEY_P8` | Contents of the notarization App Store Connect team API key file |

Set these Actions **variables**:

| Variable | Value |
| --- | --- |
| `APPLE_SIGNING_IDENTITY` | Full `Developer ID Application: Name (TEAMID)` identity from `security find-identity -v -p codesigning` |
| `APPLE_TEAM_ID` | Apple Developer team identifier; not the enrollment ID |
| `APPLE_API_KEY_ID` | App Store Connect team API key identifier |
| `APPLE_API_ISSUER` | Issuer UUID for that team API key |

Use an App Store Connect team key with the Developer role for notarization.
Keep the private key and certificate export out of Git and build artifacts.
Store a backup securely; Apple's API private key can only be downloaded once.
The runner creates a temporary keychain, validates notarization credentials
before building, and removes the keychain even if the build fails.

## Verification and publishing

Run **Release → Run workflow** on a branch to test new signing configuration.
It retains verified DMG and ZIP files as an Actions artifact for seven days,
without publishing a GitHub release. A `v*` tag builds and publishes the same
verified formats. The workflow builds for the macOS runner's architecture.

Local checks before pushing:

```bash
cd frontend
npx vitest run forge.signing.test.ts
npx eslint forge.config.ts forge.signing.ts forge.signing.test.ts
npx tsc --noEmit
npm run package
```

`scripts/prepare-macos-signing.sh` imports release credentials and creates a
notarization profile; it expects the workflow's environment variables.
`scripts/verify-macos-release.sh` checks the packaged app and ZIP, then signs and
notarizes the DMG. Its Gatekeeper checks preserve the normal macOS protections.

Java needs JIT/executable-memory entitlements and library-validation exceptions
for JNI libraries extracted by SQLite and JNA. Python needs executable memory
for libffi callbacks. Electron retains osx-sign's standard hardened-runtime
entitlements. Binary data such as JARs is sealed as app resources, not signed
individually through extended attributes.

Before a certificate expires, issue a replacement, update the two certificate
secrets and signing identity, then verify a manual branch build. Revoke a
compromised API key in App Store Connect and replace its secret and identifiers.
