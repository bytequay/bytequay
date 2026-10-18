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
import { describe, expect, it } from 'vitest';
import {
  SETTINGS_ENTRIES,
  entryFor,
  matchesSearch,
  settingsLocationFromRoute,
  settingsRoute,
} from './routes';

describe('settings routes', () => {
  it('round-trips every page', () => {
    for (const entry of SETTINGS_ENTRIES) {
      const workspaceId = entry.group === 'Workspace' ? 'ws-1' : null;
      const parsed = settingsLocationFromRoute(settingsRoute(entry.section, workspaceId));
      expect(parsed).toEqual({ section: entry.section, workspaceId });
    }
  });

  it('exposes browser behavior under Personal and finds it by its setting name', () => {
    expect(settingsRoute('browser')).toBe('#/settings/personal/browser');
    expect(entryFor('browser').group).toBe('Personal');
    expect(matchesSearch(entryFor('browser'), 'full screen behavior')).toBe(true);
  });

  it('places Jira accounts under Integrations', () => {
    expect(settingsRoute('jira')).toBe('#/settings/integrations/jira');
    expect(matchesSearch(entryFor('jira'), 'reconnect')).toBe(true);
  });

  it('keeps optional Google Apps setup under Integrations and finds both apps', () => {
    expect(settingsRoute('google')).toBe('#/settings/integrations/google');
    expect(entryFor('google')).toMatchObject({ label: 'Google Apps', group: 'Integrations' });
    expect(matchesSearch(entryFor('google'), 'Gmail')).toBe(true);
    expect(matchesSearch(entryFor('google'), 'Calendar')).toBe(true);
  });

  it('keeps the optional Lab link under Integrations and finds it by its settings', () => {
    expect(settingsRoute('lab')).toBe('#/settings/integrations/lab');
    expect(entryFor('lab')).toMatchObject({ label: 'Lab', group: 'Integrations' });
    expect(matchesSearch(entryFor('lab'), 'open lab')).toBe(true);
  });

  it('carries the workspace a link names, so a link can edit one the app is not showing', () => {
    expect(settingsLocationFromRoute('#/settings/workspace/ws-9/agents'))
      .toEqual({ section: 'ws-agents', workspaceId: 'ws-9' });
    expect(settingsLocationFromRoute('#/settings/workspace/agents'))
      .toEqual({ section: 'ws-agents', workspaceId: null });
  });

  it('escapes a workspace id that would otherwise split the path', () => {
    expect(settingsRoute('ws-token', 'a/b')).toBe('#/settings/workspace/a%2Fb/token');
    expect(settingsLocationFromRoute(settingsRoute('ws-token', 'a/b')))
      .toEqual({ section: 'ws-token', workspaceId: 'a/b' });
  });

  it('leaves the section unset for a bare hash so the last-open page can be restored', () => {
    expect(settingsLocationFromRoute('#/settings')).toEqual({ section: null, workspaceId: null });
    expect(settingsLocationFromRoute('#/settings/')).toEqual({ section: null, workspaceId: null });
  });

  it('is not fooled by another route or an unknown page', () => {
    expect(settingsLocationFromRoute('#/home')).toBeNull();
    expect(settingsLocationFromRoute('#/workspaces/ws-1/settings')).toBeNull();
    expect(settingsLocationFromRoute('#/settings/agents/nonsense')).toEqual({ section: null, workspaceId: null });
  });

  it('matches search on a row title, not only on the page label', () => {
    const github = entryFor('github');
    expect(matchesSearch(entryFor('general'), 'quiet')).toBe(true);
    expect(matchesSearch(entryFor('general'), 'background refresh')).toBe(true);
    expect(matchesSearch(github, 'quiet')).toBe(false);
    expect(matchesSearch(github, 'GitHub')).toBe(true);
    expect(matchesSearch(github, 'worktree')).toBe(false);
    expect(matchesSearch(github, '  ')).toBe(true);
  });
});
