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

export type WorkspacePhase = 'preparing' | 'ready';

export type WorkspaceTab =
  | 'home' | 'tasks' | 'prs' | 'issues' | 'commits' | 'settings';

export function workspacePhase(workspace: WorkspaceDto): WorkspacePhase {
  return workspaceHasReadableCode(workspace) ? 'ready' : 'preparing';
}

export function workspacePhaseLabel(workspace: WorkspaceDto): string {
  return {
    preparing: 'Preparing',
    ready: 'Ready',
  }[workspacePhase(workspace)];
}

export function workspacePhaseColor(workspace: WorkspaceDto): string {
  return {
    preparing: '#9a6700',
    ready: '#1a7f37',
  }[workspacePhase(workspace)];
}

export function workspaceIsUpdating(workspace: WorkspaceDto): boolean {
  return !workspaceHasReadableCode(workspace);
}

export function workspaceHasReadableCode(workspace: WorkspaceDto): boolean {
  return workspace.lastCodeSyncAt !== null;
}

/* ── Card status ────────────────────────────────────────────────── */

/** What a workspace is doing, as the cards and tiles report it. */
export type SyncState = 'queued' | 'syncing' | 'ready' | 'stale' | 'failed';

/** Labels and people are re-read on a slow cadence; past this the card says so. */
const METADATA_STALE_MS = 7 * 86_400_000;

/** Nothing synced yet reads as queued; metadata landing first means the clone
 *  is already running. */
export function workspaceCodeState(workspace: WorkspaceDto): SyncState {
  if (workspace.lastCodeSyncAt !== null) return 'ready';
  return workspace.lastMetadataSyncAt === null ? 'queued' : 'syncing';
}

export function workspaceMetadataState(workspace: WorkspaceDto, now = Date.now()): SyncState {
  if (workspace.lastMetadataSyncAt === null) {
    return workspace.lastCodeSyncAt === null ? 'queued' : 'syncing';
  }
  return now - Date.parse(workspace.lastMetadataSyncAt) > METADATA_STALE_MS ? 'stale' : 'ready';
}

export function workspaceSyncState(workspace: WorkspaceDto, now = Date.now()): SyncState {
  const code = workspaceCodeState(workspace);
  return code === 'ready' ? workspaceMetadataState(workspace, now) : code;
}

export function syncStateColor(state: SyncState): string {
  return {
    queued: '#8c959f',
    syncing: '#0969da',
    ready: '#1a7f37',
    stale: '#bf8700',
    failed: '#cf222e',
  }[state];
}

export function metadataAgeDays(workspace: WorkspaceDto, now = Date.now()): number {
  if (workspace.lastMetadataSyncAt === null) return 0;
  return Math.floor((now - Date.parse(workspace.lastMetadataSyncAt)) / 86_400_000);
}

/** Commits per day over the trailing {@code days}, oldest bucket first. */
export function commitsPerDay(timestamps: string[], days: number, now = Date.now()): number[] {
  const buckets = new Array<number>(days).fill(0);
  for (const timestamp of timestamps) {
    const age = Math.floor((now - Date.parse(timestamp)) / 86_400_000);
    if (age >= 0 && age < days) buckets[days - 1 - age] += 1;
  }
  return buckets;
}

export function workspaceRoute(workspaceId: string, tab: WorkspaceTab = 'home'): string {
  const base = `#/workspaces/${encodeURIComponent(workspaceId)}`;
  return tab === 'home' ? base : `${base}/${tab}`;
}

export function workspaceIssueRoute(workspaceId: string, issueNumber: number): string {
  return `${workspaceRoute(workspaceId, 'issues')}/${issueNumber}`;
}

export function workspacePullRequestRoute(workspaceId: string, pullRequestNumber: number): string {
  return `${workspaceRoute(workspaceId, 'prs')}/${pullRequestNumber}`;
}

export function workspaceLocationFromRoute(hash: string): {
  workspaceId: string;
  tab: WorkspaceTab | null;
  pullRequestNumber: number | null;
  issueNumber: number | null;
} | null {
  const match = /^#\/workspaces\/([^/]+)(?:\/(home|tasks|prs|issues|commits|settings)(?:\/(\d+))?)?$/.exec(hash);
  if (!match) return null;
  const tab = match[2] as WorkspaceTab | undefined;
  const number = match[3] ? Number(match[3]) : null;
  if (number !== null && (number < 1 || tab !== 'prs' && tab !== 'issues')) return null;
  try {
    return {
      workspaceId: decodeURIComponent(match[1]),
      tab: tab ?? null,
      pullRequestNumber: tab === 'prs' ? number : null,
      issueNumber: tab === 'issues' ? number : null,
    };
  }
  catch {
    return null;
  }
}

export function workspaceIdFromRoute(hash: string): string | null {
  return workspaceLocationFromRoute(hash)?.workspaceId ?? null;
}

/** "owner/repo" from a GitHub remote URL, or null for non-GitHub remotes. */
export function githubSlug(url: string): string | null {
  const match = /github\.com[:/]+([^/]+)\/([^/]+?)(?:\.git)?\/?$/.exec(url.trim());
  return match ? `${match[1]}/${match[2]}` : null;
}
