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
const assert = require('node:assert/strict');
const { app, BrowserWindow, ipcMain } = require('electron');
const { spawn } = require('node:child_process');
const { readFileSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');
const { pathToFileURL } = require('node:url');
const { randomUUID } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const config = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const { IdeViews } = require('./ideViews.cjs');
const phase = value => process.stdout.write(`PHASE:${value}\n`);
app.setPath('userData', join(config.trial, 'electron-profile'));
const deadline = setTimeout(() => { console.error('Lifecycle benchmark timed out'); app.exit(2); }, 360_000);

app.whenReady().then(async () => {
  const { processTracker } = await import(pathToFileURL(join(__dirname, 'ide-benchmark-processes.mjs')).href);
  let server;
  let serverTree;
  let connection;
  let stoppedAt;
  let starts = 0;
  let previousPort = 0;
  let serverLog = '';
  let attempt = 'initial';
  let attemptStarted = performance.now();
  const stages = [];
  const mark = (name, timestamp = performance.now()) => stages.push({ attempt, name, elapsedMs: timestamp - attemptStarted });
  ipcMain.on('benchmark.timing', (_event, name, timestamp) => mark(name, timestamp - performance.timeOrigin));
  global.__benchmarkBackend = async (url, init = {}) => {
    const route = new URL(url).pathname.replace('/agent/ide', '');
    if (route.endsWith('/touch')) return {};
    if (init.method === 'DELETE') {
      await serverTree?.stop(); server = undefined; connection = undefined; stoppedAt = performance.now();
      return {};
    }
    if (route.startsWith('/tasks/') && init.method === 'POST') {
      if (connection) return connection;
      starts++;
      mark('serverSpawn');
      server = spawn(join(config.bundle, 'server/node'), [join(config.bundle, 'server/out/server-main.js'), '--host', '127.0.0.1', '--port', config.reusePort ? String(previousPort) : '0',
        '--connection-token-file', join(config.profile, 'connection-token'), '--server-data-dir', join(config.profile, 'data'),
        '--extensions-dir', join(config.profile, 'extensions'), '--telemetry-level', 'off', '--log', 'warn', '--reconnection-grace-time', '0'],
      { cwd: config.project, env: { ...process.env, JAVA_HOME: config.javaHome, GOTELEMETRY: 'off' }, stdio: ['ignore', 'pipe', 'pipe'] });
      serverTree = processTracker(server.pid);
      const port = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Synthetic server startup timed out')), 30_000);
        let startupOutput = '';
        const collect = chunk => { serverLog += chunk; startupOutput += chunk; const match = /Extension host agent listening on (\d+)/.exec(startupOutput); if (match) { clearTimeout(timeout); resolve(Number(match[1])); } };
        server.stdout.on('data', collect); server.stderr.on('data', collect); server.once('error', reject);
      });
      previousPort = port;
      mark('serverListening');
      connection = { taskId: 'benchmark-workspace', sessionId: randomUUID(), authority: `127.0.0.1:${port}`,
        connectionToken: config.token, root: config.project, bundleRoot: config.bundle, cacheRoot: config.profile, commit: config.commit };
      return connection;
    }
    if (route.startsWith('/tasks/') && init.method === 'GET') return connection ?? null;
    throw new Error(`Unexpected synthetic backend request: ${init.method} ${route}`);
  };
  const owner = new BrowserWindow({ show: false, width: 1000, height: 740,
    webPreferences: { preload: join(__dirname, 'ownerPreload.cjs'), sandbox: true, contextIsolation: true } });
  const windowEvents = [];
  let minimizedAt;
  let restoringByHarness = false;
  const recordWindowEvent = name => {
    const event = { name, attempt, elapsedMs: performance.now() - attemptStarted, minimized: !owner.isDestroyed() && owner.isMinimized(), restoringByHarness,
      minimizedInterval: minimizedAt !== undefined && !restoringByHarness };
    windowEvents.push(event);
    if (name === 'restore' && minimizedAt !== undefined && !restoringByHarness) process.stdout.write('NOTICE:' + JSON.stringify(event) + '\n');
  };
  for (const event of ['show', 'hide', 'focus', 'blur', 'minimize', 'restore']) owner.on(event, () => recordWindowEvent(event));
  app.on('activate', () => recordWindowEvent('appActivate'));
  const views = new IdeViews(() => owner);
  app.on('web-contents-created', (_event, contents) => {
    if (contents === owner.webContents) return;
    mark('rendererCreated');
    contents.once('did-start-loading', () => mark('rendererLoadStarted'));
    contents.once('dom-ready', () => mark('rendererDomReady'));
    contents.once('did-finish-load', () => mark('rendererLoadFinished'));
  });
  views.register();
  for (const event of ['show', 'hide', 'minimize', 'restore']) owner.on(event, () => views.refreshVisibility());
  const events = [];
  ipcMain.on('benchmark.event', (_event, value) => events.push(value));
  const host = { kind: 'task', workspaceId: 'benchmark-workspace', taskId: 'benchmark-task' };
  const present = id => owner.webContents.executeJavaScript(`window.benchmarkOwner.present(${JSON.stringify(id)})`);
  const nativeContents = () => owner.contentView.children.find(view => view.webContents && view.webContents !== owner.webContents)?.webContents;
  const ready = contents => contents.executeJavaScript('window.lifecycle.then(value => value.ready)');
  try {
    await owner.loadURL('data:text/html,<body style="font:14px system-ui;margin:24px">Isolated IDE lifecycle verification</body>');
    owner.showInactive();
    attemptStarted = performance.now(); mark('openRequested');
    const firstId = await views.open(host);
    mark('openResolved');
    await present(firstId);
    const first = nativeContents();
    assert.ok(first, 'Production IdeViews must own a native editor');
    const initialNavigation = await ready(first);
    await delay(config.diagnostic ? 1000 : 10_000);
    phase('active');
    await delay(config.diagnostic ? 1000 : 10_000);
    const beforeMinimizeState = await first.executeJavaScript('window.lifecycle.then(value => value.inspect())');
    console.error('Before minimize: ' + JSON.stringify(beforeMinimizeState));
    const minimized = new Promise(resolve => owner.once('minimize', resolve));
    owner.minimize(); await minimized;
    assert.equal(owner.isMinimized(), true, 'The OS window must actually minimize');
    minimizedAt = performance.now();
    if (config.diagnostic) {
      const now = Date.now;
      Date.now = () => now() + 125_000;
    }
    phase('minimized');
    await delay(config.diagnostic ? 25_000 : 150_000);
    assert.ok(!windowEvents.some(event => event.name === 'restore' && event.minimizedInterval), 'An unexpected native restore invalidates the minimized interval');
    assert.equal(owner.isMinimized(), true, 'The window must remain minimized for the entire interval');
    const suspended = events.some(event => event.id === firstId && event.closed && event.suspended);
    if (!suspended && config.expectedSuspended) console.error('Still retained: ' + JSON.stringify(await first.executeJavaScript('window.lifecycle.then(value => value.inspect())')));
    const expectSuspended = config.expectedSuspended;
    assert.equal(suspended, expectSuspended, 'The selected lifecycle must apply its expected idle policy');
    assert.equal(first.isDestroyed(), expectSuspended);
    phase('restoring');
    const restored = new Promise(resolve => owner.once('restore', resolve));
    restoringByHarness = true;
    owner.restore(); await restored;
    const resumeStarted = performance.now();
    attempt = 'resume'; attemptStarted = resumeStarted; mark('resumeRequested');
    const nextId = await owner.webContents.executeJavaScript(`window.benchmarkOwner.resume(${JSON.stringify(host)}, ${JSON.stringify(firstId)})`);
    mark('resumeResolved');
    assert.equal(typeof nextId, 'string', 'The production resume IPC must reuse or reopen the runtime');
    await present(nextId);
    const next = nativeContents();
    const resumedNavigation = expectSuspended ? await ready(next)
      : await next.executeJavaScript('window.lifecycle.then(value => value.verify())');
    if (expectSuspended) assert.equal(resumedNavigation.restoredNavigation, true, 'The reopened editor must restore its saved file');
    const resumeMs = performance.now() - resumeStarted;
    mark('resumeNavigationReturned');
    phase('resumed');
    await delay(config.diagnostic ? 1000 : 10_000);
    process.stdout.write('RESULT:' + JSON.stringify({ variant: config.variant, diagnostic: config.diagnostic, initialNavigation, resumedNavigation,
      suspended, beforeMinimizeState, serverStarts: starts, sameEditorAfterResume: firstId === nextId, resumeMs, stages, windowEvents,
      suspensionMs: stoppedAt === undefined ? null : stoppedAt - minimizedAt }) + '\n');
    clearTimeout(deadline);
    process.stdin.once('end', async () => { views.dispose(); await serverTree?.stop(); app.exit(0); }); process.stdin.resume();
  } catch (error) {
    console.error(error); console.error('Native window events: ' + JSON.stringify(windowEvents));
    clearTimeout(deadline); views.dispose(); await serverTree?.stop(); app.exit(1);
  } finally {
    writeFileSync(join(config.trial, 'server.log'), serverLog);
  }
});
