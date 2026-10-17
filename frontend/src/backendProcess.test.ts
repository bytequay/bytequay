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
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ app: { isPackaged: true }, showErrorBox: vi.fn(), spawn: vi.fn(), execFileSync: vi.fn(), existsSync: vi.fn() }));
vi.mock('electron', () => ({ app: mocks.app, dialog: { showErrorBox: mocks.showErrorBox } }));
vi.mock('node:child_process', () => ({ spawn: mocks.spawn, execFileSync: mocks.execFileSync }));
vi.mock('node:fs', () => ({ existsSync: mocks.existsSync }));

const resourcesPath = Object.getOwnPropertyDescriptor(process, 'resourcesPath');
Object.defineProperty(process, 'resourcesPath', { configurable: true, value: '/test/resources' });
afterAll(() => {
  if (resourcesPath) Object.defineProperty(process, 'resourcesPath', resourcesPath);
  else Reflect.deleteProperty(process, 'resourcesPath');
});

let child: EventEmitter & { stdout: PassThrough; stderr: PassThrough; kill: ReturnType<typeof vi.fn> };
let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  vi.useFakeTimers();
  vi.stubEnv('BYTEQUAY_BACKEND_PORT', undefined);
  // spawnBackend rewrites PATH; stubbing it restores the original after each test.
  vi.stubEnv('PATH', process.env.PATH);
  mocks.app.isPackaged = true;
  mocks.existsSync.mockReturnValue(true);
  child = Object.assign(new EventEmitter(), {
    stdout: new PassThrough(), stderr: new PassThrough(), kill: vi.fn(),
  });
  mocks.spawn.mockReturnValue(child);
  fetchMock = vi.fn().mockImplementation(async () => new Response('ByteQuay-backend ok'));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  child.stdout.end();
  child.stderr.end();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('backend port discovery', () => {
  it('launches the bundled runtime without a system Java installation', async () => {
    vi.stubEnv('JAVA_HOME', '/missing/jdk');
    vi.stubEnv('PATH', '/no-system-java');
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    expect(mocks.existsSync).toHaveBeenCalledWith('/test/resources/jre/bin/java');
    expect(mocks.spawn).toHaveBeenCalledWith('/test/resources/jre/bin/java',
      expect.arrayContaining(['-jar', '/test/resources/bytequay-backend.jar']), expect.any(Object));
  });

  it('gives the backend the login shell PATH that a Finder launch lacks', async () => {
    vi.stubEnv('SHELL', '/bin/zsh');
    vi.stubEnv('PATH', '/usr/bin:/bin');
    mocks.execFileSync.mockReturnValue('rc noise\n__BYTEQUAY_PATH__/Users/me/.nvm/bin:/usr/bin');
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    expect(mocks.execFileSync).toHaveBeenCalledWith('/bin/zsh', ['-ilc', expect.stringContaining('$PATH')], expect.objectContaining({ timeout: 5_000 }));
    const path = String(mocks.spawn.mock.calls[0][2].env.PATH).split(':');
    expect(path.slice(0, 3)).toEqual(['/Users/me/.nvm/bin', '/usr/bin', '/bin']);
    expect(path).toContain('/opt/homebrew/bin');
  });

  it('still adds the usual tool folders when the login shell probe fails', async () => {
    vi.stubEnv('PATH', '/usr/bin:/bin');
    mocks.execFileSync.mockImplementation(() => { throw new Error('timed out'); });
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    expect(String(mocks.spawn.mock.calls[0][2].env.PATH).split(':')).toEqual(expect.arrayContaining(['/usr/bin', '/opt/homebrew/bin', '/usr/local/bin']));
  });

  it('reports an incomplete installation when the bundled runtime is missing', async () => {
    mocks.existsSync.mockReturnValue(false);
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    expect(mocks.spawn).not.toHaveBeenCalled();
    expect(await backend.waitForBackendReady()).toBe(false);
    backend.reportBackendFailure();
    expect(mocks.showErrorBox).toHaveBeenCalledWith('ByteQuay backend is not running', expect.stringContaining('Reinstall ByteQuay'));
  });

  it('reports an unusable bundled executable without falling back to system Java', async () => {
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    child.emit('error', new Error('permission denied'));
    expect(await backend.waitForBackendReady()).toBe(false);
    backend.reportBackendFailure();
    expect(mocks.spawn).toHaveBeenCalledTimes(1);
    expect(mocks.showErrorBox).toHaveBeenCalledWith('ByteQuay backend is not running', expect.stringContaining('Reinstall ByteQuay'));
  });

  it('disables the Goal experiment in packaged apps even when the environment enables it', async () => {
    vi.stubEnv('BYTEQUAY_EXPERIMENTS_GOALS', 'true');
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    expect(mocks.spawn.mock.calls[0]?.[1]).toContain('--bytequay.experiments.goals=false');
  });

  it.each([9527, 41234])('waits for the child to report port %s before connecting', async port => {
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    const ready = backend.waitForBackendReady();
    await vi.advanceTimersByTimeAsync(300);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(() => backend.backendBase()).toThrow('has not reported its port');
    child.stdout.write('ordinary startup log\nBYTEQUAY_BACKEND_PO');
    child.stdout.write(`RT=${port}\n`);
    await vi.advanceTimersByTimeAsync(300);
    expect(await ready).toBe(true);
    expect(backend.backendBase()).toBe(`http://127.0.0.1:${port}`);
    expect(fetchMock).toHaveBeenCalledWith(`http://127.0.0.1:${port}/hello`, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    child.stdout.write('BYTEQUAY_BACKEND_PORT=12345\n');
    expect(backend.backendBase()).toBe(`http://127.0.0.1:${port}`);
  });

  it.each([
    { runtime: undefined, expected: 'https://auth.example.test' },
    { runtime: 'https://override.example.test', expected: 'https://override.example.test' },
    { runtime: '', expected: '' },
  ])('passes the packaged Jira service default with deliberate runtime override $runtime', async ({ runtime, expected }) => {
    vi.stubGlobal('__BYTEQUAY_JIRA_AUTH_URL__', 'https://auth.example.test');
    vi.stubEnv('BYTEQUAY_JIRA_AUTH_URL', runtime ?? '');
    if (runtime === undefined) delete process.env.BYTEQUAY_JIRA_AUTH_URL;
    const backend = await import('./backendProcess'); backend.spawnBackend();
    expect(mocks.spawn.mock.calls[0]?.[2].env.BYTEQUAY_JIRA_AUTH_URL).toBe(expected);
  });
  it('leaves Jira unavailable when neither build nor runtime provides a service', async () => {
    vi.stubEnv('BYTEQUAY_JIRA_AUTH_URL', '');
    delete process.env.BYTEQUAY_JIRA_AUTH_URL;
    const backend = await import('./backendProcess'); backend.spawnBackend();
    expect(mocks.spawn.mock.calls[0]?.[2].env.BYTEQUAY_JIRA_AUTH_URL).toBe('');
  });

  it('uses the development launcher port without spawning Java', async () => {
    mocks.app.isPackaged = false;
    vi.stubEnv('BYTEQUAY_BACKEND_PORT', '42345');
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    expect(mocks.spawn).not.toHaveBeenCalled();
    expect(await backend.waitForBackendReady()).toBe(true);
    expect(backend.backendBase()).toBe('http://127.0.0.1:42345');
  });

  it('ignores a development port in the packaged app', async () => {
    vi.stubEnv('BYTEQUAY_BACKEND_PORT', '42345');
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    expect(() => backend.backendBase()).toThrow('has not reported its port');
  });

  it.each(['0', '65536', '9527/path'])('rejects invalid announced port %s', async port => {
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    child.stdout.write(`BYTEQUAY_BACKEND_PORT=${port}\n`);
    expect(await backend.waitForBackendReady()).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(() => backend.backendBase()).toThrow('Invalid ByteQuay backend port');
  });

  it('rejects a successful HTTP response from a different application', async () => {
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    child.stdout.write('BYTEQUAY_BACKEND_PORT=9527\n');
    fetchMock.mockImplementation(async () => new Response('another application'));
    const ready = backend.waitForBackendReady(300);
    await vi.advanceTimersByTimeAsync(300);
    expect(await ready).toBe(false);
  });

  it('times out without contacting any port if the child never announces one', async () => {
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    const ready = backend.waitForBackendReady(300);
    await vi.advanceTimersByTimeAsync(300);
    expect(await ready).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(() => backend.backendBase()).toThrow('did not report its port');
    child.stdout.write('BYTEQUAY_BACKEND_PORT=41234\n');
    expect(await backend.waitForBackendReady()).toBe(true);
    expect(backend.backendBase()).toBe('http://127.0.0.1:41234');
  });

  it('forgets the address when its child exits and reports the failure once', async () => {
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    child.stdout.write('BYTEQUAY_BACKEND_PORT=41234\n');
    child.emit('exit', 1, null);
    expect(await backend.waitForBackendReady()).toBe(false);
    expect(() => backend.backendBase()).toThrow('exited with code 1');
    backend.reportBackendFailure();
    expect(mocks.showErrorBox).toHaveBeenCalledTimes(1);
  });

  it('does not report an intentional shutdown as a failure', async () => {
    const backend = await import('./backendProcess');
    backend.spawnBackend();
    backend.killBackend();
    child.emit('exit', null, 'SIGTERM');
    expect(child.kill).toHaveBeenCalledWith('SIGTERM');
    expect(mocks.showErrorBox).not.toHaveBeenCalled();
  });
});
