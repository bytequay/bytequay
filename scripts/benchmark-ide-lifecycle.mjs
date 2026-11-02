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
import { appendFile, copyFile, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { processTracker } from './ide-benchmark-processes.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const frontend = join(root, 'frontend');
const require = createRequire(join(frontend, 'package.json'));
const { build: buildJs } = require('esbuild');
const { build } = await import(join(dirname(require.resolve('vite/package.json')), 'dist/node/index.js'));
const options = Object.fromEntries(Array.from({ length: (process.argv.length - 2) / 2 }, (_, i) => [process.argv[2 + i * 2], process.argv[3 + i * 2]]));
const diagnostic = options['--diagnostic'] === 'true';
const variants = options['--variant'] ? [options['--variant']] : ['before', 'after'];
assert.ok(variants.every(variant => ['before', 'after'].includes(variant)) && (diagnostic || variants.length === 2));
const runs = Number(options['--runs'] ?? 3);
assert.ok(Number.isSafeInteger(runs) && runs > 0 && runs <= 5);
const directory = join(frontend, 'vendor/ide-benchmark/warm-resume');
const resultsDirectory = join(frontend, 'test-results/ide-fast-resume');
const bundle = join(frontend, 'vendor/ide');
const javaHome = execFileSync('/usr/libexec/java_home', ['-v', '25'], { encoding: 'utf8' }).trim();
const manifest = JSON.parse(await readFile(join(frontend, 'src/ide/runtimeManifest.json'), 'utf8'));
await mkdir(directory, { recursive: true });
await mkdir(resultsDirectory, { recursive: true });
const sha = value => createHash('sha256').update(value).digest('hex');
const selectedFiles = { 'ideViews.ts': 'src/ideViews.ts', 'idePreload.ts': 'src/idePreload.ts', 'drafts.ts': 'src/ide/drafts.ts', 'runtime.ts': 'src/ide/runtime.ts' };
if (options['--prepared'] !== 'true') {
  const metadata = { variants: {}, common: {}, methodology: { productionMinification: true, rememberedServerPort: true, baselineSuspends: true, selectedAfterRemainsWarm: true }, scope: 'Production IdeViews, preload, draft/navigation tracker, native editor and Java server. Synthetic backend transport controls isolated server processes; owner window reproduces main visibility events. Full React IdeSurface and real backend are covered separately by product tests.' };
  for (const variant of ['before', 'after']) {
    const base = join(directory, variant);
    const main = join(base, 'main');
    const dist = join(base, 'renderer/ide_window');
    await mkdir(main, { recursive: true });
    const sources = new Map();
    metadata.variants[variant] = {};
    for (const [name, file] of Object.entries(selectedFiles)) {
      const source = await readFile(variant === 'before' ? join(frontend, 'vendor/ide-benchmark/fast-resume-before', name) : join(frontend, file), 'utf8');
      sources.set(join(frontend, file), source); metadata.variants[variant][name] = sha(source);
      await writeFile(join(base, name), source);
    }
    const adapter = new Map([
      [join(frontend, 'src/backendJson.ts'), 'export const backendJson = (url, init) => globalThis.__benchmarkBackend(url, init); export const backendJsonIfChanged = backendJson;'],
      [join(frontend, 'src/backendProcess.ts'), 'export const backendBase = () => "http://benchmark.invalid";'],
      [join(frontend, 'src/repositoryAvatars.ts'), 'export const repositoryAvatar = () => { throw new Error("No avatar requests in lifecycle benchmark"); };'],
      [join(frontend, 'src/browserTabs.ts'), 'export const browserBounds = value => value;'],
    ]);
    const plugin = { name: 'lifecycle-source', setup(build) { build.onLoad({ filter: /\.ts$/ }, args => {
      const contents = sources.get(args.path) ?? adapter.get(args.path);
      return contents === undefined ? undefined : { contents, loader: 'ts', resolveDir: dirname(args.path) };
    }); } };
    await buildJs({ entryPoints: [join(frontend, 'src/ideViews.ts')], outfile: join(main, 'ideViews.cjs'), bundle: true,
      platform: 'node', format: 'cjs', external: ['electron'], plugins: [plugin], logLevel: 'silent' });
    await buildJs({ entryPoints: [join(frontend, 'src/idePreload.ts')], outfile: join(main, 'idePreload.js'), bundle: true,
      platform: 'node', format: 'cjs', external: ['electron'], plugins: [plugin], logLevel: 'silent' });
    await appendFile(join(main, 'idePreload.js'), `\n;(() => { const { contextBridge, ipcRenderer } = require('electron'); contextBridge.exposeInMainWorld('benchmarkTiming', { mark: (name, timestamp) => ipcRenderer.send('benchmark.timing', name, timestamp) }); })();\n`);
    await copyFile(join(root, 'scripts/fixtures/ide-lifecycle.cjs'), join(main, 'benchmark.cjs'));
    await copyFile(join(root, 'scripts/ide-benchmark-processes.mjs'), join(main, 'ide-benchmark-processes.mjs'));
    await writeFile(join(main, 'ownerPreload.cjs'), `const { contextBridge, ipcRenderer } = require('electron');
      contextBridge.exposeInMainWorld('benchmarkOwner', {
        present: id => ipcRenderer.invoke('ide:present', id, { x: 0, y: 0, width: 1000, height: 700 }),
        resume: (host, id) => ipcRenderer.invoke('ide:resume', host, id),
      });
      ipcRenderer.on('ide:event', (_event, value) => ipcRenderer.send('benchmark.event', value));`);
    const built = await build({ root: frontend, configFile: join(frontend, 'vite.ide.config.ts'), logLevel: 'warn',
      plugins: [{ name: 'lifecycle-source', enforce: 'pre', load: id => sources.get(id) }],
      build: { outDir: dist, emptyOutDir: true, rollupOptions: { input: join(frontend, 'src/ide/fixtures/lifecycleBenchmark.ts'),
        preserveEntrySignatures: 'strict', output: { entryFileNames: 'renderer.js' } } } });
    const styles = (Array.isArray(built) ? built : [built]).flatMap(item => item.output ?? []).filter(item => item.fileName.endsWith('.css'));
    assert.ok(styles.length > 0);
    await mkdir(join(dist, 'ide'), { recursive: true });
    await writeFile(join(dist, 'ide/index.html'), `<!doctype html><meta charset="utf-8">${styles.map(item => `<link rel="stylesheet" href="../${item.fileName}">`).join('')}<body style="margin:0"><script type="module">import { run } from "../renderer.js"; window.lifecycle = run();</script>`);
  }
  for (const name of ['scripts/benchmark-ide-lifecycle.mjs', 'scripts/fixtures/ide-lifecycle.cjs', 'scripts/ide-benchmark-processes.mjs', 'frontend/src/ide/fixtures/lifecycleBenchmark.ts', 'frontend/src/ide/idleState.ts']) {
    const source = await readFile(join(root, name)); metadata.common[name] = sha(source);
    await writeFile(join(directory, name.replaceAll('/', '_')), source);
  }
  await writeFile(join(directory, 'metadata.json'), JSON.stringify(metadata, null, 2));
}
if (options['--prepare-only'] === 'true') process.exit(0);
const metadata = JSON.parse(await readFile(join(directory, 'metadata.json'), 'utf8'));
for (const [file, hash] of Object.entries(metadata.common)) assert.equal(sha(await readFile(join(root, file))), hash, `Rebuild changed source: ${file}`);
for (const [name, file] of Object.entries(selectedFiles)) assert.equal(sha(await readFile(join(frontend, file))), metadata.variants.after[name], `Rebuild changed source: ${name}`);
const reports = [];
for (let run = 1; run <= runs; run++) for (const variant of variants) {
  const trial = join(directory, `${diagnostic ? 'diagnostic' : 'final'}-${run}-${variant}`);
  await rm(trial, { recursive: true, force: true });
  const project = join(trial, 'project'), profile = join(trial, 'server-profile');
  await mkdir(join(project, 'src/example'), { recursive: true });
  await writeFile(join(project, '.project'), '<?xml version="1.0"?><projectDescription><name>example</name><projects/><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>');
  await writeFile(join(project, '.classpath'), '<?xml version="1.0"?><classpath><classpathentry kind="src" path="src"/><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="bin"/></classpath>');
  await writeFile(join(project, 'src/example/Class0.java'), 'package example;\npublic class Class0 {\n public static String value() { return "0"; }\n}\n');
  await writeFile(join(project, 'src/example/Entry.java'), 'package example;\npublic class Entry {\n public static String value() {\n  return Class0.value();\n }\n}\n');
  await mkdir(join(profile, 'data/data/Machine'), { recursive: true });
  await mkdir(join(profile, 'extensions'), { recursive: true });
  for (const extension of manifest.extensions) {
    const name = `${extension.id}-${extension.version}`;
    await symlink(join(bundle, 'extensions', name), join(profile, 'extensions', name));
  }
  await writeFile(join(profile, 'data/data/Machine/settings.json'), JSON.stringify({ 'java.jdt.ls.java.home': javaHome,
    'java.jdt.ls.vmargs': '-XX:+UseG1GC -XX:G1PeriodicGCInterval=60000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:GCTimeRatio=4 -Dsun.zip.disableMemoryMapping=true -Xmx6g -Xms100m -Xlog:disable' }));
  const token = randomBytes(32).toString('hex');
  await writeFile(join(profile, 'connection-token'), token, { mode: 0o600 });
  const config = join(trial, 'config.json');
  await writeFile(config, JSON.stringify({ variant, diagnostic, trial, project, profile, bundle, javaHome, token, commit: manifest.serverCommit, reusePort: true, expectedSuspended: variant === 'before' }), { mode: 0o600 });
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
  const electron = spawn(require('electron'), [join(directory, variant, 'main/benchmark.cjs'), config], { env, stdio: ['pipe', 'pipe', 'pipe'] });
  const tracker = processTracker(electron.pid);
  const started = performance.now();
  let phase = 'startup', output = '', pending = '';
  const samples = [];
  const sample = () => samples.push({ seconds: (performance.now() - started) / 1000, phase, ...tracker.sample() });
  sample(); const sampler = setInterval(sample, 500);
  try {
    const result = await new Promise((resolveResult, reject) => {
      electron.stderr.on('data', value => { output += value; });
      electron.stdout.on('data', value => {
        pending += value;
        for (let end = pending.indexOf('\n'); end >= 0; end = pending.indexOf('\n')) {
          const line = pending.slice(0, end); pending = pending.slice(end + 1);
          if (line.startsWith('PHASE:')) { sample(); phase = line.slice(6); console.log(JSON.stringify({ run, variant, phase })); }
          if (line.startsWith('NOTICE:')) console.log(line);
          if (line.startsWith('RESULT:')) { sample(); clearInterval(sampler); resolveResult(JSON.parse(line.slice(7))); }
        }
      });
      electron.once('error', reject);
      electron.once('exit', code => reject(new Error(`Lifecycle exited ${code}: ${output.slice(-12000)}`)));
    });
    const phases = {};
    for (const name of ['active', 'minimized', 'resumed']) {
      const rows = samples.filter(item => item.phase === name).slice(-20);
      assert.ok(rows.length > 0, `Missing phase ${name}`);
      phases[name] = { sampleCount: rows.length, firstSecond: rows[0].seconds, lastSecond: rows.at(-1).seconds,
        meanRssMiB: rows.reduce((sum, item) => sum + item.rssKiB, 0) / rows.length / 1024,
        maxJavaProcesses: Math.max(...rows.map(item => item.processes.filter(process => process.kind === 'java').length)) };
    }
    reports.push({ run, ...result, phases, samples });
    console.log(JSON.stringify({ run, ...result, phases }));
  } finally {
    clearInterval(sampler); electron.stdin.end(); await tracker.stop();
    await writeFile(join(trial, 'electron.log'), output);
  }
  await writeFile(join(resultsDirectory, diagnostic ? 'warm-diagnostic.json' : 'lifecycle.json'), JSON.stringify({ diagnostic,
    minimizedSeconds: diagnostic ? 25 : 150, diagnosticClockOffsetMilliseconds: diagnostic ? 125_000 : 0,
    workload: { javaProjects: 1, javaClasses: 2, openFiles: 1, dirtyFiles: 0, explicitTerminals: 0, pairedTrials: runs, sampleMilliseconds: 500 },
    metadata, reports }, null, 2));
}
