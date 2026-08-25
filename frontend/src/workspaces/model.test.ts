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
import type { WorkspaceDto } from '../types';
import {
  workspaceFromDto,
  workspaceHasReadableCode,
  workspaceIdFromRoute,
  workspaceIssueRoute,
  workspaceLocationFromRoute,
  workspacePhaseLabel,
  workspacePullRequestRoute,
  workspaceRoute,
} from './model';

describe('workspaceFromDto', () => {
  it('preserves the backend identity and maps an explicit upstream link', () => {
    const workspace = workspaceFromDto(dto('FORK'));

    expect(workspace.id).toBe('workspace-id');
    expect(workspace.path).toBe('/managed/workspace-id/repository');
    expect(workspace.originRemote).toBe('origin');
    expect(workspace.upstreamRemote).toBe('upstream');
    expect(workspace.upstreamOwner).toBe('upstream');
    expect(workspace.monitoredOwner).toBe('upstream');
    expect(workspacePhaseLabel(workspace)).toBe('Preparing');
  });

  it('does not invent an upstream for a direct workspace', () => {
    expect(workspaceFromDto(dto('DIRECT')).upstreamRemote).toBeNull();
  });

  it('does not infer an upstream from GitHub fork topology', () => {
    expect(workspaceFromDto(dto('FORK', false)).upstreamRemote).toBeNull();
  });

  it('uses one status rule for every workspace list', () => {
    const workspace = workspaceFromDto(dto('DIRECT'));
    expect(workspacePhaseLabel(workspace)).toBe('Preparing');
    expect(workspacePhaseLabel({
      ...workspace,
      lastCodeSyncAt: '2026-08-21T00:00:00Z',
    })).toBe('Ready');
  });

  it('uses the last successful code sync to decide whether the checkout is readable', () => {
    const workspace = workspaceFromDto(dto('DIRECT'));

    expect(workspaceHasReadableCode(workspace)).toBe(false);
    expect(workspaceHasReadableCode({
      ...workspace,
      lastCodeSyncAt: '2026-08-21T00:00:00Z',
    })).toBe(true);
  });

  it('round-trips workspace IDs through the renderer route', () => {
    const route = workspaceRoute('workspace/id');
    expect(route).toBe('#/workspaces/workspace%2Fid');
    expect(workspaceIdFromRoute(route)).toBe('workspace/id');
    expect(workspaceIdFromRoute('#/workspaces')).toBeNull();

    const issueRoute = workspaceIssueRoute('workspace/id', 42);
    expect(issueRoute).toBe('#/workspaces/workspace%2Fid/issues/42');
    expect(workspaceLocationFromRoute(issueRoute)).toEqual({
      workspaceId: 'workspace/id',
      tab: 'issues',
      pullRequestNumber: null,
      issueNumber: 42,
    });

    const pullRequestRoute = workspacePullRequestRoute('workspace/id', 7);
    expect(pullRequestRoute).toBe('#/workspaces/workspace%2Fid/prs/7');
    expect(workspaceLocationFromRoute(pullRequestRoute)).toEqual({
      workspaceId: 'workspace/id',
      tab: 'prs',
      pullRequestNumber: 7,
      issueNumber: null,
    });
  });

  it('round-trips every workspace tab through the renderer route', () => {
    for (const tab of ['home', 'tasks', 'prs', 'issues', 'commits', 'memory', 'settings'] as const) {
      const route = workspaceRoute('workspace/id', tab);
      expect(workspaceLocationFromRoute(route)).toEqual({
        workspaceId: 'workspace/id',
        tab: tab === 'home' ? null : tab,
        pullRequestNumber: null,
        issueNumber: null,
      });
    }
  });
});

function dto(topology: WorkspaceDto['topology'], linked = topology === 'FORK'): WorkspaceDto {
  return {
    id: 'workspace-id',
    name: 'contributor/project',
    selectedRepositoryId: 100,
    selectedOwner: 'contributor',
    selectedRepo: 'project',
    upstreamRepositoryId: linked ? 200 : null,
    upstreamOwner: linked ? 'upstream' : null,
    upstreamRepo: linked ? 'project' : null,
    monitoredRepositoryId: linked ? 200 : 100,
    monitoredOwner: linked ? 'upstream' : 'contributor',
    monitoredRepo: 'project',
    topology,
    defaultBranch: 'main',
    upstreamDefaultBranch: linked ? 'main' : null,
    localRepositoryPath: '/managed/workspace-id/repository',
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    displayOrder: 0,
  };
}
