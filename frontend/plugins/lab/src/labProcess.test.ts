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
import { afterAll, afterEach, beforeEach, expect, it, vi, type Mock } from 'vitest';

const mocks = vi.hoisted(() => ({
  app: { isPackaged: true, getAppPath: () => '/checkout/frontend' }, spawn: vi.fn(), existsSync: vi.fn(), mkdir: vi.fn(), createServer: vi.fn(),
}));
vi.mock('electron', () => ({ app: mocks.app }));
vi.mock('node:child_process', () => ({ spawn: mocks.spawn }));
vi.mock('node:crypto', () => ({ randomUUID: () => 'launch-id' }));
vi.mock('node:fs', () => ({ existsSync: mocks.existsSync }));
vi.mock('node:fs/promises', () => ({ mkdir: mocks.mkdir }));
vi.mock('node:net', () => ({ createServer: mocks.createServer }));
vi.mock('node:os', () => ({ homedir: () => '/users/test' }));

const resourcesPath = Object.getOwnPropertyDescriptor(process, 'resourcesPath');
Object.defineProperty(process, 'resourcesPath', { configurable: true, value: '/installed/resources' });
afterAll(() => {
  if (resourcesPath) Object.defineProperty(process, 'resourcesPath', resourcesPath);
  else Reflect.deleteProperty(process, 'resourcesPath');
});

class Child extends EventEmitter {
  stdout = new PassThrough();
  stderr = new PassThrough();
  pid = 8100 + children.length;
  kill = vi.fn(() => { this.end(null, 'SIGKILL'); return true; });
  end(code: number | null = 0, signal: string | null = null) { this.emit('exit', code, signal); this.emit('close', code, signal); }
}
let children: Child[];
let fetchMock: Mock<[], Promise<Response>>;
const flush = () => vi.advanceTimersByTimeAsync(0);

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  vi.useFakeTimers();
  children = [];
  mocks.app.isPackaged = true;
  mocks.existsSync.mockReturnValue(true);
  mocks.mkdir.mockResolvedValue(undefined);
  mocks.spawn.mockImplementation(() => { const child = new Child(); children.push(child); return child; });
  mocks.createServer.mockImplementation(() => Object.assign(new EventEmitter(), {
    listen: (_port: number, _host: string, ready: () => void) => ready(), address: () => ({ port: 49152 }), close: (done: () => void) => done(),
  }));
  fetchMock = vi.fn(async () => new Response('ok'));
  vi.stubGlobal('fetch', fetchMock);
  vi.spyOn(process, 'kill').mockImplementation(pid => { children.find(child => child.pid === -pid)?.end(null, 'SIGKILL'); return true; });
});
afterEach(() => {
  children.forEach(child => { child.stdout.end(); child.stderr.end(); });
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

it('refreshes before serving, using only bundled Python even with no system Python or uv', async () => {
  vi.stubEnv('PATH', '/no/tools');
  vi.stubEnv('PYTHONPATH', '/untrusted/modules');
  vi.stubEnv('BYTEQUAY_DESKTOP_SESSION_TOKEN', 'private');
  const lab = await import('./labProcess');
  const opened = lab.openLab();
  expect(lab.openLab()).toBe(opened);
  await flush();
  expect(children).toHaveLength(1);
  expect(mocks.spawn.mock.calls[0]).toEqual(['/installed/resources/lab/python/bin/python3',
    ['-B', '-s', '/installed/resources/lab/app/desktop.py', '--folder', '/users/test/Library/Application Support/ByteQuay Lab',
      '--runtime', '/users/test/Library/Application Support/ByteQuay/agent-runtime', '--database', '/users/test/Library/Application Support/ByteQuay/bytequay-1.0.db'],
    expect.objectContaining({ detached: true, env: expect.objectContaining({ PATH: '/no/tools', BYTEQUAY_LAB_PARENT_PID: String(process.pid) }) })]);
  expect(mocks.spawn.mock.calls[0][2].env).not.toHaveProperty('PYTHONPATH');
  expect(mocks.spawn.mock.calls[0][2].env).not.toHaveProperty('BYTEQUAY_DESKTOP_SESSION_TOKEN');
  expect(fetchMock).not.toHaveBeenCalled();
  children[0].end();
  expect(await opened).toBe('http://127.0.0.1:49152/lab-launch-id/');
  expect(mocks.spawn.mock.calls[1][1]).toEqual(expect.arrayContaining(['/installed/resources/lab/app/dashboard.py', '--port', '49152', '--base-path', 'lab-launch-id']));
  expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:49152/lab-launch-id/_stcore/health', expect.any(Object));
  lab.stopLab();
});

it('reopens a healthy dashboard without rebuilding its statistics', async () => {
  const lab = await import('./labProcess');
  const first = lab.openLab(); await flush(); children[0].end(); await first;
  const second = lab.openLab();
  expect(await second).toBe(await first);
  expect(children).toHaveLength(2);
  lab.stopLab();
});

it('ends the dashboard when closed, and starts it again with fresh statistics on the next open', async () => {
  const lab = await import('./labProcess');
  const first = lab.openLab(); await flush(); children[0].end(); await first;
  lab.closeLab();
  expect(process.kill).toHaveBeenCalledWith(-children[1].pid, 'SIGKILL');
  const second = lab.openLab(); await flush(); children[2].end(); await second;
  expect(children).toHaveLength(4);
  lab.stopLab();
});

it('keeps a dashboard that is still starting when closed', async () => {
  const lab = await import('./labProcess');
  const opening = lab.openLab(); await flush();
  lab.closeLab();
  children[0].end();
  expect(await opening).toBe('http://127.0.0.1:49152/lab-launch-id/');
  expect(children[1].kill).not.toHaveBeenCalled();
  lab.stopLab();
});

it('restarts a dashboard that exited since the last open', async () => {
  const lab = await import('./labProcess');
  const first = lab.openLab(); await flush(); children[0].end(); await first;
  children[1].end(1);
  const second = lab.openLab(); await flush(); children[2].end(); await second;
  expect(children).toHaveLength(4);
  lab.stopLab();
});

it('surfaces refresh failure without opening stale data and allows retry', async () => {
  const lab = await import('./labProcess');
  const failed = expect(lab.openLab()).rejects.toThrow('database is locked');
  await flush(); children[0].stderr.write('database is locked'); children[0].end(1); await failed;
  expect(children).toHaveLength(1);
  const retried = lab.openLab(); await flush(); children[1].end();
  expect(await retried).toBe('http://127.0.0.1:49152/lab-launch-id/');
  lab.stopLab();
});

it('reports an incomplete installation without trying an arbitrary system interpreter', async () => {
  mocks.existsSync.mockReturnValue(false);
  const lab = await import('./labProcess');
  await expect(lab.openLab()).rejects.toThrow('installation is incomplete');
  expect(mocks.spawn).not.toHaveBeenCalled();
});

it('reports a failed spawn and permits a fresh attempt', async () => {
  const lab = await import('./labProcess');
  const failed = expect(lab.openLab()).rejects.toThrow('permission denied');
  await flush(); children[0].emit('error', new Error('permission denied')); await failed;
  const retried = lab.openLab(); await flush(); children[1].end(); await retried;
  lab.stopLab();
});

it('rejects a health response if the dashboard process exits before it arrives', async () => {
  fetchMock.mockImplementation(async () => { children[1].end(1); return new Response('ok'); });
  const lab = await import('./labProcess');
  const failed = expect(lab.openLab()).rejects.toThrow('exited with code 1');
  await flush(); children[0].end(); await vi.advanceTimersByTimeAsync(200); await failed;
});

it('times out an unresponsive dashboard and terminates its process group', async () => {
  fetchMock.mockImplementation(async () => new Response('unavailable', { status: 503 }));
  const lab = await import('./labProcess');
  const failed = expect(lab.openLab()).rejects.toThrow('did not become ready');
  await flush(); children[0].end(); await vi.advanceTimersByTimeAsync(30_000); await failed;
  expect(process.kill).toHaveBeenCalledWith(-children[1].pid, 'SIGKILL');
});

it('stops a refresh on quit and never starts the dashboard afterward', async () => {
  const lab = await import('./labProcess');
  const failed = expect(lab.openLab()).rejects.toThrow('Could not refresh');
  await flush(); lab.stopLab(); await failed;
  expect(children).toHaveLength(1);
  await expect(lab.openLab()).rejects.toThrow('shutting down');
});

it('stops a dashboard still waiting for readiness when ByteQuay quits', async () => {
  fetchMock.mockImplementation(async () => new Response('starting', { status: 503 }));
  const lab = await import('./labProcess');
  const failed = expect(lab.openLab()).rejects.toThrow('shutting down');
  await flush(); children[0].end(); await flush(); lab.stopLab();
  await vi.advanceTimersByTimeAsync(200); await failed;
  expect(process.kill).toHaveBeenCalledWith(-children[1].pid, 'SIGKILL');
});

it('takes a task only from a link on the running dashboard, with a repository and a bounded text', async () => {
  const lab = await import('./labProcess');
  const link = (query: string, base = 'http://127.0.0.1:49152/lab-launch-id/') => `${base}task?${query}`;
  const good = link('repository=bytequay%2Fbytequay&text=Shorten%20it%0A%0ALab%20suggestion%3A%20Shorten%20it');
  expect(lab.labTask(good)).toBeNull();
  const opened = lab.openLab(); await flush(); children[0].end(); await opened;
  expect(lab.labTask(good)).toEqual({ repository: 'bytequay/bytequay', text: 'Shorten it\n\nLab suggestion: Shorten it' });
  expect(lab.labTask(link(`repository=a%2Fb&text=${'x'.repeat(20_000)}`))?.text).toHaveLength(20_000);
  for (const url of [
    link('repository=a%2Fb&text=t', 'http://127.0.0.1:49153/lab-launch-id/'),
    link('repository=a%2Fb&text=t', 'http://127.0.0.1:49152/lab-another-launch/'),
    'http://127.0.0.1:49152/lab-launch-id/tasks?repository=a%2Fb&text=t',
    'http://127.0.0.1:49152/lab-launch-id/?repository=a%2Fb&text=t',
    link('repository=bytequay&text=t'), link('repository=a%2Fb%2Fc&text=t'), link('repository=a%20b%2Fc&text=t'), link('text=t'),
    link('repository=a%2Fb&text=%20%0A'), link('repository=a%2Fb'), link(`repository=a%2Fb&text=${'x'.repeat(20_001)}`),
  ]) expect(lab.labTask(url), url.slice(0, 80)).toBeNull();
  children[1].end(1);
  expect(lab.labTask(good)).toBeNull();
});

it('uses the prepared runtime with current source files during development', async () => {
  mocks.app.isPackaged = false;
  const lab = await import('./labProcess');
  const opened = lab.openLab(); await flush();
  expect(mocks.spawn.mock.calls[0][0]).toBe('/checkout/frontend/vendor/lab/python/bin/python3');
  expect(mocks.spawn.mock.calls[0][1]).toContain('/checkout/scripts/review-calibration/desktop.py');
  children[0].end(); await opened; lab.stopLab();
});

it('recognizes only managed local Lab pages and preserves their page, query and fragment', async () => {
  const { labPage } = await import('./labProcess');
  const route = '/lab-01234567-89ab-cdef-0123-456789abcdef/';
  expect(labPage(`http://127.0.0.1:45000${route}`)).toBe('');
  expect(labPage(`http://127.0.0.1:45000${route}Resources?view=week#memory`)).toBe('Resources?view=week#memory');
  for (const address of [`https://example.com${route}`, `http://localhost:45000${route}`, `http://127.0.0.1${route}`,
    `http://user@127.0.0.1:45000${route}`, 'http://127.0.0.1:45000/lab-other/', 'not a URL']) {
    expect(labPage(address)).toBeNull();
  }
});

it('starts the dashboard for an expired saved page and reuses it for later navigation', async () => {
  const lab = await import('./labProcess');
  const saved = 'http://127.0.0.1:45000/lab-01234567-89ab-cdef-0123-456789abcdef/Resources?view=week#memory';
  const restored = lab.resolveLabPage(saved);
  await flush();
  expect(children).toHaveLength(1);
  children[0].end();
  expect(await restored).toBe('http://127.0.0.1:49152/lab-launch-id/Resources?view=week#memory');
  expect(await lab.resolveLabPage(saved)).toBe(await restored);
  expect(await lab.resolveLabPage('https://example.com/')).toBe('https://example.com/');
  expect(children).toHaveLength(2);
  lab.stopLab();
});

it('waits for dashboard readiness when another saved page is selected during startup', async () => {
  const lab = await import('./labProcess');
  const saved = 'http://127.0.0.1:45000/lab-01234567-89ab-cdef-0123-456789abcdef/';
  let ready: (response: Response) => void = () => {};
  fetchMock.mockImplementationOnce(() => new Promise<Response>(resolve => { ready = resolve; }));
  const first = lab.resolveLabPage(saved);
  await flush(); children[0].end(); await flush();
  expect(children).toHaveLength(2);
  let loaded = false;
  const second = lab.resolveLabPage(`${saved}Resources`).then(url => { loaded = true; return url; });
  await flush();
  expect(loaded).toBe(false);
  ready(new Response('ok'));
  expect(await second).toBe(`${await first}Resources`);
  lab.stopLab();
});
