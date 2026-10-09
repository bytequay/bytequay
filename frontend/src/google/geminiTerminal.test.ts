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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { GeminiTerminal } from './geminiTerminal';

const mocks = vi.hoisted(() => ({ execFile: vi.fn(), access: vi.fn(), mkdir: vi.fn(), writeFile: vi.fn(), chmod: vi.fn(), fetch: vi.fn(), end: vi.fn() }));
vi.mock('node:child_process', () => ({ execFile: mocks.execFile }));
vi.mock('node:fs/promises', () => ({ access: mocks.access, mkdir: mocks.mkdir, writeFile: mocks.writeFile, chmod: mocks.chmod }));
const secret = 'AQ.test-only-key_123';
let stored: string | null;
let keychainError: number | null;
let rejectWrite: boolean;
let processFailure: boolean;

beforeEach(() => {
  vi.resetAllMocks(); vi.stubGlobal('fetch', mocks.fetch);
  stored = null; keychainError = null; rejectWrite = false; processFailure = false;
  mocks.fetch.mockResolvedValue({ ok: true, body: null });
  mocks.access.mockImplementation(async (file: string) => { if (file !== '/test/bin/gemini') throw new Error('missing'); });
  mocks.execFile.mockImplementation((file: string, args: string[], _options: unknown, callback: (error: { code: number; message?: string } | null, stdout: string) => void) => {
    if (args[0] === '-i') return { stdin: { on: vi.fn(), end: (input: string) => {
      mocks.end(input);
      if (!rejectWrite) stored = secret;
      callback(null, '');
    } } };
    if (processFailure) callback({ code: 1, message: secret }, '');
    else if (file === '/usr/bin/security' && args[0] === 'find-generic-password') {
      callback(keychainError !== null ? { code: keychainError } : stored === null ? { code: 44 } : null, args.includes('-w') ? stored ?? '' : '');
    } else { if (args[0] === 'delete-generic-password') stored = null; callback(null, ''); }
    return { stdin: { on: vi.fn(), end: vi.fn() } };
  });
});
afterEach(() => { vi.unstubAllGlobals(); });
const terminal = () => new GeminiTerminal("/test/ByteQuay's data", '/test/home', '/test/bin');

it('validates before replacing a key and never sends secrets through process arguments or status', async () => {
  expect(await terminal().save(` ${secret} `)).toEqual({ installed: true, configured: true });
  expect(mocks.fetch).toHaveBeenCalledWith('https://generativelanguage.googleapis.com/v1beta/models?pageSize=1', expect.objectContaining({
    headers: { 'x-goog-api-key': secret }, redirect: 'error',
  }));
  expect(mocks.end).toHaveBeenCalledWith(expect.stringContaining(secret));
  expect(JSON.stringify(mocks.execFile.mock.calls.map(call => call.slice(0, 3)))).not.toContain(secret);
  expect(mocks.writeFile).not.toHaveBeenCalled();
});

it('preserves the saved key on invalid input, network failure, rejection, or quota errors', async () => {
  stored = 'previous-key';
  for (const invalid of [null, '', 'bad\nkey-value', 'x'.repeat(4097)]) await expect(terminal().save(invalid)).rejects.toThrow('Paste a valid');
  mocks.fetch.mockRejectedValueOnce(new Error(secret));
  await expect(terminal().save(secret)).rejects.toThrow('Could not reach Google');
  mocks.fetch.mockResolvedValueOnce({ ok: false, status: 403, body: null });
  await expect(terminal().save(secret)).rejects.toThrow('Google did not accept');
  mocks.fetch.mockResolvedValueOnce({ ok: false, status: 429, body: null });
  await expect(terminal().save(secret)).rejects.toThrow('Google is limiting');
  expect(stored).toBe('previous-key'); expect(mocks.execFile).not.toHaveBeenCalled();
});

it('does not treat a failed interactive Keychain write as success', async () => {
  stored = 'previous-key'; rejectWrite = true;
  await expect(terminal().save(secret)).rejects.toThrow('could not be saved');
});

it('distinguishes missing credentials from Keychain access failures', async () => {
  expect(await terminal().status()).toEqual({ configured: false, installed: true });
  keychainError = 36;
  await expect(terminal().status()).rejects.toThrow('Cannot read Gemini key status');
});

it('opens a private launcher that retrieves the key at runtime without embedding it or changing global settings', async () => {
  stored = secret;
  await terminal().open();
  const writes = mocks.writeFile.mock.calls;
  expect(writes).toHaveLength(2);
  expect(writes[0][0]).toBe("/test/ByteQuay's data/gemini-terminal/.gemini/settings.json");
  expect(JSON.parse(writes[0][1])).toEqual({ security: { auth: { selectedType: 'gemini-api-key' } } });
  expect(writes[1][1]).toContain("cd '/test/ByteQuay'\\''s data/gemini-terminal'");
  expect(writes[1][1]).toContain('security find-generic-password');
  expect(writes[1][1]).toContain('unset GOOGLE_API_KEY GOOGLE_GENAI_USE_VERTEXAI');
  expect(writes[1][1]).toContain("exec '/test/bin/gemini'");
  expect(JSON.stringify(writes)).not.toContain(secret);
  expect(writes[1][2]).toEqual({ mode: 0o700 });
  expect(mocks.execFile).toHaveBeenLastCalledWith('/usr/bin/open', ['-a', 'Terminal', writes[1][0]], expect.anything(), expect.any(Function));
});

it('refuses to launch without a key or CLI and removes only the ByteQuay key', async () => {
  await expect(terminal().open()).rejects.toThrow('Save a Gemini API key first');
  stored = secret; mocks.access.mockRejectedValue(new Error('missing'));
  await expect(terminal().open()).rejects.toThrow('Install Gemini CLI');
  expect(mocks.writeFile).not.toHaveBeenCalled();
  expect(await terminal().remove()).toEqual({ configured: false, installed: false });
  expect(mocks.execFile.mock.calls.find(call => call[1][0] === 'delete-generic-password')?.[1])
    .toEqual(['delete-generic-password', '-s', 'com.bytequay.gemini', '-a', 'gemini-api-key']);
});

it('does not expose subprocess output when launching fails', async () => {
  stored = secret;
  mocks.execFile.mockImplementationOnce((_file: string, _args: string[], _options: unknown, callback: (error: null, stdout: string) => void) => {
    callback(null, ''); return { stdin: { on: vi.fn(), end: vi.fn() } };
  });
  processFailure = true;
  await expect(terminal().open()).rejects.toThrow('Gemini setup could not access');
});
