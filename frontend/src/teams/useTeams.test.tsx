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

const directory = (accountLogin: string): TeamDirectory => ({ accountLogin, circles: [] });
const request = () => {
  let resolve!: (value: TeamDirectory) => void;
  const promise = new Promise<TeamDirectory>(done => { resolve = done; });
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

it('shares a local read and withholds another account’s directory', async () => {
  const hooks = await import('./useTeams');
  const pending = request();
  getTeams.mockReturnValue(pending.promise);
  const first = hooks.refreshTeams();
  expect(hooks.refreshTeams()).toBe(first);
  pending.resolve(directory('alice'));
  await first;
  function Probe({ login }: { login: string }) { return <span>{hooks.useTeams(login).directory?.accountLogin ?? 'empty'}</span>; }
  expect(renderToStaticMarkup(<Probe login="ALICE" />)).toContain('alice');
  expect(renderToStaticMarkup(<Probe login="bob" />)).toContain('empty');
  expect(getTeams).toHaveBeenCalledTimes(1);
});

it('clears immediately on account change and discards an older account response', async () => {
  const hooks = await import('./useTeams');
  const old = request();
  const next = request();
  getTeams.mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise);
  const first = hooks.refreshTeams();
  events.dispatchEvent(new Event(hooks.GITHUB_ACCOUNT_CHANGED));
  hooks.updateTeams(directory('alice'));
  function Probe() { return <span>{hooks.useTeams().directory?.accountLogin ?? 'empty'}</span>; }
  expect(renderToStaticMarkup(<Probe />)).toContain('empty');
  const second = hooks.refreshTeams();
  next.resolve(directory('bob'));
  await second;
  old.resolve(directory('alice'));
  await first;
  hooks.updateTeams(directory('alice'));
  expect(renderToStaticMarkup(<Probe />)).toContain('bob');
});

it('does not overwrite a membership mutation with an earlier read and clears failed reads', async () => {
  const hooks = await import('./useTeams');
  const old = request();
  getTeams.mockReturnValueOnce(old.promise);
  const first = hooks.refreshTeams();
  hooks.updateTeams({ ...directory('alice'), circles: [{ id: 'saved', name: 'Saved', description: '', members: [], repositories: [] }] });
  old.resolve(directory('alice'));
  await first;
  function Probe() { const state = hooks.useTeams(); return <span>{state.directory?.circles[0]?.name ?? state.error ?? 'empty'}</span>; }
  expect(renderToStaticMarkup(<Probe />)).toContain('Saved');
  getTeams.mockRejectedValueOnce(new Error('Account unavailable'));
  await hooks.refreshTeams();
  expect(renderToStaticMarkup(<Probe />)).toContain('Account unavailable');
});
