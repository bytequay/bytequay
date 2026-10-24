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
/* eslint-disable import/no-unresolved -- Pinned VS Code service exports. */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { VSBuffer } from '@codingame/monaco-vscode-api/vscode/vs/base/common/buffer';
import { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import { FileOperationError, FileOperationResult } from '@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files';
import type { IdeConnection } from './contracts';
import { coordinateSaves } from './save';

const mocks = vi.hoisted(() => ({ files: { readFile: vi.fn(), writeFile: vi.fn(), resolve: vi.fn() }, textFiles: { write: vi.fn(), isDirty: vi.fn() }, save: vi.fn() }));
vi.mock('@codingame/monaco-vscode-api', () => ({ IFileService: 'files', ITextFileService: 'textFiles', getService: async (service: string) => service === 'files' ? mocks.files : mocks.textFiles }));
const connection = { authority: '127.0.0.1:4321', root: '/fixture/task' } as IdeConnection;
const uri = URI.from({ scheme: 'vscode-remote', authority: connection.authority, path: '/fixture/task/main.go' });

beforeEach(async () => {
  vi.resetAllMocks();
  mocks.files.writeFile = vi.fn();
  mocks.textFiles.write = vi.fn(async (resource, text: string, options) => mocks.files.writeFile(resource, VSBuffer.fromString(text), options));
  mocks.files.readFile.mockResolvedValue({ value: VSBuffer.fromString('before'), etag: 'loaded' });
  mocks.files.resolve.mockResolvedValue({ etag: 'saved' });
  vi.stubGlobal('window', { ideWorkbench: { save: mocks.save } });
  await coordinateSaves(connection);
});

describe('coordinated IDE saves', () => {
  it('preserves encoded bytes and hashes the version checked immediately before the atomic backend save', async () => {
    const bytes = new Uint8Array([255, 254, 65, 0]);
    await mocks.files.writeFile(uri, VSBuffer.wrap(bytes), { etag: 'loaded' });
    const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode('before')))].map(byte => byte.toString(16).padStart(2, '0')).join('');
    expect(mocks.save).toHaveBeenCalledWith('main.go', Buffer.from(bytes).toString('base64'), hash);
    expect(mocks.files.resolve).toHaveBeenCalledWith(uri, { resolveMetadata: true });
  });
  it('reports the exact text snapshot only after its write succeeds', async () => {
    const saves = await coordinateSaves(connection);
    const saved = vi.fn();
    saves.onDidSave(saved);
    let finish: () => void = () => { throw new Error('Save has not started'); };
    mocks.save.mockImplementation(() => new Promise<void>(resolve => { finish = resolve; }));
    const chunks = ['saved ', 'snapshot'];
    const writing = mocks.textFiles.write(uri, { read: () => chunks.shift() ?? null }, { etag: 'loaded' });
    await vi.waitFor(() => expect(mocks.save).toHaveBeenCalled());
    expect(saved).not.toHaveBeenCalled();
    finish();
    await writing;
    expect(saved).toHaveBeenCalledWith({ resource: uri, text: 'saved snapshot' });
    mocks.save.mockRejectedValueOnce(new Error('Unavailable'));
    await expect(mocks.textFiles.write(uri, 'failed snapshot', { etag: 'loaded' })).rejects.toThrow('Unavailable');
    expect(saved).toHaveBeenCalledTimes(1);
  });
  it('rejects a stale buffer before sending any bytes', async () => {
    await expect(mocks.files.writeFile(uri, VSBuffer.fromString('draft'), { etag: 'old' })).rejects.toMatchObject({ fileOperationResult: FileOperationResult.FILE_MODIFIED_SINCE });
    expect(mocks.save).not.toHaveBeenCalled();
  });
  it('keeps a save conflict that occurs after the initial disk read visible to the native editor', async () => {
    mocks.save.mockRejectedValue(new Error('Error invoking remote method: IDE_FILE_CHANGED: The file changed on disk'));
    await expect(mocks.files.writeFile(uri, VSBuffer.fromString('draft'), { etag: 'loaded' })).rejects.toMatchObject({ fileOperationResult: FileOperationResult.FILE_MODIFIED_SINCE });
    expect(mocks.files.resolve).not.toHaveBeenCalled();
  });
  it('creates only when the file is absent and refuses cross-worktree saves', async () => {
    mocks.files.readFile.mockRejectedValue(new FileOperationError('Missing', FileOperationResult.FILE_NOT_FOUND));
    await mocks.files.writeFile(uri, VSBuffer.fromString('new'));
    expect(mocks.save).toHaveBeenCalledWith('main.go', 'bmV3', '-');
    mocks.save.mockClear();
    await expect(mocks.files.writeFile(uri.with({ path: '/fixture/another/main.go' }), VSBuffer.fromString('other'))).rejects.toThrow('inside the task');
    expect(mocks.save).not.toHaveBeenCalled();
  });
  it('replaces the bound method cached by VS Code’s delayed-service proxy', async () => {
    const original = vi.fn();
    mocks.files.writeFile = original;
    const service = mocks.files;
    const cached: Record<string, unknown> = {};
    const proxy = new Proxy(service, {
      get(target, key: keyof typeof service) {
        if (key in cached) return cached[key];
        const value = target[key];
        return cached[key] = typeof value === 'function' ? value.bind(target) : value;
      },
      set(target, key: keyof typeof service, value) { target[key] = value; return true; },
      defineProperty(target, key, descriptor) { cached[String(key)] = descriptor.value; return Reflect.defineProperty(target, key, descriptor); },
    });
    const api = await import('@codingame/monaco-vscode-api');
    vi.spyOn(api, 'getService').mockResolvedValueOnce(proxy);
    await coordinateSaves(connection);
    await proxy.writeFile(uri, VSBuffer.fromString('draft'), { etag: 'loaded' });
    expect(original).not.toHaveBeenCalled();
    expect(mocks.save).toHaveBeenCalledWith('main.go', 'ZHJhZnQ=', expect.any(String));
  });

});
