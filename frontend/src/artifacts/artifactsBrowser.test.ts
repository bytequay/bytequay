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
// A STUN lookup or a UDP candidate would carry data past every request check.
const udpCandidates = () => new Promise(resolve => {
  const peer = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.invalid:3478' }] });
  const found = [];
  const done = () => { peer.close(); resolve(found.filter(protocol => protocol === 'udp').length); };
  peer.onicecandidate = event => { if (event.candidate) found.push(event.candidate.protocol); else done(); };
  peer.createDataChannel('probe');
  peer.createOffer().then(offer => peer.setLocalDescription(offer));
  setTimeout(done, 3000);
});
window.checks = (async () => ({
  ran: true, network: await blocked('https://example.com/'), backend: await blocked('http://127.0.0.1:9527/hello'), webrtc: await udpCandidates() === 0,
  popup: window.open('https://example.com/') === null, evalBlocked, bridge: typeof window.bridge === 'undefined' && typeof require === 'undefined',
}))();
</script>
</body></html>`;

// Main answers the page's bridge from these conversations, as the backend would: a view and an agent event log each, and records each call.
const SETUP = `
const html = { v1: ${JSON.stringify(PAGE)}, v2: '<!doctype html><link rel="stylesheet" href="theme.css"><main><h1>Deletes on read, simpler</h1></main>' };
let delayedPageId = null;
let releasePageRead;
const artifactView = new ArtifactView(() => window, async target => {
  if (target.workspaceId !== 'w1' || !(target.pageId in html)) throw new Error('This page no longer exists');
  if (target.pageId === delayedPageId) {
    delayedPageId = null;
    await new Promise(resolve => { releasePageRead = resolve; });
  }
  return html[target.pageId];
});
artifactView.register();
const backgroundCaptures = [];
ipcMain.handle('fixture:background', async () => {
  const view = artifactView.view;
  if (!view?.getVisible()) return [];
  const heading = await view.webContents.executeJavaScript("document.querySelector('h1')?.textContent");
  const image = (await view.webContents.capturePage(undefined, { stayHidden: true })).toDataURL();
  backgroundCaptures.push({ heading, image });
  return [{ ...view.getBounds(), image }];
});
const at = minutes => new Date(Date.now() - minutes * 60_000).toISOString();
const root = { providerThreadId: null, parentProviderThreadId: null, logicalAgentPath: null, providerTurnId: null, providerItemId: null, providerEventId: null };
const QUESTION = JSON.stringify({ questions: [{ question: 'Which path?', header: 'Path', multiSelect: false,
  options: [{ label: 'Read path', description: 'How a scan skips rows' }, { label: 'Write path', description: 'How deletes are written' }] }] });
const conversations = {};
let next = 0;
const conversation = (id, title, minutes) => {
  conversations[id] = { view: { artifact: { id, title, working: false, updatedAt: at(minutes) }, offers: [], pages: [], questions: [], pendingApprovals: [] }, events: [] };
  return conversations[id];
};
// The same events a task's turn records.
const emit = (id, turnId, type, payload) => {
  const log = conversations[id].events;
  log.push({ schemaVersion: 2, eventId: 'e' + (++next), sequence: log.length + 1, taskId: id, turnId, source: root, occurredAt: at(0), type, payload });
};
const turn = (id, turnId, text) => {
  emit(id, turnId, 'user_message', { text, attachments: [], deliveryKind: 'TURN' });
  emit(id, turnId, 'lifecycle', { phase: 'TURN_STARTED', message: 'Turn started' });
  conversations[id].view.artifact.working = true;
};
const answer = (id, turnId, text) => emit(id, turnId, 'assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text });
const done = (id, turnId, message = 'Turn completed') => {
  emit(id, turnId, 'lifecycle', { phase: 'TURN_COMPLETED', message });
  conversations[id].view.artifact.working = false;
};
const page = (id, version, turnId) => ({ id, key: 'p1', version, title: 'Deletes on read', commit: '8bafef5c0ffee', writtenAt: at(0), turnId });

conversation('a1', 'Help me learn how Iceberg position deletes work', 12);
turn('a1', 't1', 'Help me learn how Iceberg position deletes work');
emit('a1', 't1', 'tool', { name: 'Grep', state: 'COMPLETED', summary: '74:import com.example.iceberg.delete.DeleteManager;', durationMs: null, description: 'Find where deletes are read' });
answer('a1', 't1', 'Position deletes are applied per data file (\`DeleteManager.java:118\`).');
done('a1', 't1');
turn('a1', 't2', 'The read path, and how it differs from equality deletes');
emit('a1', 't2', 'approval_requested', { approvalId: 'q1', approvalKind: 'OTHER', title: 'Allow Claude AskUserQuestion', detail: QUESTION, scope: ['AskUserQuestion'],
  sandboxed: false, actionable: true, availableDecisions: ['ALLOW_ONCE', 'DENY'] });
conversations.a1.view.questions = ['q1'];
conversations.a1.view.pendingApprovals = [{ ...conversations.a1.events.at(-1).payload, taskId: 'a1', turnId: 't2', source: root, occurredAt: at(0) }];
conversation('a2', 'Snapshots and manifests', 90);
turn('a2', 't21', 'Explain snapshots');
answer('a2', 't21', 'Here is the page.');
conversations.a2.view.pages.push({ ...page('gone', 1, 't21'), title: 'Snapshots' });
done('a2', 't21');
conversation('a3', 'Explain manifests', 3000);
turn('a3', 't31', 'Explain manifests');
emit('a3', 't31', 'error', { code: 'TURN_FAILED', message: 'Claude turn timed out', retryable: false });
done('a3', 't31', 'Turn failed: Claude turn timed out');

let busy = false;
let turns = 4;
const calls = [];
ipcMain.handle('fixture:list', () => Object.values(conversations).map(entry => entry.view)
  .sort((left, right) => right.artifact.updatedAt.localeCompare(left.artifact.updatedAt))
  .map(view => ({ artifact: view.artifact, pages: new Set(view.pages.map(entry => entry.key)).size })));
ipcMain.handle('fixture:view', (_event, workspaceId, id) => conversations[id].view);
ipcMain.handle('fixture:events', (_event, workspaceId, id, before, after) => {
  const all = conversations[id].events;
  const events = before !== undefined ? all.filter(event => event.sequence < Number(before)).slice(-500)
    : after !== undefined ? all.filter(event => event.sequence > Number(after)).slice(0, 500) : all.slice(-500);
  const first = events[0]?.sequence ?? 1;
  const last = events.at(-1)?.sequence ?? Number(after ?? 0);
  const latestSequence = all.at(-1)?.sequence ?? 0;
  return { events, beforeCursor: first > 1 ? String(first) : null, afterCursor: last < latestSequence ? String(last) : null, latestSequence };
});
ipcMain.handle('fixture:start', (_event, workspaceId, text) => {
  calls.push(['start', text]);
  conversation('a4', text, 0);
  turn('a4', 't41', text);
  return conversations.a4.view;
});
ipcMain.handle('fixture:send', (_event, workspaceId, id, text) => {
  calls.push(['send', id, text]);
  if (busy) throw new Error('Wait for “Help me learn how Iceberg position deletes work” to finish');
  turn(id, 't' + (++turns), text);
  return conversations[id].view;
});
ipcMain.handle('fixture:offer', (_event, workspaceId, id, offerId, accept) => {
  calls.push(['offer', id, offerId, accept]);
  const offered = conversations[id].view.offers.find(entry => entry.id === offerId);
  offered.state = accept ? 'ACCEPTED' : 'DISMISSED';
  if (accept) turn(id, 't3', 'Make the page.');
  return conversations[id].view;
});
ipcMain.handle('fixture:question', (_event, workspaceId, id, approvalId, allow, answers) => {
  calls.push(['question', id, approvalId, allow, answers]);
  conversations[id].view.questions = [];
  conversations[id].view.pendingApprovals = [];
  emit(id, 't2', 'approval_resolved', { approvalId, outcome: allow ? 'ALLOWED' : 'DENIED', forTask: false });
  return conversations[id].view;
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
const artifactPresentations = [];
const showArtifact = artifactView.show.bind(artifactView);
artifactView.show = (...args) => { artifactPresentations.push(args); return showArtifact(...args); };
const text = selector => inApp('[...document.querySelectorAll(' + JSON.stringify(selector) + ')].map(element => element.textContent)');
const click = selector => until(() => inApp('(() => { const element = document.querySelector(' + JSON.stringify(selector) + '); element?.click(); return !!element; })()'), selector);
const press = name => until(() => inApp('(() => { const button = [...document.querySelectorAll("button")].find(button => button.textContent.trim() === ' + JSON.stringify(name)
  + ' && !button.disabled); button?.click(); return !!button; })()'), name);
const open = title => until(() => inApp('(() => { const row = [...document.querySelectorAll(".artifacts-row")].find(row => row.querySelector(".artifacts-row__title").textContent === '
  + JSON.stringify(title) + '); row?.click(); return !!row; })()'), title);
const type = async (label, value) => {
  window.webContents.focus();
  // A sent message leaves the box once its answer is read; typing waits for that, as a person would.
  await until(() => inApp('document.querySelector(' + JSON.stringify('textarea[aria-label="' + label + '"]') + ')?.value === ""'), 'an empty ' + label);
  await click('textarea[aria-label="' + label + '"]');
  await inApp('document.querySelector(' + JSON.stringify('textarea[aria-label="' + label + '"]') + ').focus()');
  await window.webContents.insertText(value);
};
const send = label => until(() => inApp('(() => { const button = document.querySelector(' + JSON.stringify('[aria-label="' + label + '"]')
  + '); if (!button || button.disabled) return false; button.click(); return true; })()'), label);
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

// The conversation is a task's transcript: a turn per message, and the agent's question as the task's question card.
await until(() => inApp("!!document.querySelector('.agt-ask')"), 'the question card');
const turnCount = (await text('.agt-turn')).length;
const asked = await text('.agt-user__bubble');
const answered = await text('.agt-turn > .agt-answer');
await capture('question');
await click('.agt-ask__option');
await send('Send answer: Which path?');
await until(() => calls.some(call => call[0] === 'question'), 'the answer');
answer('a1', 't2', 'Then the read path.');
conversations.a1.view.offers.push({ id: 'o1', turnId: 't2', title: 'Deletes on read', covers: 'How a scan skips rows named by position deletes.', state: 'SHOWN' });
done('a1', 't2');
const offered = await until(() => inApp("document.querySelectorAll('.agt-turn')[1]?.querySelector('.artifacts-offer')?.textContent ?? ''"), 'the offer under its turn');
const questionClosed = await inApp("!document.querySelector('[aria-label=\\"Send answer: Which path?\\"]')");
await capture('offer');

await press('Make the page');
const making = await until(() => inApp("document.querySelector('.artifacts-panel__placeholder')?.textContent ?? ''"), 'the placeholder');
const writingVersion = (await text('.artifacts-versions'))[0];
await capture('making');
conversations.a1.view.pages.push(page('v1', 1, 't3'));
conversations.a1.view.artifact.title = 'Deletes on read';
answer('a1', 't3', "Here's the page.");
done('a1', 't3');

const first = await shown('a1', 'Deletes on read');
const checks = await first.executeJavaScript('window.checks');
const tried = await first.executeJavaScript("document.getElementById('toggle').click(); !document.getElementById('answer').hidden");
window.webContents.debugger.attach('1.3');
await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
await inApp('(() => { const entrance = document.createElement("dialog"); entrance.id = "companion-entrance"; entrance.open = true; entrance.textContent = "A companion is arriving"; const input = document.createElement("input"); input.id = "entrance-focus-choice"; document.body.append(entrance, input); })()');
await inApp('document.getElementById("companion-entrance").setAttribute("data-focus-break-entrance", "")');
await until(() => !artifactView.view.getVisible(), 'the companion to cover the native page');
const beforeEntranceFocus = artifactPresentations.length;
await inApp('document.getElementById("entrance-focus-choice").focus()');
const entranceKeepsNewFocus = await until(() => artifactPresentations.slice(beforeEntranceFocus).some(([, bounds, allowFocusRestore]) => bounds === null && allowFocusRestore === false),
  'the page to relinquish focus selected during entrance');
await inApp('document.getElementById("companion-entrance").removeAttribute("data-focus-break-entrance")');
const afterEntrance = await shown('a1', 'Deletes on read');
const entranceRetainedPage = afterEntrance === first && await first.executeJavaScript('!document.getElementById("answer").hidden');
await inApp('document.getElementById("companion-entrance").remove(); document.getElementById("entrance-focus-choice").remove()');
window.webContents.debugger.detach();
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
const pageLine = await until(async () => inApp("document.querySelectorAll('.agt-turn')[2]?.querySelector('.artifacts-pageline')?.textContent ?? ''"), 'the page line under its turn');

await type('Message the agent', 'Make the diagram simpler');
await send('Send message');
await send('Stop');
const keptVisible = artifactView.view.getVisible();
await capture('working');
conversations.a1.view.pages.push(page('v2', 2, 't5'));
done('a1', 't5');
await shown('a1', 'Deletes on read, simpler');
const versions = await text('.artifacts-versions button');
await press('v1');
await shown('a1', 'Deletes on read');
await capture('versions');

await inApp('ArtifactTests.startBreak()');
await until(() => inApp('!!document.querySelector("[data-focus-break-entrance] .focus-break__background")'), 'a break over the first page');
const beforeReplacement = backgroundCaptures.length;
delayedPageId = 'v2';
await press('v2');
await until(() => releasePageRead, 'the replacement page read to wait');
const pendingPicture = await until(async () => {
  const picture = backgroundCaptures.slice(beforeReplacement).find(capture => capture.heading === 'Deletes on read');
  return picture && await inApp('document.querySelector(".focus-break__background")?.src === ' + JSON.stringify(picture.image)) && picture;
}, 'the old document pixels while its replacement is loading');
releasePageRead();
const loadedPicture = await until(async () => {
  const picture = backgroundCaptures.find(capture => capture.heading === 'Deletes on read, simpler');
  return picture && await inApp('document.querySelector(".focus-break__background")?.src === ' + JSON.stringify(picture.image)) && picture;
}, 'the completed page load to replace the old break image');
const delayedBreakBackground = pendingPicture.image !== loadedPicture.image
  && await inApp('!!document.querySelector("[data-focus-break-entrance]")');
await inApp('ArtifactTests.stopBreak()');
await shown('a1', 'Deletes on read, simpler');
await press('v1');
await shown('a1', 'Deletes on read');

busy = true;
await type('Message the agent', 'And the write path?');
await send('Send message');
const refused = await until(() => inApp("document.querySelector('.artifacts-notice [role=alert]')?.textContent ?? ''"), 'the refusal');
const kept = await inApp('document.querySelector(\\'textarea[aria-label="Message the agent"]\\').value');
busy = false;

await click('[aria-label="Back to Artifacts"]');
const counted = await until(async () => { const titles = await text('.artifacts-row'); return titles.length === 3 && titles; }, 'the list again');
const hiddenOnList = await until(() => !artifactView.view.getVisible(), 'the page to hide');

await open('Snapshots and manifests');
const missing = await until(() => inApp("document.querySelector('.artifacts-page__view [role=alert] span')?.textContent ?? ''"), 'the missing page');
await click('[aria-label="Back to Artifacts"]');
await open('Explain manifests');
const failure = await until(() => inApp("document.querySelector('.agt-error')?.textContent ?? ''"), 'the failure');
await capture('failure');

await click('[aria-label="Back to Artifacts"]');
await type('Ask about this repository', 'Where are dynamic filters applied?');
await send('Send');
const started = await until(async () => { const bubbles = await text('.agt-user__bubble'); return bubbles.length === 1 && bubbles; }, 'the new conversation');
const results = { rows, turnCount, asked, answered, offered, questionClosed, making: making.replace(/\\d+:\\d\\d/, 'm:ss'), writingVersion, checks, tried, entranceRetainedPage, entranceKeepsNewFocus, stayed, placed,
  light, dark, panel, pageLine, keptVisible, versions, delayedBreakBackground, refused, kept, counted, hiddenOnList, missing, failure, started, calls };`;

it('renders a conversation as a task transcript, asks with the question card, then shows its page versions beside it, locked down', async () => {
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
  artifactEvents: (id, artifactId, before, after) => ipcRenderer.invoke('fixture:events', id, artifactId, before, after),
  sendArtifact: (id, artifactId, text) => ipcRenderer.invoke('fixture:send', id, artifactId, text),
  answerArtifactOffer: (id, artifactId, offerId, accept) => ipcRenderer.invoke('fixture:offer', id, artifactId, offerId, accept),
  answerArtifactQuestion: (id, artifactId, approvalId, allow, answers) => ipcRenderer.invoke('fixture:question', id, artifactId, approvalId, allow, answers),
  stopArtifact: (id, artifactId) => ipcRenderer.invoke('fixture:stop', id, artifactId),
  showArtifact: (target, bounds, allowFocusRestore) => ipcRenderer.invoke('artifacts:show', target, bounds, allowFocusRestore),
  captureFocusBreakBackground: () => ipcRenderer.invoke('fixture:background'),
});`,
      setup: SETUP, body: BODY, timeoutMs: 90_000,
    });
    expect(result).toEqual({
      rows: ['Help me learn how Iceberg position deletes work12m ago', 'Snapshots and manifests11h ago', 'Explain manifests2d ago'],
      turnCount: 2,
      asked: ['Help me learn how Iceberg position deletes work', 'The read path, and how it differs from equality deletes'],
      answered: ['Position deletes are applied per data file (DeleteManager.java:118).'],
      offered: 'Deletes on readHow a scan skips rows named by position deletes.Make the pageNot yet',
      questionClosed: true,
      making: 'Writing the page…m:ss',
      writingVersion: 'v1 writing…',
      checks: { ran: true, network: true, backend: true, webrtc: true, popup: true, evalBlocked: true, bridge: true },
      tried: true,
      entranceRetainedPage: true,
      entranceKeepsNewFocus: true,
      stayed: 'bytequay-artifact://a1/w1/',
      placed: true,
      light: 'rgb(31, 35, 40)',
      dark: 'rgb(201, 205, 212)',
      panel: ['Deletes on read8bafef5v1'],
      pageLine: 'Deletes on read· v1',
      keptVisible: true,
      versions: ['v1', 'v2'],
      delayedBreakBackground: true,
      refused: 'Wait for “Help me learn how Iceberg position deletes work” to finish',
      kept: 'And the write path?',
      counted: ['Deletes on read112m ago', 'Snapshots and manifests11h ago', 'Explain manifests2d ago'],
      hiddenOnList: true,
      missing: 'This page could not be opened: This page no longer exists',
      failure: 'Claude turn timed out',
      started: ['Where are dynamic filters applied?'],
      calls: [['question', 'a1', 'q1', true, { 'Which path?': 'Read path' }], ['offer', 'a1', 'o1', true], ['send', 'a1', 'Make the diagram simpler'], ['stop', 'a1'],
        ['send', 'a1', 'And the write path?'], ['start', 'Where are dynamic filters applied?']],
    });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(150_000));
