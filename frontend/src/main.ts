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
import { app, BrowserWindow, dialog, ipcMain, Menu, nativeImage, shell, WebContentsView } from 'electron';
import path from 'node:path';
import fs from 'node:fs';
import { Agent, type Dispatcher, setGlobalDispatcher } from 'undici';
import {
  isPendingManualValidationResponse,
  PrRemoteCommandKeys,
} from './prRemoteCommandKeys';
import type { LocalPrReviewPublicationDto } from './types';

// Pin Node's fetch timeouts explicitly so a future Node default
// change can't quietly flip behaviour under us. 30s for headers
// matches Node's current default and is generous enough for any
// honest backend handler; if a request stalls longer than that the
// JVM is wedged, not slow. Body timeout is bumped to 60s for the
// handful of endpoints that stream a large payload over HTTP/1
// keep-alive (PR diff fetches in particular).
//
// Streaming SSE endpoints and explicitly bounded long operations use their
// own dispatcher/signal instead of weakening this fail-fast default.
setGlobalDispatcher(new Agent({
  headersTimeout: 30_000,
  bodyTimeout: 60_000,
}));

const manualValidationDispatcher = new Agent({
  headersTimeout: 11 * 60_000,
  bodyTimeout: 11 * 60_000,
});

// Keep one command identity across a transport/server failure so an explicit
// user retry replays the durable backend result instead of authorizing a
// second GitHub effect. A completed or definitively rejected HTTP request
// closes that intent; a later click is then a new command.
let prRemoteCommandKeys: PrRemoteCommandKeys;

async function fetchPrRemoteCommand(
  intent: string,
  url: string,
  init: RequestInit,
  dispatcher?: Dispatcher,
): Promise<Response> {
  const commandId = prRemoteCommandKeys.acquire(intent);
  const headers = new Headers(init.headers);
  headers.set('Idempotency-Key', commandId);
  const request: RequestInit & { dispatcher?: Dispatcher } = {
    ...init,
    headers,
    dispatcher,
  };
  const response = await fetch(url, request);
  if (response.status >= 400 && response.status < 500) {
    prRemoteCommandKeys.complete(intent);
  }
  return response;
}

function completePrRemoteCommand(intent: string): void {
  prRemoteCommandKeys.complete(intent);
}

function isLocalPrReviewPublication(value: unknown): value is LocalPrReviewPublicationDto {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.prId === 'string'
    && (typeof candidate.reviewId === 'string' || candidate.reviewId === null)
    && typeof candidate.commandId === 'string'
    && typeof candidate.status === 'string'
    && ['QUEUED', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'INDETERMINATE']
      .includes(candidate.status)
    && typeof candidate.terminal === 'boolean'
    && typeof candidate.finalized === 'boolean'
    && typeof candidate.blocksNewPublication === 'boolean';
}

function completeTerminalReviewPublication(publication: LocalPrReviewPublicationDto): void {
  if (publication.finalized
    && (publication.status === 'PUBLISHED' || publication.terminal)) {
    prRemoteCommandKeys.completeCommand(publication.commandId);
  }
}

async function runPrRemoteCommand(
  intent: string,
  url: URL | string,
  init: RequestInit,
): Promise<Response> {
  const response = await fetchPrRemoteCommand(intent, url.toString(), init);
  if (response.ok) {
    completePrRemoteCommand(intent);
  }
  return response;
}
import { BACKEND_BASE, killBackend, reportBackendFailure, spawnBackend, waitForBackendReady } from './backendProcess';
import { registerTaskStreamIpc } from './threadStreamBridge';

// Override the menu-bar / About-box / dock display name. Without this
// Electron uses its own name in dev mode (the packaged build picks
// this up from forge.config.ts -> packagerConfig.name).
app.setName('ByteQuay');

// App-wide RAM ceiling is ~8 GB. The renderer that hosts our UI and
// every embedded WebContentsView (one per open GitHub page) each get
// their own V8 heap; default max-old-space is ~4 GB per process, so
// without a cap two open embeds plus our renderer can easily climb to
// 8–10 GB. 1 GB per V8 heap is enough for our use (the heaviest page
// is the diff viewer, which we've measured around ~400 MB). Combined
// with the ~2 GB JVM cap and CapacityManager's 4-way CLI lane (capped
// to 512 MB heap each), a busy session lands around ~7.8 GB. Must be
// set before app `ready` so every spawned renderer picks it up.
app.commandLine.appendSwitch('js-flags', '--max-old-space-size=1024');

// Register the bytequay:// custom URL scheme so the OS sends OAuth
// redirects (GitHub, future integrations) back to our running app.
// `open-url` (macOS) / second-instance args (Win/Linux) carry the
// inbound URL once registered. The packaged build also declares this
// in Info.plist; the runtime call covers the dev workflow where the
// .app bundle isn't installed.
const APP_PROTOCOL = 'bytequay';
app.setAsDefaultProtocolClient(APP_PROTOCOL);

// Only one ByteQuay may run at a time: a second instance would spawn its own
// backend against the same port and the same SQLite file, and the user just
// sees two identical windows. Hand focus to the window that's already open.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });
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
 *  About panel renders it crisply. Logs the chosen path so missing-icon issues are visible
 *  in the dev console without a debugger. */
function resolveIconPath(): string | null {
  const roots = [
    path.join(__dirname, '..', '..', '..', 'build'),
    path.join(__dirname, '..', '..', 'build'),
    path.join(process.cwd(), 'build'),
    path.join(process.cwd(), '..', 'build'),
    path.join(app.getAppPath(), 'build'),
    path.join(app.getAppPath(), '..', 'build'),
  ];
  for (const root of roots) {
    const candidate = path.join(root, 'icon.icns');
    if (fs.existsSync(candidate)) {
      // eslint-disable-next-line no-console
      console.log('[ByteQuay] resolved app icon:', candidate);
      return candidate;
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
          click: () => requestInAppOpen('https://github.com/bytequay/bytequay'),
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
    applicationVersion: app.getVersion(),
    version: 'pre-1.0',
    copyright: '© 2026 Jian Chen — Apache License 2.0',
    website: 'https://github.com/bytequay/bytequay',
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

const MAIN_BG = '#ffffff';

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

function requestInAppOpen(url: string): void {
  if (!/^https?:/i.test(url) || !mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send('inapp:open-request', { url });
}

// Third-party auth hosts that refuse to work in embedded browsers. Google has
// enforced this since 2021 on all OAuth flows, regardless of user agent — we
// can't spoof our way around it. Intercept the redirect early and surface a
// banner in the UI so the user knows to switch to GitHub password auth.
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

// `createWindow` is async but `mainWindow` only exists once it has actually
// run, so a dock click during startup (the packaged build awaits the backend
// before opening anything) used to slip past the "no windows yet" check and
// build a second window. Memoise the in-flight call so every caller — ready
// and activate alike — joins the same one.
let pendingWindow: Promise<void> | null = null;

function ensureWindow(): Promise<void> {
  if (mainWindow && !mainWindow.isDestroyed()) return Promise.resolve();
  if (!pendingWindow) {
    pendingWindow = createWindow().finally(() => { pendingWindow = null; });
  }
  return pendingWindow;
}

const createWindow = async () => {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    // `.wu-page` declares a 900px content floor and clips (overflow: hidden)
    // anything past it, so a window narrower than the nav rail's default
    // width (272px) plus that floor cuts off the page header's actions.
    minWidth: 1180,
    minHeight: 620,
    backgroundColor: MAIN_BG,
    vibrancy: process.platform === 'darwin' ? 'sidebar' : undefined,
    visualEffectState: process.platform === 'darwin' ? 'active' : undefined,
    // Hide the native macOS title bar. Lets our GlobalTopbar be the single
    // nav row instead of sitting under a redundant "ByteQuay" title strip.
    // The native traffic-light buttons are hidden outright below — the
    // renderer draws its own (smaller) close/minimize/zoom dots instead —
    // but setWindowButtonVisibility still requires 'hidden'/'hiddenInset'.
    titleBarStyle: 'hiddenInset',
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
  // The native traffic lights are a fixed OS size we can't restyle — hide
  // them permanently and let the renderer's own (smaller, always-on) dots
  // in .sb-traffic be the only close/minimize/zoom controls, in every
  // window state. macOS-only API; no-op elsewhere.
  if (process.platform === 'darwin') mainWindow.setWindowButtonVisibility(false);

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
    if (mainWindow) {
      mainWindow.webContents.setZoomFactor(1);
      sendFullScreenState(mainWindow.isFullScreen());
    }
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

async function ds4Get(path: string): Promise<unknown> {
  return backendJson(`${BACKEND_BASE}${path}`);
}

async function ds4Post(path: string): Promise<unknown> {
  const res = await fetch(`${BACKEND_BASE}${path}`, { method: 'POST' });
  // 202 and 409 both carry a JSON body the caller wants to surface;
  // only treat 5xx / 404 / 400 as errors that block the renderer.
  if (res.status >= 500 || res.status === 404 || res.status === 400) {
    const detail = await res.text().catch(() => '');
    throw new Error(detail || `${path} returned ${res.status}`);
  }
  return res.json();
}

async function backendJson<T = unknown>(input: string | URL, init?: RequestInit): Promise<T> {
  const response = await fetch(input, init);
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(extractMessage(body)
      || `${init?.method ?? 'GET'} ${input.toString()} returned ${response.status}`);
  }
  return response.json() as Promise<T>;
}

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

  // Window controls for the renderer's fake traffic-light dots — the
  // native buttons are permanently hidden (see createWindow), so these
  // are the only close / minimize / restore controls in any state.
  ipcMain.handle('window:control', (_event, action: string) => {
    if (!mainWindow) return;
    if (action === 'close') mainWindow.close();
    else if (action === 'minimize') mainWindow.minimize();
    else if (action === 'zoom') mainWindow.setFullScreen(!mainWindow.isFullScreen());
  });

  // Shown in the Settings rail footer and stamped into bug reports.
  ipcMain.handle('app:version', () => {
    return { version: app.getVersion() };
  });

  ipcMain.handle('dev:local-data-reset-available', () => {
    const marker = process.env.BYTEQUAY_DEV_RESET_MARKER;
    return !app.isPackaged && typeof marker === 'string' && path.isAbsolute(marker);
  });

  ipcMain.handle('dev:reset-local-data', () => {
    const marker = process.env.BYTEQUAY_DEV_RESET_MARKER;
    if (app.isPackaged || typeof marker !== 'string' || !path.isAbsolute(marker)) {
      throw new Error('Local data reset is only available when ByteQuay is started with ./dev.sh');
    }

    // dev.sh observes this marker only after Electron exits. It then stops the
    // backend and all agent subprocesses before removing persistent user data.
    fs.writeFileSync(marker, 'reset\n', { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    setImmediate(() => app.quit());
    return true;
  });

  // SSE broker for per-thread live event streams. Renderer subscribes via
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

  ipcMain.handle('backend:hello', async () => {
    const res = await fetch(`${BACKEND_BASE}/hello`);
    if (!res.ok) throw new Error(`backend /hello returned ${res.status}`);
    return res.text();
  });

  ipcMain.handle('backend:listPrs', async () => {
    return backendJson(`${BACKEND_BASE}/prs`);
  });

  // Saved Views — user-defined concepts (scope=USER) the agent's
  // list_terms / lookup_term tools can read.
  ipcMain.handle('backend:listSavedViews', async () => {
    return backendJson(`${BACKEND_BASE}/api/concepts/user`);
  });
  ipcMain.handle('backend:createSavedView', async (_event, body: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/concepts/user`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  });
  ipcMain.handle('backend:deleteSavedView', async (_event, name: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/concepts/user/${encodeURIComponent(name)}`, {
      method: 'DELETE',
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend delete saved view returned ${res.status}: ${text}`);
    }
  });

  // Typed memory items — pairs with the existing blob-proposal
  // endpoints in WorkspaceController. v1 shows them as a preview
  // surface below the blob diff; later phases promote them to the
  // canonical banner.
  ipcMain.handle('backend:listPendingMemoryItems', async (_event, workspaceId: string) => {
    return backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory/items/pending`);
  });
  ipcMain.handle('backend:applyMemoryItem', async (_event, workspaceId: string, itemId: number) => {
    return backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory/items/${itemId}/apply`,
      { method: 'POST' });
  });
  ipcMain.handle('backend:discardMemoryItem', async (_event, workspaceId: string, itemId: number) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory/items/${itemId}/discard`,
      { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend discard memory item returned ${res.status}: ${body}`);
    }
  });

  // Concept catalog — read-only viewer endpoint behind the
  // Settings page. Returns the full registry minus runtime
  // gating (this is a developer surface).
  ipcMain.handle('backend:listConcepts', async (_event, query: { kind?: string; query?: string }) => {
    const params = new URLSearchParams();
    if (query.kind) params.set('kind', query.kind);
    if (query.query) params.set('query', query.query);
    return backendJson(`${BACKEND_BASE}/api/concepts?${params.toString()}`);
  });

  ipcMain.handle('brain:getView', async (_event, taskId: string) => {
    return backendJson(`${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/brain`);
  });

  ipcMain.handle('brain:sendMessage', async (_event, taskId: string, text: string, images?: string[]) => {
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/brain/message`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, images }),
      },
    );
  });

  ipcMain.handle('stages:getDetail', async (_event, stageId: string) => {
    return backendJson(`${BACKEND_BASE}/api/stages/${encodeURIComponent(stageId)}/detail`);
  });

  ipcMain.handle('stages:getReadinessAssistance', async (
    _event, taskId: string, stageId: string,
  ) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/stages/${encodeURIComponent(stageId)}/readiness-assistance`,
    );
    if (res.status === 404) return null;
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend readiness assistance returned ${res.status}: ${body}`);
    }
    return res.json();
  });

  ipcMain.handle('stages:authorizeReadinessAssistance', async (
    _event,
    taskId: string,
    stageId: string,
    body: import('./types').ReadinessAssistanceRequest,
  ) => {
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/stages/${encodeURIComponent(stageId)}/readiness-assistance`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      },
    );
  });

  ipcMain.handle('runs:forTask', async (_event, taskId: string) => {
    return backendJson(`${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/runs`);
  });

  ipcMain.handle('runs:get', async (_event, runId: string) => {
    return backendJson(`${BACKEND_BASE}/api/runs/${encodeURIComponent(runId)}`);
  });

  ipcMain.handle('rounds:forTask', async (_event, taskId: string) => {
    return backendJson(`${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/rounds`);
  });

  ipcMain.handle('rounds:approve', async (_event, roundId: string) => {
    return backendJson(
      `${BACKEND_BASE}/api/rounds/${encodeURIComponent(roundId)}/approve`, { method: 'POST' });
  });

  // ── PR (unified local/external aggregate) ────────────────────────────
  // A task has at most one PR; a 404 means "none yet", surfaced as null so
  // the renderer can fall back to the remote PR view.
  ipcMain.handle('pr:forTask', async (_event, taskId: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/pr`);
    if (res.status === 404) {
      return null;
    }
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend PR-for-task returned ${res.status}: ${body}`);
    }
    return res.json();
  });
  ipcMain.handle('pr:forRepoPull', async (_event, owner: string, repo: string, number: number) => {
    return backendJson(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/prs/${number}`,
    );
  });
  ipcMain.handle('pr:bundle', async (_event, prId: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/bundle`);
    if (res.status === 404) {
      return null;
    }
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend PR bundle returned ${res.status}: ${body}`);
    }
    return res.json();
  });
  ipcMain.handle('pr:updateDetails', async (_event, prId: string, body: { title?: string; description?: string }) => {
    return backendJson(`${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  });
  ipcMain.handle('pr:push', async (_event, prId: string) => {
    const intent = `push:${prId}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/push`,
      { method: 'POST' },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend PR push returned ${res.status}: ${body}`);
    }
    const result = await res.json();
    completePrRemoteCommand(intent);
    return result;
  });
  ipcMain.handle('pr:merge', async (_event, prId: string, method: string) => {
    const payload = JSON.stringify({ method });
    const intent = `merge:${prId}:${payload}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/merge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend PR merge returned ${res.status}: ${body}`);
    }
    const result = await res.json();
    completePrRemoteCommand(intent);
    return result;
  });
  ipcMain.handle('pr:dequeue', async (_event, prId: string) => {
    const intent = `dequeue:${prId}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/merge-queue`,
      { method: 'DELETE' },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend PR dequeue returned ${res.status}: ${body}`);
    }
    const result = await res.json();
    completePrRemoteCommand(intent);
    return result;
  });
  ipcMain.handle('pr:deleteBranch', async (_event, prId: string) => {
    const intent = `delete-branch:${prId}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/branch`,
      { method: 'DELETE' },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend PR delete-branch returned ${res.status}: ${body}`);
    }
    const result = await res.json();
    completePrRemoteCommand(intent);
    return result;
  });
  ipcMain.handle('pr:postRemoteComment', async (_event, prId: string, body: string) => {
    const payload = JSON.stringify({ body });
    const intent = `comment:${prId}:${payload}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/remote-comments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PR comment returned ${res.status}: ${text}`);
    }
    const result = await res.json();
    completePrRemoteCommand(intent);
    return result;
  });
  ipcMain.handle('pr:publishReview', async (_event, prId: string, body?: unknown) => {
    const payload = body === undefined ? undefined : JSON.stringify(body);
    const intent = `publish-review:${prId}:${payload ?? ''}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/publish-review`, {
      method: 'POST',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: payload,
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body) || `Could not publish review (${res.status})`);
    }
    const result = await res.json();
    if (isLocalPrReviewPublication(result)) {
      completeTerminalReviewPublication(result);
    }
    else {
      // Task-owned PRs retain their existing runtime projection.
      completePrRemoteCommand(intent);
    }
    return result;
  });
  ipcMain.handle('pr:reviewPublication:get', async (_event, prId: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/review-publication`,
    );
    if (res.status === 404) return null;
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(extractMessage(body)
        || `Could not load review publication (${res.status})`);
    }
    const result: unknown = await res.json();
    if (!isLocalPrReviewPublication(result)) {
      throw new Error('Backend returned an invalid review publication');
    }
    completeTerminalReviewPublication(result);
    return result;
  });
  ipcMain.handle('agentReview:get', async (_event, prId: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/agent-review`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend agent review returned ${res.status}: ${body}`);
    }
    return res.json();
  });
  ipcMain.handle('agentReview:start', async (_event, prId: string, body?: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/agent-review`, {
      method: 'POST',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  });

  ipcMain.handle('quickReview:start', async (_event, prId: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/agent-review`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ preset: 'quick' }),
      },
    );
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Quick review start failed (${res.status}): ${body}`);
    }
    await res.json();
    return { state: 'RUNNING' };
  });

  ipcMain.handle('quickReview:status', async (_event, prId: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/agent-review`,
    );
    if (res.status === 404) return { state: 'IDLE', error: null };
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Quick review status failed (${res.status}): ${body}`);
    }
    const review = await res.json() as {
      snapshot_preparation?: {
        scope?: string;
        status?: string;
        error?: string | null;
      } | null;
      rounds?: Array<{ scope?: string; status?: string }>;
    };
    const preparation = review.snapshot_preparation?.scope === 'quick'
      ? review.snapshot_preparation
      : null;
    if (preparation?.status === 'REQUESTED') {
      return { state: 'RUNNING', error: null };
    }
    if (preparation !== null
      && ['FAILED', 'CANCELED', 'SUPERSEDED'].includes(preparation.status ?? '')) {
      return {
        state: 'FAILED',
        error: preparation.error
          ?? (preparation.status === 'CANCELED'
            ? 'Quick review was canceled.'
            : preparation.status === 'SUPERSEDED'
              ? 'Quick review source changed before it could start.'
              : 'Quick review preparation failed.'),
      };
    }
    const rounds = (review.rounds ?? []).filter(round => round.scope === 'quick');
    if (rounds.some(round => round.status === 'QUEUED' || round.status === 'RUNNING')) {
      return { state: 'RUNNING', error: null };
    }
    const latest = rounds.at(-1);
    if (latest === undefined) {
      return preparation?.status === 'COMPLETED'
        ? { state: 'FAILED', error: 'Quick review preparation completed without a review round.' }
        : { state: 'IDLE', error: null };
    }
    if (latest.status?.startsWith('COMPLETED') === true) {
      return { state: 'DONE', error: null };
    }
    return {
      state: 'FAILED',
      error: latest.status === 'CANCELLED'
        ? 'Quick review was canceled.'
        : 'Quick review did not complete. Retry to start a new durable turn.',
    };
  });

  ipcMain.handle('quickReview:latest', async (_event, prId: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/agent-review`,
    );
    if (res.status === 404) return null;
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`Quick review result failed (${res.status}): ${body}`);
    }
    return res.json();
  });
  ipcMain.handle('agentReview:getByThread', async (_event, threadId: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/agent-reviews/by-thread/${encodeURIComponent(threadId)}`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend agent review by thread returned ${res.status}: ${body}`);
    }
    return res.json();
  });
  ipcMain.handle('agentReview:continue', async (_event, reviewId: string, body: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/agent-reviews/${encodeURIComponent(reviewId)}/rounds`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
  });
  ipcMain.handle('agentReview:sendRoundMessage', async (_event, roundId: string, body: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/review-rounds/${encodeURIComponent(roundId)}/messages`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
  });
  ipcMain.handle('agentReview:updateRoundBudget', async (_event, roundId: string, body: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/review-rounds/${encodeURIComponent(roundId)}/budget`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
  });
  ipcMain.handle('agentReview:answerFinding', async (_event, findingId: string, text: string) => {
    return backendJson(`${BACKEND_BASE}/api/findings/${encodeURIComponent(findingId)}/answer`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }),
    });
  });
  ipcMain.handle('agentReview:mutateFinding', async (_event, findingId: string, body: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/findings/${encodeURIComponent(findingId)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
  });
  ipcMain.handle('agentReview:getRoundLog', async (_event, roundId: string) => {
    return backendJson(`${BACKEND_BASE}/api/review-rounds/${encodeURIComponent(roundId)}/log`);
  });
  ipcMain.handle('agentReview:cancelRound', async (_event, roundId: string) => {
    return backendJson(`${BACKEND_BASE}/api/review-rounds/${encodeURIComponent(roundId)}/cancel`, {
      method: 'POST',
    });
  });
  ipcMain.handle('pr:addComment', async (_event, prId: string, body: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/comments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  });
  ipcMain.handle('pr:resolveComment', async (_event, commentId: string) => {
    return backendJson(`${BACKEND_BASE}/api/prs/comments/${encodeURIComponent(commentId)}`, {
      method: 'PATCH',
    });
  });
  ipcMain.handle('pr:deleteComment', async (_event, commentId: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/prs/comments/${encodeURIComponent(commentId)}`, {
      method: 'DELETE',
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PR delete comment returned ${res.status}: ${text}`);
    }
    return undefined;
  });
  ipcMain.handle('pr:dismissComment', async (_event, commentId: string) => {
    return backendJson(`${BACKEND_BASE}/api/prs/comments/${encodeURIComponent(commentId)}/dismiss`, {
      method: 'PATCH',
    });
  });
  ipcMain.handle('pr:reopenComment', async (_event, commentId: string) => {
    return backendJson(`${BACKEND_BASE}/api/prs/comments/${encodeURIComponent(commentId)}/reopen`, {
      method: 'PATCH',
    });
  });
  ipcMain.handle('pr:runTests', async (_event, prId: string) => {
    // The backend waits for its durable operation, so this request gets a
    // matching bounded timeout without weakening every other backend call.
    const intent = `run-tests:${prId}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/run-tests`,
      { method: 'POST' },
      manualValidationDispatcher,
    );
    if (isPendingManualValidationResponse(res.status)) {
      throw new Error('Manual PR validation is still queued or running; retry to check its result');
    }
    // Terminal success or failure closes this intent. A later click may then
    // authorize a fresh operation; transport failure and HTTP 202 retain it.
    completePrRemoteCommand(intent);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PR run-tests returned ${res.status}: ${text}`);
    }
    const result = await res.json();
    return result;
  });

  ipcMain.handle('pr:dashboardList', async () => {
    return backendJson(`${BACKEND_BASE}/api/prs`);
  });
  ipcMain.handle('pr:dashboardSync', async () => {
    return backendJson(`${BACKEND_BASE}/api/prs/sync-list`, { method: 'POST' });
  });
  ipcMain.handle('pr:dashboardMarkHandled', async (_event, prId: string, action: string) => {
    const res = await fetch(`${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/handle`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PR mark-handled returned ${res.status}: ${text}`);
    }
  });
  ipcMain.handle('pr:dashboardApprove', async (_event, prId: string) => {
    const intent = `approve:${prId}`;
    const res = await fetchPrRemoteCommand(
      intent,
      `${BACKEND_BASE}/api/prs/${encodeURIComponent(prId)}/approve`,
      { method: 'POST' },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend PR approve returned ${res.status}: ${text}`);
    }
    completePrRemoteCommand(intent);
  });

  ipcMain.handle('stages:steer', async (
    _event,
    stageId: string,
    text: string,
    images?: string[],
    mode: 'APPEND' | 'CANCEL_AND_REPLACE' = 'APPEND',
    expectedPredecessorStageTurnId?: string,
  ) => {
    return backendJson(
      `${BACKEND_BASE}/api/stages/${encodeURIComponent(stageId)}/steer`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          text, images, mode, expectedPredecessorStageTurnId,
        }),
      },
    );
  });

  ipcMain.handle('plans:approve', async (_event, planStageId: string) => {
    return backendJson(
      `${BACKEND_BASE}/api/stages/${encodeURIComponent(planStageId)}/approve`,
      { method: 'POST' },
    );
  });

  ipcMain.handle('backend:pullRequestDetail', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/detail`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    return backendJson(url);
  });

  ipcMain.handle('backend:refreshPullRequestDetail', async (_event, repo: string, number: number, maxAgeSeconds?: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/detail/refresh`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    if (typeof maxAgeSeconds === 'number' && maxAgeSeconds > 0) {
      url.searchParams.set('maxAgeSeconds', String(maxAgeSeconds));
    }
    return backendJson(url, { method: 'POST' });
  });

  ipcMain.handle('backend:prCheckFailure', async (_event, repo: string, checkRunId: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/checkFailure`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('checkRunId', String(checkRunId));
    return backendJson(url);
  });

  ipcMain.handle('backend:prDiffFiles', async (_event, repo: string, number: number) => {
const url = new URL(`${BACKEND_BASE}/prs/diffFiles`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    return backendJson(url);
  });

  ipcMain.handle('backend:prCommits', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/commits`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    return backendJson(url);
  });

  ipcMain.handle('backend:prCommitDiff', async (_event, repo: string, number: number, sha: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/commitDiff`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    url.searchParams.set('sha', sha);
    return backendJson(url);
  });

  ipcMain.handle('backend:fileBlob', async (_event, repo: string, path: string, sha: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/fileBlob`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('path', path);
    url.searchParams.set('sha', sha);
    return backendJson(url);
  });

  ipcMain.handle('backend:updatePrBody', async (_event, repo: string, number: number, body: string) => {
const url = new URL(`${BACKEND_BASE}/prs/body`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const payload = JSON.stringify({ body });
    const res = await runPrRemoteCommand(`update-body:${repo}#${number}:${payload}`, url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
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
    const payload = JSON.stringify({ body, close });
    const intent = `dashboard-comment:${repo}#${number}:${payload}`;
    const res = await fetchPrRemoteCommand(intent, url.toString(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Comment failed (${res.status}): ${text}`);
    }
    completePrRemoteCommand(intent);
  });

  ipcMain.handle('backend:replyToReviewThread', async (_event, repo: string, number: number, rootCommentId: number, body: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-threads/${rootCommentId}/reply`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const payload = JSON.stringify({ body });
    const res = await runPrRemoteCommand(
      `reply-thread:${repo}#${number}:${rootCommentId}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Reply failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('pr:addPullRequestReaction', async (_event, repo: string, number: number, content: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/${number}/reactions`);
    url.searchParams.set('repo', repo);
    const payload = JSON.stringify({ content });
    const res = await runPrRemoteCommand(
      `react-pr:${repo}#${number}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Reaction failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('pr:addReviewReaction', async (_event, repo: string, number: number, commentId: number, content: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-comments/${commentId}/reactions`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const payload = JSON.stringify({ content });
    const res = await runPrRemoteCommand(
      `react-review-comment:${repo}#${number}:${commentId}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Reaction failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('pr:addIssueReaction', async (_event, repo: string, number: number, commentId: number, content: string) => {
    const url = new URL(`${BACKEND_BASE}/prs/issue-comments/${commentId}/reactions`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const payload = JSON.stringify({ content });
    const res = await runPrRemoteCommand(
      `react-issue-comment:${repo}#${number}:${commentId}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Reaction failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('pr:setThreadResolved', async (_event, repo: string, number: number, prId: number, rootCommentId: number, resolved: boolean) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-threads/${rootCommentId}/resolved`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    url.searchParams.set('prId', String(prId));
    const payload = JSON.stringify({ resolved });
    const res = await runPrRemoteCommand(
      `resolve-thread:${repo}#${number}:${rootCommentId}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
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
    const res = await runPrRemoteCommand(
      `add-reviewer:${repo}#${number}:${reviewer}`,
      url,
      { method: 'POST' },
    );
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
    const res = await runPrRemoteCommand(
      `remove-reviewer:${repo}#${number}:${reviewer}`,
      url,
      { method: 'DELETE' },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Remove reviewer failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:getPrMetadataChoices', async (_event, repo: string, number: number) => {
    const url = new URL(`${BACKEND_BASE}/prs/metadata`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    return backendJson(url);
  });

  ipcMain.handle('backend:setPrAssignee', async (_event, repo: string, number: number, login: string, selected: boolean) => {
    const url = new URL(`${BACKEND_BASE}/prs/assignees`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const payload = JSON.stringify({ value: login, selected });
    const res = await runPrRemoteCommand(
      `set-assignee:${repo}#${number}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Update assignee failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:setPrLabel', async (_event, repo: string, number: number, label: string, selected: boolean) => {
    const url = new URL(`${BACKEND_BASE}/prs/labels`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const payload = JSON.stringify({ value: label, selected });
    const res = await runPrRemoteCommand(
      `set-label:${repo}#${number}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Update label failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:createInlineReviewComment', async (
    _event,
    repo: string,
    number: number,
    body: string,
    path: string,
    line: number,
    side: 'LEFT' | 'RIGHT',
    startLine: number | null,
    startSide: 'LEFT' | 'RIGHT' | null,
  ) => {
    const url = new URL(`${BACKEND_BASE}/prs/review-comments`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    // startLine / startSide are optional; null when single-line. The
    // backend treats startLine===line the same as null and strips both
    // before forwarding to GitHub.
    const payload = JSON.stringify({ body, path, line, side, startLine, startSide });
    const res = await runPrRemoteCommand(
      `inline-comment:${repo}#${number}:${payload}`,
      url,
      {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Inline review comment failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('backend:applySuggestion', async (
    _event,
    repo: string,
    number: number,
    suggestion: string,
    path: string,
    line: number,
    startLine: number | null,
  ) => {
    const url = new URL(`${BACKEND_BASE}/prs/suggestions/apply`);
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
    const payload = JSON.stringify({ suggestion, path, line, startLine });
    const res = await runPrRemoteCommand(
      `apply-suggestion:${repo}#${number}:${payload}`,
      url,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: payload,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Apply suggestion failed (${res.status}): ${text}`);
    }
  });

  ipcMain.handle('repos:list', async () => {
    return backendJson(`${BACKEND_BASE}/api/repos`);
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
    return backendJson(`${BACKEND_BASE}/api/profile`);
  });

  ipcMain.handle('repos:contributionGraph', async (_event, login: string) => {
    const url = new URL(`${BACKEND_BASE}/api/contribution-graph`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });

  ipcMain.handle('repos:contributionGraphDay', async (_event, login: string, date: string) => {
    const url = new URL(`${BACKEND_BASE}/api/contribution-graph/day`);
    url.searchParams.set('login', login);
    url.searchParams.set('date', date);
    return backendJson(url);
  });

  ipcMain.handle('repos:pulls', async (_event, owner: string, repo: string) => {
    return backendJson(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls`,
    );
  });

  ipcMain.handle('repos:pull', async (_event, owner: string, repo: string, number: number) => {
    return backendJson(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls/${number}`,
    );
  });

  ipcMain.handle('repos:searchPulls', async (_event, owner: string, repo: string, query: string) => {
    const url = new URL(`${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls/search`);
    url.searchParams.set('q', query);
    return backendJson(url);
  });

  ipcMain.handle('repos:issues', async (_event, owner: string, repo: string, state?: string) => {
    const url = new URL(`${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues`);
    if (state) url.searchParams.set('state', state);
    return backendJson(url);
  });

  ipcMain.handle('productIssues:report', async (_event, title: string, body: string) => {
    const reportBody = `${body.trim()}\n\n---\nReported from ByteQuay ${app.getVersion()}.`;
    return backendJson(`${BACKEND_BASE}/api/product-issues`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, body: reportBody }),
    });
  });

  ipcMain.handle('repos:meta', async (_event, owner: string, repo: string) => {
    return backendJson(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/meta`,
    );
  });

  ipcMain.handle('repos:listLocal', async () => {
    return backendJson(`${BACKEND_BASE}/api/repos/local`);
  });

  // Native folder picker for settings/install flows. Renderer can't
  // open dialogs directly because contextIsolation hides the Electron
  // module surface.
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

  ipcMain.handle('repos:managedClonePlan', async (
    _event, owner: string, repo: string,
  ) => {
    return backendJson(
      `${BACKEND_BASE}/api/repos/local/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/clone-plan`,
    );
  });

  ipcMain.handle('repos:userRepos', async () => {
    return backendJson(`${BACKEND_BASE}/api/user/repos`);
  });

  ipcMain.handle('repos:recentActivity', async (_event, login: string) => {
const url = new URL(`${BACKEND_BASE}/api/activity/recent`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });

  ipcMain.handle('repos:followingActivity', async (_event, login: string) => {
const url = new URL(`${BACKEND_BASE}/api/activity/following`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });

  // Footprints visit capture. Fire-and-forget by contract: a failed
  // write must never surface to the renderer or block navigation, so we
  // swallow errors here and only warn.
  ipcMain.handle('footprints:recordVisit', async (_event, visit: {
    surfaceType: string;
    surfaceId: string;
    title?: string | null;
    context?: string | null;
  }) => {
    try {
      const res = await fetch(`${BACKEND_BASE}/api/footprints/visit`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(visit),
      });
      if (!res.ok) {
        console.warn(`backend /api/footprints/visit returned ${res.status}`);
      }
    }
    catch (e) {
      console.warn('recordSurfaceVisit failed', e);
    }
  });

  ipcMain.handle('footprints:get', async (_event, date?: string) => {
    const url = new URL(`${BACKEND_BASE}/api/footprints`);
    if (date) url.searchParams.set('date', date);
    return backendJson(url);
  });

  ipcMain.handle('repos:updateProfile', async (_event, name: string, bio: string, location: string) => {
return backendJson(`${BACKEND_BASE}/api/profile`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, bio, location }),
    });
  });

  ipcMain.handle('repos:searchRepos', async (_event, query: string) => {
const url = new URL(`${BACKEND_BASE}/api/search/repos`);
    url.searchParams.set('q', query);
    return backendJson(url);
  });

  ipcMain.handle('shell:openExternal', async (_event, url: string) => {
    if (typeof url === 'string' && (url.startsWith('https://') || url.startsWith('http://'))) {
      await shell.openExternal(url);
    }
  });

  ipcMain.handle('inapp:open', (_event, url: string) => {
    if (typeof url === 'string') requestInAppOpen(url);
  });

  type Bounds = { x: number; y: number; width: number; height: number };
  const roundBounds = (b: Bounds): Bounds => ({
    x: Math.round(b.x),
    y: Math.round(b.y),
    width: Math.max(0, Math.round(b.width)),
    height: Math.max(0, Math.round(b.height)),
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

  // Pop the URL out into its own native Electron window — vanilla
  // BrowserWindow with OS-supplied chrome (close / minimize / Cmd+←
  // for back via the application menu). Independent of the main app
  // window's lifecycle, so multiple URLs can sit side-by-side without
  // a tab strip. Sharing the in-app-browser partition keeps cookies
  // (i.e. logged-in state) consistent with the overlay.
  ipcMain.handle('inapp:popOut', async (_event, url: string) => {
    openInPopupWindow(url);
  });

  // ── GitHub OAuth ────────────────────────────────────────────────────────
  // Renderer-driven handshake: getAuthorizeUrl → openExternal → GitHub
  // redirects to bytequay://github-oauth-callback → open-url handler
  // below forwards code/state to the backend → emits
  // github:oauth-complete. The token lands in the same Keychain slot
  // the PAT path uses, so the rest of the app sees no difference
  // between OAuth and PAT auth.
  ipcMain.handle('githubOAuth:authorizeUrl', async () => {
    return backendJson(`${BACKEND_BASE}/api/auth/github/authorize-url`);
  });

  // Alternative to the OAuth dance for orgs that block PATs but have
  // approved the GitHub CLI: import the token `gh` already holds. Lands
  // in the same Keychain slot, so nothing downstream cares.
  ipcMain.handle('githubCli:available', async (): Promise<{ available: boolean }> => {
    return backendJson<{ available: boolean }>(`${BACKEND_BASE}/api/auth/github/cli`);
  });

  ipcMain.handle('githubCli:import', async (): Promise<{ login: string }> => {
    const res = await fetch(`${BACKEND_BASE}/api/auth/github/cli/import`, { method: 'POST' });
    const body = (await res.json().catch((): null => null)) as { login?: string; message?: string } | null;
    if (!res.ok || !body?.login) {
      // Spring's error body carries gh's own guidance ("please run: gh auth login").
      throw new Error(body?.message ?? `backend /api/auth/github/cli/import returned ${res.status}`);
    }
    return { login: body.login };
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
    return backendJson(`${BACKEND_BASE}/api/auth/gmail/imap/connect`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.trim(), appPassword }),
    });
  });

  ipcMain.handle('gmail:listAccounts', async () => {
    return backendJson(`${BACKEND_BASE}/api/auth/gmail/accounts`);
  });

  ipcMain.handle('gmail:disconnect', async (_event, email: string) => {
    if (typeof email !== 'string' || email.trim().length === 0) {
      throw new Error('email must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/auth/gmail/accounts/${encodeURIComponent(email)}`,
      { method: 'DELETE' });
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
    return backendJson(`${BACKEND_BASE}/api/email/threads?${params.toString()}`);
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
    return backendJson(
      `${BACKEND_BASE}/api/email/threads/refresh?${params.toString()}`,
      { method: 'POST' });
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
    return backendJson(
      `${BACKEND_BASE}/api/email/threads/${encodeURIComponent(id)}?${params.toString()}`);
  });

  // Archive / mark-read / mark-unread share the POST shape: account in
  // query, no body. Each maps to a single users.threads.modify call.
  const threadAction = async (action: string, account: string, id: string) => {
    const params = new URLSearchParams({ account });
    return backendJson(
      `${BACKEND_BASE}/api/email/threads/${encodeURIComponent(id)}/${action}?${params.toString()}`,
      { method: 'POST' });
  };
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
    return backendJson(
      `${BACKEND_BASE}/api/email/threads/${encodeURIComponent(id)}/reply?${params.toString()}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ body }),
      });
  });
  ipcMain.handle('email:muteSender', async (_event, payload: unknown) => {
    const { account, sender } = (payload ?? {}) as { account?: string; sender?: string };
    if (!account || !sender) throw new Error('account and sender are required');
    const params = new URLSearchParams({ account });
    return backendJson(
      `${BACKEND_BASE}/api/email/muted-senders?${params.toString()}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sender }),
      });
  });
  ipcMain.handle('email:unmuteSender', async (_event, payload: unknown) => {
    const { account, sender } = (payload ?? {}) as { account?: string; sender?: string };
    if (!account || !sender) throw new Error('account and sender are required');
    const params = new URLSearchParams({ account });
    return backendJson(
      `${BACKEND_BASE}/api/email/muted-senders/${encodeURIComponent(sender)}?${params.toString()}`,
      { method: 'DELETE' });
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
    return backendJson(`${BACKEND_BASE}/api/email/tags?${params.toString()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
  });
  ipcMain.handle('email:updateTag', async (_event, payload: unknown) => {
    const { id, input } = (payload ?? {}) as {
      id?: string;
      input?: { name: string; subjectContains: string; action: string };
    };
    if (!id || !input) throw new Error('id and input are required');
    return backendJson(`${BACKEND_BASE}/api/email/tags/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
  });
  ipcMain.handle('email:deleteTag', async (_event, payload: unknown) => {
    const { id } = (payload ?? {}) as { id?: string };
    if (!id) throw new Error('id is required');
    return backendJson(`${BACKEND_BASE}/api/email/tags/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    });
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

  // ── Threads ───────────────────────────────────────────────────────────────
  // Local AI coding threads. The list endpoint returns rows across all
  // statuses; the page does its own grouping. Create kicks off the first
  // turn synchronously so the returned row already carries the agent
  // session id where available.
  ipcMain.handle('threads:list', async (_event, args: unknown) => {
    // Back-compat: an older preload passed the groupId positionally
    // as a bare string. The new shape is { groupId?, workspaceId? }.
    let groupId: string | null = null;
    let workspaceId: string | null = null;
    if (typeof args === 'string') {
      groupId = args;
    }
    else if (args !== null && typeof args === 'object') {
      const obj = args as { groupId?: unknown; workspaceId?: unknown };
      if (typeof obj.groupId === 'string' && obj.groupId.trim().length > 0) {
        groupId = obj.groupId;
      }
      if (typeof obj.workspaceId === 'string' && obj.workspaceId.trim().length > 0) {
        workspaceId = obj.workspaceId;
      }
    }
    let url = `${BACKEND_BASE}/api/threads`;
    const params: string[] = [];
    if (groupId !== null) params.push(`groupId=${encodeURIComponent(groupId)}`);
    if (workspaceId !== null) params.push(`workspaceId=${encodeURIComponent(workspaceId)}`);
    if (params.length > 0) url += `?${params.join('&')}`;
    return backendJson(url);
  });

  ipcMain.handle('threads:activeTurns', async () => {
    return backendJson(`${BACKEND_BASE}/api/threads/turns/active`);
  });

  ipcMain.handle('threadGroups:list', async () => {
    return backendJson(`${BACKEND_BASE}/api/thread-groups`);
  });

  ipcMain.handle('threads:create', async (_event, request: unknown) => {
    return backendJson(`${BACKEND_BASE}/api/threads`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request ?? {}),
    });
  });

  // ── Notifications ───────────────────────────────────────────────────────
  ipcMain.handle('notifications:list', async () => {
    return backendJson(`${BACKEND_BASE}/api/notifications`);
  });

  ipcMain.handle('notifications:listUnread', async () => {
    return backendJson(`${BACKEND_BASE}/api/notifications?status=UNREAD`);
  });

  ipcMain.handle('notifications:listForThread', async (_event, threadId: unknown) => {
    if (typeof threadId !== 'string' || threadId.trim().length === 0) {
      throw new Error('threadId must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/notifications?threadId=${encodeURIComponent(threadId)}`);
  });

  ipcMain.handle('notifications:markRead', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/notifications/${encodeURIComponent(id)}/read`,
      { method: 'POST' });
  });

  ipcMain.handle('notifications:dismiss', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/notifications/${encodeURIComponent(id)}/dismiss`,
      { method: 'POST' });
  });

  ipcMain.handle('notifications:approve', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('approve args must be an object');
    }
    const a = args as { id?: unknown; editedBody?: unknown; expectedAction?: unknown };
    if (typeof a.id !== 'string' || a.id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    // editedBody is optional. A present string — including "" — is an
    // explicit override and must be forwarded verbatim: the backend
    // distinguishes null (no override, use the parked body) from ""
    // (the user cleared the textarea on purpose). Only omit the field
    // when the renderer sent no string at all.
    const body: Record<string, unknown> = {};
    if (typeof a.editedBody === 'string') {
      body.editedBody = a.editedBody;
    }
    if (typeof a.expectedAction === 'string' && a.expectedAction.length > 0) {
      body.expectedAction = a.expectedAction;
    }
    return backendJson(
      `${BACKEND_BASE}/api/notifications/${encodeURIComponent(a.id)}/approve`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
  });

  ipcMain.handle('notifications:discard', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('discard args must be an object');
    }
    const a = args as { id?: unknown; expectedAction?: unknown };
    if (typeof a.id !== 'string' || a.id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const body: Record<string, unknown> = {};
    if (typeof a.expectedAction === 'string' && a.expectedAction.length > 0) {
      body.expectedAction = a.expectedAction;
    }
    return backendJson(
      `${BACKEND_BASE}/api/notifications/${encodeURIComponent(a.id)}/discard`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
  });

  ipcMain.handle('threads:listCommits', async (_event, id: unknown, taskId?: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const query = typeof taskId === 'string' && taskId.trim().length > 0
      ? `?taskId=${encodeURIComponent(taskId)}` : '';
    return backendJson(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/commits${query}`);
  });

  ipcMain.handle('threads:cumulativeDiff', async (_event, id: unknown, taskId?: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const query = typeof taskId === 'string' && taskId.trim().length > 0
      ? `?taskId=${encodeURIComponent(taskId)}` : '';
    const url = `${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/cumulative-diff${query}`;
    return backendJson(url);
  });

  ipcMain.handle('threads:fileBlob', async (_event, id: unknown, taskId: unknown, path: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    if (typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('taskId must be a non-empty string');
    }
    if (typeof path !== 'string' || path.length === 0) {
      throw new Error('path must be a non-empty string');
    }
    const url = `${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/fileBlob?taskId=${encodeURIComponent(taskId)}&path=${encodeURIComponent(path)}`;
    return backendJson(url);
  });

  ipcMain.handle('threads:interrupt', async (
    _event, id: unknown, turnId: unknown,
  ) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    if (turnId !== undefined
      && (typeof turnId !== 'string' || turnId.trim().length === 0)) {
      throw new Error('turnId must be a non-empty string when provided');
    }
    const url = new URL(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/interrupt`);
    if (typeof turnId === 'string') url.searchParams.set('turnId', turnId);
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/threads/${id}/interrupt returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('stages:interrupt', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/stages/${encodeURIComponent(id)}/interrupt`,
      { method: 'POST' });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`backend stage interrupt returned ${res.status}: ${body}`);
    }
  });

  ipcMain.handle('threads:get', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const res = await fetch(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/threads/${id} returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('threads:index', async (_event, payload: unknown) => {
    if (typeof payload !== 'object' || payload === null) {
      throw new Error('payload must be an object');
    }
    const { id, cursor, limit, direction } = payload as {
      id?: unknown; cursor?: unknown; limit?: unknown; direction?: unknown;
    };
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    const url = new URL(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/index`);
    if (typeof cursor === 'number' && Number.isFinite(cursor)) {
      url.searchParams.set('cursor', String(Math.trunc(cursor)));
    }
    if (typeof limit === 'number' && Number.isFinite(limit) && limit > 0) {
      url.searchParams.set('limit', String(Math.trunc(limit)));
    }
    if (direction === 'initial' || direction === 'before') {
      url.searchParams.set('direction', direction);
    } else if (direction !== undefined) {
      throw new Error('direction must be initial or before');
    }
    return backendJson(url);
  });

  ipcMain.handle('threads:traceEvents', async (_event, payload: unknown) => {
    if (typeof payload !== 'object' || payload === null) {
      throw new Error('payload must be an object');
    }
    const { id, requestMessageIds } = payload as {
      id?: unknown; requestMessageIds?: unknown;
    };
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    if (!Array.isArray(requestMessageIds)
      || requestMessageIds.some(value => typeof value !== 'string' || value.trim().length === 0)) {
      throw new Error('requestMessageIds must be an array of non-empty strings');
    }
    const url = new URL(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/traces`);
    for (const requestMessageId of requestMessageIds as string[]) {
      url.searchParams.append('requestMessageId', requestMessageId);
    }
    return backendJson(url);
  });

  ipcMain.handle('threads:tasks:list', async (_event, threadId: unknown) => {
    if (typeof threadId !== 'string' || threadId.trim().length === 0) {
      throw new Error('threadId must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/tasks`);
  });

  ipcMain.handle('workspaces:list', async () => {
    return backendJson(`${BACKEND_BASE}/api/workspaces`);
  });

  ipcMain.handle('workspace:api', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('workspace request must be an object');
    }
    const request = args as { path?: unknown; method?: unknown; body?: unknown };
    if (typeof request.path !== 'string'
        || !/^\/api\/(?:workspaces(?:\/|$)|workspace-creations(?:\/|$)|sessions(?:\/|$)|trunks(?:\/|$)|notifications\/workspace(?:\/|$))/.test(request.path)
        || request.path.includes('..')) {
      throw new Error('workspace request path is not allowed');
    }
    const method = typeof request.method === 'string'
      ? request.method.toUpperCase()
      : 'GET';
    if (!['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
      throw new Error('workspace request method is not allowed');
    }
    const response = await fetch(`${BACKEND_BASE}${request.path}`, {
      method,
      headers: request.body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
      // Bound the wait so a slow/stuck backend endpoint fails fast and names
      // itself, instead of hanging ~5 min on undici's default header timeout.
      signal: AbortSignal.timeout(60_000),
    }).catch((err: unknown) => {
      const reason = err instanceof Error && err.name === 'TimeoutError'
        ? 'timed out after 60s (backend slow or unresponsive)'
        : err instanceof Error ? err.message : String(err);
      throw new Error(`workspace request ${method} ${request.path} failed: ${reason}`);
    });
    if (!response.ok) {
      const detail = await response.text().catch(() => '');
      throw new Error(`workspace request ${method} ${request.path} returned ${response.status}: ${detail}`);
    }
    if (response.status === 204) return null;
    return response.json();
  });

  ipcMain.handle('workspaces:delete', async (_event, workspaceId: unknown) => {
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`,
      { method: 'DELETE' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend DELETE /api/workspaces/${workspaceId} returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('workspaces:memory:get', async (_event, workspaceId: unknown) => {
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory`);
  });

  ipcMain.handle('workspaces:memory:set', async (_event, args: unknown) => {
    const params = args as { workspaceId?: unknown; memoryMd?: unknown };
    const workspaceId = params?.workspaceId;
    const memoryMd = params?.memoryMd;
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0
        || typeof memoryMd !== 'string') {
      throw new Error('workspaceId must be a non-empty string; memoryMd must be a string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ memoryMd }),
      });
  });

  ipcMain.handle('workspaces:memory:distill', async (_event, workspaceId: unknown) => {
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory/distill`,
      { method: 'POST' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST memory/distill returned ${res.status}: ${text}`);
    }
    // 204 → nothing to queue (no Overalls, scratch workspace, or
    // proposed body identical to current memory). 200 carries the
    // pending WorkspaceMemoryProposal — the user confirms it via the
    // banner before memory_md actually changes.
    if (res.status === 204) return null;
    return res.json();
  });

  ipcMain.handle('workspaces:memory:proposal:get', async (_event, workspaceId: unknown) => {
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory/proposal`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET memory/proposal returned ${res.status}: ${text}`);
    }
    if (res.status === 204) return null;
    return res.json();
  });

  ipcMain.handle('workspaces:memory:proposal:apply', async (_event, workspaceId: unknown) => {
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory/proposal/apply`,
      { method: 'POST' });
  });

  ipcMain.handle('workspaces:memory:proposal:discard', async (_event, workspaceId: unknown) => {
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/memory/proposal/discard`,
      { method: 'POST' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST memory/proposal/discard returned ${res.status}: ${text}`);
    }
  });

  ipcMain.handle('reviews:get', async (_event, passId: unknown) => {
    if (typeof passId !== 'string' || passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(passId)}`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/reviews/${passId} returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('reviews:byThread', async (_event, threadId: unknown) => {
    if (typeof threadId !== 'string' || threadId.trim().length === 0) {
      throw new Error('threadId must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/reviews/by-thread/${encodeURIComponent(threadId)}`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /api/reviews/by-thread/${threadId} returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  ipcMain.handle('reviews:publication:get', async (_event, passId: unknown) => {
    if (typeof passId !== 'string' || passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    const normalizedPassId = passId.trim();
    const res = await fetch(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(normalizedPassId)}/publication`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(
        `backend GET /api/reviews/${normalizedPassId}/publication returned ${res.status}: ${text}`,
      );
    }
    const publication = await res.json() as { status?: unknown; terminal?: unknown };
    if (publication.status === 'PUBLISHED' || publication.terminal === true) {
      completePrRemoteCommand(`review-pass-publish:${normalizedPassId}`);
    }
    return publication;
  });

  ipcMain.handle('workspace:insights:get', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('workspace insights args must be an object');
    }
    const { workspaceId, window } = args as { workspaceId: string; window: string };
    if (typeof workspaceId !== 'string' || workspaceId.length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    const w = typeof window === 'string' ? window : '7d';
    return backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/insights?window=${encodeURIComponent(w)}`);
  });

  ipcMain.handle('ai:ledger:get', async (_event, month: unknown) => {
    const m = typeof month === 'string' && month.length > 0 ? month : '';
    const url = new URL(`${BACKEND_BASE}/api/ai/ledger`);
    if (m.length > 0) url.searchParams.set('month', m);
    return backendJson(url);
  });

  ipcMain.handle('reviews:arbitrate', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('reviews:arbitrate args must be an object');
    }
    const a = args as { passId?: unknown; findingId?: unknown; resolution?: unknown };
    if (typeof a.passId !== 'string' || a.passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    if (typeof a.findingId !== 'string' || a.findingId.trim().length === 0) {
      throw new Error('findingId must be a non-empty string');
    }
    if (a.resolution !== 'include' && a.resolution !== 'drop') {
      throw new Error("resolution must be 'include' or 'drop'");
    }
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(a.passId)}`
      + `/findings/${encodeURIComponent(a.findingId)}/arbitrate`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ resolution: a.resolution }),
      });
  });

  ipcMain.handle('reviews:addFinding', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('reviews:addFinding args must be an object');
    }
    const a = args as {
      passId?: unknown; severity?: unknown; path?: unknown; line?: unknown; comment?: unknown;
    };
    if (typeof a.passId !== 'string' || a.passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    if (typeof a.comment !== 'string' || a.comment.trim().length === 0) {
      throw new Error('comment must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(a.passId)}/findings`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          severity: typeof a.severity === 'string' ? a.severity : 'MAJOR',
          path: typeof a.path === 'string' && a.path.trim().length > 0 ? a.path : null,
          line: typeof a.line === 'number' ? a.line : null,
          comment: a.comment,
        }),
      });
  });

  ipcMain.handle('reviews:steer', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('reviews:steer args must be an object');
    }
    const a = args as { passId?: unknown; targetParticipantId?: unknown; message?: unknown };
    if (typeof a.passId !== 'string' || a.passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    if (typeof a.targetParticipantId !== 'string' || a.targetParticipantId.trim().length === 0) {
      throw new Error('targetParticipantId must be a non-empty string');
    }
    if (typeof a.message !== 'string' || a.message.trim().length === 0) {
      throw new Error('message must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(a.passId)}/steer`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetParticipantId: a.targetParticipantId, message: a.message }),
      });
  });

  ipcMain.handle('reviews:raiseBudget', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('reviews:raiseBudget args must be an object');
    }
    const a = args as { passId?: unknown; addCostMilli?: unknown; addRounds?: unknown };
    if (typeof a.passId !== 'string' || a.passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    const addCostMilli = typeof a.addCostMilli === 'number' ? a.addCostMilli : 0;
    const addRounds = typeof a.addRounds === 'number' ? a.addRounds : 0;
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(a.passId)}/raise-budget`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ addCostMilli, addRounds }),
      });
  });

  ipcMain.handle('reviews:resume', async (_event, passId: unknown) => {
    if (typeof passId !== 'string' || passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(passId)}/resume`,
      { method: 'POST', headers: { 'Content-Type': 'application/json' } });
  });

  ipcMain.handle('reviews:complete', async (_event, passId: unknown) => {
    if (typeof passId !== 'string' || passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(passId)}/complete`,
      { method: 'POST', headers: { 'Content-Type': 'application/json' } });
  });

  for (const action of ['resolve', 'reopen'] as const) {
    ipcMain.handle(`review:${action}`, async (_event, id: unknown) => {
      if (typeof id !== 'string' || id.trim().length === 0) {
        throw new Error('id must be a non-empty string');
      }
      const res = await fetch(
        `${BACKEND_BASE}/api/review-comments/${encodeURIComponent(id)}/${action}`,
        { method: 'POST' });
      if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`backend POST review-comment ${action} returned ${res.status}: ${text}`);
      }
      return undefined;
    });
  }

  ipcMain.handle('review:submit', async (_event, taskId: unknown, payload: unknown) => {
    if (typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('taskId must be a non-empty string');
    }
    const params = (payload ?? {}) as { body?: unknown; verdict?: unknown; commentIds?: unknown };
    const body = {
      body: typeof params.body === 'string' ? params.body : '',
      verdict: typeof params.verdict === 'string' ? params.verdict : '',
      commentIds: Array.isArray(params.commentIds)
        ? params.commentIds.filter((id): id is string => typeof id === 'string')
        : null,
    };
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/submit-review`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
  });

  // ── Backlog (trunk Backlog tab) ──────────────────────────────────────
  const requireString = (value: unknown, name: string): string => {
    if (typeof value !== 'string' || value.trim().length === 0) {
      throw new Error(`${name} must be a non-empty string`);
    }
    return value;
  };

  ipcMain.handle('backlog:list', async (_event, threadId: unknown) => {
    const id = requireString(threadId, 'threadId');
    return backendJson(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/backlog`);
  });

  ipcMain.handle('backlog:create', async (_event, args: unknown) => {
    const params = args as { threadId?: unknown; title?: unknown; body?: unknown; tags?: unknown; priority?: unknown };
    const id = requireString(params?.threadId, 'threadId');
    const body = {
      title: typeof params.title === 'string' ? params.title : '',
      body: typeof params.body === 'string' ? params.body : '',
      tags: Array.isArray(params.tags) ? params.tags.filter(t => typeof t === 'string') : [],
      priority: typeof params.priority === 'string' ? params.priority : undefined,
    };
    return backendJson(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/backlog`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  });

  ipcMain.handle('backlog:update', async (_event, args: unknown) => {
    const params = args as { itemId?: unknown; title?: unknown; body?: unknown; tags?: unknown };
    const id = requireString(params?.itemId, 'itemId');
    const body: Record<string, unknown> = {};
    if (typeof params.title === 'string') body.title = params.title;
    if (typeof params.body === 'string') body.body = params.body;
    if (Array.isArray(params.tags)) body.tags = params.tags.filter(t => typeof t === 'string');
    return backendJson(`${BACKEND_BASE}/api/backlog/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  });

  ipcMain.handle('backlog:delete', async (_event, itemId: unknown) => {
    const id = requireString(itemId, 'itemId');
    const res = await fetch(`${BACKEND_BASE}/api/backlog/${encodeURIComponent(id)}`, { method: 'DELETE' });
    if (!res.ok) {
      throw new Error(`backend DELETE backlog returned ${res.status}: ${await res.text().catch(() => '')}`);
    }
  });

  ipcMain.handle('backlog:skip', async (_event, args: unknown) => {
    const params = args as { itemId?: unknown; reason?: unknown };
    const id = requireString(params?.itemId, 'itemId');
    return backendJson(`${BACKEND_BASE}/api/backlog/${encodeURIComponent(id)}/skip`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: typeof params.reason === 'string' ? params.reason : undefined }),
    });
  });

  ipcMain.handle('backlog:revive', async (_event, itemId: unknown) => {
    const id = requireString(itemId, 'itemId');
    return backendJson(`${BACKEND_BASE}/api/backlog/${encodeURIComponent(id)}/revive`, { method: 'POST' });
  });

  ipcMain.handle('questions:list', async (_event, threadId: unknown) => {
    const id = requireString(threadId, 'threadId');
    return backendJson(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/questions`);
  });

  ipcMain.handle('questions:answer', async (_event, args: unknown) => {
    const params = args as { questionId?: unknown; answerOptionId?: unknown; answerFreeForm?: unknown };
    const id = requireString(params?.questionId, 'questionId');
    return backendJson(`${BACKEND_BASE}/api/questions/${encodeURIComponent(id)}/answer`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        answerOptionId: typeof params.answerOptionId === 'string' ? params.answerOptionId : undefined,
        answerFreeForm: typeof params.answerFreeForm === 'string' ? params.answerFreeForm : undefined,
      }),
    });
  });

  // ── Thread signals (trunk Notifications tab) ─────────────────────────
  ipcMain.handle('signals:list', async (_event, threadId: unknown) => {
    const id = requireString(threadId, 'threadId');
    return backendJson(`${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/signals`);
  });

  ipcMain.handle('signals:markRead', async (_event, signalId: unknown) => {
    const id = requireString(signalId, 'signalId');
    const res = await fetch(`${BACKEND_BASE}/api/signals/${encodeURIComponent(id)}/read`, { method: 'POST' });
    if (!res.ok) {
      throw new Error(`backend POST signal read returned ${res.status}: ${await res.text().catch(() => '')}`);
    }
  });

  ipcMain.handle('reviews:spawnBuild', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('reviews:spawnBuild args must be an object');
    }
    const a = args as {
      passId?: unknown;
      workspaceId?: unknown;
      openingTitle?: unknown;
      selectedFindingIds?: unknown;
    };
    if (typeof a.passId !== 'string' || a.passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    const body: Record<string, unknown> = {};
    if (typeof a.workspaceId === 'string' && a.workspaceId.length > 0) {
      body.workspaceId = a.workspaceId;
    }
    if (typeof a.openingTitle === 'string' && a.openingTitle.length > 0) {
      body.openingTitle = a.openingTitle;
    }
    if (a.selectedFindingIds !== undefined) {
      // Omission is intentional backward-compatible select-all. Presence is
      // an exact subset; the backend rejects an empty subset.
      if (!Array.isArray(a.selectedFindingIds)
        || !a.selectedFindingIds.every((id) => typeof id === 'string' && id.length > 0)) {
        throw new Error('selectedFindingIds must be an array of non-empty strings');
      }
      body.selectedFindingIds = a.selectedFindingIds;
    }
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(a.passId)}/spawn-build`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
  });

  ipcMain.handle('reviews:buildComments:get', async (_event, passId: unknown) => {
    const id = requireString(passId, 'passId');
    const res = await fetch(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(id)}/build-comments`,
    );
    if (res.status === 404) return null;
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET build-comments returned ${res.status}: ${text}`);
    }
    return res.json();
  });

  const decideReviewBuildComments = async (
    decision: 'approve' | 'discard', args: unknown,
  ) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error(`reviews:buildComments:${decision} args must be an object`);
    }
    const a = args as { passId?: unknown; commandId?: unknown };
    const passId = requireString(a.passId, 'passId');
    const commandId = requireString(a.commandId, 'commandId');
    return backendJson(
      `${BACKEND_BASE}/api/reviews/${encodeURIComponent(passId)}/build-comments/${decision}`,
      { method: 'POST', headers: { 'Idempotency-Key': commandId } },
    );
  };

  ipcMain.handle('reviews:buildComments:approve', (_event, args: unknown) =>
    decideReviewBuildComments('approve', args));
  ipcMain.handle('reviews:buildComments:discard', (_event, args: unknown) =>
    decideReviewBuildComments('discard', args));

  ipcMain.handle('reviews:publish', async (_event, args: unknown) => {
    if (typeof args !== 'object' || args === null) {
      throw new Error('reviews:publish args must be an object');
    }
    const a = args as { passId?: unknown; verdict?: unknown; findingIds?: unknown };
    if (typeof a.passId !== 'string' || a.passId.trim().length === 0) {
      throw new Error('passId must be a non-empty string');
    }
    if (typeof a.verdict !== 'string' || a.verdict.trim().length === 0) {
      throw new Error('verdict must be a non-empty string');
    }
    if (!Array.isArray(a.findingIds) || a.findingIds.some(id => typeof id !== 'string')) {
      throw new Error('findingIds must be an array of strings');
    }
    const passId = a.passId.trim();
    const intent = `review-pass-publish:${passId}`;
    const url = `${BACKEND_BASE}/api/reviews/${encodeURIComponent(passId)}/publish`;
    const res = await fetchPrRemoteCommand(intent, url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verdict: a.verdict, findingIds: a.findingIds }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend POST /api/reviews/${passId}/publish returned ${res.status}: ${text}`);
    }
    const publication = await res.json() as { status?: unknown; terminal?: unknown };
    if (publication.status === 'PUBLISHED' || publication.terminal === true) {
      completePrRemoteCommand(intent);
    }
    return publication;
  });

  ipcMain.handle('workspaces:repos:list', async (_event, workspaceId: unknown) => {
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0) {
      throw new Error('workspaceId must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/repos`);
  });

  ipcMain.handle('threads:tasks:cancel', async (_event, args: unknown) => {
    const params = args as { threadId?: unknown; taskId?: unknown };
    const threadId = params?.threadId;
    const taskId = params?.taskId;
    if (typeof threadId !== 'string' || threadId.trim().length === 0
        || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('threadId and taskId must be non-empty strings');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
        + `/tasks/${encodeURIComponent(taskId)}/cancel`,
      { method: 'POST' });
  });

  for (const action of ['pause', 'resume'] as const) {
    ipcMain.handle(`threads:tasks:${action}`, async (_event, args: unknown) => {
      const params = args as { threadId?: unknown; taskId?: unknown };
      const threadId = params?.threadId;
      const taskId = params?.taskId;
      if (typeof threadId !== 'string' || threadId.trim().length === 0
          || typeof taskId !== 'string' || taskId.trim().length === 0) {
        throw new Error('threadId and taskId must be non-empty strings');
      }
      const url = action === 'resume'
        ? `${BACKEND_BASE}/api/tasks/${encodeURIComponent(taskId)}/resume`
        : `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
          + `/tasks/${encodeURIComponent(taskId)}/${action}`;
      return backendJson(url, { method: 'POST' });
    });
  }

  ipcMain.handle(
    'development-flow:branch-sync:recover',
    async (_event, args: unknown) => {
      const params = args as {
        taskId?: unknown;
        episodeId?: unknown;
        command?: unknown;
      };
      if (typeof params?.taskId !== 'string'
          || params.taskId.trim().length === 0
          || typeof params?.episodeId !== 'string'
          || params.episodeId.trim().length === 0
          || params.command === null || typeof params.command !== 'object') {
        throw new Error('taskId, episodeId, and command are required');
      }
      return backendJson(
        `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
          + `/branch-sync/${encodeURIComponent(params.episodeId)}/recover`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(params.command),
        },
      );
    },
  );

  ipcMain.handle(
    'development-flow:worktree:recover',
    async (_event, args: unknown) => {
      const params = args as {
        taskId?: unknown;
        quarantineId?: unknown;
        command?: unknown;
      };
      if (typeof params?.taskId !== 'string'
          || params.taskId.trim().length === 0
          || typeof params?.quarantineId !== 'string'
          || params.quarantineId.trim().length === 0
          || params.command === null || typeof params.command !== 'object') {
        throw new Error('taskId, quarantineId, and command are required');
      }
      return backendJson(
        `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
          + '/worktree-quarantines/'
          + `${encodeURIComponent(params.quarantineId)}/recover`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(params.command),
        },
      );
    },
  );

  ipcMain.handle(
    'development-flow:local-publish-base-sync:approve',
    async (_event, args: unknown) => {
      const params = args as { taskId?: unknown; blockerId?: unknown };
      if (typeof params?.taskId !== 'string' || params.taskId.trim().length === 0
          || typeof params?.blockerId !== 'string'
          || params.blockerId.trim().length === 0) {
        throw new Error('taskId and blockerId are required');
      }
      return backendJson(
        `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
          + '/local-publish/base-sync/blockers/'
          + `${encodeURIComponent(params.blockerId)}/approve`,
        { method: 'POST' },
      );
    },
  );

  ipcMain.handle(
    'development-flow:local-publish-base-sync:extend',
    async (_event, args: unknown) => {
      const params = args as {
        taskId?: unknown;
        episodeId?: unknown;
        blockerId?: unknown;
        command?: unknown;
      };
      if (typeof params?.taskId !== 'string'
          || params.taskId.trim().length === 0
          || typeof params?.episodeId !== 'string'
          || params.episodeId.trim().length === 0
          || typeof params?.blockerId !== 'string'
          || params.blockerId.trim().length === 0
          || params.command === null || typeof params.command !== 'object') {
        throw new Error(
          'taskId, episodeId, blockerId, and command are required');
      }
      return backendJson(
        `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
          + '/local-publish/base-sync/episodes/'
          + `${encodeURIComponent(params.episodeId)}/blockers/`
          + `${encodeURIComponent(params.blockerId)}/extend`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(params.command),
        },
      );
    },
  );

  ipcMain.handle('development-flow:plan:recover', async (_event, args: unknown) => {
    const params = args as {
      taskId?: unknown;
      failedTurnId?: unknown;
      command?: unknown;
    };
    if (typeof params?.taskId !== 'string' || params.taskId.trim().length === 0
        || typeof params?.failedTurnId !== 'string' || params.failedTurnId.trim().length === 0
        || params.command === null || typeof params.command !== 'object') {
      throw new Error('taskId, failedTurnId, and command are required');
    }
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
        + `/plan/turns/${encodeURIComponent(params.failedTurnId)}/recover`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params.command),
      },
    );
  });

  ipcMain.handle('development-flow:development-brain:recover', async (_event, args: unknown) => {
    const params = args as {
      taskId?: unknown;
      failedTurnId?: unknown;
      command?: unknown;
    };
    if (typeof params?.taskId !== 'string' || params.taskId.trim().length === 0
        || typeof params?.failedTurnId !== 'string' || params.failedTurnId.trim().length === 0
        || params.command === null || typeof params.command !== 'object') {
      throw new Error('taskId, failedTurnId, and command are required');
    }
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
        + `/local-development/brain-turns/${encodeURIComponent(params.failedTurnId)}/recover`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params.command),
      },
    );
  });

  ipcMain.handle('development-flow:branch-sync-brain:recover', async (_event, args: unknown) => {
    const params = args as {
      taskId?: unknown;
      failedTurnId?: unknown;
      command?: unknown;
    };
    if (typeof params?.taskId !== 'string' || params.taskId.trim().length === 0
        || typeof params?.failedTurnId !== 'string' || params.failedTurnId.trim().length === 0
        || params.command === null || typeof params.command !== 'object') {
      throw new Error('taskId, failedTurnId, and command are required');
    }
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
        + `/remote-repair/brain-turns/${encodeURIComponent(params.failedTurnId)}/recover`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params.command),
      },
    );
  });

  ipcMain.handle('development-flow:local-stage:recover', async (_event, args: unknown) => {
    const params = args as {
      taskId?: unknown;
      failedTurnId?: unknown;
      command?: unknown;
    };
    if (typeof params?.taskId !== 'string' || params.taskId.trim().length === 0
        || typeof params?.failedTurnId !== 'string' || params.failedTurnId.trim().length === 0
        || params.command === null || typeof params.command !== 'object') {
      throw new Error('taskId, failedTurnId, and command are required');
    }
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
        + `/local-development/turns/${encodeURIComponent(params.failedTurnId)}/recover`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params.command),
      },
    );
  });

  ipcMain.handle('development-flow:cleanup:recover', async (_event, args: unknown) => {
    const params = args as {
      taskId?: unknown;
      stepId?: unknown;
      command?: unknown;
    };
    if (typeof params?.taskId !== 'string' || params.taskId.trim().length === 0
        || typeof params?.stepId !== 'string' || params.stepId.trim().length === 0
        || params.command === null || typeof params.command !== 'object') {
      throw new Error('taskId, stepId, and command are required');
    }
    return backendJson(
      `${BACKEND_BASE}/api/tasks/${encodeURIComponent(params.taskId)}`
        + `/cleanup/steps/${encodeURIComponent(params.stepId)}/recover`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params.command),
      },
    );
  });

  ipcMain.handle('threads:tasks:autoApprove:get', async (_event, args: unknown) => {
    const { threadId, taskId } = (args ?? {}) as { threadId?: unknown; taskId?: unknown };
    if (typeof threadId !== 'string' || threadId.trim().length === 0
        || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('threadId and taskId must be non-empty strings');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
        + `/tasks/${encodeURIComponent(taskId)}/auto-approve`);
  });

  ipcMain.handle('threads:tasks:autoApprove:set', async (_event, args: unknown) => {
    const { threadId, taskId, enabled } =
      (args ?? {}) as { threadId?: unknown; taskId?: unknown; enabled?: unknown };
    if (typeof threadId !== 'string' || threadId.trim().length === 0
        || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('threadId and taskId must be non-empty strings');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
        + `/tasks/${encodeURIComponent(taskId)}/auto-approve`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: enabled === true }),
      });
  });

  ipcMain.handle('threads:tasks:autoMerge:get', async (_event, args: unknown) => {
    const { threadId, taskId } = (args ?? {}) as { threadId?: unknown; taskId?: unknown };
    if (typeof threadId !== 'string' || threadId.trim().length === 0
        || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('threadId and taskId must be non-empty strings');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
        + `/tasks/${encodeURIComponent(taskId)}/auto-merge`);
  });

  ipcMain.handle('threads:tasks:autoMerge:set', async (_event, args: unknown) => {
    const { threadId, taskId, enabled } =
      (args ?? {}) as { threadId?: unknown; taskId?: unknown; enabled?: unknown };
    if (typeof threadId !== 'string' || threadId.trim().length === 0
        || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('threadId and taskId must be non-empty strings');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
        + `/tasks/${encodeURIComponent(taskId)}/auto-merge`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: enabled === true }),
      });
  });

  ipcMain.handle('threads:tasks:minApprovals:get', async (_event, args: unknown) => {
    const { threadId, taskId } = (args ?? {}) as { threadId?: unknown; taskId?: unknown };
    if (typeof threadId !== 'string' || threadId.trim().length === 0
        || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('threadId and taskId must be non-empty strings');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
        + `/tasks/${encodeURIComponent(taskId)}/min-approvals`);
  });

  ipcMain.handle('threads:tasks:minApprovals:set', async (_event, args: unknown) => {
    const { threadId, taskId, minApprovals } =
      (args ?? {}) as { threadId?: unknown; taskId?: unknown; minApprovals?: unknown };
    if (typeof threadId !== 'string' || threadId.trim().length === 0
        || typeof taskId !== 'string' || taskId.trim().length === 0) {
      throw new Error('threadId and taskId must be non-empty strings');
    }
    const value = typeof minApprovals === 'number' ? minApprovals : 0;
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}`
        + `/tasks/${encodeURIComponent(taskId)}/min-approvals`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ minApprovals: value }),
      });
  });

  ipcMain.handle('threads:trunk:send', async (_event, args: unknown) => {
    const params = args as { threadId?: unknown; input?: unknown; images?: unknown };
    const threadId = params?.threadId;
    const input = params?.input;
    const images = Array.isArray(params?.images) ? params.images : undefined;
    if (typeof threadId !== 'string' || threadId.trim().length === 0) {
      throw new Error('threadId must be a non-empty string');
    }
    if (typeof input !== 'string' || input.trim().length === 0) {
      throw new Error('input must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/trunk-turns`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ input, images }),
      });
  });

  ipcMain.handle('threads:attachment:read', async (_event, args: unknown) => {
    const params = args as { threadId?: unknown; path?: unknown };
    const threadId = params?.threadId;
    const path = params?.path;
    if (typeof threadId !== 'string' || threadId.trim().length === 0) {
      throw new Error('threadId must be a non-empty string');
    }
    if (typeof path !== 'string' || path.trim().length === 0) {
      throw new Error('path must be a non-empty string');
    }
    const res = await fetch(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/attachments?path=${encodeURIComponent(path)}`);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`backend GET /attachments returned ${res.status}: ${text}`);
    }
    const contentType = res.headers.get('content-type') ?? 'application/octet-stream';
    const bytes = Buffer.from(await res.arrayBuffer());
    return `data:${contentType};base64,${bytes.toString('base64')}`;
  });

  ipcMain.handle('workspaces:repos:autoFix', async (_event, args: unknown) => {
    const params = args as { workspaceId?: unknown; owner?: unknown; repo?: unknown; enabled?: unknown };
    const workspaceId = params?.workspaceId;
    const owner = params?.owner;
    const repo = params?.repo;
    const enabled = params?.enabled;
    if (typeof workspaceId !== 'string' || workspaceId.trim().length === 0
        || typeof owner !== 'string' || owner.trim().length === 0
        || typeof repo !== 'string' || repo.trim().length === 0
        || typeof enabled !== 'boolean') {
      throw new Error('workspaceId / owner / repo must be non-empty strings; enabled must be a boolean');
    }
    return backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`
        + `/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/auto-fix-enabled`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ autoFixEnabled: enabled }),
      });
  });

  ipcMain.handle('threads:turns', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/turns`);
  });

  ipcMain.handle('threads:permissions', async (_event, id: unknown) => {
    if (typeof id !== 'string' || id.trim().length === 0) {
      throw new Error('id must be a non-empty string');
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/permissions`);
  });

  ipcMain.handle('threads:decide', async (_event, payload: unknown) => {
    const { id, callId, decision, preApprove, expectedRevision } =
      (payload ?? {}) as {
        id?: string;
        callId?: string;
        decision?: string;
        preApprove?: { toolName?: string; count?: number };
        expectedRevision?: number;
      };
    if (!id || !callId || !decision) {
      throw new Error('id, callId, and decision are required');
    }
    const body: Record<string, unknown> = { callId, decision };
    if (Number.isInteger(expectedRevision) && expectedRevision >= 0) {
      body.expectedRevision = expectedRevision;
    }
    if (preApprove && preApprove.toolName && preApprove.count) {
      body.preApproveToolName = preApprove.toolName;
      body.preApproveCount = preApprove.count;
    }
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(id)}/decisions`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
  });

  // ── Credentials ─────────────────────────────────────────────────────────
  // Credentials are uniquely identified by the pair (type, name). The backend
  // exposes them at /api/credentials with optional ?type= filter; per-row
  // operations live at /api/credentials/{type}/{name}.
  ipcMain.handle('credentials:list', async (_event, type: string | null) => {
    const url = type
      ? `${BACKEND_BASE}/api/credentials?type=${encodeURIComponent(type)}`
      : `${BACKEND_BASE}/api/credentials`;
    return backendJson(url);
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

  ipcMain.handle('credentials:test', async (_event, type: string, name: string, instanceName: string) => {
    const target = instanceName && instanceName.length > 0 ? instanceName : 'default api';
    const url = `${BACKEND_BASE}/api/credentials/${encodeURIComponent(type)}/${encodeURIComponent(name)}/${encodeURIComponent(target)}/test`;
    return backendJson(url, { method: 'POST' });
  });

  ipcMain.handle('credentials:setDefault', async (_event, type: string, name: string, instanceName: string) => {
    const target = instanceName && instanceName.length > 0 ? instanceName : 'default api';
    const url = `${BACKEND_BASE}/api/credentials/${encodeURIComponent(type)}/${encodeURIComponent(name)}/${encodeURIComponent(target)}/default`;
    return backendJson(url, { method: 'PUT' });
  });

  // ── Work-model axis ────────────────────────────────────────────────────
  // CLI auto-detection can take ~600 ms per agent on cold cache and the
  // OS may stall a wedged probe longer — cap at 8 s so the picker shows
  // a clear error instead of spinning "Loading work models…" forever.
  ipcMain.handle('workModels:options', async () => {
    const controller = new AbortController();
    const t = setTimeout(() => controller.abort(), 8_000);
    try {
      return backendJson(`${BACKEND_BASE}/api/work-models`, { signal: controller.signal });
    }
    catch (e) {
      if ((e as { name?: string }).name === 'AbortError') {
        throw new Error('Work-model options timed out after 8s — backend may be unresponsive.');
      }
      throw e;
    }
    finally {
      clearTimeout(t);
    }
  });

  ipcMain.handle('workModels:refresh', async () => {
    const controller = new AbortController();
    const t = setTimeout(() => controller.abort(), 8_000);
    try {
      return backendJson(`${BACKEND_BASE}/api/work-models/refresh`, {
        method: 'POST',
        signal: controller.signal,
      });
    }
    catch (e) {
      if ((e as { name?: string }).name === 'AbortError') {
        throw new Error('Work-model refresh timed out after 8s.');
      }
      throw e;
    }
    finally {
      clearTimeout(t);
    }
  });

  ipcMain.handle('threads:getWorkModel', async (_event, args: unknown) => {
    const { threadId } = args as { threadId: string };
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/work-model`,
    );
  });

  ipcMain.handle('threads:setWorkModel', async (_event, args: unknown) => {
    const { threadId, model } = args as { threadId: string; model: unknown };
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/work-model`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workModel: model }),
      },
    );
  });

  ipcMain.handle('threads:getTaskWorkModel', async (_event, args: unknown) => {
    const { threadId, taskId } = args as { threadId: string; taskId: string };
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/tasks/${encodeURIComponent(taskId)}/work-model`,
    );
  });

  ipcMain.handle('threads:setTaskWorkModel', async (_event, args: unknown) => {
    const { threadId, taskId, model } = args as {
      threadId: string;
      taskId: string;
      model: unknown;
    };
    return backendJson(
      `${BACKEND_BASE}/api/threads/${encodeURIComponent(threadId)}/tasks/${encodeURIComponent(taskId)}/work-model`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workModel: model }),
      },
    );
  });

  ipcMain.handle('threads:getStageWorkModel', async (_event, args: unknown) => {
    const { stageId } = args as { stageId: string };
    return backendJson(
      `${BACKEND_BASE}/api/stages/${encodeURIComponent(stageId)}/work-model`,
    );
  });

  ipcMain.handle('threads:setStageWorkModel', async (_event, args: unknown) => {
    const { stageId, model } = args as { stageId: string; model: unknown };
    return backendJson(
      `${BACKEND_BASE}/api/stages/${encodeURIComponent(stageId)}/work-model`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workModel: model }),
      },
    );
  });

  // ds4 lifecycle / config / metrics passthrough. Every endpoint
  // under /api/ds4 is localhost-only and not agent-callable; see the
  // backend Ds4Controller for the contract.
  ipcMain.handle('ds4:status', async () => ds4Get('/api/ds4/status'));
  ipcMain.handle('ds4:start', async () => ds4Post('/api/ds4/start'));
  ipcMain.handle('ds4:stop', async (_event, args: unknown) => {
    const { confirm } = (args as { confirm?: boolean }) ?? {};
    const suffix = confirm === true ? '?confirm=true' : '';
    return ds4Post(`/api/ds4/stop${suffix}`);
  });
  ipcMain.handle('ds4:restart', async () => ds4Post('/api/ds4/restart'));
  ipcMain.handle('ds4:getConfig', async () => ds4Get('/api/ds4/config'));
  ipcMain.handle('ds4:setConfig', async (_event, args: unknown) => {
    const { config, restart } = args as { config: unknown; restart?: boolean };
    const suffix = restart === true ? '?restart=true' : '';
    return backendJson(`${BACKEND_BASE}/api/ds4/config${suffix}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    });
  });
  ipcMain.handle('ds4:metrics', async () => ds4Get('/api/ds4/metrics'));
  ipcMain.handle('ds4:install', async (_event, body: unknown) => {
    const res = await fetch(`${BACKEND_BASE}/api/ds4/install`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body ?? { reuseExisting: false }),
    });
    if (res.status >= 500 || res.status === 404) {
      const detail = await res.text().catch(() => '');
      throw new Error(detail || `ds4 install returned ${res.status}`);
    }
    return res.json();
  });
  ipcMain.handle('ds4:installStatus', async () => ds4Get('/api/ds4/install/status'));

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

  // ── Skills CRUD ────────────────────────────────────────────────────
  ipcMain.handle('skills:list', async () => {
    return backendJson(`${BACKEND_BASE}/skills`);
  });

  ipcMain.handle('skills:create', async (_event, input: Record<string, unknown>) => {
    return backendJson(`${BACKEND_BASE}/skills`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
  });

  ipcMain.handle('skills:update', async (_event, id: number, input: Record<string, unknown>) => {
    return backendJson(`${BACKEND_BASE}/skills/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
  });

  ipcMain.handle('skills:delete', async (_event, id: number) => {
    return backendJson(`${BACKEND_BASE}/skills/${id}`, { method: 'DELETE' });
  });

  ipcMain.handle('skills:setEnabled', async (_event, id: number, enabled: boolean) => {
    return backendJson(`${BACKEND_BASE}/skills/${id}/enabled`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled }),
    });
  });

  ipcMain.handle('skills:draft', async (_event, prompt: string, scope: string) => {
    return backendJson(`${BACKEND_BASE}/skills/draft`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt, scope }),
    });
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
    prRemoteCommandKeys = new PrRemoteCommandKeys(
      undefined,
      path.join(app.getPath('userData'), 'pending-pr-remote-command-keys-v1.json'),
    );
    registerIpc();
    spawnBackend();
    if (app.isPackaged && !await waitForBackendReady()) {
      // Without this the window opens against a dead sidecar and every action
      // fails with a bare "fetch failed".
      reportBackendFailure();
    }
    // Open the window immediately so the user isn't staring at a blank screen.
    // The sync runs in the background; the frontend will show data once it arrives.
    await ensureWindow();
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
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
    return;
  }
  void ensureWindow();
});
