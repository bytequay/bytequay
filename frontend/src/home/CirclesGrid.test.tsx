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
import { afterEach, expect, it, vi } from 'vitest';
import { openCircle } from '../teams/TeamsPage';
import type { TeamDirectory } from '../teams/types';
import CirclesGrid from './CirclesGrid';

afterEach(() => { vi.unstubAllGlobals(); });

const GITHUB = { provider: 'GITHUB', webBaseUrl: 'https://github.com', apiBaseUrl: 'https://api.github.com' } as const;
const GITLAB = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.example.com', apiBaseUrl: 'https://gitlab.example.com/api/v4' } as const;

it('draws one tile per circle of every account and offers creation even when there are none', () => {
  const github: TeamDirectory = { accountId: 1, accountLogin: 'viewer', server: GITHUB, circles: [{
    id: 'c1', name: 'Connectors', description: '', avatar: { color: 'blue', shape: 'square' }, repositories: [],
    members: [1, 2, 3, 4, 5].map(id => ({ remoteId: id, login: `u${id}`, name: '', avatarUrl: '' })),
  }] };
  const gitlab: TeamDirectory = { accountId: 2, accountLogin: 'viewer', server: GITLAB, circles: [{
    id: 'c2', name: 'Runners', description: '', avatar: { color: 'green', shape: 'circle' }, repositories: [],
    members: [{ remoteId: 9, login: 'jane.doe', name: '', avatarUrl: '' }],
  }] };
  const html = renderToStaticMarkup(<CirclesGrid directories={[github, gitlab]} attention={[]} />);
  expect(html).toContain('data-circle-id="c1"');
  expect(html).toContain('Connectors');
  expect(html).toContain('data-circle-id="c2"');
  expect(html).toContain('cir-avatars');
  expect(html.match(/<img class="avatar/g)).toHaveLength(3);
  // A GitLab username can name someone else on GitHub, so its tile never borrows a GitHub picture.
  expect(html.match(/avatar--fallback/g)).toHaveLength(1);
  expect(html).toContain('2 others');
  expect(html).toContain('<b>0</b> need you');
  expect(html).toContain('+ Add circle');
  const empty = renderToStaticMarkup(<CirclesGrid directories={[{ ...github, circles: [] }]} attention={[]} />);
  expect(empty).toContain('+ Add circle');
  expect(empty).toContain('No circles yet.');
  expect(renderToStaticMarkup(<CirclesGrid directories={null} attention={[]} />)).toBe('');
  expect(renderToStaticMarkup(<CirclesGrid directories={[]} attention={[]} />)).toBe('');
});

it('opens the Circles page on the circle’s account and keeps its last tab', () => {
  const store = new Map([['bytequay.circles.view.v2', JSON.stringify({ 2: { circleId: null, tab: 'activity' } })]]);
  const location = { hash: '#/home' };
  vi.stubGlobal('window', { location, localStorage: { getItem: (key: string) => store.get(key) ?? null, setItem: (key: string, value: string) => store.set(key, value) } });
  openCircle(2, 'c1');
  expect(JSON.parse(store.get('bytequay.circles.view.v2') ?? '{}')).toEqual({ 2: { circleId: 'c1', tab: 'activity' } });
  expect(store.get('bytequay.circles.account.v1')).toBe('2');
  expect(location.hash).toBe('#/teams');
});
