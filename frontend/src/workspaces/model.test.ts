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
  commitsPerDay,
  metadataAgeDays,
  workspaceCodeState,
  workspaceHasReadableCode,
  workspaceIdFromRoute,
  workspaceIssueRoute,
  workspaceLocationFromRoute,
  workspaceMetadataState,
  workspacePhaseLabel,
  workspacePullRequestRoute,
  workspaceAgentTaskRoute,
  workspaceRoute,
  workspaceSyncState,
} from './model';

describe('workspace model', () => {
  it('uses one status rule for every workspace list', () => {
    const workspace = dto('DIRECT');
    expect(workspacePhaseLabel(workspace)).toBe('Preparing');
    expect(workspacePhaseLabel({
      ...workspace,
      lastCodeSyncAt: '2026-08-21T00:00:00Z',
    })).toBe('Ready');
  });

  it('walks a new workspace from queued through cloning to ready', () => {
    const queued = dto('DIRECT');
    const cloning = { ...queued, lastMetadataSyncAt: '2026-08-21T00:00:00Z' };
    const ready = { ...cloning, lastCodeSyncAt: '2026-08-21T00:01:00Z' };
    const now = Date.parse('2026-08-21T00:02:00Z');

    expect(workspaceCodeState(queued)).toBe('queued');
    expect(workspaceMetadataState(queued, now)).toBe('queued');
    expect(workspaceCodeState(cloning)).toBe('syncing');
    expect(workspaceCodeState(ready)).toBe('ready');
    expect(workspaceSyncState(ready, now)).toBe('ready');
  });

  it('calls labels and people stale once the cached copy passes a week', () => {
    const workspace = {
      ...dto('DIRECT'),
      lastCodeSyncAt: '2026-08-21T00:00:00Z',
      lastMetadataSyncAt: '2026-08-12T00:00:00Z',
    };
    const now = Date.parse('2026-08-21T00:00:00Z');

    expect(workspaceMetadataState(workspace, now)).toBe('stale');
    expect(workspaceSyncState(workspace, now)).toBe('stale');
    expect(metadataAgeDays(workspace, now)).toBe(9);
  });

  it('buckets commits into one bar per trailing day, oldest first', () => {
    const now = Date.parse('2026-08-21T12:00:00Z');

    expect(commitsPerDay([
      '2026-08-21T11:00:00Z',
      '2026-08-21T09:00:00Z',
      '2026-08-20T09:00:00Z',
      '2026-08-01T09:00:00Z',
    ], 3, now)).toEqual([0, 1, 2]);
  });

  it('uses the last successful code sync to decide whether the checkout is readable', () => {
    const workspace = dto('DIRECT');

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
      agentTaskId: null,
    });

    const pullRequestRoute = workspacePullRequestRoute('workspace/id', 7);
    expect(pullRequestRoute).toBe('#/workspaces/workspace%2Fid/prs/7');
    expect(workspaceLocationFromRoute(pullRequestRoute)).toEqual({
      workspaceId: 'workspace/id',
      tab: 'prs',
      pullRequestNumber: 7,
      issueNumber: null,
      agentTaskId: null,
    });
  });

  it('round-trips an agent task through the renderer route', () => {
    const route = workspaceAgentTaskRoute('workspace/id', 'task/1');
    expect(route).toBe('#/workspaces/workspace%2Fid/tasks/agent/task%2F1');

    // The route owns the open task, so a reload or back/forward reopens the same one.
    expect(workspaceLocationFromRoute(route)).toEqual({
      workspaceId: 'workspace/id',
      tab: 'tasks',
      pullRequestNumber: null,
      issueNumber: null,
      agentTaskId: 'task/1',
    });

    // Only the tasks tab owns an agent task.
    expect(workspaceLocationFromRoute('#/workspaces/w/prs/agent/task-1')).toBeNull();
    expect(workspaceLocationFromRoute('#/workspaces/w/tasks')?.agentTaskId).toBeNull();
  });

  it('walks create, open, back and forward without carrying a stale task', () => {
    // What the history stack holds after opening a task from the list and going back.
    const history = [
      workspaceRoute('w', 'tasks'),
      workspaceAgentTaskRoute('w', 'task-1'),
      workspaceRoute('w', 'tasks'),
    ];
    expect(history.map(hash => workspaceLocationFromRoute(hash)?.agentTaskId))
      .toEqual([null, 'task-1', null]);

    // Forward to a second task, then across tabs: leaving tasks clears the open task.
    expect(workspaceLocationFromRoute(workspaceAgentTaskRoute('w', 'task-2'))?.agentTaskId).toBe('task-2');
    expect(workspaceLocationFromRoute(workspaceRoute('w', 'prs'))?.agentTaskId).toBeNull();
  });

  it('round-trips every workspace tab through the renderer route', () => {
    for (const tab of ['home', 'tasks', 'prs', 'issues', 'commits', 'settings'] as const) {
      const route = workspaceRoute('workspace/id', tab);
      expect(workspaceLocationFromRoute(route)).toEqual({
        workspaceId: 'workspace/id',
        tab: tab === 'home' ? null : tab,
        pullRequestNumber: null,
        issueNumber: null,
        agentTaskId: null,
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
