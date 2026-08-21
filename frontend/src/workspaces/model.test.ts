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
  workspaceIdFromRoute,
  workspacePhaseLabel,
  workspaceRoute,
} from './model';

describe('workspaceFromDto', () => {
  it('preserves the backend identity and maps fork remotes', () => {
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

  it('uses one status rule for every workspace list', () => {
    const workspace = workspaceFromDto(dto('DIRECT'));
    expect(workspacePhaseLabel({ ...workspace, codeState: 'READY' })).toBe('Ready');
    expect(workspacePhaseLabel({ ...workspace, codeState: 'SYNCING' })).toBe('Syncing');
    expect(workspacePhaseLabel({ ...workspace, metadataState: 'FAILED' })).toBe('Needs attention');
  });

  it('round-trips workspace IDs through the renderer route', () => {
    const route = workspaceRoute('workspace/id');
    expect(route).toBe('#/workspaces/workspace%2Fid');
    expect(workspaceIdFromRoute(route)).toBe('workspace/id');
    expect(workspaceIdFromRoute('#/workspaces')).toBeNull();
  });
});

function dto(topology: WorkspaceDto['topology']): WorkspaceDto {
  return {
    id: 'workspace-id',
    name: 'contributor/project',
    selectedRepositoryId: 100,
    selectedOwner: 'contributor',
    selectedRepo: 'project',
    upstreamRepositoryId: topology === 'FORK' ? 200 : null,
    upstreamOwner: topology === 'FORK' ? 'upstream' : null,
    upstreamRepo: topology === 'FORK' ? 'project' : null,
    monitoredRepositoryId: topology === 'FORK' ? 200 : 100,
    monitoredOwner: topology === 'FORK' ? 'upstream' : 'contributor',
    monitoredRepo: 'project',
    topology,
    defaultBranch: 'main',
    localRepositoryPath: '/managed/workspace-id/repository',
    codeState: 'PENDING',
    metadataState: 'PENDING',
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    codeError: null,
    metadataError: null,
    displayOrder: 0,
  };
}
