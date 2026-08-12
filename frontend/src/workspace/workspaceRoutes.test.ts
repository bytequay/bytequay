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
  parseWorkspaceRoute,
  workspaceRouteHash,
  type WorkspaceNavigation,
} from './workspaceRoutes';

describe('workspaceRoutes', () => {
  const routes: WorkspaceNavigation[] = [
    { nav: { view: 'home' }, workspaceId: null },
    { nav: { view: 'pulls' }, workspaceId: null },
    { nav: { view: 'workspaces-landing' }, workspaceId: null },
    { nav: { view: 'workspace', section: 'today' }, workspaceId: 'w 1' },
    { nav: { view: 'workspace', section: 'trunks' }, workspaceId: 'w1' },
    { nav: { view: 'thread-detail', threadId: 'trunk/one' }, workspaceId: 'w1' },
    { nav: { view: 'workspace', section: 'pull-requests' }, workspaceId: 'w1' },
    {
      nav: { view: 'workspace', section: 'pull-requests', prNumber: 148 },
      workspaceId: 'w1',
    },
    {
      nav: {
        view: 'workspace', section: 'pull-requests', prNumber: 148,
        prId: 'pr/148', agentColumn: true,
      },
      workspaceId: 'w1',
    },
    {
      nav: {
        view: 'workspace', section: 'pull-requests',
        prId: 'local pr', agentColumn: true,
      },
      workspaceId: 'w1',
    },
    { nav: { view: 'workspace', section: 'issues' }, workspaceId: 'w1' },
    { nav: { view: 'workspace', section: 'issues', issueNumber: 482 }, workspaceId: 'w1' },
    { nav: { view: 'workspace', section: 'sessions' }, workspaceId: 'w1' },
    {
      nav: { view: 'workspace', section: 'sessions', sessionId: 'run 3' },
      workspaceId: 'w1',
    },
    { nav: { view: 'workspace', section: 'backlog' }, workspaceId: 'w1' },
    {
      nav: { view: 'workspace', section: 'backlog', backlogKey: 'BQ-23' },
      workspaceId: 'w1',
    },
    { nav: { view: 'workspace', section: 'branches' }, workspaceId: 'w1' },
    {
      nav: { view: 'workspace', section: 'branches', branchName: 'dev/clamp-fix' },
      workspaceId: 'w1',
    },
    { nav: { view: 'workspace', section: 'commits' }, workspaceId: 'w1' },
    { nav: { view: 'syncs' }, workspaceId: 'w1' },
    { nav: { view: 'syncs', jobId: 'job/2 31' }, workspaceId: 'w1' },
    { nav: { view: 'workspace', section: 'memory' }, workspaceId: 'w1' },
    { nav: { view: 'workspace', section: 'insights' }, workspaceId: 'w1' },
    { nav: { view: 'workspace', section: 'notifications' }, workspaceId: 'w1' },
    {
      nav: { view: 'workspace', section: 'settings', settingsSection: 'agents' },
      workspaceId: 'w1',
    },
    {
      nav: { view: 'workspace', section: 'settings', settingsSection: 'danger zone' },
      workspaceId: 'w1',
    },
  ];

  it.each(routes)('round-trips $nav.view', route => {
    const hash = workspaceRouteHash(route.nav, route.workspaceId);
    expect(hash).not.toBeNull();
    if (hash === null) throw new Error('route has no public hash');
    expect(parseWorkspaceRoute(hash)).toEqual(route);
  });

  it('redirects malformed entity paths to their workspace section', () => {
    expect(parseWorkspaceRoute('#/workspace/w1/prs/nope')).toEqual({
      nav: { view: 'workspace', section: 'pull-requests' }, workspaceId: 'w1',
    });
    expect(parseWorkspaceRoute('#/workspace/w1/sessions')).toEqual({
      nav: { view: 'workspace', section: 'sessions', sessionId: undefined }, workspaceId: 'w1',
    });
  });

  it('keeps numbered PR hashes unchanged', () => {
    expect(workspaceRouteHash(
      { view: 'workspace', section: 'pull-requests', prNumber: 148 }, 'w1'))
      .toBe('#/workspace/w1/prs/148');
  });

  it('recognizes legacy repository routes', () => {
    expect(parseWorkspaceRoute('#/repository/acme/widget/issues')).toEqual({
      nav: { view: 'repo', owner: 'acme', repo: 'widget', initialTab: 'issues' },
      workspaceId: null,
    });
    expect(parseWorkspaceRoute('#/repo/acme/widget/pulls')).toEqual({
      nav: { view: 'repo', owner: 'acme', repo: 'widget', initialTab: 'pulls' },
      workspaceId: null,
    });
    expect(parseWorkspaceRoute('#/local-repo/acme/widget/branches')).toEqual({
      nav: { view: 'local-repo', owner: 'acme', repo: 'widget' }, workspaceId: null,
    });
    expect(parseWorkspaceRoute('#/repos')).toEqual({
      nav: { view: 'workspaces-landing' }, workspaceId: null,
    });
  });

  it('does not keep the removed CI harness route as an alias', () => {
    const removedSegment = ['ci', 'harness'].join('-');
    expect(parseWorkspaceRoute(`#/workspace/w1/${removedSegment}`)).toEqual({
      nav: { view: 'workspace', section: 'today' }, workspaceId: 'w1',
    });
  });

  it('falls unknown paths back to Home', () => {
    expect(parseWorkspaceRoute('#/something-else')).toEqual({
      nav: { view: 'home' }, workspaceId: null,
    });
  });
});
