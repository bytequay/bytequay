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
import { parseWorkspaceRoute, workspaceRouteHash, type WorkspaceRoute } from './workspaceRoutes';

describe('workspaceRoutes', () => {
  const routes: WorkspaceRoute[] = [
    { kind: 'home' },
    { kind: 'reviews' },
    { kind: 'workspaces' },
    { kind: 'workspace', workspaceId: 'w 1' },
    { kind: 'trunks', workspaceId: 'w1' },
    { kind: 'trunks', workspaceId: 'w1', trunkId: 'trunk/one' },
    { kind: 'pull-request', workspaceId: 'w1' },
    { kind: 'pull-request', workspaceId: 'w1', number: 148 },
    { kind: 'pull-request', workspaceId: 'w1', number: 148, prId: 'pr/148', agentColumn: true },
    { kind: 'pull-request', workspaceId: 'w1', prId: 'local pr', agentColumn: true },
    { kind: 'issue', workspaceId: 'w1' },
    { kind: 'issue', workspaceId: 'w1', number: 482 },
    { kind: 'session', workspaceId: 'w1' },
    { kind: 'session', workspaceId: 'w1', sessionId: 'run 3' },
    { kind: 'backlog', workspaceId: 'w1' },
    { kind: 'backlog', workspaceId: 'w1', key: 'BQ-23' },
    { kind: 'branches', workspaceId: 'w1' },
    { kind: 'branches', workspaceId: 'w1', name: 'dev/clamp-fix' },
    { kind: 'commits', workspaceId: 'w1' },
    { kind: 'memory', workspaceId: 'w1' },
    { kind: 'insights', workspaceId: 'w1' },
    { kind: 'notifications', workspaceId: 'w1' },
    { kind: 'settings', workspaceId: 'w1' },
    { kind: 'settings', workspaceId: 'w1', section: 'danger zone' },
  ];

  it.each(routes)('round-trips $kind', route => {
    expect(parseWorkspaceRoute(workspaceRouteHash(route))).toEqual(route);
  });

  it('redirects malformed entity paths to the workspace hub', () => {
    expect(parseWorkspaceRoute('#/workspace/w1/prs/nope'))
      .toEqual({ kind: 'pull-request', workspaceId: 'w1' });
    expect(parseWorkspaceRoute('#/workspace/w1/sessions'))
      .toEqual({ kind: 'session', workspaceId: 'w1' });
  });

  it('keeps legacy numbered PR hashes unchanged', () => {
    expect(workspaceRouteHash({ kind: 'pull-request', workspaceId: 'w1', number: 148 }))
      .toBe('#/workspace/w1/prs/148');
  });

  it('recognizes legacy repo routes for workspace resolution', () => {
    expect(parseWorkspaceRoute('#/repository/acme/widget/issues')).toEqual({
      kind: 'legacy-repo',
      owner: 'acme',
      repo: 'widget',
      page: 'issues',
    });
    expect(parseWorkspaceRoute('#/repo/acme/widget/pulls')).toEqual({
      kind: 'legacy-repo',
      owner: 'acme',
      repo: 'widget',
      page: 'pulls',
    });
    expect(parseWorkspaceRoute('#/local-repo/acme/widget/branches')).toEqual({
      kind: 'legacy-repo',
      owner: 'acme',
      repo: 'widget',
      page: 'branches',
    });
    expect(parseWorkspaceRoute('#/repos')).toEqual({ kind: 'legacy-repo' });
  });

  it('falls unknown paths back to Home', () => {
    expect(parseWorkspaceRoute('#/something-else')).toEqual({ kind: 'home' });
  });
});
