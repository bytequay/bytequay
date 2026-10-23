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
import { createRoot } from 'react-dom/client';
import RepoAvatar from '../RepoAvatar';
import { assert, settle } from './harness';

function image(color: string): string {
  const canvas = document.createElement('canvas'); canvas.width = 2; canvas.height = 2;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Missing canvas');
  context.fillStyle = color; context.fillRect(0, 0, 2, 2);
  return canvas.toDataURL('image/png');
}

export async function run(): Promise<string[]> {
  const old = image('red'); const fresh = image('green'); const other = image('blue');
  const pending = new Map<string, ReturnType<typeof Promise.withResolvers<string | null>>>();
  const results: string[] = [];
  Object.assign(window, { bridge: { getRepositoryAvatar: async (owner: string, refresh = false) => {
    if (!refresh) return owner === 'owner' ? old : owner === 'apache' ? other : null;
    const request = Promise.withResolvers<string | null>(); pending.set(owner, request); return request.promise;
  } } });
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  const visible = (source: string) => host.querySelector<HTMLImageElement>('img')?.src === source
    && host.querySelector<HTMLImageElement>('img')?.style.opacity === '1';
  async function waitFor(check: () => boolean) {
    for (let i = 0; i < 60 && !check(); i++) await settle();
    assert(check(), 'Avatar did not reach the expected state');
  }
  try {
    root.render(<RepoAvatar owner="Owner" repo="repository" size={18} />);
    await waitFor(() => visible(old) && pending.has('owner'));
    results.push('cached before refresh');
    pending.get('owner')?.resolve(fresh);
    await waitFor(() => visible(fresh));
    results.push('background update');

    window.dispatchEvent(new Event('online'));
    root.render(<RepoAvatar owner="apache" repo="iceberg" size={18} />);
    await waitFor(() => visible(other) && pending.has('apache'));
    pending.get('owner')?.resolve(old);
    pending.get('apache')?.reject(new Error('offline'));
    await settle();
    assert(visible(other), 'Previous owner or failed refresh replaced the cached image');
    results.push('owner changed');

    root.render(<RepoAvatar owner="new-owner" repo="project" size={18} />);
    await waitFor(() => pending.has('new-owner'));
    pending.get('new-owner')?.reject(new Error('offline'));
    await settle();
    assert(host.querySelector('img') === null && host.textContent === 'N', 'Missing avatar lost its initial fallback');
    results.push('offline fallback');
    window.dispatchEvent(new Event('online'));
    pending.get('new-owner')?.resolve(fresh);
    await waitFor(() => visible(fresh));
    results.push('online recovery');
    return results;
  } finally { root.unmount(); host.remove(); }
}
