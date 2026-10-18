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
import { expect, it } from 'vitest';
import { runBrowserFixture, deadline } from './fixtures/browserFixture';

it('shows cached repository avatars before refresh and recovers missing images without stale owner updates', async () => {
  const results = await runBrowserFixture<string[]>({
    prefix: 'bytequay-repository-avatar-', entry: 'src/fixtures/repositoryAvatarBrowser.tsx', global: 'AvatarTests', timeoutMs: 45_000,
  });
  expect(results).toEqual(['cached before refresh', 'background update', 'owner changed', 'offline fallback', 'online recovery']);
}, deadline(60_000));
