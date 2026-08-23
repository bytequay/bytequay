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

import type { WorkspaceDto } from '../types';

/** Renderer projection of the backend-owned watched repository workspace. */
export type Workspace = {
  id: string;
  name: string;
  path: string;
  defaultBranch: string;
  upstreamDefaultBranch: string | null;
  /** Remote the workspace pushes to; the cherry-pick target. */
  originRemote: string;
  /** Read-only cherry-pick source, or null when nothing is linked. */
  upstreamRemote: string | null;
  selectedRepositoryId: number;
  selectedOwner: string;
  selectedRepo: string;
  upstreamRepositoryId: number | null;
  upstreamOwner: string | null;
  upstreamRepo: string | null;
  monitoredOwner: string;
  monitoredRepo: string;
  topology: WorkspaceDto['topology'];
  codeState: WorkspaceDto['codeState'];
  metadataState: WorkspaceDto['metadataState'];
  codeError: string | null;
  metadataError: string | null;
  lastCodeSyncAt: string | null;
  lastMetadataSyncAt: string | null;
  displayOrder: number;
};

export type WorkspacePhase = 'preparing' | 'ready' | 'syncing' | 'needs-attention';

export function workspaceFromDto(dto: WorkspaceDto): Workspace {
  return {
    id: dto.id,
    name: dto.name,
    path: dto.localRepositoryPath,
    defaultBranch: dto.defaultBranch,
    upstreamDefaultBranch: dto.upstreamDefaultBranch,
    originRemote: 'origin',
    upstreamRemote: dto.upstreamRepositoryId === null ? null : 'upstream',
    selectedRepositoryId: dto.selectedRepositoryId,
    selectedOwner: dto.selectedOwner,
    selectedRepo: dto.selectedRepo,
    upstreamRepositoryId: dto.upstreamRepositoryId,
    upstreamOwner: dto.upstreamOwner,
    upstreamRepo: dto.upstreamRepo,
    monitoredOwner: dto.monitoredOwner,
    monitoredRepo: dto.monitoredRepo,
    topology: dto.topology,
    codeState: dto.codeState,
    metadataState: dto.metadataState,
    codeError: dto.codeError,
    metadataError: dto.metadataError,
    lastCodeSyncAt: dto.lastCodeSyncAt,
    lastMetadataSyncAt: dto.lastMetadataSyncAt,
    displayOrder: dto.displayOrder,
  };
}

export function workspacePhase(workspace: Workspace): WorkspacePhase {
  if (workspace.codeState === 'FAILED' || workspace.metadataState === 'FAILED') {
    return 'needs-attention';
  }
  if (workspace.codeState === 'SYNCING' || workspace.metadataState === 'SYNCING') {
    return 'syncing';
  }
  return workspace.codeState === 'READY' ? 'ready' : 'preparing';
}

export function workspacePhaseLabel(workspace: Workspace): string {
  return {
    preparing: 'Preparing',
    ready: 'Ready',
    syncing: 'Syncing',
    'needs-attention': 'Needs attention',
  }[workspacePhase(workspace)];
}

export function workspacePhaseColor(workspace: Workspace): string {
  return {
    preparing: '#9a6700',
    ready: '#1a7f37',
    syncing: '#0969da',
    'needs-attention': '#cf222e',
  }[workspacePhase(workspace)];
}

export function workspaceStateLabel(state: Workspace['codeState'] | Workspace['metadataState']): string {
  return {
    PENDING: 'Preparing',
    CLONING: 'Cloning',
    READY: 'Ready',
    SYNCING: 'Syncing',
    FAILED: 'Needs attention',
  }[state];
}

export function workspaceIsUpdating(workspace: Workspace): boolean {
  return ['PENDING', 'CLONING', 'SYNCING'].includes(workspace.codeState)
    || workspace.metadataState === 'SYNCING';
}

export function workspaceRoute(workspaceId: string): string {
  return `#/workspaces/${encodeURIComponent(workspaceId)}`;
}

export function workspaceIdFromRoute(hash: string): string | null {
  const match = /^#\/workspaces\/([^/]+)$/.exec(hash);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  }
  catch {
    return null;
  }
}

/** "owner/repo" from a GitHub remote URL, or null for non-GitHub remotes. */
export function githubSlug(url: string): string | null {
  const match = /github\.com[:/]+([^/]+)\/([^/]+?)(?:\.git)?\/?$/.exec(url.trim());
  return match ? `${match[1]}/${match[2]}` : null;
}
