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
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { build } from 'vite';
import { expect, it } from 'vitest';
import { deadline, runBrowserFixture } from '../fixtures/browserFixture';

// A saved page as the writer produces one: the app theme, a summary, steps, and a script.
const PAGE = `<!doctype html>
<html><head><meta charset="utf-8"><title>Delete files</title><link rel="stylesheet" href="theme.css">
<style>.summary { padding: 10px 14px; border-radius: 8px; background: var(--surface-inset); } .flow { display: flex; gap: 8px; align-items: center; margin: 16px 0; }
.flow span { padding: 4px 10px; border: 1px solid var(--line-default); border-radius: 6px; }</style></head>
<body>
<h1>Delete files</h1>
<p class="summary">In short: Iceberg marks deleted rows in separate files. Readers merge them at scan time.</p>
<div class="flow"><span>Data file</span>→<span>Position deletes</span>→<span>Scan result</span></div>
<ol><li>Open <code>IcebergPageSource</code>.</li><li>Find where deletes join the rows.</li><li>Run the delete test.</li></ol>
<button id="toggle">Check your answer</button><p id="answer" hidden>A position delete names a file and a row.</p>
<script>
document.getElementById('toggle').addEventListener('click', () => { document.getElementById('answer').hidden = false; });
const blocked = async url => { try { await fetch(url); return false; } catch { return true; } };
let evalBlocked = false;
try { eval('1'); } catch { evalBlocked = true; }
window.checks = (async () => ({
  ran: true, network: await blocked('https://example.com/'), backend: await blocked('http://127.0.0.1:9527/hello'),
  popup: window.open('https://example.com/') === null, evalBlocked, bridge: typeof window.bridge === 'undefined' && typeof require === 'undefined',
}))();
</script>
</body></html>`;

// Main answers the page's bridge from these records, as the backend would, and records each call.
const SETUP = `
const pages = { a1: ${JSON.stringify(PAGE)}, a2: '<!doctype html><title>Snapshots</title><link rel="stylesheet" href="theme.css"><h1>Snapshots and manifests</h1>' };
const artifactView = new ArtifactView(() => window, async target => {
  if (target.workspaceId !== 'w1' || !(target.artifactId in pages)) throw new Error('This artifact no longer exists');
  return pages[target.artifactId];
});
artifactView.register();
const minutes = count => new Date(Date.now() - count * 60_000).toISOString();
const record = (id, title, state, age, extra = {}) => ({ id, title, ask: 'Explain ' + title, state, failure: null, writtenAt: minutes(age), next: [], updatedAt: minutes(age), ...extra });
const records = [
  record('a1', 'Delete files', 'READY', 2, { next: [{ id: 'c1', title: 'Equality deletes', ask: 'Explain equality deletes', state: 'SHOWN', artifactId: null }] }),
  record('a2', 'Snapshots and manifests', 'READY', 90),
  record('a3', 'A page that is gone', 'READY', 3000),
  record('a5', 'Manifests', 'FAILED', 4000, { failure: 'Stopped', writtenAt: null }),
];
const calls = [];
const find = id => records.find(item => item.id === id);
ipcMain.handle('fixture:list', () => records);
ipcMain.handle('fixture:ask', (_event, workspaceId, ask) => {
  calls.push(['ask', ask]);
  const asked = { ...record('a4', ask, 'WRITING', 0), ask, writtenAt: null };
  records.unshift(asked);
  return asked;
});
ipcMain.handle('fixture:change', (_event, workspaceId, id, ask) => {
  calls.push(['change', id, ask]);
  Object.assign(find(id), { ask, state: 'WRITING', failure: null, updatedAt: minutes(0) });
  return { ...find(id) };
});
ipcMain.handle('fixture:stop', (_event, workspaceId, id) => { calls.push(['stop', id]); });
ipcMain.handle('fixture:next', (_event, workspaceId, id, cardId, action) => {
  calls.push(['next', id, cardId, action]);
  find(id).next = find(id).next.map(card => card.id === cardId ? { ...card, state: 'DISMISSED' } : card);
  return { ...find(id) };
});`;

const BODY = `
const { webContents } = require('electron');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const until = async (check, what) => {
  for (let attempt = 0; attempt < 200; attempt++) { const value = await check(); if (value) return value; await sleep(50); }
  throw new Error('Timed out waiting for ' + what);
};
const page = host => webContents.getAllWebContents().find(contents => !contents.isDestroyed() && !contents.isLoading() && contents.getURL().startsWith('bytequay-artifact://' + host + '/'));
const inApp = script => window.webContents.executeJavaScript(script);
const colour = contents => contents.executeJavaScript('getComputedStyle(document.documentElement).color');
const field = label => 'document.querySelector(' + JSON.stringify('textarea[aria-label="' + label + '"]') + ')';
const type = async (label, text) => {
  window.webContents.focus();
  await inApp(field(label) + '.focus()');
  await window.webContents.insertText(text);
};
const submit = label => inApp(field(label) + '.form.requestSubmit()');
const press = name => inApp('[...document.querySelectorAll("button")].find(button => button.textContent.trim() === ' + JSON.stringify(name) + ').click()');
const capture = async name => {
  if (!process.env.BYTEQUAY_ARTIFACT_CAPTURE) return;
  // The native view paints outside the window's own page, so the shot lays its image where it sits.
  const view = artifactView.view;
  await view.webContents.executeJavaScript('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
  await sleep(300);
  const shot = (await view.webContents.capturePage()).toDataURL();
  const bounds = view.getBounds();
  await inApp('new Promise(resolve => { const image = new Image(); image.id = "capture"; image.onload = resolve; image.src = '
    + JSON.stringify(shot) + '; Object.assign(image.style, { position: "fixed", left: "' + bounds.x + 'px", top: "' + bounds.y + 'px", width: "' + bounds.width + 'px", height: "' + bounds.height + 'px" }); document.body.append(image); })');
  require('fs').writeFileSync(process.env.BYTEQUAY_ARTIFACT_CAPTURE + '-' + name + '.png', (await window.webContents.capturePage()).toPNG());
  await inApp('document.getElementById("capture").remove()');
};
// A native view in a hidden window may never finish loading; this one is shown, but transparent and click-through.
window.setOpacity(0); window.setIgnoreMouseEvents(true); window.showInactive();
await inApp('ArtifactTests.render()');

const first = await until(() => page('a1'), 'the first page');
const checks = await first.executeJavaScript('window.checks');
const answered = await first.executeJavaScript("document.getElementById('toggle').click(); !document.getElementById('answer').hidden");
await first.executeJavaScript("location.href = 'https://example.com/'; true");
await sleep(300);
const stayed = first.getURL();
const box = JSON.parse(await inApp("JSON.stringify(document.querySelector('.artifacts-page__view').getBoundingClientRect())"));
const bounds = artifactView.view.getBounds();
const placed = bounds.x === Math.ceil(box.x) && bounds.y === Math.ceil(box.y) && Math.abs(bounds.width - box.width) <= 1 && Math.abs(bounds.height - box.height) <= 1;
const light = await colour(first);
await capture('light');
nativeTheme.themeSource = 'dark';
const dark = await until(async () => { const value = await colour(first); return value === light ? null : value; }, 'dark colours');
await capture('dark');
nativeTheme.themeSource = 'light';

const card = await inApp("document.querySelector('.artifacts-next__card')?.textContent ?? ''");
await press('Not now');
await until(() => inApp("!document.querySelector('.artifacts-next__card')"), 'the card to go');

await type('Ask to change this page', 'Make it simpler');
await submit('Ask to change this page');
const rewriting = await until(() => inApp("document.querySelector('.artifacts-running')?.textContent ?? ''"), 'the rewrite');
const keptVisible = artifactView.view.getVisible();
await press('Stop');
pages.a1 = pages.a1.replace('<h1>Delete files</h1>', '<h1>Delete files, simpler</h1>');
Object.assign(find('a1'), { state: 'READY', writtenAt: new Date().toISOString(), updatedAt: new Date().toISOString() });
const reloaded = await until(async () => { const contents = page('a1'); return contents && await contents.executeJavaScript("document.querySelector('h1')?.textContent === 'Delete files, simpler'"); },
  'the rewritten page');

await press('Snapshots and manifests1h ago');
const second = await until(() => page('a2'), 'the second page');
const origin = await second.executeJavaScript('location.origin');
await press('A page that is gone2d ago');
const missing = await until(() => inApp("document.querySelector('.artifacts-page__view [role=alert] p')?.textContent ?? ''"), 'the missing page');
const hidden = await until(() => !artifactView.view.getVisible(), 'the page to hide');

await press('Manifests' + 'Failed');
const failure = await until(() => inApp("document.querySelector('.artifacts-failure')?.textContent ?? ''"), 'the failure');
await press('Try again');
await until(() => calls.some(call => call[0] === 'change' && call[1] === 'a5'), 'the retry');

await type('Ask for a page', 'Explain snapshots');
await submit('Ask for a page');
const rows = await until(async () => {
  const titles = await inApp("[...document.querySelectorAll('.pull-row')].map(row => row.querySelector('.pull-row__title').textContent)");
  return titles.length === 5 ? titles : null;
}, 'the new page in the list');
const writing = await inApp("document.querySelector('.artifacts-detail__placeholder')?.textContent ?? ''");
const results = { checks, answered, stayed, placed, light, dark, card, rewriting: rewriting.replace(/\\d+:\\d\\d/, 'm:ss'), keptVisible, reloaded, origin, missing, hidden,
  failure, writing: writing.replace(/\\d+:\\d\\d/, 'm:ss'), rows, calls };`;

it('writes, changes and shows pages whose scripts run while their network, popups, navigation and eval stay blocked', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-artifact-view-'));
  try {
    // The view is main-process code; registering its scheme has to happen before the app is ready.
    await build({ configFile: false, logLevel: 'silent', build: { minify: false, outDir: directory, emptyOutDir: false,
      lib: { entry: resolve('src/artifactView.ts'), formats: ['cjs'], fileName: () => 'artifactView.cjs' }, rollupOptions: { external: ['electron', /^node:/] } } });
    const result = await runBrowserFixture<Record<string, unknown>>({
      prefix: 'bytequay-artifacts-', entry: 'src/artifacts/fixtures/artifactsBrowser.tsx', global: 'ArtifactTests', styles: true, width: 1000, height: 640,
      beforeReady: `const { ArtifactView } = require(${JSON.stringify(join(directory, 'artifactView.cjs'))});`,
      preload: `const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('bridge', {
  listArtifacts: id => ipcRenderer.invoke('fixture:list', id),
  askArtifact: (id, ask) => ipcRenderer.invoke('fixture:ask', id, ask),
  changeArtifact: (id, artifactId, ask) => ipcRenderer.invoke('fixture:change', id, artifactId, ask),
  stopArtifact: (id, artifactId) => ipcRenderer.invoke('fixture:stop', id, artifactId),
  writeNextArtifact: (id, artifactId, cardId) => ipcRenderer.invoke('fixture:next', id, artifactId, cardId, 'write'),
  dismissNextArtifact: (id, artifactId, cardId) => ipcRenderer.invoke('fixture:next', id, artifactId, cardId, 'dismiss'),
  showArtifact: (target, bounds) => ipcRenderer.invoke('artifacts:show', target, bounds),
});`,
      setup: SETUP, body: BODY, timeoutMs: 60_000,
    });
    expect(result).toEqual({
      checks: { ran: true, network: true, backend: true, popup: true, evalBlocked: true, bridge: true },
      answered: true,
      stayed: 'bytequay-artifact://a1/w1/',
      placed: true,
      light: 'rgb(31, 35, 40)',
      dark: 'rgb(201, 205, 212)',
      card: 'Equality deletesWrite itNot now',
      rewriting: 'Rewriting… m:ssStop',
      keptVisible: true,
      reloaded: true,
      origin: 'bytequay-artifact://a2',
      missing: 'This page could not be opened: This artifact no longer exists',
      hidden: true,
      failure: 'StoppedTry again',
      writing: 'Writing this page… m:ss',
      rows: ['Explain snapshots', 'Delete files', 'Snapshots and manifests', 'A page that is gone', 'Manifests'],
      calls: [['next', 'a1', 'c1', 'dismiss'], ['change', 'a1', 'Make it simpler'], ['stop', 'a1'], ['change', 'a5', 'Explain Manifests'], ['ask', 'Explain snapshots']],
    });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(120_000));
