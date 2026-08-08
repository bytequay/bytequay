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
import { useCallback, useEffect, useRef, useState } from 'react';
import type { LogoColor, StatusDotVariant } from '../ui/primitives';
import type { RepoChip, ThreadRow } from '../ui/workspace';
import type { ThreadDto, WorkspaceCardDto } from '../types';
import type { WorkspaceOverviewDto } from '../workspace/workspaceApi';

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

function toThreadRow(t: ThreadDto): ThreadRow {
  return {
    id: t.id,
    name: t.title,
    status: threadStatusDot(t.status),
    flow: t.flow,
    attentionCount: t.unread === true ? 1 : undefined,
  };
}

export type WorkspaceNavData = {
  /** The active workspace's card (for the switcher + header), or null. */
  activeWorkspace: WorkspaceCardDto | null;
  /** The active workspace's threads as sidebar rows. */
  threads: ThreadRow[];
  /** The active workspace's threads as raw DTOs — for the main-pane
   *  thread-card surface, which needs more than the sidebar row carries
   *  (branch, timestamp, task status). */
  rawThreads: ThreadDto[];
  /** Live workspace counts and pinned/today projections for the rail. */
  overview: WorkspaceOverviewDto | null;
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
  const [loaded, setLoaded] = useState<{
    workspaceId: string | null;
    threads: ThreadDto[];
    overview: WorkspaceOverviewDto | null;
  }>({ workspaceId: null, threads: [], overview: null });
  const requestRef = useRef(0);

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listWorkspaces === undefined) return;
    const request = ++requestRef.current;
    try {
      const ws = await bridge.listWorkspaces();
      if (request !== requestRef.current) return;
      setWorkspaces(ws);
      if (activeWorkspaceId !== null && bridge.listTasks !== undefined) {
        const [threadRows, workspaceOverview] = await Promise.all([
          bridge.listTasks({ workspaceId: activeWorkspaceId }),
          bridge.workspaceApi?.<WorkspaceOverviewDto>({
            path: `/api/workspaces/${encodeURIComponent(activeWorkspaceId)}/overview`,
          }) ?? Promise.resolve(null),
        ]);
        if (request !== requestRef.current) return;
        setLoaded({ workspaceId: activeWorkspaceId, threads: threadRows, overview: workspaceOverview });
      }
      else {
        setLoaded({ workspaceId: null, threads: [], overview: null });
      }
    }
    catch { /* leave the last loaded state */ }
  }, [activeWorkspaceId]);

  // Poll so a newly created thread (or workspace) appears in the rail
  // without waiting for a workspace switch — the rail has no other
  // refresh trigger after the initial load.
  useEffect(() => {
    void load();
    const id = window.setInterval(() => { void load(); }, 5000);
    return () => {
      requestRef.current += 1;
      window.clearInterval(id);
    };
  }, [load]);

  const activeWorkspace = workspaces.find(w => w.id === activeWorkspaceId) ?? null;
  const threads = loaded.workspaceId === activeWorkspaceId ? loaded.threads : [];
  const overview = loaded.workspaceId === activeWorkspaceId ? loaded.overview : null;
  const repos: RepoChip[] = (activeWorkspace?.repos ?? []).map(r => ({
    initials: monogram(r), color: logoColorFor(r),
  }));

  return {
    activeWorkspace,
    threads: threads.map(toThreadRow),
    rawThreads: threads,
    overview,
    repos,
    refresh: () => { void load(); },
  };
}
