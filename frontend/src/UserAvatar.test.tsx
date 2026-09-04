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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import UserAvatar, { userCardPosition } from './UserAvatar';

describe('UserAvatar', () => {
  it('renders the avatar and optional login in the same accessible profile trigger', () => {
    const html = renderToStaticMarkup(
      <UserAvatar login="octocat" avatarUrl="https://avatars.test/octocat" size={24} showLogin />,
    );

    expect(html).toContain('aria-label="Open octocat&#x27;s GitHub profile"');
    expect(html).toContain('src="https://avatars.test/octocat"');
    expect(html).toContain('<strong>octocat</strong></button>');
  });

  it('keeps the hover card inside the window and moves it above low avatars', () => {
    vi.stubGlobal('window', { innerWidth: 800, innerHeight: 600 });

    expect(userCardPosition({ left: 760, top: 550, bottom: 574 }))
      .toEqual({ left: 468, bottom: 58 });

    vi.unstubAllGlobals();
  });
});
