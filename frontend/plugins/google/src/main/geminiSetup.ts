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
import { access } from 'node:fs/promises';
import path from 'node:path';
import type { GeminiSetupStatus } from '../contracts';

const SERVICE = 'com.bytequay.gemini';
const ACCOUNT = 'gemini-api-key';
const KEY_ARGS = ['-s', SERVICE, '-a', ACCOUNT];

function run(file: string, args: string[], input?: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = execFile(file, args, { timeout: 30_000, maxBuffer: 16_384 }, (error, stdout) => {
      // Never forward subprocess errors: they can contain credentials or command input.
      if (error) reject(new Error('Gemini setup could not access macOS Keychain. Try again and allow access if macOS asks.'));
      else resolve(stdout.trim());
    });
    child.stdin?.on('error', () => { /* Completion is reported by execFile. */ });
    child.stdin?.end(input);
  });
}

export class GeminiSetup {
  private readonly searchPath: string;

  constructor(home: string, environmentPath = process.env.PATH ?? '') {
    this.searchPath = [environmentPath, '/opt/homebrew/bin', '/usr/local/bin', '/usr/bin', '/bin', path.join(home, '.local/bin')].filter(Boolean).join(':');
  }

  private async executable(): Promise<string | null> {
    for (const directory of this.searchPath.split(':').filter(path.isAbsolute)) {
      const file = path.join(directory, 'gemini');
      try { await access(file, constants.X_OK); return file; } catch { /* Try the next installation. */ }
    }
    return null;
  }

  async status(): Promise<GeminiSetupStatus> {
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

  async save(value: unknown): Promise<GeminiSetupStatus> {
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

  async remove(): Promise<GeminiSetupStatus> {
    if ((await this.status()).configured) await run('/usr/bin/security', ['delete-generic-password', ...KEY_ARGS]);
    return this.status();
  }

  async credentials(): Promise<{ executable: string; key: string; searchPath: string }> {
    const executable = await this.executable();
    if (!executable) throw new Error('Install Gemini CLI first.');
    if (!(await this.status()).configured) throw new Error('Save a Gemini API key in Google Apps settings first.');
    return { executable, key: await run('/usr/bin/security', ['find-generic-password', ...KEY_ARGS, '-w']), searchPath: this.searchPath };
  }
}
