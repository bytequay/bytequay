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
import { ChildProcess, spawn } from 'node:child_process';
import path from 'node:path';

export const BACKEND_PORT = 53123;
export const BACKEND_BASE = `http://127.0.0.1:${BACKEND_PORT}`;

let child: ChildProcess | null = null;

/**
 * Spawn the packaged Spring Boot JAR as a child process.
 * In dev mode, we skip this — run Spring Boot manually from IntelliJ.
 */
export function spawnBackend(): void {
  if (!app.isPackaged) return;
  if (child) return;

  const jar = path.join(process.resourcesPath, 'backend', 'daily-review-backend.jar');
  child = spawn('java', ['-jar', jar], {
    stdio: ['ignore', 'inherit', 'inherit'],
    detached: false,
  });
  child.on('exit', (code) => {
    console.log(`[backend] exited with code ${code}`);
    child = null;
  });
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
    await new Promise((r) => setTimeout(r, 300));
  }
  return false;
}
