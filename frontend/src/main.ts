/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { app, BrowserWindow, clipboard, dialog, ipcMain, Menu, nativeImage, session, shell, WebContentsView } from 'electron';
import path from 'node:path';
import fs from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
import started from 'electron-squirrel-startup';
import { BACKEND_BASE, killBackend, spawnBackend, waitForBackendReady } from './backendProcess';
import { registerTaskStreamIpc } from './taskStreamBridge';

// Override the menu-bar / About-box / dock display name. Without this
// Electron uses its own name in dev mode (the packaged build picks
// this up from forge.config.ts -> packagerConfig.name).
app.setName('ByteQuay');

// Register the bytequay:// custom URL scheme so the OS sends OAuth
// redirects (GitHub, future integrations) back to our running app.
// `open-url` (macOS) / second-instance args (Win/Linux) carry the
// inbound URL once registered. The packaged build also declares this
// in Info.plist; the runtime call covers the dev workflow where the
// .app bundle isn't installed.
const APP_PROTOCOL = 'bytequay';
app.setAsDefaultProtocolClient(APP_PROTOCOL);

/** Pre-1.0 version surfaced in the About dialog and packaged metadata.
 *  Bump alongside frontend/package.json + backend/pom.xml when we cut
 *  a release. Shown in the About panel as e.g. "ByteQuay 0.1.0". */
const APP_VERSION = '0.1.0';

// Handle creating/removing shortcuts on Windows when installing/uninstalling.
if (started) {
  app.quit();
}

/** macOS-only: replace the default Electron dock icon with the project
 *  logo while running under `dev.sh` / `npm start`. The packaged build
 *  picks up the icon from forge.config.ts; this hook only matters for
 *  the dev workflow where Electron Forge spawns Electron directly and
 *  the .app bundle's icon isn't in play. Best-effort — silently skips
 *  if the asset isn't found (e.g. when running unpackaged from a
 *  context that doesn't include /assets). */
/** Resolve the on-disk path to the app icon. Tries several layouts
 *  because __dirname differs between the Vite-built dev path
 *  (frontend/.vite/build/main.js) and the packaged .app bundle
 *  (Resources/app.asar/...). Prefers .icns on macOS — the system
 *  About panel renders it crisper than a PNG — and falls back to
 *  .png. Logs the chosen path so missing-icon issues are visible
 *  in the dev console without a debugger. */
function resolveIconPath(): string | null {
  const filename = process.platform === 'darwin' ? 'icon.icns' : 'icon.png';
  const fallback = 'icon.png';
  const roots = [
    path.join(__dirname, '..', '..', '..', 'build'),
    path.join(__dirname, '..', '..', 'build'),
    path.join(process.cwd(), 'build'),
    path.join(process.cwd(), '..', 'build'),
    path.join(app.getAppPath(), 'build'),
    path.join(app.getAppPath(), '..', 'build'),
  ];
  for (const root of roots) {
    for (const name of [filename, fallback]) {
      const candidate = path.join(root, name);
      if (fs.existsSync(candidate)) {
        // eslint-disable-next-line no-console
        console.log('[ByteQuay] resolved app icon:', candidate);
        return candidate;
      }
    }
  }
  // eslint-disable-next-line no-console
  console.warn('[ByteQuay] could not find app icon; About panel and dock will fall back to Electron defaults.');
  return null;
}

function applyDevDockIcon(): void {
  // Run on macOS even when packaged: the .app's CFBundleIconFile is
  // already correct in production, but calling app.dock.setIcon also
  // updates [NSApp applicationIconImage], which is what the system
  // About panel reads on screen. Without that explicit set, the About
  // panel can render Electron's atom even when iconPath is configured.
  if (process.platform !== 'darwin' || !app.dock) {
    return;
  }
  const iconPath = resolveIconPath();
  if (!iconPath) return;
  const image = nativeImage.createFromPath(iconPath);
  if (!image.isEmpty()) {
    app.dock.setIcon(image);
  }
}

/** macOS only: replace Electron's default application menu with one
 *  whose first sub-menu is titled "ByteQuay". macOS picks the menu
 *  bar's app-name slot from the FIRST submenu's role / label, not
 *  from app.setName() — so without this the bar still reads
 *  "Electron" in dev mode even though every "About / Hide / Quit X"
 *  string is correct. The packaged .app gets its name from the
 *  bundle so this is mostly a dev-mode quality-of-life fix. */
function installApplicationMenu(): void {
  if (process.platform !== 'darwin') {
    return;
  }
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: 'ByteQuay',
      submenu: [
        { role: 'about' },
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' },
      ],
    },
    { role: 'editMenu' },
    { role: 'viewMenu' },
    { role: 'windowMenu' },
    {
      role: 'help',
      submenu: [
        {
          label: 'Open project on GitHub',
          click: () => { void shell.openExternal('https://github.com/chenjian2664/bytequay'); },
        },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

/** Populates the data the system About-this-app panel renders. The
 *  panel itself is shown by the { role: 'about' } menu item. iconPath
 *  must point at a real PNG/ICNS on disk — Electron loads it lazily
 *  when the user opens the panel. */
function configureAboutPanel(): void {
  const opts: Electron.AboutPanelOptionsOptions = {
    applicationName: 'ByteQuay',
    applicationVersion: APP_VERSION,
    version: 'pre-1.0',
    copyright: '© 2026 Jian Chen — Apache License 2.0',
    website: 'https://github.com/chenjian2664/bytequay',
    credits: 'A native macOS desktop app for daily developer review work.',
  };
  const iconPath = resolveIconPath();
  if (iconPath) {
    opts.iconPath = iconPath;
  }
  app.setAboutPanelOptions(opts);
}

function normalizeDevServerUrl(urlString: string): string {
  const url = new URL(urlString);
  if (url.hostname === 'localhost') {
    url.hostname = '127.0.0.1';
  }
  return url.toString();
}

const MAIN_BG = '#f4efe5';

/** Extracts the human-friendly message field from a Spring error body
 *  ({@code {"timestamp":..., "status":..., "message":..., "path":...}})
 *  so the renderer surfaces "wrong remote: …" instead of a JSON dump.
 *  Falls back to the raw body when parsing fails. */
function extractMessage(body: string): string {
  if (!body) return '';
  try {
    const parsed = JSON.parse(body) as { message?: string };
    return parsed.message ?? body;
  } catch {
    return body;
  }
}

let mainWindow: BrowserWindow | null = null;
// Embedded github.com review view overlaid on the main window's content area.
// Only one at a time — we swap it out when a new PR is opened for review.
let reviewView: WebContentsView | null = null;
// Generic in-app browser overlay for any URL — used when the user clicks
// an external link in the React UI. Single instance: opening a new URL
// replaces whatever's currently mounted. The user closes it via the
// toolbar's × button, which IPCs `inapp:unmount` and we tear it down.
let inappView: WebContentsView | null = null;

// Third-party auth hosts that refuse to work in embedded browsers. Google has
// enforced this since 2021 on all OAuth flows, regardless of user agent — we
// can't spoof our way around it. Intercept the redirect early and surface a
// banner in the UI so the user knows to switch to GitHub password auth.
const BLOCKED_AUTH_HOSTS: { host: string; provider: string }[] = [
  { host: 'accounts.google.com', provider: 'Google' },
  { host: 'login.microsoftonline.com', provider: 'Microsoft' },
  { host: 'appleid.apple.com', provider: 'Apple' },
];

function matchBlockedAuthHost(url: string): string | null {
  try {
    const host = new URL(url).hostname.toLowerCase();
    for (const { host: h, provider } of BLOCKED_AUTH_HOSTS) {
      if (host === h || host.endsWith('.' + h)) return provider;
    }
  } catch { /* invalid URL */ }
  return null;
}

// GitHub routes that mean the user is in the middle of signing in. We show a
// proactive banner on these so the user doesn't pick passkey and then sit on
// a hung "Waiting for input from browser interaction..." screen — Electron
// can't drive macOS's platform authenticator, so the passkey prompt will
// never resolve.
function isGithubSignInUrl(url: string): boolean {
  try {
    const u = new URL(url);
    if (u.hostname !== 'github.com') return false;
    const p = u.pathname;
    return p === '/login'
      || p.startsWith('/session')
      || p.startsWith('/account_verifications')
      || p.startsWith('/sso')
      || p.startsWith('/login/oauth')
      || p.startsWith('/two-factor')
      || p.startsWith('/webauthn');
  } catch { return false; }
}

function destroyReviewView(): void {
  if (!reviewView) return;
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.contentView.removeChildView(reviewView);
  }
  // Closing the webContents releases the tab's resources.
  const wc = reviewView.webContents;
  if (wc && !wc.isDestroyed()) {
    wc.close();
  }
  reviewView = null;
}

function destroyInappView(): void {
  if (!inappView) return;
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.contentView.removeChildView(inappView);
  }
  const wc = inappView.webContents;
  if (wc && !wc.isDestroyed()) {
    wc.close();
  }
  inappView = null;
}

/**
 * Spawns a native Electron BrowserWindow loading {@code url}. Shares
 * the in-app-browser cookie partition so logged-in state matches the
 * overlay. Window-open from inside spawns another popup so the user
 * can keep multiple pages open without ever bouncing back through the
 * overlay path.
 */
function openInPopupWindow(url: string): void {
  if (!/^https?:/i.test(url)) return;
  const popup = new BrowserWindow({
    width: 1100,
    height: 720,
    backgroundColor: '#ffffff',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      partition: 'persist:inapp-browser',
    },
  });
  popup.webContents.setUserAgent(
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
    '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
  );
  popup.webContents.setWindowOpenHandler(({ url: nestedUrl }) => {
    if (/^https?:/i.test(nestedUrl)) openInPopupWindow(nestedUrl);
    return { action: 'deny' };
  });
  void popup.loadURL(url);
}

const createWindow = async () => {
  mainWindow = new BrowserWindow({
    width: 1100,
    height: 720,
    backgroundColor: MAIN_BG,
    // Hide the native macOS title bar but keep the traffic-light buttons,
    // inset into the top-left of the window. Lets our GlobalTopbar be the
    // single nav row instead of sitting under a redundant "ByteQuay"
    // title strip. Y is tuned to sit roughly centered against the 44px
    // .global-topbar height — see frontend/src/css/base.css.
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 14, y: 14 },
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.on('closed', () => {
    destroyReviewView();
    destroyInappView();
    mainWindow = null;
  });

  // Fullscreen state → renderer. When macOS native fullscreen hides
  // the inset traffic lights, the topbar's 78px reserve looks like an
  // empty gap; the renderer fills it with a brand mark only while
  // fullscreen. We push state on transition (and on did-finish-load
  // below) so the React state stays in sync with the OS window.
  const sendFullScreenState = (isFullScreen: boolean) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('window:fullscreen-state', { isFullScreen });
    }
  };
  mainWindow.on('enter-full-screen', () => sendFullScreenState(true));
  mainWindow.on('leave-full-screen', () => sendFullScreenState(false));
  mainWindow.webContents.on('did-finish-load', () => {
    if (mainWindow) sendFullScreenState(mainWindow.isFullScreen());
  });
  // The `window:get-fullscreen` IPC handler that pairs with the
  // did-finish-load push above (renderer pulls the truth on mount to
  // recover from the race) is registered once in `registerIpc()` — it
  // can't live here because `createWindow` runs again on macOS
  // `activate` after the window is closed, and `ipcMain.handle`
  // throws on a second registration of the same channel.

  // Route every external link into the in-app browser overlay rather
  // than letting Electron spawn a child window or replace the main
  // window's React UI. The renderer subscribes to `inapp:open-request`
  // and mounts an InAppBrowser overlay with ←/→/× chrome — gives the
  // user a clear path back to the app via the × close button.
  const requestInAppOpen = (url: string) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('inapp:open-request', { url });
    }
  };
  mainWindow.webContents.setWindowOpenHandler(({ url, disposition }) => {
    if (!/^https?:/i.test(url)) return { action: 'deny' };
    // Cmd-click (mac) / Ctrl-click (linux/win) → spawn a native popup
    // window directly, skipping the in-app overlay. Disposition surfaces
    // these modifier intents as 'foreground-tab' / 'background-tab' /
    // 'new-window'. Plain clicks go through the overlay path.
    if (disposition === 'foreground-tab'
        || disposition === 'background-tab'
        || disposition === 'new-window') {
      void openInPopupWindow(url);
    } else {
      requestInAppOpen(url);
    }
    return { action: 'deny' };
  });
  // Origin of the app's own renderer — `http://localhost:<port>` in dev
  // (Vite served), undefined in packaged builds (renderer is loaded from
  // file:// which doesn't match the http(s) regex below anyway). Used to
  // distinguish self-navigations (e.g. Vite HMR triggering a reload after
  // a long idle / sleep wake) from real external link clicks. Without
  // this guard the will-navigate handler would intercept Vite's recovery
  // reload and pop an InAppBrowser loading the app inside itself —
  // see docs/mockups/issue/long-running-page.png.
  const appOrigin = (() => {
    if (!MAIN_WINDOW_VITE_DEV_SERVER_URL) return null;
    try {
      return new URL(normalizeDevServerUrl(MAIN_WINDOW_VITE_DEV_SERVER_URL)).origin;
    }
    catch {
      return null;
    }
  })();
  // Internal-link scheme used by enriched email bodies — see
  // EmailHtmlEnricher. The iframe's <base target="_top"> turns the
  // anchor click into a top-frame navigation that we intercept and
  // forward to the renderer over IPC instead of letting it actually
  // navigate.
  const handleBytequayNav = (raw: string) => {
    let parsed: URL;
    try {
      parsed = new URL(raw);
    }
    catch {
      return;
    }
    // host = action (URL.host lowercases). The action is
    // "pr-diff" today; new actions go on the same channel.
    const action = parsed.host;
    const params: Record<string, string> = {};
    parsed.searchParams.forEach((value, key) => { params[key] = value; });
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('app:nav-request', { action, params });
    }
  };
  // Belt-and-braces for same-window navigation: a plain <a href> click
  // (or middle-click that bypasses target=_blank) used to navigate the
  // main window itself off to the external page, replacing the React UI.
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (url.startsWith('bytequay://')) {
      event.preventDefault();
      handleBytequayNav(url);
      return;
    }
    if (!/^https?:/i.test(url)) return;
    // Skip navigations back to the app's own origin (Vite HMR reload,
    // dev-server reconnect after sleep, etc.). Otherwise we'd hijack
    // them into an in-app overlay showing the app inside itself.
    if (appOrigin && url.startsWith(appOrigin)) return;
    event.preventDefault();
    requestInAppOpen(url);
  });
  // Tear down any overlay WebContentsView whenever the renderer
  // (re)loads. The overlays live on mainWindow.contentView at the
  // main-process level, so they survive a Cmd-R / Ctrl-R refresh
  // unless we explicitly remove them here — without this, refreshing
  // the PR detail page left the embedded github.com tab stranded on
  // top of the home page with no close button (see
  // docs/mockups/issue/pr-details/refresh-bad-home.png). The initial
  // page load also fires this event but both destroy* helpers are
  // no-ops when the views are null, so it's safe.
  mainWindow.webContents.on('did-start-loading', () => {
    destroyReviewView();
    destroyInappView();
  });

  if (MAIN_WINDOW_VITE_DEV_SERVER_URL) {
    await mainWindow.loadURL(normalizeDevServerUrl(MAIN_WINDOW_VITE_DEV_SERVER_URL));
  } else {
    await mainWindow.loadFile(
      path.join(__dirname, `../renderer/${MAIN_WINDOW_VITE_NAME}/index.html`),
    );
  }
};

function registerIpc(): void {
  // Synchronous pull of the current window's fullscreen state — the
  // renderer queries this on mount to recover from a race with the
  // did-finish-load push from `createWindow`. Registered here (not
  // inside `createWindow`) because `createWindow` runs again on the
  // macOS `activate` event after the window is closed, and
  // `ipcMain.handle` throws on a second registration.
  ipcMain.handle('window:get-fullscreen', () => {
    return mainWindow ? mainWindow.isFullScreen() : false;
  });

  // SSE broker for per-task live event streams. Renderer subscribes via
  // window.bridge.subscribeTaskStream(); main opens the upstream SSE
  // connection and forwards parsed events. Replaces the 1s poll while
  // the page is RUNNING.
  registerTaskStreamIpc(() => mainWindow);

  // Backend is the single source of truth for the GitHub PAT. These handlers
  // proxy to /api/credentials with the singleton (ACCOUNT, "github") slot.
  // App.tsx caches the existence check via its `status` state; it explicitly
  // re-flips that state after upsert/delete so no client-side cache lives
  // here — every call hits the backend.
  ipcMain.handle('pat:save', async (_event, pat: string) => {
    if (typeof pat !== 'string' || pat.trim().length === 0) {
      throw new Error('PAT must be a non-empty string');
    }
    const res = await fetch(`${BACKEND_BASE}/api/credentials`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'ACCOUNT', name: 'github', value: pat.trim(), label: null, notes: null }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Save GitHub PAT failed (${res.status}): ${body}`);
    }
    // Kick off an immediate sync so the user sees their PRs without waiting
    // for the next scheduler tick.
    void fetch(`${BACKEND_BASE}/api/settings/sync/trigger`, { method: 'POST' }).catch(() => { /* best-effort */ });
    return true;
  });

  ipcMain.handle('pat:has', async () => {
    try {
      const res = await fetch(`${BACKEND_BASE}/api/credentials/account/exists`);
      if (!res.ok) return false;
      const body = await res.json() as { configured?: boolean };
      return body.configured === true;
    } catch {
      return false;
    }
  });

  ipcMain.handle('pat:clear', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/credentials/ACCOUNT/github`, { method: 'DELETE' });
    if (!res.ok) throw new Error(`Clear GitHub PAT failed (${res.status})`);
    return true;
  });

  ipcMain.handle('backend:hello', async () => {
    const res = await fetch(`${BACKEND_BASE}/hello`);
    if (!res.ok) throw new Error(`backend /hello returned ${res.status}`);
    return res.text();
  });

  ipcMain.handle('backend:listPrs', async () => {
    const res = await fetch(`${BACKEND_BASE}/prs`);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:markPrViewed', async (_event, prId: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/viewed`);
    url.searchParams.set('id', String(prId));
    await fetch(url, { method: 'POST' }).catch(() => { /* best-effort */ });
  });

  ipcMain.handle('backend:markPrHandled', async (_event, prId: number, action: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/handle`);
    url.searchParams.set('id', String(prId));
    url.searchParams.set('action', action);
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Mark handled failed (${res.status}): ${body}`);
    }
  });

  ipcMain.handle('backend:reopenPr', async (_event, prId: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/reopen`);
    url.searchParams.set('id', String(prId));
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Reopen failed (${res.status}): ${body}`);
    }
  });

  ipcMain.handle('backend:prHistory', async (_event, page: number, perPage?: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/history`);
    url.searchParams.set('page', String(page));
    if (typeof perPage === 'number') url.searchParams.set('perPage', String(perPage));
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/history returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:prAnalytics', async (_event, scope: string, tz?: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/analytics`);
    url.searchParams.set('scope', scope);
    if (tz) url.searchParams.set('tz', tz);
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/analytics returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:myActivity', async (_event, scope: string, tz?: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/my-activity`);
    url.searchParams.set('scope', scope);
    if (tz) url.searchParams.set('tz', tz);
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/my-activity returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:snoozePr', async (_event, prId: number, untilIso: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/snooze`);
    url.searchParams.set('id', String(prId));
    url.searchParams.set('until', untilIso);
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Snooze failed (${res.status}): ${body}`);
    }
  });

  ipcMain.handle('backend:unsnoozePr', async (_event, prId: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/unsnooze`);
    url.searchParams.set('id', String(prId));
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Unsnooze failed (${res.status}): ${body}`);
    }
  });

  ipcMain.handle('backend:clearSnoozeWakeReason', async (_event, prId: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/snooze/wake-reason/clear`);
    url.searchParams.set('id', String(prId));
    await fetch(url, { method: 'POST' }).catch(() => { /* best-effort */ });
  });

  ipcMain.handle('backend:pullRequestDetail', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/detail`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/detail returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:refreshPullRequestDetail', async (_event, repo: string, number: number, maxAgeSeconds?: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/detail/refresh`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    if (typeof maxAgeSeconds === 'number' && maxAgeSeconds > 0) {
      url.searchParams.set('maxAgeSeconds', String(maxAgeSeconds));
    }
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/detail/refresh returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:prCi', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/ci`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/ci returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:prConflictPaths', async (_event, owner: string, repo: string, prNumber: number, baseRef: string) => {
    // Lives under /api/repos/local/{owner}/{repo} rather than /prs/...
    // because conflict-path enumeration is a local-clone git operation
    // (merge-tree) — it has no GitHub-API call path.
    const url = new URL(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/conflict-paths`,
    );
    url.searchParams.set('prNumber', String(prNumber));
    url.searchParams.set('baseRef', baseRef);
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/repos/local/.../conflict-paths returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:prCheckLog', async (_event, repo: string, checkRunId: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/checkLog`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('checkRunId', String(checkRunId));
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/checkLog returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:setPrDraft', async (_event, repo: string, number: number, draft: boolean) => {
    const url = new URL(`${BACKEND_BASE}/prs/draft`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ draft }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(body || `backend /prs/draft returned ${res.status}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:prDiffFiles', async (_event, repo: string, number: number) => {
const url = new URL(`${BACKEND_BASE}/prs/diffFiles`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/diffFiles returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:prCommits', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/commits`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/commits returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:prCommitDiff', async (_event, repo: string, number: number, sha: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/commitDiff`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    url.searchParams.set('sha', sha);
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/commitDiff returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('backend:fileBlob', async (_event, repo: string, path: string, sha: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/fileBlob`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('path', path);
    url.searchParams.set('sha', sha);
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /prs/fileBlob returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('settings:getSyncSettings', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/settings/sync`);
    if (!res.ok) throw new Error(`backend /api/settings/sync returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('settings:setSyncSettings', async (_event, settings: { intervalSeconds: number }) => {
    const res = await fetch(`${BACKEND_BASE}/api/settings/sync`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings),
    });
    if (!res.ok) throw new Error(`backend PUT /api/settings/sync returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('settings:triggerSync', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/settings/sync/trigger`, { method: 'POST' });
    if (!res.ok) throw new Error(`backend /api/settings/sync/trigger returned ${res.status}`);
  });

  ipcMain.handle('backend:approvePr', async (_event, prId: number, repo: string, number: number) => {
const url = new URL(`${BACKEND_BASE}/prs/approve`);
    url.searchParams.set('id', String(prId));
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Approve failed (${res.status}): ${body}`);
    }
  });

  ipcMain.handle('backend:updatePrBody', async (_event, repo: string, number: number, body: string) => {
const url = new URL(`${BACKEND_BASE}/prs/body`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Update body failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:commentPr', async (_event, prId: number, repo: string, number: number, body: string, close: boolean) => {
const url = new URL(`${BACKEND_BASE}/prs/comment`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    url.searchParams.set('id', String(prId));
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body, close }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Comment failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:replyToReviewThread', async (_event, repo: string, number: number, rootCommentId: number, body: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-threads/${rootCommentId}/reply`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Reply failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:editIssueComment', async (_event, repo: string, commentId: number, body: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/issue-comments/${commentId}/body`);
    url.searchParams.set('repo', repo);
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Edit comment failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:editReviewComment', async (_event, repo: string, commentId: number, body: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-comments/${commentId}/body`);
    url.searchParams.set('repo', repo);
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Edit comment failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('pr:addReviewReaction', async (_event, repo: string, commentId: number, content: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-comments/${commentId}/reactions`);
    url.searchParams.set('repo', repo);
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Reaction failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('pr:addIssueReaction', async (_event, repo: string, commentId: number, content: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/issue-comments/${commentId}/reactions`);
    url.searchParams.set('repo', repo);
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Reaction failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('pr:setThreadResolved', async (_event, repo: string, prId: number, rootCommentId: number, resolved: boolean) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-threads/${rootCommentId}/resolved`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('prId', String(prId));
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolved }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Resolve failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:addRequestedReviewer', async (_event, repo: string, number: number, reviewer: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/reviewers`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    url.searchParams.set('reviewer', reviewer);
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Add reviewer failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:removeRequestedReviewer', async (_event, repo: string, number: number, reviewer: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/reviewers`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    url.searchParams.set('reviewer', reviewer);
    const res = await fetch(url, { method: 'DELETE' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Remove reviewer failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:getSuggestedReviewers', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/reviewers/suggested`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url);
    if (!res.ok) {
      // Non-essential affordance — return empty rather than throwing.
      return [];
    }
    return res.json();
  });

  ipcMain.handle('backend:createInlineReviewComment', async (
    _event,
    repo: string,
    number: number,
    body: string,
    path: string,
    line: number,
    side: 'LEFT' | 'RIGHT',
    commitId: string,
    startLine: number | null,
    startSide: 'LEFT' | 'RIGHT' | null,
  ) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-comments`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    // startLine / startSide are optional; null when single-line. The
    // backend treats startLine===line the same as null and strips both
    // before forwarding to GitHub.
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body, path, line, side, commitId, startLine, startSide }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Inline review comment failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:mergePr', async (_event, prId: number, repo: string, number: number, strategy?: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/merge`);
    url.searchParams.set('id', String(prId));
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    if (strategy) url.searchParams.set('strategy', strategy);
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Merge failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:list', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/repos`);
    if (!res.ok) throw new Error(`backend /api/repos returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:add', async (_event, owner: string, repo: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/repos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ owner, repo }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Add repo failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:remove', async (_event, owner: string, repo: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`, {
      method: 'DELETE',
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Remove repo failed (${res.status}): ${body}`);
    }
  });

  ipcMain.handle('repos:profile', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/profile`);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/profile returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:contributionGraph', async (_event, login: string) => {
    const url = new URL(`${BACKEND_BASE}/api/contribution-graph`);
    url.searchParams.set('login', login);
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend contribution-graph returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:pulls', async (_event, owner: string, repo: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend repo pulls returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:pull', async (_event, owner: string, repo: string, number: number) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls/${number}`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend repo pull returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:issues', async (_event, owner: string, repo: string, state?: string) => {
    const url = new URL(`${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues`);
    if (state) url.searchParams.set('state', state);
    const res = await fetch(url);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend repo issues returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:issueDetail', async (_event, owner: string, repo: string, number: number) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${number}/detail`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend issue detail returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:createIssueComment', async (_event, owner: string, repo: string, number: number, body: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${number}/comments`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ body }),
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend issue comment returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:setIssueState', async (_event, owner: string, repo: string, number: number, state: 'open' | 'closed') => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${number}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ state }),
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend setIssueState returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:addIssueCommentReaction', async (_event, owner: string, repo: string, commentId: number, content: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/comments/${commentId}/reactions`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content }),
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend addIssueCommentReaction returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:setIssueSubscription', async (_event, owner: string, repo: string, number: number, subscribed: boolean) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${number}/subscription`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subscribed }),
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend setIssueSubscription returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:meta', async (_event, owner: string, repo: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/meta`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend repo meta returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:activity', async (_event, owner: string, repo: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/activity`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend repo activity returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:listLocal', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/repos/local`);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/repos/local returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  // Native folder picker for the Locate-existing flow + the
  // Change-destination button in the Clone-fresh flow. Renderer
  // can't open dialogs directly because contextIsolation hides
  // the Electron module surface.
  ipcMain.handle('repos:pickFolder', async (
    _event, options?: { defaultPath?: string; title?: string },
  ) => {
    if (!mainWindow || mainWindow.isDestroyed()) {
      // Fail loudly rather than returning null silently — the
      // renderer surfaces the message so a misconfigured
      // mainWindow doesn't read as "click does nothing".
      throw new Error('main window is unavailable');
    }
    // On macOS, passing the parent window opens the dialog as a
    // sheet attached to the title bar. Without it the dialog is
    // a free-floating window — we want the sheet for visual
    // anchoring to the modal underneath.
    const result = await dialog.showOpenDialog(mainWindow, {
      properties: ['openDirectory', 'createDirectory'],
      defaultPath: options?.defaultPath,
      title: options?.title,
    });
    if (result.canceled || result.filePaths.length === 0) return null;
    return result.filePaths[0];
  });

  ipcMain.handle('repos:defaultClonePath', async (
    _event, owner: string, repo: string,
  ): Promise<string> => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/default-clone-path`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend default-clone-path returned ${res.status}: ${body}`);
    }
    const json = await res.json();
    return json.defaultPath;
  });

  ipcMain.handle('repos:cloneRepo', async (
    _event, owner: string, repo: string, destination: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/clone`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ destination }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `clone failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:fetchLocal', async (
    _event, owner: string, repo: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/fetch`,
      { method: 'POST' },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `fetch failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:pullLocal', async (
    _event, owner: string, repo: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pull`,
      { method: 'POST' },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `pull failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:pushLocalForce', async (
    _event, owner: string, repo: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/push-force`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmed: true }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `force push failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:pushLocal', async (
    _event, owner: string, repo: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/push`,
      { method: 'POST' },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `push failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:deleteLocalBranches', async (
    _event, owner: string, repo: string, names: string[], deleteRemote?: boolean,
  ): Promise<string[]> => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/branches`,
      {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ names, deleteRemote: !!deleteRemote }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `delete branches failed (${res.status})`);
    }
    const json = await res.json();
    return json.deleted ?? [];
  });

  ipcMain.handle('repos:draftPullRequest', async (
    _event, owner: string, repo: string, base: string, head: string,
  ): Promise<{ title: string; description: string }> => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pull-requests/draft`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ base, head }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `draft PR failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:createPullRequest', async (
    _event,
    owner: string,
    repo: string,
    payload: { title: string; body: string; base: string; draft: boolean },
  ): Promise<{ number: number; htmlUrl: string }> => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pull-requests`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `create PR failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:switchLocalBranch', async (
    _event, owner: string, repo: string, name: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/branches/switch`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `switch failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:checkoutRemoteBranch', async (
    _event, owner: string, repo: string, name: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/branches/checkout-remote`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `checkout failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:createLocalBranch', async (
    _event, owner: string, repo: string, name: string, base?: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/branches`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, base: base ?? null }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `create-branch failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:listLocalActivity', async (
    _event, owner: string, repo: string, limit?: number,
  ) => {
    const params = new URLSearchParams();
    if (typeof limit === 'number' && limit > 0) params.set('limit', String(limit));
    const query = params.toString();
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/activity`
        + (query ? `?${query}` : ''),
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `activity failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:listLocalCommits', async (
    _event, owner: string, repo: string, revision?: string, limit?: number,
  ) => {
    const params = new URLSearchParams();
    if (revision && revision.trim()) params.set('revision', revision.trim());
    if (typeof limit === 'number' && limit > 0) params.set('limit', String(limit));
    const query = params.toString();
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/commits`
        + (query ? `?${query}` : ''),
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `commits failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:getLocalCommitDetail', async (
    _event, owner: string, repo: string, sha: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/commits/${encodeURIComponent(sha)}/detail`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `commit detail fetch failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:listLocalWorkingTreeFiles', async (
    _event, owner: string, repo: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/working-tree/files`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `working-tree status failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:getLocalWorkingTreeDiff', async (
    _event, owner: string, repo: string, path: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/working-tree/diff?path=${encodeURIComponent(path)}`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `working-tree diff fetch failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:listLocalRangeFiles', async (
    _event, owner: string, repo: string, base: string, head: string,
  ) => {
    const params = new URLSearchParams();
    params.set('base', base);
    params.set('head', head);
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/range/files?${params.toString()}`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `range files lookup failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:getLocalRangeDiff', async (
    _event, owner: string, repo: string, base: string, head: string, path: string,
  ) => {
    const params = new URLSearchParams();
    params.set('base', base);
    params.set('head', head);
    params.set('path', path);
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/range/diff?${params.toString()}`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `range diff fetch failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:listLocalCommitFiles', async (
    _event, owner: string, repo: string, sha: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/commits/${encodeURIComponent(sha)}/files`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `commit files failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:getLocalCommitDiff', async (
    _event, owner: string, repo: string, sha: string, path: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/commits/${encodeURIComponent(sha)}/diff?path=${encodeURIComponent(path)}`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `commit diff failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:getLocalCommitRangeDiff', async (
    _event, owner: string, repo: string, oldestSha: string, newestSha: string, path: string,
  ) => {
    const params = new URLSearchParams();
    params.set('oldest', oldestSha);
    params.set('newest', newestSha);
    params.set('path', path);
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/commits-range/diff?${params.toString()}`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `commit range-diff failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:getLocalMergeBase', async (
    _event, owner: string, repo: string, branch: string, base: string | undefined,
  ) => {
    const params = new URLSearchParams();
    params.set('branch', branch);
    if (base) params.set('base', base);
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/merge-base?${params.toString()}`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `merge-base lookup failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:listLocalBranches', async (
    _event, owner: string, repo: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/branches`,
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `branches list failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:locateRepo', async (
    _event, owner: string, repo: string, path: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/locate`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `locate failed (${res.status})`);
    }
    return res.json();
  });

  ipcMain.handle('repos:revealInFinder', async (_event, repoPath: string) => {
    if (!repoPath) throw new Error('No path mapped for this repo');
    // showItemInFolder reveals the *parent* with the item selected.
    // For a repo directory we want the folder itself open in Finder,
    // so use openPath which double-clicks the folder.
    const err = await shell.openPath(repoPath);
    if (err) throw new Error(err);
  });

  // Try a list of well-known macOS terminal apps in order. We don't
  // probe LaunchServices for installation up front — `open -a` exits
  // non-zero if the app isn't there, so the loop short-circuits on
  // the first match. iTerm first since most devs who installed it
  // prefer it over the bundled Terminal.
  const TERMINAL_CANDIDATES = ['iTerm', 'Terminal'];
  ipcMain.handle('repos:openInTerminal', async (_event, repoPath: string) => {
    if (!repoPath) throw new Error('No path mapped for this repo');
    for (const appName of TERMINAL_CANDIDATES) {
      try {
        await execFileAsync('open', ['-a', appName, repoPath]);
        return;
      } catch {
        // try next candidate
      }
    }
    throw new Error('No supported terminal found (tried iTerm, Terminal)');
  });

  // Same shape as the terminal opener — picking among installed IDEs
  // is a settings concern we'll address later. For now we walk a
  // sensible mac-dev default order.
  const IDE_CANDIDATES = [
    'Visual Studio Code',
    'Cursor',
    'IntelliJ IDEA',
    'IntelliJ IDEA CE',
    'WebStorm',
    'GoLand',
    'PyCharm',
    'PyCharm CE',
  ];
  ipcMain.handle('repos:openInIDE', async (_event, repoPath: string) => {
    if (!repoPath) throw new Error('No path mapped for this repo');
    for (const appName of IDE_CANDIDATES) {
      try {
        await execFileAsync('open', ['-a', appName, repoPath]);
        return;
      } catch {
        // try next candidate
      }
    }
    throw new Error('No supported IDE found (tried VS Code, Cursor, JetBrains)');
  });

  ipcMain.handle('repos:setLocalClonePath', async (
    _event, owner: string, repo: string, path: string | null,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/path`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: path ?? '' }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/repos/local/.../path returned ${res.status}: ${body}`);
    }
  });

  ipcMain.handle('repos:setViewFocus', async (
    _event, owner: string, repo: string, viewFocus: 'fork' | 'upstream',
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/view-focus`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ viewFocus }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/repos/local/.../view-focus returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:userRepos', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/user/repos`);
    if (!res.ok) throw new Error(`backend /api/user/repos returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:userOrgs', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/user/orgs`);
    if (!res.ok) throw new Error(`backend /api/user/orgs returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:recentActivity', async (_event, login: string) => {
const url = new URL(`${BACKEND_BASE}/api/activity/recent`);
    url.searchParams.set('login', login);
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/activity/recent returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:followingActivity', async (_event, login: string) => {
const url = new URL(`${BACKEND_BASE}/api/activity/following`);
    url.searchParams.set('login', login);
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/activity/following returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('home:dailyCard', async () => {
    const res = await fetch(`${BACKEND_BASE}/daily-card`);
    if (!res.ok) throw new Error(`backend /daily-card returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:updateProfile', async (_event, name: string, bio: string, location: string) => {
const res = await fetch(`${BACKEND_BASE}/api/profile`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, bio, location }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Update profile failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('repos:searchRepos', async (_event, query: string) => {
const url = new URL(`${BACKEND_BASE}/api/search/repos`);
    url.searchParams.set('q', query);
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/search/repos returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:searchUsers', async (_event, query: string) => {
    const url = new URL(`${BACKEND_BASE}/api/search/users`);
    url.searchParams.set('q', query);
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/search/users returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:getStats', async (_event, login: string, force?: boolean) => {
    const url = new URL(`${BACKEND_BASE}/api/stats`);
    url.searchParams.set('login', login);
    if (force) url.searchParams.set('force', 'true');
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/stats returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('shell:openExternal', async (_event, url: string) => {
    if (typeof url === 'string' && (url.startsWith('https://') || url.startsWith('http://'))) {
      await shell.openExternal(url);
    }
  });

  ipcMain.handle('shell:writeClipboard', async (_event, text: string) => {
    if (typeof text === 'string') {
      clipboard.writeText(text);
    }
  });

  type Bounds = { x: number; y: number; width: number; height: number };
  const roundBounds = (b: Bounds): Bounds => ({
    x: Math.round(b.x),
    y: Math.round(b.y),
    width: Math.max(0, Math.round(b.width)),
    height: Math.max(0, Math.round(b.height)),
  });

  ipcMain.handle('review:mount', async (_event, repo: string, number: number, bounds: Bounds) => {
    if (typeof repo !== 'string' || !/^[\w.-]+\/[\w.-]+$/.test(repo) || !Number.isInteger(number)) {
      throw new Error('invalid repo or number');
    }
    if (!mainWindow || mainWindow.isDestroyed()) return;
    // Drop any previous review view so we don't stack overlays.
    destroyReviewView();
    const view = new WebContentsView({
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        // Persistent partition so GitHub's login cookie survives restarts —
        // users only go through the passkey/2FA dance once.
        partition: 'persist:github-review',
      },
    });
    // Electron on macOS can't use Touch ID/Face ID for WebAuthn (needs a
    // browser entitlement Apple only grants approved browsers). A Chrome UA
    // nudges github.com to offer the full list of alternate sign-in methods
    // (password + TOTP, security key, recovery code).
    view.webContents.setUserAgent(
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
      '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    );
    view.setBackgroundColor('#ffffff');
    view.setBounds(roundBounds(bounds));
    mainWindow.contentView.addChildView(view);
    reviewView = view;

    const send = (channel: string, payload?: unknown) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send(channel, payload);
      }
    };
    const notifyBlocked = (provider: string) => send('review:auth-blocked', { provider });
    const notifySignInPage = () => send('review:sign-in-page');

    view.webContents.on('will-navigate', (e, targetUrl) => {
      const provider = matchBlockedAuthHost(targetUrl);
      if (provider) {
        e.preventDefault();
        notifyBlocked(provider);
      }
    });
    view.webContents.on('will-redirect', (e, targetUrl) => {
      const provider = matchBlockedAuthHost(targetUrl);
      if (provider) {
        e.preventDefault();
        notifyBlocked(provider);
      }
    });
    view.webContents.setWindowOpenHandler(({ url: targetUrl }) => {
      const provider = matchBlockedAuthHost(targetUrl);
      if (provider) {
        notifyBlocked(provider);
        return { action: 'deny' };
      }
      return { action: 'allow' };
    });
    // Push the latest can-go-back/forward state to the renderer on every
    // navigation. Drives the ←/→ toolbar buttons in ReviewScreen so they
    // light up only when there's actually history to walk.
    const pushNavState = () => {
      if (!reviewView) return;
      const wc = reviewView.webContents;
      send('review:nav-state', {
        canGoBack: wc.navigationHistory.canGoBack(),
        canGoForward: wc.navigationHistory.canGoForward(),
      });
    };
    view.webContents.on('did-navigate', (_e, targetUrl) => {
      if (isGithubSignInUrl(targetUrl)) notifySignInPage();
      pushNavState();
    });
    view.webContents.on('did-navigate-in-page', (_e, targetUrl) => {
      if (isGithubSignInUrl(targetUrl)) notifySignInPage();
      pushNavState();
    });
    // Initial push so the buttons render in the right state once the
    // first page settles.
    view.webContents.on('did-finish-load', pushNavState);

    void view.webContents.loadURL(`https://github.com/${repo}/pull/${number}`);
  });

  ipcMain.handle('review:setBounds', async (_event, bounds: Bounds) => {
    if (!reviewView) return;
    reviewView.setBounds(roundBounds(bounds));
  });

  ipcMain.handle('review:unmount', async () => {
    destroyReviewView();
  });

  // ←/→ on the review toolbar drive the embed's own history. Mirrors
  // Chrome's back/forward — useful when a comment links to another page
  // on github.com inside the embed, and the user wants to return to
  // the original PR page without exiting the review screen entirely.
  ipcMain.handle('review:goBack', async () => {
    if (!reviewView) return;
    const wc = reviewView.webContents;
    if (wc.navigationHistory.canGoBack()) wc.navigationHistory.goBack();
  });
  ipcMain.handle('review:goForward', async () => {
    if (!reviewView) return;
    const wc = reviewView.webContents;
    if (wc.navigationHistory.canGoForward()) wc.navigationHistory.goForward();
  });

  // ─── In-app browser overlay (mounted on demand for any URL the user
  // clicks in the React UI) ───────────────────────────────────────────
  ipcMain.handle('inapp:mount', async (_event, url: string, bounds: Bounds) => {
    if (typeof url !== 'string' || !/^https?:/i.test(url)) {
      throw new Error('invalid url');
    }
    if (!mainWindow || mainWindow.isDestroyed()) return;
    destroyInappView();
    const view = new WebContentsView({
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        // Separate persistent partition so cookies set inside the
        // in-app browser don't leak into the github.com review embed
        // (and vice versa).
        partition: 'persist:inapp-browser',
      },
    });
    // Same Chrome UA as the review embed so SSO / passkey error pages
    // match what the user sees in their normal browser, and so any
    // protected pages don't refuse the request as "unsupported browser".
    view.webContents.setUserAgent(
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
      '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    );
    view.setBackgroundColor('#ffffff');
    view.setBounds(roundBounds(bounds));
    mainWindow.contentView.addChildView(view);
    inappView = view;

    const send = (channel: string, payload?: unknown) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send(channel, payload);
      }
    };
    const pushNavState = () => {
      if (!inappView) return;
      const wc = inappView.webContents;
      send('inapp:nav-state', {
        url: wc.getURL(),
        title: wc.getTitle(),
        canGoBack: wc.navigationHistory.canGoBack(),
        canGoForward: wc.navigationHistory.canGoForward(),
        loading: wc.isLoadingMainFrame(),
      });
    };
    view.webContents.on('did-navigate', pushNavState);
    view.webContents.on('did-navigate-in-page', pushNavState);
    view.webContents.on('did-finish-load', pushNavState);
    view.webContents.on('did-start-loading', pushNavState);
    view.webContents.on('did-stop-loading', pushNavState);
    view.webContents.on('page-title-updated', pushNavState);
    // Links opened from inside the in-app browser stay inside the
    // in-app browser — `setWindowOpenHandler` would normally spawn a
    // new Electron window otherwise. The same `did-navigate` will then
    // push the URL into the toolbar.
    view.webContents.setWindowOpenHandler(({ url: targetUrl }) => {
      if (/^https?:/i.test(targetUrl)) {
        void view.webContents.loadURL(targetUrl);
      }
      return { action: 'deny' };
    });

    void view.webContents.loadURL(url);
  });

  ipcMain.handle('inapp:setBounds', async (_event, bounds: Bounds) => {
    if (!inappView) return;
    inappView.setBounds(roundBounds(bounds));
  });

  ipcMain.handle('inapp:unmount', async () => {
    destroyInappView();
  });

  ipcMain.handle('inapp:goBack', async () => {
    if (!inappView) return;
    const wc = inappView.webContents;
    if (wc.navigationHistory.canGoBack()) wc.navigationHistory.goBack();
  });
  ipcMain.handle('inapp:goForward', async () => {
    if (!inappView) return;
    const wc = inappView.webContents;
    if (wc.navigationHistory.canGoForward()) wc.navigationHistory.goForward();
  });
  ipcMain.handle('inapp:reload', async () => {
    if (!inappView) return;
    inappView.webContents.reload();
  });
  ipcMain.handle('inapp:loadUrl', async (_event, url: string) => {
    if (!inappView) return;
    if (typeof url !== 'string' || !/^https?:/i.test(url)) return;
    void inappView.webContents.loadURL(url);
  });

  // Pop the URL out into its own native Electron window — vanilla
  // BrowserWindow with OS-supplied chrome (close / minimize / Cmd+←
  // for back via the application menu). Independent of the main app
  // window's lifecycle, so multiple URLs can sit side-by-side without
  // a tab strip. Sharing the in-app-browser partition keeps cookies
  // (i.e. logged-in state) consistent with the overlay.
  ipcMain.handle('inapp:popOut', async (_event, url: string) => {
    openInPopupWindow(url);
  });

  // Clear cookies + storage for the review partition and reload /login so the
  // user gets a clean username+password form. Fixes the case where a stale
  // half-authenticated cookie makes github.com skip the password page and
  // show only a passkey/device-verification prompt (which can't complete
  // inside Electron).
  ipcMain.handle('review:resetSignIn', async (_event, repo: string, number: number) => {
    if (!reviewView) return;
    if (typeof repo !== 'string' || !/^[\w.-]+\/[\w.-]+$/.test(repo) || !Number.isInteger(number)) {
      throw new Error('invalid repo or number');
    }
    const ses = session.fromPartition('persist:github-review');
    await ses.clearStorageData({
      storages: ['cookies', 'localstorage', 'indexdb', 'websql', 'serviceworkers', 'cachestorage'],
    });
    const returnTo = encodeURIComponent(`/${repo}/pull/${number}`);
    void reviewView.webContents.loadURL(`https://github.com/login?return_to=${returnTo}`);
  });

  // ── Teams ──────────────────────────────────────────────────────────────
  ipcMain.handle('teams:list', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/teams`);
    if (!res.ok) throw new Error(`backend /api/teams returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('teams:get', async (_event, id: number) => {
    const res = await fetch(`${BACKEND_BASE}/api/teams/${id}`);
    if (!res.ok) throw new Error(`backend /api/teams/${id} returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('teams:create', async (_event, req: unknown) => {
    const res = await fetch(`${BACKEND_BASE}/api/teams`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Create team failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('teams:update', async (_event, id: number, req: unknown) => {
    const res = await fetch(`${BACKEND_BASE}/api/teams/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Update team failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('teams:replaceMembers', async (_event, id: number, members: string[]) => {
    const res = await fetch(`${BACKEND_BASE}/api/teams/${id}/members`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ members }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Replace members failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('teams:delete', async (_event, id: number) => {
    const res = await fetch(`${BACKEND_BASE}/api/teams/${id}`, { method: 'DELETE' });
    if (!res.ok) throw new Error(`Delete team failed (${res.status})`);
  });

  ipcMain.handle('teams:pulls', async (_event, id: number) => {
    const res = await fetch(`${BACKEND_BASE}/api/teams/${id}/pulls`);
    if (!res.ok) throw new Error(`backend /api/teams/${id}/pulls returned ${res.status}`);
    return res.json();
  });

  // First-paint endpoint for the team kanban: { columns: {col: PR[]},
  // totals: {col: int} }. perColumn caps how many PRs each column ships
  // up-front. force=true bypasses the per-team TTL cache for the
  // explicit refresh button.
  ipcMain.handle('teams:pullsByColumn', async (_event, id: number, perColumn: number, force: boolean) => {
    const url = new URL(`${BACKEND_BASE}/api/teams/${id}/pulls/by-column`);
    url.searchParams.set('perColumn', String(perColumn));
    if (force) url.searchParams.set('force', 'true');
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/teams/${id}/pulls/by-column returned ${res.status}`);
    return res.json();
  });

  // "+ N more" pagination for one column. Always served from the cached
  // fan-out — pagination clicks shouldn't re-hit GitHub.
  ipcMain.handle('teams:pullsColumnPage', async (_event, id: number, column: string, offset: number, limit: number) => {
    const url = new URL(`${BACKEND_BASE}/api/teams/${id}/pulls/column`);
    url.searchParams.set('column', column);
    url.searchParams.set('offset', String(offset));
    url.searchParams.set('limit', String(limit));
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/teams/${id}/pulls/column returned ${res.status}`);
    return res.json();
  });

  // Merged-PR count for the team home "Merged this week" stat. Backend
  // does the is:merged search fan-out; the renderer caches the response
  // for 10 minutes so the upstream lookup runs at most once per team
  // per cache window.
  ipcMain.handle('teams:mergedRecently', async (_event, id: number, days: number) => {
    const url = new URL(`${BACKEND_BASE}/api/teams/${id}/merged-recently`);
    url.searchParams.set('days', String(days));
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/teams/${id}/merged-recently returned ${res.status}`);
    const body = await res.json();
    return body.count as number;
  });

  // ── GitHub OAuth ────────────────────────────────────────────────────────
  // Renderer-driven handshake: getAuthorizeUrl → openExternal → GitHub
  // redirects to bytequay://github-oauth-callback → open-url handler
  // below forwards code/state to the backend → emits
  // github:oauth-complete. The token lands in the same Keychain slot
  // the PAT path uses, so the rest of the app sees no difference
  // between OAuth and PAT auth.
  ipcMain.handle('githubOAuth:authorizeUrl', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/auth/github/authorize-url`);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/auth/github/authorize-url returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('githubOAuth:connection', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/auth/github/connection`);
    if (!res.ok) throw new Error(`backend /api/auth/github/connection returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('githubOAuth:disconnect', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/auth/github/disconnect`, { method: 'POST' });
    if (!res.ok) throw new Error(`backend /api/auth/github/disconnect returned ${res.status}`);
    return res.json();
  });

  // ── Gmail (IMAP + app password) ────────────────────────────────────────

  ipcMain.handle('gmailImap:connect', async (_event, payload: unknown) => {
    if (!payload || typeof payload !== 'object') {
      throw new Error('payload must be { email, appPassword }');
    }
    const { email, appPassword } = payload as { email?: string; appPassword?: string };
    if (typeof email !== 'string' || email.trim().length === 0) {
      throw new Error('email must be a non-empty string');
    }
    if (typeof appPassword !== 'string' || appPassword.trim().length === 0) {
      throw new Error('appPassword must be a non-empty string');
    }
    const res = await fetch(`${BACKEND_BASE}/api/auth/gmail/imap/connect`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.trim(), appPassword }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/auth/gmail/imap/connect returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('gmail:listAccounts', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/auth/gmail/accounts`);
    if (!res.ok) throw new Error(`backend /api/auth/gmail/accounts returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('gmail:disconnect', async (_event, email: string) => {
    if (typeof email !== 'string' || email.trim().length === 0) {
      throw new Error('email must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/auth/gmail/accounts/${encodeURIComponent(email)}`,
      { method: 'DELETE' });
    if (!res.ok) throw new Error(`backend /api/auth/gmail/accounts/${email} returned ${res.status}`);
    return res.json();
  });

  // ── Email (Gmail inbox, thread-based) ──────────────────────────────────
  // All endpoints operate on Gmail's thread abstraction — one card per
  // conversation, archive / mark-read apply to the whole thread.
  ipcMain.handle('email:listThreads', async (_event, payload: unknown) => {
    if (!payload || typeof payload !== 'object') {
      throw new Error('payload must be { account, pageSize? }');
    }
    const { account, pageSize } = payload as { account?: string; pageSize?: number };
    if (typeof account !== 'string' || account.trim().length === 0) {
      throw new Error('account must be a non-empty string');
    }
    const params = new URLSearchParams({ account: account.trim() });
    if (typeof pageSize === 'number' && pageSize > 0) {
      params.set('pageSize', String(pageSize));
    }
    const res = await fetch(`${BACKEND_BASE}/api/email/threads?${params.toString()}`);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/email/threads returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('email:refreshThreads', async (_event, payload: unknown) => {
    const { account, pageSize } = (payload ?? {}) as { account?: string; pageSize?: number };
    if (typeof account !== 'string' || account.trim().length === 0) {
      throw new Error('account must be a non-empty string');
    }
    const params = new URLSearchParams({ account: account.trim() });
    if (typeof pageSize === 'number' && pageSize > 0) {
      params.set('pageSize', String(pageSize));
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/email/threads/refresh?${params.toString()}`,
      { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/email/threads/refresh returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('email:getThread', async (_event, payload: unknown) => {
    const { account, id } = (payload ?? {}) as { account?: string; id?: string };
    if (typeof account !== 'string' || account.trim().length === 0) {
      throw new Error('account must be a non-empty string');
    }
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const params = new URLSearchParams({ account: account.trim() });
    const res = await fetch(
      `${BACKEND_BASE}/api/email/threads/${encodeURIComponent(id)}?${params.toString()}`);
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/email/threads/${id} returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  // Archive / mark-read / mark-unread share the POST shape: account in
  // query, no body. Each maps to a single users.threads.modify call.
  const threadAction = async (action: string, account: string, id: string) => {
    const params = new URLSearchParams({ account });
    const res = await fetch(
      `${BACKEND_BASE}/api/email/threads/${encodeURIComponent(id)}/${action}?${params.toString()}`,
      { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend /api/email/threads/${id}/${action} returned ${res.status}: ${body}`);
    }
    return res.json();
  };

  ipcMain.handle('email:archiveThread', async (_event, payload: unknown) => {
    const { account, id } = (payload ?? {}) as { account?: string; id?: string };
    if (!account || !id) throw new Error('account and id are required');
    return threadAction('archive', account, id);
  });
  ipcMain.handle('email:markThreadRead', async (_event, payload: unknown) => {
    const { account, id } = (payload ?? {}) as { account?: string; id?: string };
    if (!account || !id) throw new Error('account and id are required');
    return threadAction('mark-read', account, id);
  });
  ipcMain.handle('email:markThreadUnread', async (_event, payload: unknown) => {
    const { account, id } = (payload ?? {}) as { account?: string; id?: string };
    if (!account || !id) throw new Error('account and id are required');
    return threadAction('mark-unread', account, id);
  });
  ipcMain.handle('email:readAndArchiveThread', async (_event, payload: unknown) => {
    const { account, id } = (payload ?? {}) as { account?: string; id?: string };
    if (!account || !id) throw new Error('account and id are required');
    return threadAction('read-and-archive', account, id);
  });
  ipcMain.handle('email:keepThreadInInbox', async (_event, payload: unknown) => {
    const { account, id } = (payload ?? {}) as { account?: string; id?: string };
    if (!account || !id) throw new Error('account and id are required');
    return threadAction('keep-in-inbox', account, id);
  });
  ipcMain.handle('email:replyThread', async (_event, payload: unknown) => {
    const { account, id, body } = (payload ?? {}) as { account?: string; id?: string; body?: string };
    if (!account || !id) throw new Error('account and id are required');
    if (!body || !body.trim()) throw new Error('body is required');
    const params = new URLSearchParams({ account });
    const res = await fetch(
      `${BACKEND_BASE}/api/email/threads/${encodeURIComponent(id)}/reply?${params.toString()}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ body }),
      });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/email/threads/${id}/reply returned ${res.status}: ${text}`);
    }
    return res.json();
  });
  ipcMain.handle('email:muteSender', async (_event, payload: unknown) => {
    const { account, sender } = (payload ?? {}) as { account?: string; sender?: string };
    if (!account || !sender) throw new Error('account and sender are required');
    const params = new URLSearchParams({ account });
    const res = await fetch(
      `${BACKEND_BASE}/api/email/muted-senders?${params.toString()}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sender }),
      });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/email/muted-senders returned ${res.status}: ${text}`);
    }
    return res.json();
  });
  ipcMain.handle('email:unmuteSender', async (_event, payload: unknown) => {
    const { account, sender } = (payload ?? {}) as { account?: string; sender?: string };
    if (!account || !sender) throw new Error('account and sender are required');
    const params = new URLSearchParams({ account });
    const res = await fetch(
      `${BACKEND_BASE}/api/email/muted-senders/${encodeURIComponent(sender)}?${params.toString()}`,
      { method: 'DELETE' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend DELETE /api/email/muted-senders returned ${res.status}: ${text}`);
    }
    return res.json();
  });
  ipcMain.handle('email:listMutedSenders', async (_event, payload: unknown) => {
    const { account } = (payload ?? {}) as { account?: string };
    if (!account) throw new Error('account is required');
    const params = new URLSearchParams({ account });
    const res = await fetch(`${BACKEND_BASE}/api/email/muted-senders?${params.toString()}`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/email/muted-senders returned ${res.status}: ${text}`);
    }
    const json = await res.json();
    return (json && Array.isArray(json.senders)) ? json.senders as string[] : [];
  });
  ipcMain.handle('email:listTags', async (_event, payload: unknown) => {
    const { account } = (payload ?? {}) as { account?: string };
    if (!account) throw new Error('account is required');
    const params = new URLSearchParams({ account });
    const res = await fetch(`${BACKEND_BASE}/api/email/tags?${params.toString()}`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/email/tags returned ${res.status}: ${text}`);
    }
    const json = await res.json();
    return (json && Array.isArray(json.tags)) ? json.tags : [];
  });
  ipcMain.handle('email:createTag', async (_event, payload: unknown) => {
    const { account, input } = (payload ?? {}) as {
      account?: string;
      input?: { name: string; subjectContains: string; action: string };
    };
    if (!account || !input) throw new Error('account and input are required');
    const params = new URLSearchParams({ account });
    const res = await fetch(`${BACKEND_BASE}/api/email/tags?${params.toString()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/email/tags returned ${res.status}: ${text}`);
    }
    return res.json();
  });
  ipcMain.handle('email:updateTag', async (_event, payload: unknown) => {
    const { id, input } = (payload ?? {}) as {
      id?: string;
      input?: { name: string; subjectContains: string; action: string };
    };
    if (!id || !input) throw new Error('id and input are required');
    const res = await fetch(`${BACKEND_BASE}/api/email/tags/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PUT /api/email/tags/${id} returned ${res.status}: ${text}`);
    }
    return res.json();
  });
  ipcMain.handle('email:deleteTag', async (_event, payload: unknown) => {
    const { id } = (payload ?? {}) as { id?: string };
    if (!id) throw new Error('id is required');
    const res = await fetch(`${BACKEND_BASE}/api/email/tags/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend DELETE /api/email/tags/${id} returned ${res.status}: ${text}`);
    }
    return res.json();
  });
  ipcMain.handle('email:listArchived', async (_event, payload: unknown) => {
    const { account } = (payload ?? {}) as { account?: string };
    if (!account) throw new Error('account is required');
    const params = new URLSearchParams({ account });
    const res = await fetch(`${BACKEND_BASE}/api/email/archived?${params.toString()}`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/email/archived returned ${res.status}: ${text}`);
    }
    const json = await res.json();
    return (json && Array.isArray(json.entries)) ? json.entries : [];
  });

  // ── Tasks ───────────────────────────────────────────────────────────────
  // Local AI coding tasks. The list endpoint returns rows across all
  // statuses; the page does its own grouping. Create kicks off the first
  // turn synchronously so the returned row already carries the agent
  // session id where available.
  ipcMain.handle('tasks:list', async (_event, groupId: unknown) => {
    let url = `${BACKEND_BASE}/api/tasks`;
    if (typeof groupId === 'string' && groupId.trim().length > 0) {
      url += `?groupId=${encodeURIComponent(groupId)}`;
    }
    const res = await fetch(url);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/tasks returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('taskGroups:list', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/task-groups`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/task-groups returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('taskGroups:listMemberships', async () => {
    const res = await fetch(`${BACKEND_BASE}/api/task-groups/memberships`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend /api/task-groups/memberships returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('taskGroups:create', async (_event, request: unknown) => {
    const res = await fetch(`${BACKEND_BASE}/api/task-groups`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request ?? {}),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/task-groups returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('taskGroups:update', async (_event, payload: unknown) => {
    const { id, patch } = (payload ?? {}) as { id?: string; patch?: unknown };
    if (!id || typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/task-groups/${encodeURIComponent(id)}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch ?? {}),
      });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PATCH /api/task-groups/${id} returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('taskGroups:delete', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/task-groups/${encodeURIComponent(id)}`,
      { method: 'DELETE' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend DELETE /api/task-groups/${id} returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('taskGroups:addMember', async (_event, payload: unknown) => {
    const { groupId, taskId } = (payload ?? {}) as { groupId?: string; taskId?: string };
    if (!groupId || typeof groupId !== 'string' || groupId.trim().length === 0) {
      throw new Error('groupId must be a non-empty string');
    }
    if (!taskId || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('taskId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/task-groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(taskId)}`,
      { method: 'POST' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/task-groups/${groupId}/members/${taskId} returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('taskGroups:removeMember', async (_event, payload: unknown) => {
    const { groupId, taskId } = (payload ?? {}) as { groupId?: string; taskId?: string };
    if (!groupId || typeof groupId !== 'string' || groupId.trim().length === 0) {
      throw new Error('groupId must be a non-empty string');
    }
    if (!taskId || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('taskId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/task-groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(taskId)}`,
      { method: 'DELETE' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend DELETE /api/task-groups/${groupId}/members/${taskId} returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('tasks:create', async (_event, request: unknown) => {
    const res = await fetch(`${BACKEND_BASE}/api/tasks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request ?? {}),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/tasks returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:stop', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/stop`,
      { method: 'POST' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/tasks/${id}/stop returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('tasks:delete', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}`,
      { method: 'DELETE' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend DELETE /api/tasks/${id} returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('tasks:workingChanges', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(`${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/working-changes`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/tasks/${id}/working-changes returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:workingDiff', async (_event, id: unknown, path: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    if (typeof path !== 'string' || path.length === 0) {
      throw new Error('path must be a non-empty string');
    }
    const url = `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/working-diff?path=${encodeURIComponent(path)}`;
    const res = await fetch(url);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET ${url} returned ${res.status}: ${text}`);
    }
    const body = await res.json() as { diff?: string };
    return body.diff ?? '';
  });

  ipcMain.handle('tasks:listCommits', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(`${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/commits`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/tasks/${id}/commits returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:commitFiles', async (_event, id: unknown, sha: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    if (typeof sha !== 'string' || sha.trim().length === 0) {
      throw new Error('sha must be a non-empty string');
    }
    const url = `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/commits/${encodeURIComponent(sha)}/files`;
    const res = await fetch(url);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET ${url} returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:commitDiff', async (_event, id: unknown, sha: unknown, path: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    if (typeof sha !== 'string' || sha.trim().length === 0) {
      throw new Error('sha must be a non-empty string');
    }
    if (typeof path !== 'string' || path.length === 0) {
      throw new Error('path must be a non-empty string');
    }
    const url = `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/commits/${encodeURIComponent(sha)}/diff?path=${encodeURIComponent(path)}`;
    const res = await fetch(url);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET ${url} returned ${res.status}: ${text}`);
    }
    const body = await res.json() as { diff?: string };
    return body.diff ?? '';
  });

  ipcMain.handle('tasks:interrupt', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/interrupt`,
      { method: 'POST' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/tasks/${id}/interrupt returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('tasks:get', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(`${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/tasks/${id} returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:messages', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/messages`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/tasks/${id}/messages returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:turns', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/turns`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/tasks/${id}/turns returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:files', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/files`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/tasks/${id}/files returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:rename', async (_event, payload: unknown) => {
    const { id, title } = (payload ?? {}) as { id?: string; title?: string };
    if (!id || typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    if (typeof title !== 'string' || title.trim().length === 0) {
      throw new Error('title must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title }),
      });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PATCH /api/tasks/${id} returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:send', async (_event, payload: unknown) => {
    const { id, input } = (payload ?? {}) as { id?: string; input?: string };
    if (!id || typeof input !== 'string' || input.trim().length === 0) {
      throw new Error('id and non-empty input are required');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/messages`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ input }),
      });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/tasks/${id}/messages returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('tasks:decide', async (_event, payload: unknown) => {
    const { id, callId, decision, preApprove } =
      (payload ?? {}) as {
        id?: string;
        callId?: string;
        decision?: string;
        preApprove?: { toolName?: string; count?: number };
      };
    if (!id || !callId || !decision) {
      throw new Error('id, callId, and decision are required');
    }
    const body: Record<string, unknown> = { callId, decision };
    if (preApprove && preApprove.toolName && preApprove.count) {
      body.preApproveToolName = preApprove.toolName;
      body.preApproveCount = preApprove.count;
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(id)}/decisions`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/tasks/${id}/decisions returned ${res.status}: ${text}`);
    }
  });

  // ── Credentials ─────────────────────────────────────────────────────────
  // Credentials are uniquely identified by the pair (type, name). The backend
  // exposes them at /api/credentials with optional ?type= filter; per-row
  // operations live at /api/credentials/{type}/{name}.
  ipcMain.handle('credentials:list', async (_event, type: string | null) => {
    const url = type
      ? `${BACKEND_BASE}/api/credentials?type=${encodeURIComponent(type)}`
      : `${BACKEND_BASE}/api/credentials`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /api/credentials returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('credentials:upsert', async (_event, req: unknown) => {
    const res = await fetch(`${BACKEND_BASE}/api/credentials`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Save credential failed (${res.status}): ${body}`);
    }
    // After saving an account-level GitHub PAT, kick the sync job so the
    // user sees their PRs without waiting up to 10 seconds for the next tick.
    if (req && typeof req === 'object') {
      const r = req as { type?: unknown; name?: unknown };
      if (r.type === 'ACCOUNT' && r.name === 'github') {
        void fetch(`${BACKEND_BASE}/api/settings/sync/trigger`, { method: 'POST' })
          .catch(() => { /* best-effort */ });
      }
    }
    return res.json();
  });

  ipcMain.handle('credentials:delete', async (_event, type: string, name: string, instanceName?: string) => {
    // The backend exposes both /{type}/{name} (default-instance shortcut)
    // and /{type}/{name}/{instanceName}. Targeting the explicit instance
    // path keeps the delete unambiguous when multiple instances exist.
    const target = instanceName && instanceName.length > 0 ? instanceName : 'default api';
    const url = `${BACKEND_BASE}/api/credentials/${encodeURIComponent(type)}/${encodeURIComponent(name)}/${encodeURIComponent(target)}`;
    const res = await fetch(url, { method: 'DELETE' });
    if (!res.ok) throw new Error(`Delete credential failed (${res.status})`);
  });

  // ── AI review ───────────────────────────────────────────────────────────
  ipcMain.handle('ai:providers', async () => {
    const res = await fetch(`${BACKEND_BASE}/ai/providers`);
    if (!res.ok) throw new Error(`backend /ai/providers returned ${res.status}`);
    return res.json();
  });

  // POST /ai/polish — body { text } → { text }. Uses the active provider
  // to rewrite a developer-authored review comment for clarity / tone.
  ipcMain.handle('ai:polishComment', async (_event, text: string) => {
    const res = await fetch(`${BACKEND_BASE}/ai/polish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    });
    if (!res.ok) {
      // Surface the backend's error message rather than a generic "500".
      const detail = await res.text().catch(() => '');
      throw new Error(detail || `polish returned ${res.status}`);
    }
    const json = await res.json();
    return json.text as string;
  });

  ipcMain.handle('ai:diagnoseCheck', async (_event, checkName: string, log: string) => {
    const res = await fetch(`${BACKEND_BASE}/ai/diagnoseCheck`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ checkName, log }),
    });
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      throw new Error(detail || `diagnoseCheck returned ${res.status}`);
    }
    const json = await res.json();
    return json.suggestion as string;
  });

  ipcMain.handle('ai:getSettings', async () => {
    const res = await fetch(`${BACKEND_BASE}/ai/settings`);
    if (!res.ok) throw new Error(`backend /ai/settings returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('ai:setSettings', async (_event, provider: string, model: string | null) => {
    const url = new URL(`${BACKEND_BASE}/ai/settings`);
    url.searchParams.set('provider', provider);
    if (model) url.searchParams.set('model', model);
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) throw new Error(`backend POST /ai/settings returned ${res.status}`);
    return res.json();
  });

  // ── Review skills CRUD ────────────────────────────────────────────────
  ipcMain.handle('skills:list', async () => {
    const res = await fetch(`${BACKEND_BASE}/skills`);
    if (!res.ok) throw new Error(`backend /skills returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('skills:create', async (_event, input: {
    skillName: string;
    repo: string;
    llmProvider: string | null;
    description: string | null;
    context: string | null;
  }) => {
    const res = await fetch(`${BACKEND_BASE}/skills`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Create skill failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('skills:update', async (_event, id: number, input: {
    skillName: string;
    repo: string;
    llmProvider: string | null;
    description: string | null;
    context: string | null;
  }) => {
    const res = await fetch(`${BACKEND_BASE}/skills/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Update skill failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('skills:delete', async (_event, id: number) => {
    const res = await fetch(`${BACKEND_BASE}/skills/${id}`, { method: 'DELETE' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Delete skill failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:run', async (_event, prId: number, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/ai/review`);
    url.searchParams.set('prId', String(prId));
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`AI review failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:latest', async (_event, prId: number) => {
    const url = new URL(`${BACKEND_BASE}/ai/review/latest`);
    url.searchParams.set('prId', String(prId));
    const res = await fetch(url);
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`backend /ai/review/latest returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('ai:delete', async (_event, draftId: number) => {
    const res = await fetch(`${BACKEND_BASE}/ai/review/${draftId}`, { method: 'DELETE' });
    if (!res.ok) throw new Error(`Delete draft failed (${res.status})`);
  });

  // Async start: returns immediately while the backend runs the review on its
  // executor; pair with ai:status polling and ai:latest to fetch the result.
  ipcMain.handle('ai:start', async (_event, prId: number, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/ai/review/start`);
    url.searchParams.set('prId', String(prId));
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`AI review start failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:status', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/ai/review/status`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const res = await fetch(url);
    if (!res.ok) throw new Error(`backend /ai/review/status returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('ai:publish', async (_event, draftId: number, event: string, body: string | null) => {
    const url = new URL(`${BACKEND_BASE}/ai/review/${draftId}/publish`);
    url.searchParams.set('event', event);
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: body ?? null }),
    });
    if (!res.ok) {
      const errBody = await res.text().catch(() => '');
      throw new Error(`Publish failed (${res.status}): ${errBody}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:publishForPr', async (_event, payload: {
    prId: number;
    repo: string;
    number: number;
    headSha: string | null;
    event: string;
    body: string | null;
  }) => {
    const url = `${BACKEND_BASE}/ai/review/publish-for-pr`;
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      const errBody = await res.text().catch(() => '');
      throw new Error(`Publish failed (${res.status}): ${errBody}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:editComment', async (_event, draftId: number, commentId: number, editedBody: string | null) => {
    const url = `${BACKEND_BASE}/ai/review/${draftId}/comments/${commentId}`;
    const res = await fetch(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ editedBody }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Edit comment failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:deleteComment', async (_event, draftId: number, commentId: number) => {
    const url = `${BACKEND_BASE}/ai/review/${draftId}/comments/${commentId}`;
    const res = await fetch(url, { method: 'DELETE' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Delete comment failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:dismissComment', async (_event, draftId: number, commentId: number, dismissed: boolean) => {
    const url = `${BACKEND_BASE}/ai/review/${draftId}/comments/${commentId}/dismissed`;
    const res = await fetch(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dismissed }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Dismiss comment failed (${res.status}): ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('ai:stageComment', async (_event, payload: {
    prId: number;
    repo: string;
    number: number;
    headSha: string | null;
    filePath: string;
    line: number;
    side: 'LEFT' | 'RIGHT';
    startLine?: number | null;
    startSide?: 'LEFT' | 'RIGHT' | null;
    body: string;
  }) => {
    const url = `${BACKEND_BASE}/ai/review/stage`;
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Stage comment failed (${res.status}): ${body}`);
    }
    return res.json();
  });
}

async function bootstrapSync(): Promise<void> {
  // The backend reads the GitHub PAT directly from the credentials store
  // now, so all we need to do at startup is nudge the scheduler — if no PAT
  // is configured the trigger is a no-op.
  try {
    await fetch(`${BACKEND_BASE}/api/settings/sync/trigger`, { method: 'POST' });
  } catch (e) {
    console.warn('Could not bootstrap sync on startup:', e);
  }
}

app.on('ready', async () => {
  try {
    applyDevDockIcon();
    installApplicationMenu();
    configureAboutPanel();
    registerIpc();
    spawnBackend();
    if (app.isPackaged) {
      await waitForBackendReady();
    }
    // Open the window immediately so the user isn't staring at a blank screen.
    // The sync runs in the background; the frontend will show data once it arrives.
    await createWindow();
    // Push PAT + trigger sync after the window is open (non-blocking).
    void bootstrapSync();
  } catch (error) {
    console.error('Failed to start ByteQuay', error);
  }
});

// OAuth callback: the integration's browser tab redirects to
// bytequay://<integration>-oauth-callback?code=…&state=… and the OS
// hands the URL to our running app via this event. We forward the
// code+state to the backend's exchange endpoint and tell the renderer
// the result so it can flip from pre-connect to connected.
app.on('open-url', (event, url) => {
  event.preventDefault();
  if (!url.startsWith(APP_PROTOCOL + '://')) return;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return;
  }
  if (parsed.host === 'github-oauth-callback') {
    void handleGitHubOAuthCallback(parsed);
  }
});

async function handleGitHubOAuthCallback(parsed: URL): Promise<void> {
  const code = parsed.searchParams.get('code');
  const state = parsed.searchParams.get('state');
  if (!code || !state) {
    notifyGitHubOauthComplete({ success: false, error: 'Missing code or state in callback URL' });
    return;
  }
  try {
    const res = await fetch(`${BACKEND_BASE}/api/auth/github/callback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, state }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      notifyGitHubOauthComplete({ success: false, error: `backend ${res.status}: ${text}` });
      return;
    }
    const body = (await res.json().catch(() => ({}))) as { login?: string };
    notifyGitHubOauthComplete({ success: true, login: body.login });
  } catch (e) {
    notifyGitHubOauthComplete({ success: false, error: e instanceof Error ? e.message : String(e) });
  }
}

function notifyGitHubOauthComplete(payload: { success: boolean; error?: string; login?: string }): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('github:oauth-complete', payload);
  }
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', () => {
  killBackend();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    void createWindow();
  }
});
