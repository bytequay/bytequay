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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import CurrentUserAvatar from './CurrentUserAvatar';
import { invalidate } from './dataCache';

afterEach(() => {
  cleanup();
  invalidate('home:profile');
  Reflect.deleteProperty(window, 'bridge');
});

describe('CurrentUserAvatar', () => {
  it('renders the signed-in GitHub avatar and uses an icon fallback', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getUserProfile: vi.fn().mockResolvedValue({
        login: 'chenjian2664',
        name: 'Jack Chen',
        avatarUrl: 'https://avatars.githubusercontent.com/u/1',
        htmlUrl: 'https://github.com/chenjian2664',
        publicRepos: 1,
        followers: 1,
        following: 1,
        bio: null,
        location: null,
        company: null,
        email: null,
        hasSponsors: false,
      }),
    };
    render(<CurrentUserAvatar size={28} />);

    const avatar = await screen.findByRole('img', { name: 'chenjian2664' });
    expect(avatar.getAttribute('src')).toBe('https://avatars.githubusercontent.com/u/1');
    fireEvent.error(avatar);
    expect(screen.getByRole('img', { name: 'GitHub user' }).textContent).toBe('');
  });
});
