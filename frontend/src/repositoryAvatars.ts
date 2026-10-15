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
import { backendBase, sessionToken } from './backendProcess';

const pending = new Map<string, Promise<string | null>>();

/** Share owner reads across windows and sizes, keeping backend access in Electron main. */
export async function repositoryAvatar(owner: unknown, refresh: unknown = false): Promise<string | null> {
  if (typeof owner !== 'string' || !/^[a-z\d](?:[a-z\d-]{0,37}[a-z\d])?$/i.test(owner)
    || typeof refresh !== 'boolean') throw new Error('Invalid repository avatar request');
  const login = owner.toLowerCase();
  const key = `${login}:${refresh}`;
  const existing = pending.get(key);
  if (existing) return existing;
  const request = readAvatar(login, refresh);
  pending.set(key, request);
  try { return await request; }
  finally { pending.delete(key); }
}

async function readAvatar(owner: string, refresh: boolean): Promise<string | null> {
  const response = await fetch(`${backendBase()}/api/avatars/repositories/${owner}?refresh=${refresh}`, {
    headers: { 'X-ByteQuay-Session': sessionToken() }, signal: AbortSignal.timeout(30_000),
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Avatar read failed (${response.status})`);
  const type = response.headers.get('content-type')?.split(';')[0];
  if (!type || !['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(type)) throw new Error('Invalid avatar image type');
  return `data:${type};base64,${Buffer.from(await response.arrayBuffer()).toString('base64')}`;
}
