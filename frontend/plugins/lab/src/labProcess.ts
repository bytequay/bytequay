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
import { app } from 'electron';
import { spawn, type ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { createServer } from 'node:net';
import { homedir } from 'node:os';
import path from 'node:path';
import type { LabTask } from './contracts';

const REFRESH_TIMEOUT = 5 * 60_000;
const START_TIMEOUT = 30_000;
const TASK_TEXT_LIMIT = 20_000;
type Process = { child: ChildProcess; exited: boolean; failure: string | null; output: string };
const children = new Set<Process>();
let dashboard: { process: Process; address: string } | null = null;
let opening: Promise<string> | null = null;
let stopping = false;

function locations() {
  const bundle = app.isPackaged ? path.join(process.resourcesPath, 'lab') : path.join(app.getAppPath(), 'vendor', 'lab');
  const source = app.isPackaged ? path.join(bundle, 'app') : path.join(app.getAppPath(), '..', 'scripts', 'review-calibration');
  const data = path.join(homedir(), 'Library', 'Application Support', 'ByteQuay');
  const folder = `${data} Lab`;
  const args = ['--runtime', path.join(data, 'agent-runtime'), '--database', path.join(data, 'bytequay-1.0.db')];
  return { python: path.join(bundle, 'python', 'bin', 'python3'), source, folder, args };
}

function environment(): NodeJS.ProcessEnv {
  // The dashboard needs local tools for explicit experiments, but never the desktop's backend credentials or Python customizations.
  const allowed = ['HOME', 'PATH', 'LANG', 'LC_ALL', 'TMPDIR', 'USER', 'LOGNAME', 'SHELL', 'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL'];
  return { ...Object.fromEntries(allowed.flatMap(key => process.env[key] === undefined ? [] : [[key, process.env[key]]])),
    PYTHONNOUSERSITE: '1', PYTHONDONTWRITEBYTECODE: '1', BYTEQUAY_LAB_PARENT_PID: String(process.pid) };
}

function launch(python: string, args: string[], folder: string): Process {
  if (stopping) throw new Error('ByteQuay is shutting down.');
  const child = spawn(python, ['-B', '-s', ...args], { cwd: folder, env: environment(), detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const owned: Process = { child, exited: false, failure: null, output: '' };
  children.add(owned);
  const output = (chunk: Buffer) => { owned.output = (owned.output + chunk.toString()).slice(-4000); };
  child.stdout?.on('data', output);
  child.stderr?.on('data', output);
  child.on('error', error => { owned.failure = error.message; owned.exited = true; children.delete(owned); });
  child.on('exit', (code, signal) => {
    owned.exited = true;
    owned.failure ??= `Lab exited with ${signal ?? `code ${code}`}.`;
    children.delete(owned);
    if (dashboard?.process === owned) dashboard = null;
  });
  return owned;
}

function terminate(owned: Process): void {
  if (owned.exited) return;
  // Both jobs only read app data and atomically replace Lab snapshots. Stop their process groups, including any helper still running.
  if (owned.child.pid) {
    try { process.kill(-owned.child.pid, 'SIGKILL'); }
    catch { owned.child.kill('SIGKILL'); }
  }
  else owned.child.kill('SIGKILL');
  children.delete(owned);
}

async function refresh(python: string, source: string, folder: string, args: string[]): Promise<void> {
  const owned = launch(python, [path.join(source, 'desktop.py'), '--folder', folder, ...args], folder);
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error('Refreshing Lab data took too long. Try opening Lab again.'));
      terminate(owned);
    }, REFRESH_TIMEOUT);
    owned.child.once('error', error => { clearTimeout(timer); reject(new Error(`Could not refresh Lab data: ${error.message}`)); });
    owned.child.once('close', code => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new Error(`Could not refresh Lab data. ${owned.output.trim() || owned.failure || 'Please try again.'}`));
    });
  });
}

async function availablePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      server.close(error => {
        if (error) reject(error);
        else if (address && typeof address !== 'string') resolve(address.port);
        else reject(new Error('Could not choose a local port for Lab.'));
      });
    });
  });
}

async function healthy(address: string): Promise<boolean> {
  try {
    const response = await fetch(`${address}_stcore/health`, { signal: AbortSignal.timeout(1000), redirect: 'error' });
    return response.ok && (await response.text()).trim() === 'ok';
  }
  catch { return false; }
}

async function prepare(): Promise<string> {
  const { python, source, folder, args } = locations();
  if (!existsSync(python) || !existsSync(path.join(source, 'desktop.py'))) {
    throw new Error('ByteQuay’s Lab installation is incomplete. Reinstall ByteQuay and try again.');
  }
  await mkdir(folder, { recursive: true });
  await refresh(python, source, folder, args);
  if (stopping) throw new Error('ByteQuay is shutting down.');
  const previous = dashboard;
  if (previous && await healthy(previous.address) && !previous.process.exited) return previous.address;
  if (previous) terminate(previous.process);
  const port = await availablePort();
  // A per-launch route distinguishes our dashboard if another process takes the chosen port before Python binds it.
  const basePath = `lab-${randomUUID()}`;
  const address = `http://127.0.0.1:${port}/${basePath}/`;
  const owned = launch(python, [path.join(source, 'dashboard.py'), folder, ...args, '--port', String(port), '--base-path', basePath], folder);
  dashboard = { process: owned, address };
  const deadline = Date.now() + START_TIMEOUT;
  while (!stopping && !owned.exited && Date.now() < deadline) {
    if (await healthy(address) && !owned.exited && !stopping) return address;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  const reason = stopping ? 'ByteQuay is shutting down.' : owned.failure ?? 'The dashboard did not become ready. Try again.';
  terminate(owned);
  if (dashboard?.process === owned) dashboard = null;
  throw new Error(`Could not open Lab. ${reason}${owned.output.trim() ? `\n\n${owned.output.trim()}` : ''}`);
}

/** Refresh on every open; concurrent clicks share the same refresh and dashboard startup. */
export function openLab(): Promise<string> {
  if (stopping) return Promise.reject(new Error('ByteQuay is shutting down.'));
  opening ??= prepare().finally(() => { opening = null; });
  return opening;
}

/**
 * The task a link on the running dashboard asks to open, or null for any other address. Only that dashboard knows its per-launch path, so
 * another page cannot make a link this takes; and a task it takes only fills the composer, which the user still sends.
 */
export function labTask(url: string): LabTask | null {
  if (dashboard === null || !url.startsWith(`${dashboard.address}task?`)) return null;
  const query = new URL(url).searchParams;
  const repository = query.get('repository') ?? '';
  const text = query.get('text') ?? '';
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository) || text.trim() === '' || text.length > TASK_TEXT_LIMIT) return null;
  return { repository, text };
}

export function stopLab(): void {
  stopping = true;
  for (const owned of children) terminate(owned);
  dashboard = null;
}
