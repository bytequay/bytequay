#!/usr/bin/env node
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
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { copyFile, mkdir, readFile, writeFile, symlink, rm } from 'node:fs/promises';
import { homedir } from 'node:os';
import { createRequire } from 'node:module';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const frontend = join(root, 'frontend');
const require = createRequire(join(frontend, 'package.json'));
const { build } = await import(join(dirname(require.resolve('vite/package.json')), 'dist/node/index.js'));
const options = Object.fromEntries(Array.from({ length: (process.argv.length - 2) / 2 }, (_, i) => [process.argv[2 + i * 2], process.argv[3 + i * 2]]));
const label = options['--label'] ?? 'current';
assert.match(label, /^[a-zA-Z0-9_-]+$/);
const runs = Number(options['--runs'] ?? 3);
const integration = options['--integration'] === 'true';
assert.ok(Number.isSafeInteger(runs) && runs > 0 && runs <= 10);
const directory = join(frontend, 'vendor', 'ide-benchmark', label);
const resultsDirectory = join(frontend, 'test-results', 'ide-trim', label);
const bundle = resolve(options['--bundle'] ?? join(frontend, 'vendor', 'ide'));
const javaHome = execFileSync('/usr/libexec/java_home', ['-v', '25'], { encoding: 'utf8' }).trim();
const source = async path => options['--ref'] ? execFileSync('git', ['show', `${options['--ref']}:${path}`], { cwd: root, encoding: 'utf8' }) : readFile(join(root, path), 'utf8');
const runtimeSource = await source('frontend/src/ide/runtime.ts');
const executionSource = await source('frontend/src/ide/executions.ts');
const manifestSource = await source('frontend/src/ide/runtimeManifest.json');
const fixtureSource = await readFile(join(frontend, 'src/ide/fixtures/resourceBenchmark.ts'), 'utf8');
const driverSource = await readFile(fileURLToPath(import.meta.url), 'utf8');
const manifest = JSON.parse(manifestSource);
const javaArguments = '-XX:+UseG1GC -XX:G1PeriodicGCInterval=60000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:GCTimeRatio=4 -Dsun.zip.disableMemoryMapping=true -Xmx6g -Xms100m -Xlog:disable';
await mkdir(directory, { recursive: true });
await mkdir(resultsDirectory, { recursive: true });
await writeFile(join(directory, 'runtime.ts'), runtimeSource);
await writeFile(join(directory, 'executions.ts'), executionSource);
await writeFile(join(directory, 'runtimeManifest.json'), manifestSource);
await writeFile(join(directory, 'resourceBenchmark.ts'), fixtureSource);
await writeFile(join(directory, 'benchmark-ide.mjs'), driverSource);
const dist = join(directory, 'dist');
const built = await build({ root: frontend, configFile: join(frontend, 'vite.ide.config.ts'), logLevel: 'warn', plugins: [{
  name: 'benchmark-source', enforce: 'pre',
  load(id) {
    if (id === join(frontend, 'src/ide/runtime.ts')) return runtimeSource;
    if (id === join(frontend, 'src/ide/executions.ts')) return executionSource;
    if (id === join(frontend, 'src/ide/runtimeManifest.json')) return manifestSource;
    if (id === join(frontend, 'src/ide/fixtures/resourceBenchmark.ts')) return fixtureSource;
  },
}], build: { outDir: dist, emptyOutDir: true, minify: false, rollupOptions: { input: join(frontend, 'src/ide/fixtures/resourceBenchmark.ts'), preserveEntrySignatures: 'strict', output: { entryFileNames: 'renderer.js' } } } });
const styles = (Array.isArray(built) ? built : [built]).flatMap(output => output.output ?? []).filter(asset => asset.fileName.endsWith('.css'));
assert.ok(styles.length > 0, 'The real editor stylesheet must be built and loaded');
await writeFile(join(dist, 'index.html'), `<!doctype html><meta charset="utf-8">${styles.map(asset => `<link rel="stylesheet" href="./${asset.fileName}">`).join('')}<body style="margin:0"><script type="module">import { run } from "./renderer.js"; window.result = run();</script>`);

const cpuSeconds = value => {
  const parts = value.split(':').map(Number);
  return parts.reduce((total, part) => total * 60 + part, 0);
};
assert.equal(cpuSeconds('01:02.50'), 62.5);
const processRows = () => execFileSync('/bin/ps', ['-axo', 'pid=,ppid=,rss=,time=,lstart=,stat=,comm='], { encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' } }).trim().split('\n').flatMap(line => {
  const match = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\S+)\s+([A-Za-z]{3}\s+[A-Za-z]{3}\s+\d+\s+\d{2}:\d{2}:\d{2}\s+\d{4})\s+(\S+)\s+(.*)$/.exec(line);
  if (!match || match[6].startsWith('Z')) return [];
  const [, pid, ppid, rss, time, started, , command] = match;
  return [{ pid: Number(pid), ppid: Number(ppid), started, identity: `${started}|${command}`, rssKiB: Number(rss), cpuSeconds: cpuSeconds(time), kind: command.includes('/bin/java') ? 'java' : command.includes('Electron') ? 'electron' : 'server' }];
});
const reports = [];
for (let run = 0; run < runs; run++) {
  const trial = join(directory, `run-${run + 1}`);
  await rm(trial, { recursive: true, force: true });
  const project = join(trial, 'project');
  for (let module = 0; module < 8; module++) {
    const moduleRoot = join(project, `module${module}`);
    await mkdir(join(moduleRoot, 'src/example'), { recursive: true });
    await writeFile(join(moduleRoot, '.project'), `<?xml version="1.0"?><projectDescription><name>module${module}</name><projects/><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>`);
    const libraries = integration && module === 0 ? '<classpathentry kind="lib" path="lib/junit.jar"/><classpathentry kind="lib" path="lib/hamcrest.jar"/>' : '';
    await writeFile(join(moduleRoot, '.classpath'), `<?xml version="1.0"?><classpath><classpathentry kind="src" path="src"/><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>${libraries}<classpathentry kind="output" path="bin"/></classpath>`);
    for (let file = 0; file < 100; file++) await writeFile(join(moduleRoot, `src/example/Class${file}.java`), `package example;\npublic class Class${file} {\n public static String value() { return "${file}"; }\n}\n`);
    await writeFile(join(moduleRoot, 'src/example/Entry.java'), 'package example;\npublic class Entry {\n public static String value() {\n  return Class0.value();\n }\n}\n');
    if (integration && module === 0) {
      await mkdir(join(moduleRoot, 'lib'));
      await copyFile(join(homedir(), '.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar'), join(moduleRoot, 'lib/junit.jar'));
      await copyFile(join(homedir(), '.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'), join(moduleRoot, 'lib/hamcrest.jar'));
      await writeFile(join(moduleRoot, 'src/example/EntryTest.java'), 'package example;\nimport org.junit.Test;\nimport static org.junit.Assert.assertEquals;\npublic class EntryTest {\n @Test public void value() { assertEquals("0", Entry.value()); }\n}\n');
    }
  }
  const profile = join(trial, 'server-profile');
  await mkdir(join(profile, 'data/data/Machine'), { recursive: true });
  await writeFile(join(profile, 'data/data/Machine/settings.json'), JSON.stringify({ 'java.jdt.ls.java.home': javaHome, 'java.jdt.ls.vmargs': javaArguments }));
  const extensions = join(profile, 'extensions');
  await mkdir(extensions, { recursive: true });
  for (const extension of manifest.extensions) {
    const name = `${extension.id}-${extension.version}`;
    await symlink(join(bundle, 'extensions', name), join(extensions, name));
  }
  const token = randomBytes(32).toString('hex');
  const tokenFile = join(profile, 'connection-token');
  await writeFile(tokenFile, token, { mode: 0o600 });
  const roots = new Map();
  const seen = new Map();
  const lifetimeCpu = new Map();
  const samples = [];
  let phase = 'cold';
  let electron;
  let serverOutput = '';
  let electronOutput = '';
  const started = performance.now();
  const server = spawn(join(bundle, 'server/node'), [join(bundle, 'server/out/server-main.js'), '--host', '127.0.0.1', '--port', '0', '--connection-token-file', tokenFile,
    '--server-data-dir', join(profile, 'data'), '--extensions-dir', extensions, '--telemetry-level', 'off', '--log', 'warn', '--reconnection-grace-time', '0'],
  { cwd: project, env: { ...process.env, JAVA_HOME: javaHome, GOTELEMETRY: 'off' }, stdio: ['ignore', 'pipe', 'pipe'] });
  roots.set(server.pid, processRows().find(row => row.pid === server.pid)?.identity);
  const sample = () => {
    const rows = processRows();
    const owned = new Set(rows.filter(row => roots.get(row.pid) === row.identity || seen.get(row.pid)?.identity === row.identity).map(row => row.pid));
    for (let changed = true; changed;) { changed = false; for (const row of rows) if (owned.has(row.ppid) && !owned.has(row.pid)) { owned.add(row.pid); changed = true; } }
    const processes = rows.filter(row => owned.has(row.pid));
    for (const row of processes) {
      seen.set(row.pid, row);
      // macOS can change comm to '(java)' while a process exits; its CPU time still belongs to the same lifetime.
      const lifetime = `${row.pid}|${row.started}`;
      lifetimeCpu.set(lifetime, Math.max(lifetimeCpu.get(lifetime) ?? 0, row.cpuSeconds));
    }
    samples.push({ seconds: (performance.now() - started) / 1000, phase, rssKiB: processes.reduce((total, row) => total + row.rssKiB, 0), cpuSeconds: [...lifetimeCpu.values()].reduce((total, cpu) => total + cpu, 0), processes });
  };
  sample();
  const sampler = setInterval(sample, 500);
  try {
    const port = await new Promise((resolvePort, reject) => {
      const timer = setTimeout(() => reject(new Error(`Server startup timed out: ${serverOutput}`)), 30_000);
      const collect = bytes => { serverOutput += bytes; const match = /Extension host agent listening on (\d+)/.exec(serverOutput); if (match) { clearTimeout(timer); resolvePort(Number(match[1])); } };
      server.stdout.on('data', collect); server.stderr.on('data', collect); server.once('error', reject);
    });
    const connection = { taskId: `benchmark-${run}`, sessionId: `benchmark-${run}`, authority: `127.0.0.1:${port}`, connectionToken: token, root: project, bundleRoot: bundle, cacheRoot: profile, commit: manifest.serverCommit };
    const preload = join(trial, 'preload.cjs');
    await writeFile(preload, `const { contextBridge, ipcRenderer } = require('electron'); contextBridge.exposeInMainWorld('ideWorkbench', {
      bootstrap: async () => (${JSON.stringify({ connection, context: null })}), onQuery: () => () => {}, queryReady: () => {}, queryReply: () => {},
      request: async (method, phase) => ipcRenderer.invoke(method, phase), save: async () => { throw new Error('Benchmark does not save files'); },
      startExecution: async request => ipcRenderer.invoke('benchmark.start', request), outputExecution: async (id, output) => ipcRenderer.invoke('benchmark.output', id, output),
      finishExecution: async (id, result) => ipcRenderer.invoke('benchmark.finish', id, result), stopExecution: async () => { throw new Error('Unexpected test cancellation'); },
    });`);
    const script = join(trial, 'main.cjs');
    await writeFile(script, `
      const { app, BrowserWindow, WebContentsView, protocol, net, ipcMain } = require('electron');
      const { join } = require('node:path'); const { pathToFileURL } = require('node:url');
      protocol.registerSchemesAsPrivileged([{ scheme: 'bytequay-ide', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, codeCache: true } }]);
      app.setPath('userData', ${JSON.stringify(join(trial, 'electron-profile'))});
      const deadline = setTimeout(() => { console.error('Benchmark timed out'); app.exit(2); }, 240000);
      app.whenReady().then(async () => {
        const owner = new BrowserWindow({ show: false, width: 1000, height: 700 });
        const view = new WebContentsView({ webPreferences: { preload: ${JSON.stringify(preload)}, backgroundThrottling: false, sandbox: true, contextIsolation: true } });
        view.setBounds({ x: 0, y: 0, width: 1000, height: 700 }); owner.contentView.addChildView(view);
        const contents = view.webContents;
        contents.on('console-message', event => console.error(event.message));
        contents.session.protocol.handle('bytequay-ide', request => net.fetch(pathToFileURL(join(${JSON.stringify(dist)}, decodeURIComponent(new URL(request.url).pathname))).href));
        contents.session.protocol.handle('https', request => {
          const url = new URL(request.url); let path;
          try { path = require('node:fs').realpathSync(decodeURIComponent(url.pathname)); } catch { return new Response('Not found', { status: 404 }); }
          return url.hostname === 'ide-assets.bytequay.invalid' && path.startsWith(${JSON.stringify(bundle + '/')}) ? net.fetch(pathToFileURL(path).href) : new Response('Forbidden', { status: 403 });
        });
        ipcMain.handle('benchmark.phase', (_event, phase) => process.stdout.write('PHASE:' + phase + '\\n'));
        ipcMain.handle('benchmark.integration', () => ${integration});
        let execution;
        ipcMain.handle('benchmark.start', (_event, request) => { execution = { id: 'benchmark-run', ...request, status: 'RUNNING', treeUnverified: false }; return execution; });
        ipcMain.handle('benchmark.output', () => {});
        ipcMain.handle('benchmark.finish', (_event, id, result) => {
          if (id !== execution?.id) throw new Error('Unexpected execution');
          return { ...execution, status: result.failed ? 'FAILED' : 'SUCCEEDED', treeUnverified: false };
        });
        try {
          await contents.loadURL('bytequay-ide://app/index.html');
          const result = await contents.executeJavaScript('window.result');
          if (${integration}) {
            try { require('node:fs').writeFileSync(${JSON.stringify(join(resultsDirectory, 'editor.png'))}, (await contents.capturePage()).toPNG()); }
            catch (error) { result.screenshotUnavailable = String(error); }
          }
          process.stdout.write('RESULT:' + JSON.stringify(result) + '\\n'); clearTimeout(deadline);
          // The parent samples before closing stdin, so shutdown never contaminates idle measurements.
          process.stdin.once('end', () => app.exit(0)); process.stdin.resume();
        } catch (error) { console.error(error); clearTimeout(deadline); app.exit(1); }
      });
    `);
    const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
    electron = spawn(require('electron'), [script], { env, stdio: ['pipe', 'pipe', 'pipe'] });
    roots.set(electron.pid, processRows().find(row => row.pid === electron.pid)?.identity);
    sample();
    const result = await new Promise((resolveResult, reject) => {
      const timer = setTimeout(() => reject(new Error(`Benchmark did not finish: ${electronOutput.slice(-16000)}`)), 250_000);
      let stdout = '';
      let pending = '';
      electron.stdout.on('data', bytes => {
        stdout += bytes;
        pending += bytes;
        for (let end = pending.indexOf('\n'); end >= 0; end = pending.indexOf('\n')) {
          const line = pending.slice(0, end); pending = pending.slice(end + 1);
          if (line.startsWith('PHASE:')) { sample(); phase = line.slice(6) === 'ready' ? 'warm' : line.slice(6); }
          if (line.startsWith('RESULT:')) { sample(); clearInterval(sampler); clearTimeout(timer); resolveResult(JSON.parse(line.slice(7))); }
        }
      });
      electron.stderr.on('data', bytes => { electronOutput += bytes; });
      electron.once('error', reject);
      electron.once('exit', code => { clearTimeout(timer); if (!stdout.includes('RESULT:')) reject(new Error(`Electron exited ${code}: ${electronOutput.slice(-16000)}`)); });
    });
    const measured = samples.filter(sample => sample.phase !== 'integration');
    const idle = measured.filter(sample => sample.phase === 'idle').slice(-20);
    assert.ok(samples.some(sample => sample.processes.some(process => process.kind === 'java')), 'A real Java server must run');
    const report = { run: run + 1, ...result, peakRssMiB: Math.max(...measured.map(sample => sample.rssKiB)) / 1024,
      idleRssMiB: idle.reduce((sum, sample) => sum + sample.rssKiB, 0) / idle.length / 1024,
      idleCpuSeconds: idle.at(-1).cpuSeconds - idle[0].cpuSeconds,
      idleCpuPercent: 100 * (idle.at(-1).cpuSeconds - idle[0].cpuSeconds) / (idle.at(-1).seconds - idle[0].seconds),
      cpuSeconds: measured.at(-1).cpuSeconds, samples };
    reports.push(report);
    console.log(JSON.stringify({ ...report, samples: undefined }));
  } finally {
    clearInterval(sampler);
    electron?.stdin.end();
    for (const signal of ['SIGTERM', 'SIGKILL']) {
      const identities = new Map(processRows().map(row => [row.pid, row.identity]));
      for (const row of [...seen.values()].reverse()) if (identities.get(row.pid) === row.identity) {
        try { process.kill(row.pid, signal); } catch {}
      }
      if (signal === 'SIGTERM') await delay(1500);
    }
    await writeFile(join(trial, 'server.log'), serverOutput);
    await writeFile(join(trial, 'electron.log'), electronOutput);
  }
  await writeFile(join(resultsDirectory, 'results.json'), JSON.stringify({ label, ref: options['--ref'] ?? 'working-tree', sourceHashes: Object.fromEntries(Object.entries({ 'runtime.ts': runtimeSource, 'executions.ts': executionSource, 'runtimeManifest.json': manifestSource, 'resourceBenchmark.ts': fixtureSource, 'benchmark-ide.mjs': driverSource }).map(([name, source]) => [name, createHash('sha256').update(source).digest('hex')])), workload: { modules: 8, classesPerModule: 101, warmQueries: 20, idleSeconds: integration ? 1 : 70, sampleMilliseconds: 500, integration }, javaHome, reports }, null, 2));
}
