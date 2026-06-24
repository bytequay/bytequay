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
import { useCallback, useEffect, useState } from 'react';
import type { LogoColor, StatusDotVariant } from '../ui/primitives';
import type { RepoChip, ThreadRow, WorkspaceRow } from '../ui/workspace';
import type { ThreadDto, WorkspaceCardDto } from '../types';

const PALETTE: LogoColor[] = ['purple', 'teal', 'orange', 'blue', 'pink', 'slate'];

/** Deterministic logo colour for a repo / workspace key, so the same
 *  name keeps the same gradient across renders. */
export function logoColorFor(key: string): LogoColor {
  let h = 0;
  for (let i = 0; i < key.length; i += 1) h = (h * 31 + key.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(h) % PALETTE.length];
}

/** 2-letter monogram for a repo / workspace name. */
export function monogram(name: string): string {
  const clean = name.replace(/[^a-zA-Z0-9]/g, '');
  return (clean.slice(0, 2) || '?').toLowerCase();
}

/** Map a thread's status to a sidebar status dot. */
export function threadStatusDot(status: string): StatusDotVariant {
  switch (status) {
    case 'RUNNING': return 'active';
    case 'AWAITING_REVIEW': case 'NEEDS_ATTENTION': return 'planning';
    case 'COMPLETED': case 'ARCHIVED': return 'done';
    default: return 'sleep';
  }
}

/** The repo a thread targets — the last path segment of its working dir,
 *  else its repo field. */
function threadRepo(t: ThreadDto): string {
  const wd = t.activeTask?.workingDir;
  if (typeof wd === 'string' && wd.length > 0) {
    const seg = wd.split('/').filter(Boolean).pop();
    if (seg !== undefined && seg.length > 0) return seg.toLowerCase();
  }
  return 'repo';
}

function toWorkspaceRow(w: WorkspaceCardDto): WorkspaceRow {
  const repoWord = w.repos.length === 1 ? 'repo' : 'repos';
  const threadWord = w.activeThreadCount === 1 ? 'open thread' : 'open threads';
  return {
    id: w.id,
    initials: monogram(w.name).toUpperCase(),
    color: logoColorFor(w.name),
    name: w.name,
    sub: `${w.repos.length} ${repoWord} · ${w.activeThreadCount} ${threadWord}`,
    count: w.activeThreadCount,
  };
}

function toThreadRow(t: ThreadDto): ThreadRow {
  const repo = threadRepo(t);
  return {
    id: t.id,
    initials: monogram(repo),
    color: logoColorFor(repo),
    name: t.title,
    status: threadStatusDot(t.status),
  };
}

export type WorkspaceNavData = {
  workspaces: WorkspaceRow[];
  /** The active workspace's card (for the switcher + header), or null. */
  activeWorkspace: WorkspaceCardDto | null;
  /** The active workspace's threads as sidebar rows. */
  threads: ThreadRow[];
  /** The active workspace's repo chips for the header. */
  repos: RepoChip[];
  refresh: () => void;
};

/**
 * Loads the workspace list and, when a workspace is active, its threads
 * (mapped to sidebar rows with per-repo logos) and repo chips — the data
 * behind the workspace navigation shell.
 */
export function useWorkspaceNav(activeWorkspaceId: string | null): WorkspaceNavData {
  const [workspaces, setWorkspaces] = useState<WorkspaceCardDto[]>([]);
  const [threads, setThreads] = useState<ThreadDto[]>([]);

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listWorkspaces === undefined) return;
    try {
      const ws = await bridge.listWorkspaces();
      setWorkspaces(ws);
      if (activeWorkspaceId !== null && bridge.listTasks !== undefined) {
        setThreads(await bridge.listTasks({ workspaceId: activeWorkspaceId }));
      }
      else {
        setThreads([]);
      }
    }
    catch { /* leave the last loaded state */ }
  }, [activeWorkspaceId]);

  useEffect(() => { void load(); }, [load]);

  const activeWorkspace = workspaces.find(w => w.id === activeWorkspaceId) ?? null;
  const repos: RepoChip[] = (activeWorkspace?.repos ?? []).map(r => ({
    initials: monogram(r), color: logoColorFor(r),
  }));

  return {
    workspaces: workspaces.map(toWorkspaceRow),
    activeWorkspace,
    threads: threads.map(toThreadRow),
    repos,
    refresh: () => { void load(); },
  };
}
