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
import { app, dialog } from 'electron';
import { ChildProcess, execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
import { createInterface } from 'node:readline';

declare const __BYTEQUAY_JIRA_AUTH_URL__: string;

const PORT_MESSAGE = 'BYTEQUAY_BACKEND_PORT=';

/**
 * Shared secret for the backend's agent, CI Autofix, and cherry-pick endpoints.
 * dev.sh exports it before starting both processes; a packaged app is the only
 * launcher of its own backend, so it mints one per launch. Main-process only —
 * the renderer never receives it, which is what keeps a page in the embedded
 * GitHub view from reaching those endpoints.
 */
const desktopSessionToken =
  process.env.BYTEQUAY_DESKTOP_SESSION_TOKEN || randomBytes(32).toString('hex');

/** Value for the backend's `X-ByteQuay-Session` header. */
export function sessionToken(): string {
  return desktopSessionToken;
}

let child: ChildProcess | null = null;
let failure: string | null = null;
let readinessFailure: string | null = null;
let reported = false;
let stderrTail = '';
let stopping = false;
let backendUrl: string | null = app.isPackaged ? null : portUrl(process.env.BYTEQUAY_BACKEND_PORT ?? '9527');

function portUrl(value: string): string {
  const port = Number(value);
  if (!/^\d{1,5}$/.test(value) || port < 1 || port > 65535) {
    throw new Error(`Invalid ByteQuay backend port: ${value}`);
  }
  return `http://127.0.0.1:${port}`;
}

export function backendBase(): string {
  if (failure) throw new Error(failure);
  if (!backendUrl) throw new Error(readinessFailure ?? 'The ByteQuay backend has not reported its port yet.');
  return backendUrl;
}

/**
 * Directories a developer's tools land in that a bare PATH never covers.
 * Appended after the login shell's PATH as a backstop, so the probe below
 * failing degrades to "most installs still work" rather than "nothing runs".
 */
const FALLBACK_BIN_DIRS = [
  path.join(homedir(), '.local', 'bin'),
  path.join(homedir(), '.bun', 'bin'),
  '/opt/homebrew/bin',
  '/usr/local/bin',
];

const PATH_MARKER = '__BYTEQUAY_PATH__';

/**
 * A Finder-launched .app inherits a bare PATH (/usr/bin:/bin:/usr/sbin:/sbin),
 * while the codex, claude, npm, node and gh CLIs the backend runs live on the
 * shell PATH. `-i` matters: zsh only reads .zshrc when interactive, and that is
 * where nvm and similar version managers install themselves. The marker
 * separates the answer from whatever the rc files print on startup.
 */
export function loginShellPath(): string | null {
  const shell = process.env.SHELL;
  if (!shell) return null;
  try {
    const out = execFileSync(shell, ['-ilc', `printf '%s%s' ${PATH_MARKER} "$PATH"`], {
      encoding: 'utf8',
      timeout: 5_000,
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    const marker = out.lastIndexOf(PATH_MARKER);
    if (marker < 0) return null;
    return out.slice(marker + PATH_MARKER.length).trim() || null;
  } catch {
    return null;
  }
}

/** The login shell's PATH, then the inherited one, then the fallback dirs, deduped in order. */
export function mergedPath(shellPath: string | null, currentPath: string | undefined): string {
  const entries = [...(shellPath ?? '').split(':'), ...(currentPath ?? '').split(':'), ...FALLBACK_BIN_DIRS];
  return [...new Set(entries.filter((entry) => entry.length > 0))].join(':');
}

/**
 * Spawn the packaged Spring Boot JAR as a child process.
 * In dev mode, we skip this — run Spring Boot manually from IntelliJ
 * or via dev.sh.
 *
 * The JAR is shipped via Forge's `extraResource` config, which copies
 * it into the .app's `Contents/Resources/` directory at package time.
 * `process.resourcesPath` resolves there at runtime.
 */
export function spawnBackend(): void {
  if (!app.isPackaged) return;
  if (child) return;

  // The backend inherits this PATH, and so does every CLI it spawns. Dev runs
  // start from a shell that already has it, so only the packaged app probes.
  process.env.PATH = mergedPath(loginShellPath(), process.env.PATH);

  const jar = path.join(process.resourcesPath, 'bytequay-backend.jar');
  const java = path.join(process.resourcesPath, 'jre', 'bin', 'java');
  if (!existsSync(java)) {
    failure = 'ByteQuay’s bundled Java runtime is missing. Reinstall ByteQuay from the release package.';
    return;
  }
  // App-wide RAM budget is ~8 GB; the backend gets ~2 GB of that ceiling.
  // ExitOnOutOfMemoryError flips an OOM into a clean exit so Electron
  // can surface "backend crashed" instead of getting a wedged sidecar
  // limping along after the heap gives up. Metaspace / code cache get
  // their own caps so a runaway classloader can't add another 500 MB
  // on top of -Xmx. Keep these in sync with dev.sh's MAVEN_OPTS so
  // dev mode behaves like the packaged build.
  const jvmArgs = [
    // sqlite-jdbc loads a native library; without this JDK 24+ prints a
    // restricted-method warning on every start (and will fail outright later).
    '--enable-native-access=ALL-UNNAMED',
    '-Xmx2000m',
    '-XX:MaxMetaspaceSize=256m',
    '-XX:ReservedCodeCacheSize=128m',
    '-XX:+ExitOnOutOfMemoryError',
    // Startup's query parsing and a busy hour can grow the heap far past the ~100 MB the backend keeps live, and G1 left
    // to itself never returns it while the app idles. A GC after a quiet minute, tighter free-space bounds and a higher GC
    // time allowance hand that memory back; a few GC threads suffice for a heap this small.
    '-XX:G1PeriodicGCInterval=60000',
    '-XX:MinHeapFreeRatio=10',
    '-XX:MaxHeapFreeRatio=30',
    '-XX:GCTimeRatio=4',
    '-XX:ParallelGCThreads=4',
    '-XX:ConcGCThreads=1',
    `-Dbytequay.launcher-pid=${process.pid}`,
  ];
  child = spawn(java, [...jvmArgs, '-jar', jar], {
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: false,
    env: {
      ...process.env,
      BYTEQUAY_DESKTOP_SESSION_TOKEN: desktopSessionToken,
      BYTEQUAY_JIRA_AUTH_URL: process.env.BYTEQUAY_JIRA_AUTH_URL ?? (typeof __BYTEQUAY_JIRA_AUTH_URL__ === 'string' ? __BYTEQUAY_JIRA_AUTH_URL__ : ''),
      BYTEQUAY_IDE_HOME: path.join(process.resourcesPath, 'ide'),
    },
  });
  if (child.stdout) {
    child.stdout.on('data', (chunk: Buffer) => process.stdout.write(chunk));
    // A dedicated startup line survives log-format changes and split stdout chunks.
    createInterface({ input: child.stdout }).on('line', (line) => {
      if (!line.startsWith(PORT_MESSAGE) || backendUrl || failure || stopping) return;
      try {
        backendUrl = portUrl(line.slice(PORT_MESSAGE.length));
      } catch (error) {
        failure = error instanceof Error ? error.message : String(error);
      }
    });
  }
  // The backend logs to ~/Library/Logs/ByteQuay/backend.log once Spring is up;
  // this tail only exists to explain crashes that happen before that.
  child.stderr?.on('data', (chunk: Buffer) => {
    stderrTail = (stderrTail + chunk.toString()).slice(-4000);
    process.stderr.write(chunk);
  });
  child.on('error', (e) => {
    failure = `Could not start ByteQuay’s bundled Java runtime. Reinstall ByteQuay from the release package.\n\n${e.message}`;
    backendUrl = null;
    child = null;
  });
  child.on('exit', (code, signal) => {
    console.log(`[backend] exited with code ${code}`);
    backendUrl = null;
    if (!stopping) {
      failure ??= `The ByteQuay backend exited with ${signal ? `signal ${signal}` : `code ${code}`}.\n\n${stderrTail.trim()}`;
      reportBackendFailure();
    }
    child = null;
  });
}

/** Show the recorded failure once, if there is one. */
export function reportBackendFailure(): void {
  const message = failure ?? readinessFailure;
  if (reported || !message) return;
  reported = true;
  dialog.showErrorBox('ByteQuay backend is not running', message);
}

export function killBackend(): void {
  stopping = true;
  backendUrl = null;
  if (child && !child.killed) {
    child.kill('SIGTERM');
    child = null;
  }
}

export async function waitForBackendReady(timeoutMs = 30_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (failure) return false;
    const address = backendUrl;
    if (address) {
      try {
        const res = await fetch(`${address}/hello`, {
          signal: AbortSignal.timeout(Math.max(1, Math.min(1000, deadline - Date.now()))),
        });
        if (res.ok && await res.text() === 'ByteQuay-backend ok' && !failure && backendUrl === address) {
          readinessFailure = null;
          reported = false;
          return true;
        }
      } catch {
        // not up yet
      }
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  readinessFailure = `The ByteQuay backend ${backendUrl ? `did not answer on ${backendUrl}` : 'did not report its port'}`
    + ` within ${Math.round(timeoutMs / 1000)}s.`
    + '\n\nSee ~/Library/Logs/ByteQuay/backend.log.';
  return false;
}
