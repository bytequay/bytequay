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

// A saved page as the writer produces one: the app theme and its classes, a diagram, steps, and a script.
const PAGE = `<!doctype html>
<html><head><meta charset="utf-8"><title>Deletes on read</title><link rel="stylesheet" href="theme.css"></head>
<body><main>
<span class="eyebrow is-muted">Iceberg connector · read path</span>
<h1>Deletes on read</h1>
<aside class="in-short"><p>A scan skips rows two ways: position deletes drop rows by position with a bitmap, and equality deletes match column values.</p></aside>
<figure><svg viewBox="0 0 400 80" role="img" aria-label="Data file to rows kept"><g class="node"><rect x="0.5" y="10" width="120" height="50" rx="8"/><text x="14" y="40">Data file</text></g>
<path class="edge is-accent" d="M121 35H270"/><g class="node is-accent"><rect x="270" y="10" width="120" height="50" rx="8"/><text x="284" y="40">Rows kept</text></g></svg>
<figcaption>Each row passes the position bitmap.</figcaption></figure>
<ol class="steps"><li><strong>The reader opens one data file.</strong> Deletes apply per file. <span class="cite">DeleteManager.java:118</span></li></ol>
<div class="try-it"><button id="toggle">Check your answer</button><p id="answer" hidden>A position delete names a file and a row.</p></div>
</main>
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

// Main answers the page's bridge from these conversations, as the backend would, and records each call.
const SETUP = `
const html = { v1: ${JSON.stringify(PAGE)}, v2: '<!doctype html><link rel="stylesheet" href="theme.css"><main><h1>Deletes on read, simpler</h1></main>' };
const artifactView = new ArtifactView(() => window, async target => {
  if (target.workspaceId !== 'w1' || !(target.pageId in html)) throw new Error('This page no longer exists');
  return html[target.pageId];
});
artifactView.register();
const at = minutes => new Date(Date.now() - minutes * 60_000).toISOString();
let next = 0;
const item = (artifactId, kind, text, extra = {}) => ({ id: 'i' + (++next), artifactId, kind, text, offer: null, pageId: null, at: at(0), ...extra });
const page = (id, key, version, title) => ({ id, artifactId: 'a1', key, version, title, commit: '8bafef5c0ffee', writtenAt: at(0) });
const conversation = (id, title, minutes, items, pages = []) => ({ artifact: { id, title, working: false, updatedAt: at(minutes) }, items, pages });
const views = {
  a1: conversation('a1', 'Help me learn how Iceberg position deletes work', 12, [
    item('a1', 'USER', 'Help me learn how Iceberg position deletes work'),
    item('a1', 'ACTIVITY', 'IcebergPageSourceProvider.java · 6 steps'),
    item('a1', 'REPLY', 'Position deletes are applied per data file (\`DeleteManager.java:118\`). Read path, write path, or both?'),
    item('a1', 'USER', 'The read path, and how it differs from equality deletes'),
    item('a1', 'OFFER', '', { offer: { title: 'Deletes on read', covers: 'How a scan skips rows named by position deletes.', state: 'SHOWN' } }),
  ]),
  a2: conversation('a2', 'Snapshots and manifests', 90, [item('a2', 'USER', 'Explain snapshots'), item('a2', 'PAGE', 'Snapshots · v1', { pageId: 'gone' })],
    [{ ...page('gone', 'p1', 1, 'Snapshots'), artifactId: 'a2' }]),
  a3: conversation('a3', 'Explain manifests', 3000, [item('a3', 'USER', 'Explain manifests'), item('a3', 'FAILURE', 'Stopped')]),
};
let busy = false;
const calls = [];
const working = (id, text) => {
  views[id].artifact.working = true;
  views[id].items.push(item(id, 'USER', text));
  return views[id];
};
// What the agent does at the end of a turn: say, save, and stop working.
const finish = (id, ...items) => {
  views[id].items.push(...items);
  views[id].artifact.working = false;
};
ipcMain.handle('fixture:list', () => Object.values(views).reverse().map(view => ({ artifact: view.artifact, pages: new Set(view.pages.map(entry => entry.key)).size })));
ipcMain.handle('fixture:view', (_event, workspaceId, id) => views[id]);
ipcMain.handle('fixture:start', (_event, workspaceId, text) => {
  calls.push(['start', text]);
  views.a4 = conversation('a4', text, 0, []);
  return working('a4', text);
});
ipcMain.handle('fixture:send', (_event, workspaceId, id, text) => {
  calls.push(['send', id, text]);
  if (busy) throw new Error('Wait for “Help me learn how Iceberg position deletes work” to finish');
  return working(id, text);
});
ipcMain.handle('fixture:offer', (_event, workspaceId, id, itemId, accept) => {
  calls.push(['offer', id, itemId, accept]);
  const offered = views[id].items.find(entry => entry.id === itemId);
  offered.offer = { ...offered.offer, state: accept ? 'ACCEPTED' : 'DISMISSED' };
  return accept ? working(id, 'Make the page.') : views[id];
});
ipcMain.handle('fixture:stop', (_event, workspaceId, id) => { calls.push(['stop', id]); });`;

const BODY = `
const { webContents } = require('electron');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const until = async (check, what) => {
  for (let attempt = 0; attempt < 200; attempt++) { const value = await check(); if (value) return value; await sleep(50); }
  throw new Error('Timed out waiting for ' + what);
};
const shown = (host, heading) => until(async () => {
  const contents = webContents.getAllWebContents().find(entry => !entry.isDestroyed() && !entry.isLoading() && entry.getURL().startsWith('bytequay-artifact://' + host + '/'));
  return contents && artifactView.view.getVisible() && await contents.executeJavaScript("document.querySelector('h1')?.textContent") === heading && contents;
}, heading);
const inApp = script => window.webContents.executeJavaScript(script);
const text = selector => inApp('[...document.querySelectorAll(' + JSON.stringify(selector) + ')].map(element => element.textContent)');
const click = selector => inApp('document.querySelector(' + JSON.stringify(selector) + ').click()');
const press = name => inApp('[...document.querySelectorAll("button")].find(button => button.textContent.trim() === ' + JSON.stringify(name) + ').click()');
const open = title => until(() => inApp('(() => { const row = [...document.querySelectorAll(".artifacts-row")].find(row => row.querySelector(".artifacts-row__title").textContent === '
  + JSON.stringify(title) + '); row?.click(); return !!row; })()'), title);
const type = async (label, value) => {
  window.webContents.focus();
  await inApp('document.querySelector(' + JSON.stringify('textarea[aria-label="' + label + '"]') + ').focus()');
  await window.webContents.insertText(value);
};
const send = () => until(() => inApp("(() => { const button = document.querySelector('[aria-label=\\"Send\\"]'); if (!button || button.disabled) return false; button.click(); return true; })()"),
  'the send button');
const capture = async name => {
  if (!process.env.BYTEQUAY_ARTIFACT_CAPTURE) return;
  await sleep(300);
  // The native view paints outside the window's own page, so the shot lays its image where it sits.
  const view = artifactView.view;
  const laid = view && view.getVisible();
  if (laid) {
    await view.webContents.executeJavaScript('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    const shot = (await view.webContents.capturePage()).toDataURL();
    const bounds = view.getBounds();
    await inApp('new Promise(resolve => { const image = new Image(); image.id = "capture"; image.onload = resolve; image.src = '
      + JSON.stringify(shot) + '; Object.assign(image.style, { position: "fixed", left: "' + bounds.x + 'px", top: "' + bounds.y + 'px", width: "' + bounds.width + 'px", height: "' + bounds.height + 'px" }); document.body.append(image); })');
  }
  require('fs').writeFileSync(process.env.BYTEQUAY_ARTIFACT_CAPTURE + '-' + name + '.png', (await window.webContents.capturePage()).toPNG());
  if (laid) await inApp('document.getElementById("capture").remove()');
};
// A native view in a hidden window may never finish loading; this one is shown, but transparent and click-through.
window.setOpacity(0); window.setIgnoreMouseEvents(true); window.showInactive();
await inApp('ArtifactTests.render()');

const rows = await until(async () => { const titles = await text('.artifacts-row'); return titles.length === 3 && titles; }, 'the list');
await capture('list');
await open('Help me learn how Iceberg position deletes work');
const talk = await until(async () => { const lines = await text('.artifacts-chat__column > *'); return lines.length === 5 && lines; }, 'the conversation');
await capture('offer');

await press('Make the page');
const making = await until(() => inApp("document.querySelector('.artifacts-panel__placeholder')?.textContent ?? ''"), 'the placeholder');
const writingVersion = (await text('.artifacts-versions'))[0];
await capture('making');
views.a1.pages.push(page('v1', 'p1', 1, 'Deletes on read'));
views.a1.artifact.title = 'Deletes on read';
finish('a1', item('a1', 'ACTIVITY', 'IcebergSplitSource.java · 5 steps'), item('a1', 'PAGE', 'Deletes on read · v1', { pageId: 'v1' }), item('a1', 'REPLY', "Here's the page."));

const first = await shown('a1', 'Deletes on read');
const checks = await first.executeJavaScript('window.checks');
const answered = await first.executeJavaScript("document.getElementById('toggle').click(); !document.getElementById('answer').hidden");
await first.executeJavaScript("location.href = 'https://example.com/'; true");
await sleep(300);
const stayed = first.getURL();
const box = JSON.parse(await inApp("JSON.stringify(document.querySelector('.artifacts-page__view').getBoundingClientRect())"));
const bounds = artifactView.view.getBounds();
const placed = bounds.x === Math.ceil(box.x) && bounds.y === Math.ceil(box.y) && Math.abs(bounds.width - box.width) <= 1 && Math.abs(bounds.height - box.height) <= 1;
const colour = () => first.executeJavaScript('getComputedStyle(document.body).color');
const light = await colour();
await capture('page-light');
nativeTheme.themeSource = 'dark';
const dark = await until(async () => { const value = await colour(); return value === light ? null : value; }, 'dark colours');
await capture('page-dark');
nativeTheme.themeSource = 'light';
const panel = await text('.artifacts-panel__bar');

await type('Reply', 'Make the diagram simpler');
await send();
const reading = await until(() => inApp("document.querySelector('.artifacts-line[role=status]')?.textContent ?? ''"), 'the turn');
const keptVisible = artifactView.view.getVisible();
await capture('reading');
await click('[aria-label="Stop"]');
views.a1.pages.push(page('v2', 'p1', 2, 'Deletes on read'));
finish('a1', item('a1', 'PAGE', 'Deletes on read · v2', { pageId: 'v2' }));
await shown('a1', 'Deletes on read, simpler');
const versions = await text('.artifacts-versions button');
await press('v1');
await shown('a1', 'Deletes on read');
await capture('versions');

busy = true;
await type('Reply', 'And the write path?');
await send();
const refused = await until(() => inApp("document.querySelector('.artifacts-line[role=alert]')?.textContent ?? ''"), 'the refusal');
const kept = await inApp('document.querySelector(\\'textarea[aria-label="Reply"]\\').value');
busy = false;

await click('[aria-label="Back to Artifacts"]');
const counted = await until(async () => { const titles = await text('.artifacts-row'); return titles.length === 3 && titles; }, 'the list again');
const hiddenOnList = await until(() => !artifactView.view.getVisible(), 'the page to hide');

await open('Snapshots and manifests');
const missing = await until(() => inApp("document.querySelector('.artifacts-page__view [role=alert] span')?.textContent ?? ''"), 'the missing page');
await click('[aria-label="Back to Artifacts"]');
await open('Explain manifests');
const failure = await until(() => inApp("document.querySelector('.artifacts-line.is-failure')?.textContent ?? ''"), 'the failure');
await capture('failure');
await press('Try again');
await until(() => calls.some(call => call[0] === 'send' && call[1] === 'a3'), 'the retry');

await click('[aria-label="Back to Artifacts"]');
await until(() => inApp("!!document.querySelector('textarea[aria-label=\\"Ask about this repository\\"]')"), 'the start box');
await type('Ask about this repository', 'Where are dynamic filters applied?');
await send();
const started = await until(async () => { const lines = await text('.artifacts-chat__column .agt-user__bubble'); return lines.length === 1 && lines; }, 'the new conversation');
const results = { rows, talk, making: making.replace(/\\d+:\\d\\d/, 'm:ss'), writingVersion, checks, answered, stayed, placed, light, dark, panel,
  reading: reading.replace(/\\d+:\\d\\d/, 'm:ss'), keptVisible, versions, refused, kept, counted, hiddenOnList, missing, failure, started, calls };`;

it('talks, offers a page, then shows its versions beside the chat while the page stays locked down', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-artifact-view-'));
  try {
    // The view is main-process code; registering its scheme has to happen before the app is ready.
    await build({ configFile: false, logLevel: 'silent', build: { minify: false, outDir: directory, emptyOutDir: false,
      lib: { entry: resolve('src/artifactView.ts'), formats: ['cjs'], fileName: () => 'artifactView.cjs' }, rollupOptions: { external: ['electron', /^node:/] } } });
    const result = await runBrowserFixture<Record<string, unknown>>({
      prefix: 'bytequay-artifacts-', entry: 'src/artifacts/fixtures/artifactsBrowser.tsx', global: 'ArtifactTests', styles: true, width: 1280, height: 800,
      beforeReady: `const { ArtifactView } = require(${JSON.stringify(join(directory, 'artifactView.cjs'))});`,
      preload: `const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('bridge', {
  listArtifacts: id => ipcRenderer.invoke('fixture:list', id),
  startArtifact: (id, text) => ipcRenderer.invoke('fixture:start', id, text),
  viewArtifact: (id, artifactId) => ipcRenderer.invoke('fixture:view', id, artifactId),
  sendArtifact: (id, artifactId, text) => ipcRenderer.invoke('fixture:send', id, artifactId, text),
  answerArtifactOffer: (id, artifactId, itemId, accept) => ipcRenderer.invoke('fixture:offer', id, artifactId, itemId, accept),
  stopArtifact: (id, artifactId) => ipcRenderer.invoke('fixture:stop', id, artifactId),
  showArtifact: (target, bounds) => ipcRenderer.invoke('artifacts:show', target, bounds),
});`,
      setup: SETUP, body: BODY, timeoutMs: 60_000,
    });
    expect(result).toEqual({
      rows: ['Explain manifests2d ago', 'Snapshots and manifests11h ago', 'Help me learn how Iceberg position deletes work12m ago'],
      talk: [
        'Help me learn how Iceberg position deletes work',
        'IcebergPageSourceProvider.java·6 steps',
        'Position deletes are applied per data file (DeleteManager.java:118). Read path, write path, or both?',
        'The read path, and how it differs from equality deletes',
        'Deletes on readHow a scan skips rows named by position deletes.Make the pageNot yet',
      ],
      making: 'Writing the page…m:ss',
      writingVersion: 'v1 writing…',
      checks: { ran: true, network: true, backend: true, popup: true, evalBlocked: true, bridge: true },
      answered: true,
      stayed: 'bytequay-artifact://a1/w1/',
      placed: true,
      light: 'rgb(31, 35, 40)',
      dark: 'rgb(201, 205, 212)',
      panel: ['Deletes on read8bafef5v1'],
      reading: 'Reading…m:ss',
      keptVisible: true,
      versions: ['v1', 'v2'],
      refused: 'Wait for “Help me learn how Iceberg position deletes work” to finish',
      kept: 'And the write path?',
      counted: ['Explain manifests2d ago', 'Snapshots and manifests11h ago', 'Deletes on read112m ago'],
      hiddenOnList: true,
      missing: 'This page could not be opened: This page no longer exists',
      failure: 'StoppedTry again',
      started: ['Where are dynamic filters applied?'],
      calls: [['offer', 'a1', 'i5', true], ['send', 'a1', 'Make the diagram simpler'], ['stop', 'a1'], ['send', 'a1', 'And the write path?'], ['send', 'a3', 'Explain manifests'],
        ['start', 'Where are dynamic filters applied?']],
    });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(120_000));
