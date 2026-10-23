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
import { afterEach, expect, it, vi } from 'vitest';
import { repositoryAvatar } from './repositoryAvatars';

vi.mock('./backendProcess', () => ({ backendBase: () => 'http://127.0.0.1:12345', sessionToken: () => 'desktop-only-token' }));
afterEach(() => { vi.unstubAllGlobals(); });

it('reads the existing local cache separately while a shared owner refresh is pending', async () => {
  const fresh = Promise.withResolvers<Response>();
  const fetch = vi.fn((_url: string) => _url.includes('refresh=true') ? fresh.promise : Promise.resolve(new Response('old', { headers: { 'content-type': 'image/png' } })));
  vi.stubGlobal('fetch', fetch);
  const first = repositoryAvatar('Owner', true);
  const second = repositoryAvatar('owner', true);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(await repositoryAvatar('owner')).toBe('data:image/png;base64,b2xk');
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:12345/api/avatars/repositories/owner?refresh=false',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'desktop-only-token' } }));
  fresh.resolve(new Response('new', { headers: { 'content-type': 'image/png' } }));
  expect(await Promise.all([first, second])).toEqual(['data:image/png;base64,bmV3', 'data:image/png;base64,bmV3']);
});

it('allows retries after missing images and failed requests', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response('', { status: 404 }))
    .mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(new Response('image', { headers: { 'content-type': 'image/jpeg' } }));
  vi.stubGlobal('fetch', fetch);
  expect(await repositoryAvatar('apache', true)).toBeNull();
  await expect(repositoryAvatar('apache', true)).rejects.toThrow('offline');
  expect(await repositoryAvatar('apache', true)).toBe('data:image/jpeg;base64,aW1hZ2U=');
});

it('rejects arbitrary URLs, invalid owners and non-image responses', async () => {
  const fetch = vi.fn(async () => new Response('<html>', { headers: { 'content-type': 'text/html' } }));
  vi.stubGlobal('fetch', fetch);
  for (const owner of ['../tokens', 'https://example.com', '', 'a'.repeat(40), 'alice?refresh=true']) {
    await expect(repositoryAvatar(owner, true)).rejects.toThrow('Invalid repository avatar');
  }
  await expect(repositoryAvatar('alice', 'true')).rejects.toThrow('Invalid repository avatar');
  expect(fetch).not.toHaveBeenCalled();
  await expect(repositoryAvatar('alice')).rejects.toThrow('Invalid avatar image type');
});
