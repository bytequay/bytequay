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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { TeamDirectory } from './types';

const SERVER = { provider: 'GITHUB', webBaseUrl: 'https://github.com', apiBaseUrl: 'https://api.github.com' } as const;
const directory = (accountId: number, accountLogin: string, circles: TeamDirectory['circles'] = []): TeamDirectory => ({ accountId, accountLogin, server: SERVER, circles });
const saved = { id: 'saved', name: 'Saved', description: '', avatar: { color: 'gray', shape: 'square' }, members: [], repositories: [] } satisfies TeamDirectory['circles'][number];
const request = () => {
  let resolve!: (value: TeamDirectory[]) => void;
  const promise = new Promise<TeamDirectory[]>(done => { resolve = done; });
  return { promise, resolve };
};
let events: EventTarget;
let getTeams: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.resetModules();
  events = new EventTarget();
  getTeams = vi.fn();
  vi.stubGlobal('window', { bridge: { getTeams }, addEventListener: events.addEventListener.bind(events), dispatchEvent: events.dispatchEvent.bind(events) });
});
afterEach(() => { vi.unstubAllGlobals(); });

it('shares one local read of every account’s directory', async () => {
  const hooks = await import('./useTeams');
  const pending = request();
  getTeams.mockReturnValue(pending.promise);
  const first = hooks.refreshTeams();
  expect(hooks.refreshTeams()).toBe(first);
  pending.resolve([directory(1, 'alice'), directory(2, 'alice')]);
  await first;
  function Probe() { return <span>{hooks.useTeams().directories?.map(item => item.accountId).join() ?? 'empty'}</span>; }
  expect(renderToStaticMarkup(<Probe />)).toContain('1,2');
  expect(getTeams).toHaveBeenCalledTimes(1);
});

it('replaces only the changed account, then reads again', async () => {
  const hooks = await import('./useTeams');
  getTeams.mockResolvedValueOnce([directory(1, 'alice'), directory(2, 'bob')]);
  await hooks.refreshTeams();
  const later = request();
  getTeams.mockReturnValueOnce(later.promise);
  hooks.updateTeams(directory(2, 'bob', [saved]));
  function Probe() { return <span>{hooks.useTeams().directories?.map(item => `${item.accountId}:${item.circles.length}`).join() ?? 'empty'}</span>; }
  expect(renderToStaticMarkup(<Probe />)).toContain('1:0,2:1');
  expect(getTeams).toHaveBeenCalledTimes(2);
  later.resolve([directory(2, 'bob', [saved])]);
  await hooks.refreshTeams();
  expect(renderToStaticMarkup(<Probe />)).toContain('2:1');
});

it('does not overwrite a mutation with an earlier read, reloads on a sign-in change and clears failed reads', async () => {
  const hooks = await import('./useTeams');
  getTeams.mockResolvedValueOnce([directory(1, 'alice')]);
  await hooks.refreshTeams();
  const old = request();
  getTeams.mockReturnValueOnce(old.promise).mockResolvedValueOnce([directory(1, 'alice', [saved])]);
  const first = hooks.refreshTeams();
  hooks.updateTeams(directory(1, 'alice', [saved]));
  old.resolve([directory(1, 'alice')]);
  await first;
  await new Promise(resolve => setTimeout(resolve));
  function Probe() { const state = hooks.useTeams(); return <span>{state.directories?.map(item => `${item.accountLogin}:${item.circles[0]?.name ?? ''}`).join() ?? state.error ?? 'empty'}</span>; }
  expect(renderToStaticMarkup(<Probe />)).toContain('alice:Saved');
  getTeams.mockResolvedValueOnce([directory(3, 'carol')]);
  events.dispatchEvent(new Event(hooks.GITHUB_ACCOUNT_CHANGED));
  await hooks.refreshTeams();
  expect(renderToStaticMarkup(<Probe />)).toContain('carol:');
  getTeams.mockRejectedValueOnce(new Error('Account unavailable'));
  events.dispatchEvent(new Event(hooks.GITHUB_ACCOUNT_CHANGED));
  await hooks.refreshTeams();
  expect(renderToStaticMarkup(<Probe />)).toContain('Account unavailable');
});
