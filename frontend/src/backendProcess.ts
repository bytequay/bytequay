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
import { ChildProcess, execFileSync, spawn, spawnSync } from 'node:child_process';
import path from 'node:path';

export const BACKEND_PORT = 53123;
export const BACKEND_BASE = `http://127.0.0.1:${BACKEND_PORT}`;

const MIN_JAVA_VERSION = 21;

let child: ChildProcess | null = null;
let failure: string | null = null;
let reported = false;
let stderrTail = '';

/**
 * A Finder-launched .app inherits a bare PATH (/usr/bin:/bin:/usr/sbin:/sbin),
 * where `java` is Apple's stub that fails unless a JDK is registered under
 * /Library/Java or ~/Library/Java. JDKs installed via Homebrew or SDKMAN are
 * only on the shell PATH, so we probe the usual homes explicitly.
 */
function resolveJava(): string | null {
  const candidates = [
    process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : null,
    javaHomeTool(),
    '/opt/homebrew/opt/openjdk/bin/java',
    '/usr/bin/java',
  ];
  const seen = new Set<string>();
  for (const candidate of candidates) {
    if (!candidate || seen.has(candidate)) continue;
    seen.add(candidate);
    const version = javaMajorVersion(candidate);
    if (version !== null && version >= MIN_JAVA_VERSION) return candidate;
  }
  failure = `ByteQuay needs Java ${MIN_JAVA_VERSION} or newer on this Mac and couldn't find it.\n\n`
    + `Install a JDK (e.g. "brew install openjdk@21") or set JAVA_HOME, then reopen ByteQuay.\n\n`
    + `Looked in: ${[...seen].join(', ')}`;
  return null;
}

function javaHomeTool(): string | null {
  try {
    const home = execFileSync('/usr/libexec/java_home', ['-v', `${MIN_JAVA_VERSION}+`], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
    return home ? path.join(home, 'bin', 'java') : null;
  } catch {
    return null;
  }
}

/** `java -version` prints to stderr; the stub prints an install prompt and exits non-zero. */
export function javaMajorVersion(javaBin: string): number | null {
  const res = spawnSync(javaBin, ['-version'], { encoding: 'utf8' });
  if (res.error || res.status !== 0) return null;
  const match = /version "(\d+)/.exec(`${res.stderr}${res.stdout}`);
  return match ? Number(match[1]) : null;
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

  const jar = path.join(process.resourcesPath, 'bytequay-backend.jar');
  // App-wide RAM budget is ~8 GB; the backend gets ~2 GB of that
  // ceiling, which is enough for Hibernate + the local-repo JGit
  // buffers + scheduler bookkeeping even on a long-lived session, and
  // leaves headroom alongside the 4-way CLI lane (~2 GB) and the
  // Electron renderers (~2.5 GB with one embed open).
  // ExitOnOutOfMemoryError flips an OOM into a clean exit so Electron
  // can surface "backend crashed" instead of getting a wedged sidecar
  // limping along after the heap gives up. Metaspace / code cache get
  // their own caps so a runaway classloader can't add another 500 MB
  // on top of -Xmx. Keep these in sync with dev.sh's MAVEN_OPTS so
  // dev mode behaves like the packaged build.
  const jvmArgs = [
    '-Xmx2000m',
    '-XX:MaxMetaspaceSize=256m',
    '-XX:ReservedCodeCacheSize=128m',
    '-XX:+ExitOnOutOfMemoryError',
  ];
  const java = resolveJava();
  if (!java) return;

  child = spawn(java, [...jvmArgs, '-jar', jar], {
    stdio: ['ignore', 'inherit', 'pipe'],
    detached: false,
  });
  // The backend logs to ~/Library/Logs/ByteQuay/backend.log once Spring is up;
  // this tail only exists to explain crashes that happen before that.
  child.stderr?.on('data', (chunk: Buffer) => {
    stderrTail = (stderrTail + chunk.toString()).slice(-4000);
    process.stderr.write(chunk);
  });
  child.on('error', (e) => {
    failure = `Could not start the ByteQuay backend (${java}): ${e.message}`;
    child = null;
  });
  child.on('exit', (code) => {
    console.log(`[backend] exited with code ${code}`);
    if (code !== 0 && code !== null) {
      // A crash mid-session (commonly: another ByteQuay already owns port
      // 53123) otherwise leaves every later IPC call failing with a bare
      // "fetch failed".
      failure = `The ByteQuay backend exited with code ${code}.\n\n${stderrTail.trim()}`;
      reportBackendFailure();
    }
    child = null;
  });
}

/** Show the recorded failure once, if there is one. */
export function reportBackendFailure(): void {
  if (reported || !failure) return;
  reported = true;
  dialog.showErrorBox('ByteQuay backend is not running', failure);
}

export function killBackend(): void {
  if (child && !child.killed) {
    child.kill('SIGTERM');
    child = null;
  }
}

export async function waitForBackendReady(timeoutMs = 30_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BACKEND_BASE}/hello`);
      if (res.ok) return true;
    } catch {
      // not up yet
    }
    if (failure) return false;
    await new Promise((r) => setTimeout(r, 300));
  }
  failure ??= `The ByteQuay backend did not answer on ${BACKEND_BASE} within ${Math.round(timeoutMs / 1000)}s.`
    + '\n\nSee ~/Library/Logs/ByteQuay/backend.log.';
  return false;
}
