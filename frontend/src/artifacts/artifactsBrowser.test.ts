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

// A saved page as the writer will produce one: the app theme, a summary, steps, and a script.
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

const SETUP = `
const pages = { a1: ${JSON.stringify(PAGE)}, a2: '<!doctype html><title>Snapshots</title><link rel="stylesheet" href="theme.css"><h1>Snapshots and manifests</h1>' };
const artifactView = new ArtifactView(() => window, async target => {
  if (target.workspaceId !== 'w1' || !(target.artifactId in pages)) throw new Error('This artifact no longer exists');
  return pages[target.artifactId];
});
artifactView.register();
const minutes = count => new Date(Date.now() - count * 60_000).toISOString();
ipcMain.handle('fixture:list', () => [
  { id: 'a1', title: 'Delete files', updatedAt: minutes(2) },
  { id: 'a2', title: 'Snapshots and manifests', updatedAt: minutes(90) },
  { id: 'a3', title: 'A page that is gone', updatedAt: minutes(3000) },
]);`;

const BODY = `
const { webContents } = require('electron');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const until = async (check, what) => {
  for (let attempt = 0; attempt < 200; attempt++) { const value = await check(); if (value) return value; await sleep(50); }
  throw new Error('Timed out waiting for ' + what);
};
const page = host => webContents.getAllWebContents().find(contents => !contents.isDestroyed() && !contents.isLoading() && contents.getURL().startsWith('bytequay-artifact://' + host + '/'));
const colour = contents => contents.executeJavaScript('getComputedStyle(document.documentElement).color');
const capture = async name => {
  if (!process.env.BYTEQUAY_ARTIFACT_CAPTURE) return;
  // The native view paints outside the window's own page, so the shot lays its image where it sits.
  const view = artifactView.view;
  await view.webContents.executeJavaScript('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
  await sleep(300);
  const shot = (await view.webContents.capturePage()).toDataURL();
  const bounds = view.getBounds();
  await window.webContents.executeJavaScript('new Promise(resolve => { const image = new Image(); image.id = "capture"; image.onload = resolve; image.src = '
    + JSON.stringify(shot) + '; Object.assign(image.style, { position: "fixed", left: "' + bounds.x + 'px", top: "' + bounds.y + 'px", width: "' + bounds.width + 'px", height: "' + bounds.height + 'px" }); document.body.append(image); })');
  require('fs').writeFileSync(process.env.BYTEQUAY_ARTIFACT_CAPTURE + '-' + name + '.png', (await window.webContents.capturePage()).toPNG());
  await window.webContents.executeJavaScript('document.getElementById("capture").remove()');
};
// A native view in a hidden window may never finish loading; this one is shown, but transparent and click-through.
window.setOpacity(0); window.setIgnoreMouseEvents(true); window.showInactive();
await window.webContents.executeJavaScript('ArtifactTests.render()');
const first = await until(() => page('a1'), 'the first page');
const checks = await first.executeJavaScript('window.checks');
const answered = await first.executeJavaScript("document.getElementById('toggle').click(); !document.getElementById('answer').hidden");
await first.executeJavaScript("location.href = 'https://example.com/'; true");
await sleep(300);
const stayed = first.getURL();
const box = JSON.parse(await window.webContents.executeJavaScript("JSON.stringify(document.querySelector('.artifacts-page__view').getBoundingClientRect())"));
const bounds = artifactView.view.getBounds();
const placed = bounds.x === Math.ceil(box.x) && bounds.y === Math.ceil(box.y) && Math.abs(bounds.width - box.width) <= 1 && Math.abs(bounds.height - box.height) <= 1;
const light = await colour(first);
await capture('light');
nativeTheme.themeSource = 'dark';
const dark = await until(async () => { const value = await colour(first); return value === light ? null : value; }, 'dark colours');
await capture('dark');
nativeTheme.themeSource = 'light';
await window.webContents.executeJavaScript("document.querySelectorAll('.pull-row')[1].click()");
const second = await until(() => page('a2'), 'the second page');
const origin = await second.executeJavaScript('location.origin');
await window.webContents.executeJavaScript("document.querySelectorAll('.pull-row')[2].click()");
const failure = await until(() => window.webContents.executeJavaScript("document.querySelector('.artifacts-page__view [role=alert] p')?.textContent ?? ''"), 'the failure');
const hidden = await until(() => !artifactView.view.getVisible(), 'the page to hide');
const rows = await window.webContents.executeJavaScript("[...document.querySelectorAll('.pull-row')].map(row => row.textContent)");
const results = { checks, answered, stayed, placed, light, dark, origin, failure, hidden, rows };`;

it('shows a saved page whose scripts run while its network, popups, navigation and eval stay blocked', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-artifact-view-'));
  try {
    // The view is main-process code; registering its scheme has to happen before the app is ready.
    await build({ configFile: false, logLevel: 'silent', build: { minify: false, outDir: directory, emptyOutDir: false,
      lib: { entry: resolve('src/artifactView.ts'), formats: ['cjs'], fileName: () => 'artifactView.cjs' }, rollupOptions: { external: ['electron', /^node:/] } } });
    const result = await runBrowserFixture<Record<string, unknown>>({
      prefix: 'bytequay-artifacts-', entry: 'src/artifacts/fixtures/artifactsBrowser.tsx', global: 'ArtifactTests', styles: true, width: 1000, height: 640,
      beforeReady: `const { ArtifactView } = require(${JSON.stringify(join(directory, 'artifactView.cjs'))});`,
      preload: `const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('bridge', { listArtifacts: id => ipcRenderer.invoke('fixture:list', id), showArtifact: (target, bounds) => ipcRenderer.invoke('artifacts:show', target, bounds) });`,
      setup: SETUP, body: BODY, timeoutMs: 60_000,
    });
    expect(result).toEqual({
      checks: { ran: true, network: true, backend: true, popup: true, evalBlocked: true, bridge: true },
      answered: true,
      stayed: 'bytequay-artifact://a1/w1/',
      placed: true,
      light: 'rgb(31, 35, 40)',
      dark: 'rgb(201, 205, 212)',
      origin: 'bytequay-artifact://a2',
      failure: 'This page could not be opened: This artifact no longer exists',
      hidden: true,
      rows: ['Delete files2m ago', 'Snapshots and manifests1h ago', 'A page that is gone2d ago'],
    });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(90_000));
