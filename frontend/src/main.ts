import { app, BrowserWindow, clipboard, ipcMain, session, shell, WebContentsView } from 'electron';
import path from 'node:path';
import started from 'electron-squirrel-startup';
import { BACKEND_BASE, killBackend, spawnBackend, waitForBackendReady } from './backendProcess';

// Handle creating/removing shortcuts on Windows when installing/uninstalling.
if (started) {
  app.quit();
}

function normalizeDevServerUrl(urlString: string): string {
  const url = new URL(urlString);
  if (url.hostname === 'localhost') {
    url.hostname = '127.0.0.1';
  }
  return url.toString();
}

const MAIN_BG = '#f4efe5';

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
  // Belt-and-braces for same-window navigation: a plain <a href> click
  // (or middle-click that bypasses target=_blank) used to navigate the
  // main window itself off to the external page, replacing the React UI.
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (/^https?:/i.test(url)) {
      event.preventDefault();
      requestInAppOpen(url);
    }
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

  ipcMain.handle('backend:mergePr', async (_event, prId: number, repo: string, number: number) => {
const url = new URL(`${BACKEND_BASE}/prs/merge`);
    url.searchParams.set('id', String(prId));
    url.searchParams.set('repo', repo);
    url.searchParams.set('number', String(number));
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
    if (!res.ok) throw new Error(`backend /api/profile returned ${res.status}`);
    return res.json();
  });

  ipcMain.handle('repos:pulls', async (_event, owner: string, repo: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls`,
    );
    if (!res.ok) throw new Error(`backend repo pulls returned ${res.status}`);
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

  ipcMain.handle('repos:issues', async (_event, owner: string, repo: string) => {
    const res = await fetch(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues`,
    );
    if (!res.ok) throw new Error(`backend repo issues returned ${res.status}`);
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
