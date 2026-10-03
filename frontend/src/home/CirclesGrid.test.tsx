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

it('draws one tile per circle and offers creation even when there are none', () => {
  const directory: TeamDirectory = { accountLogin: 'viewer', circles: [{
    id: 'c1', name: 'Connectors', description: '', avatar: { color: 'blue', shape: 'square' }, repositories: [],
    members: [1, 2, 3, 4, 5].map(id => ({ githubId: id, login: `u${id}`, name: '', avatarUrl: '' })),
  }] };
  const html = renderToStaticMarkup(<CirclesGrid directory={directory} attention={[]} viewerLogin="viewer" />);
  expect(html).toContain('data-circle-id="c1"');
  expect(html).toContain('Connectors');
  expect(html).toContain('cir-avatars');
  expect(html.match(/class="avatar/g)).toHaveLength(3);
  expect(html).toContain('2 others');
  expect(html).toContain('<b>0</b> need you');
  expect(html).toContain('+ Add circle');
  const empty = renderToStaticMarkup(<CirclesGrid directory={{ accountLogin: 'viewer', circles: [] }} attention={[]} viewerLogin="viewer" />);
  expect(empty).toContain('+ Add circle');
  expect(empty).toContain('No circles yet.');
  expect(renderToStaticMarkup(<CirclesGrid directory={null} attention={[]} viewerLogin="viewer" />)).toBe('');
});

it('opens the Circles page on the circle and keeps the last tab', () => {
  const store = new Map([['bytequay.circles.view.v1', JSON.stringify({ viewer: { circleId: null, tab: 'activity' } })]]);
  const location = { hash: '#/home' };
  vi.stubGlobal('window', { location, localStorage: { getItem: (key: string) => store.get(key) ?? null, setItem: (key: string, value: string) => store.set(key, value) } });
  openCircle('Viewer', 'c1');
  expect(JSON.parse(store.get('bytequay.circles.view.v1') ?? '{}')).toEqual({ viewer: { circleId: 'c1', tab: 'activity' } });
  expect(location.hash).toBe('#/teams');
});
