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
import { execFile } from 'node:child_process';
import { constants } from 'node:fs';
import { access, mkdir, writeFile, chmod } from 'node:fs/promises';
import path from 'node:path';
import type { GeminiTerminalStatus } from './contracts';

const SERVICE = 'com.bytequay.gemini';
const ACCOUNT = 'gemini-api-key';
const KEY_ARGS = ['-s', SERVICE, '-a', ACCOUNT];

function run(file: string, args: string[], input?: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = execFile(file, args, { timeout: 30_000, maxBuffer: 16_384 }, (error, stdout) => {
      // Never forward subprocess errors: they can contain credentials or command input.
      if (error) reject(new Error('Gemini setup could not access macOS Keychain or Terminal. Try again and allow access if macOS asks.'));
      else resolve(stdout.trim());
    });
    child.stdin?.on('error', () => { /* Completion is reported by execFile. */ });
    child.stdin?.end(input);
  });
}

function shellQuote(value: string): string { return `'${value.replaceAll("'", "'\\''")}'`; }

export class GeminiTerminal {
  private readonly directory: string;
  private readonly searchPath: string;

  constructor(userData: string, home: string, environmentPath = process.env.PATH ?? '') {
    this.directory = path.join(userData, 'gemini-terminal');
    this.searchPath = [environmentPath, '/opt/homebrew/bin', '/usr/local/bin', '/usr/bin', '/bin', path.join(home, '.local/bin')].filter(Boolean).join(':');
  }

  private async executable(): Promise<string | null> {
    for (const directory of this.searchPath.split(':').filter(path.isAbsolute)) {
      const file = path.join(directory, 'gemini');
      try { await access(file, constants.X_OK); return file; } catch { /* Try the next installation. */ }
    }
    return null;
  }

  async status(): Promise<GeminiTerminalStatus> {
    // security exits 44 for a missing item. Other failures must not look like an unsaved key.
    const configured = await new Promise<boolean>((resolve, reject) => {
      execFile('/usr/bin/security', ['find-generic-password', ...KEY_ARGS], { timeout: 10_000 }, error => {
        if (!error) resolve(true);
        else if (error.code === 44) resolve(false);
        else reject(new Error('Cannot read Gemini key status from macOS Keychain. Unlock your keychain and retry.'));
      });
    });
    return { configured, installed: await this.executable() !== null };
  }

  async save(value: unknown): Promise<GeminiTerminalStatus> {
    if (typeof value !== 'string' || !/^[\x21-\x7e]{10,4096}$/.test(value.trim())) throw new Error('Paste a valid Gemini API key.');
    const key = value.trim();
    let response: Response;
    try {
      response = await fetch('https://generativelanguage.googleapis.com/v1beta/models?pageSize=1', {
        headers: { 'x-goog-api-key': key }, signal: AbortSignal.timeout(20_000), redirect: 'error',
      });
    } catch { throw new Error('Could not reach Google to check this key. Your saved key has not changed.'); }
    await response.body?.cancel();
    if (!response.ok) {
      if (response.status === 429) throw new Error('Google is limiting requests for this key. Try again later; your saved key has not changed.');
      throw new Error('Google did not accept this key. Check its Gemini API access in AI Studio; your saved key has not changed.');
    }
    // Interactive stdin keeps the key out of process arguments and shell history.
    const escaped = key.replaceAll('\\', '\\\\').replaceAll('"', '\\"');
    await run('/usr/bin/security', ['-i'], `add-generic-password -U -s ${SERVICE} -a ${ACCOUNT} -w "${escaped}"\n`);
    if (await run('/usr/bin/security', ['find-generic-password', ...KEY_ARGS, '-w']) !== key) {
      throw new Error('The key could not be saved in macOS Keychain. Try again.');
    }
    return this.status();
  }

  async remove(): Promise<GeminiTerminalStatus> {
    if ((await this.status()).configured) await run('/usr/bin/security', ['delete-generic-password', ...KEY_ARGS]);
    return this.status();
  }

  async open(): Promise<void> {
    const executable = await this.executable();
    if (!executable) throw new Error('Install Gemini CLI before opening a terminal.');
    if (!(await this.status()).configured) throw new Error('Save a Gemini API key first.');
    const settingsDirectory = path.join(this.directory, '.gemini');
    await mkdir(settingsDirectory, { recursive: true, mode: 0o700 });
    await writeFile(path.join(settingsDirectory, 'settings.json'), JSON.stringify({ security: { auth: { selectedType: 'gemini-api-key' } } }), { mode: 0o600 });
    const launcher = path.join(this.directory, 'Open Gemini.command');
    await writeFile(launcher, `#!/bin/sh
set -eu
cd ${shellQuote(this.directory)}
export PATH=${shellQuote(this.searchPath)}
unset GOOGLE_API_KEY GOOGLE_GENAI_USE_VERTEXAI
GEMINI_API_KEY=$(/usr/bin/security find-generic-password -s ${SERVICE} -a ${ACCOUNT} -w) || { echo 'Unable to read Gemini key. Open ByteQuay Settings to save it again.'; exit 1; }
export GEMINI_API_KEY
exec ${shellQuote(executable)}
`, { mode: 0o700 });
    await chmod(launcher, 0o700);
    await run('/usr/bin/open', ['-a', 'Terminal', launcher]);
  }
}
