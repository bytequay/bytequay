# Releasing ByteQuay

This file documents how a release moves from a `git tag` to an
installer on a user's Mac. It covers the current workflow (signed-yet
or not), the Gatekeeper / quarantine model that decides what users
see on first launch, and the planned auto-update path.

---

## The release flow at a glance

| Stage             | Today                                        | Future (signed builds)                           |
|-------------------|----------------------------------------------|--------------------------------------------------|
| Build the DMG     | GitHub Actions on tag push (`release.yml`)   | Same workflow — adds signing + notarization steps |
| Hosting           | GitHub Releases asset                        | Same                                              |
| User installs     | Download → `xattr` workaround OR right-click → Open | Standard "downloaded from internet" prompt → Open |
| User upgrades     | Download next DMG manually                   | `update-electron-app` polls Releases, applies in background |

---

## Cutting a release (manual + automated paths)

### Versioning

Three places carry the version number — keep them in sync before tagging.

| File                                              | Field                            |
|---------------------------------------------------|----------------------------------|
| `frontend/src/main.ts`                            | `APP_VERSION`                    |
| `frontend/package.json`                           | `version`                        |
| `backend/pom.xml`                                 | top-level `<version>`            |

A small follow-up worth doing: add a CI check that compares the three
and fails if they disagree (one-line bash + xq/jq).

### Tag + push

```bash
# bump the three version fields, commit, push to main first
git push origin main

# then tag and push the tag
git tag v0.1.0 -m "First public release"
git push origin v0.1.0
```

### What happens automatically

The `release.yml` workflow at `.github/workflows/release.yml` triggers on
any tag matching `v*`:

1. Spins up a `macos-latest` runner.
2. Installs JDK 21 + Node 20.
3. Runs `npm ci` then `npm run make`. The `generateAssets` Forge hook
   builds the Spring Boot JAR via `mvn -DskipTests package` if it's
   missing.
4. Uploads every `.dmg` and `.zip` in `frontend/out/make/` to the
   matching GitHub Release.
5. Marks the release as a pre-release if the tag contains a `-`
   (e.g. `v0.2.0-rc1`).

You only have to fill in the release notes on github.com.

### Manual fallback (no CI)

If the workflow is broken or you want to ship from your local checkout:

```bash
cd frontend && npm run make
# → drops files in frontend/out/make/
```

Then on github.com → **Releases → Draft a new release**, pick the tag,
drag the DMG/ZIP into the assets dropzone, publish.

---

## Why first launch is silent on your machine but not for users

macOS Gatekeeper inspects only files that carry the
`com.apple.quarantine` extended attribute. That attribute is set by
**the thing that downloaded the file** — Safari, Chrome, AirDrop,
Mail. Locally built files don't have it, so a `npm run make` install
opens silently no matter what.

Verify the difference:

```bash
xattr frontend/out/make/.../ByteQuay-0.1.0-arm64.dmg
# → (no output — locally built, no quarantine)

xattr ~/Downloads/ByteQuay-0.1.0-arm64.dmg
# → com.apple.quarantine  (downloaded via browser)
```

To preview what an end user actually sees without round-tripping
through a real release, simulate the quarantine attribute on a local
build:

```bash
xattr -w com.apple.quarantine "0181;0;Safari;" \
  frontend/out/make/.../ByteQuay-0.1.0-arm64.dmg
open frontend/out/make/.../ByteQuay-0.1.0-arm64.dmg
```

---

## What users see at each signing level

| State                                  | First-launch dialog                                                                | Has an "Open" button? |
|----------------------------------------|-----------------------------------------------------------------------------------|----------------------|
| Locally built, no quarantine attribute | _nothing_ — opens silently                                                        | n/a                  |
| Unsigned (today's GitHub release path) | "ByteQuay can't be opened because the developer cannot be verified."              | ❌ no — must right-click → Open |
| Signed but not notarized               | "macOS cannot verify ByteQuay is free of malware."                                | ✅ yes               |
| Signed + notarized                     | "ByteQuay is an app downloaded from the Internet. Are you sure you want to open it?" | ✅ yes               |
| App Store                              | _nothing_                                                                          | n/a                  |

Until ByteQuay is signed + notarized, ship with this README snippet so
users aren't lost:

```
First-time install on macOS:
  1. Drag ByteQuay.app to /Applications.
  2. Right-click the app, choose Open, then Open in the dialog.
     (Gatekeeper blocks unsigned apps on first launch; this only
     happens once.)
```

---

## Path forward: code signing + notarization

To move from "right-click → Open" to the soft download prompt, the
work is bounded but not trivial:

1. **Enrol in Apple Developer Program** — $99/year. Issues a
   "Developer ID Application" certificate.
2. **Download + import the cert** into your Mac's Keychain.
3. **Add `osxSign` to `forge.config.ts`**:
   ```ts
   packagerConfig: {
     // …
     osxSign: { identity: 'Developer ID Application: Jian Chen (TEAMID)' },
     osxNotarize: { keychainProfile: 'bytequay-notary' },
   }
   ```
4. **Set up `xcrun notarytool store-credentials bytequay-notary`** with
   your Apple ID + an app-specific password + your team ID.
5. **For CI signing**, base64-encode the `.p12` certificate and store
   it as a GitHub secret (`MACOS_CERTIFICATE`); add an import step at
   the top of `release.yml` that loads it into a temporary keychain.
   Notary credentials become three more secrets (`APPLE_ID`,
   `APPLE_APP_SPECIFIC_PASSWORD`, `APPLE_TEAM_ID`).

A full half-day of plumbing, then permanently solved.

---

## Path forward: auto-update for installed users — TODO

`update-electron-app` reads GitHub Releases as its update server out
of the box. Once builds are signed (auto-update on macOS requires
signed binaries — Gatekeeper rejects unsigned background updates):

```bash
npm i update-electron-app
```

```ts
// frontend/src/main.ts, after app.whenReady()
import { updateElectronApp } from 'update-electron-app';
updateElectronApp({ repo: 'chenjian2664/bytequay', updateInterval: '1 hour' });
```

It polls every interval, downloads new DMGs/ZIPs in the background,
and prompts the user to restart when ready. Free, zero infra beyond
the GitHub Releases we're already using.

**Blocker**: signing must land first. **Effort once unblocked**: under
an hour to wire + verify.

---

## Universal binaries (Intel + Apple Silicon)

The DMG produced today is `arm64`-only. Intel Macs (and Rosetta
fallbacks) get an "app not supported on this Mac" dialog. Two
options when there's actual demand:

- **Two DMGs** — keep the current `arm64` build, add a second
  `forge.config.ts` matrix entry for `x64`. Workflow uploads both.
  Users pick the right one.
- **Universal binary** — add `arch: 'universal'` to `packagerConfig`.
  One DMG that runs everywhere, but ~70MB heavier on download.

Defer until someone asks. The app is targeted at developers, who
overwhelmingly run Apple Silicon by now.

---

## Tag-protection rules (when there's > 1 maintainer)

Tag pushes today only require repo Write access — for a solo project
that's just the owner. Once collaborators land, harden:

- **Settings → Tags → Add tag protection rule → pattern `v*`**, restrict
  who can create matching tags.
- **Settings → Actions → require approval** for first-time contributors
  so a tag push from a new collaborator pauses for review before
  spending macOS-runner minutes.

Not needed for ByteQuay today. Bookmark for later.
