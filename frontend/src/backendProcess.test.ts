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

const mocks = vi.hoisted(() => ({ app: { isPackaged: true }, showErrorBox: vi.fn(), spawn: vi.fn() }));
vi.mock('electron', () => ({ app: mocks.app, dialog: { showErrorBox: mocks.showErrorBox } }));
vi.mock('node:child_process', () => ({
  spawn: mocks.spawn,
  execFileSync: () => '/test/java-home',
  spawnSync: () => ({ status: 0, stderr: 'openjdk version "25"', stdout: '' }),
}));

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
  mocks.app.isPackaged = true;
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
