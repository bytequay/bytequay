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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ThreadDto, ThreadGroupDto, ThreadGroupMembershipDto, ThreadStatusDto, ThreadTurnDto, WatchedRepoDto } from '../types';
import AutoQueueList from './AutoQueueList';
import FilterChipsRow from './FilterChipsRow';
import ThreadsViewToggle, { useThreadsView } from './ThreadsViewToggle';
import GroupMenu from './GroupMenu';
import GroupSettingsDialog from './GroupSettingsDialog';
import GroupThreadGrid from './GroupThreadGrid';
import AddThreadToGroupDialog from './AddThreadToGroupDialog';
import ThreadGroupPage from './ThreadGroupPage';
import {
  type ActiveTurnSummary,
  type SchedulerDisplayStatus,
  buildActiveTurnSummaries,
  displayStatusForTask,
  threadActivityRank,
} from './threadTurnSummary';
import ThreadsLeftRail, {
  repoKey,
  type GroupFilter,
  type ProviderFilter,
  type RepoFilter,
  type StatusFilter,
} from './ThreadsLeftRail';
import { threadCompactNumber, threadDisplayBranch } from './threadDisplay';

/** Card-grid sort options. {@code newest} is the default — the user
 *  scans by recency. {@code highestCost} is handy when the user is
 *  hunting down an expensive run to investigate or kill. */
type SortMode = 'newest' | 'oldest' | 'highestCost';

/** Per-tile conversation visual mode. {@code chat} is the default
 *  (WeChat-style bubbles); {@code terminal} flips the group page to
 *  a dark Warp / tmux pane. Persisted per-device. */
type TileMode = 'chat' | 'terminal';
const TILE_MODE_KEY = 'bytequay.threads.tileMode';
function loadTileMode(): TileMode {
  try {
    const raw = window.localStorage.getItem(TILE_MODE_KEY);
    return raw === 'terminal' ? 'terminal' : 'chat';
  }
  catch { return 'chat'; }
}

type Props = {
  /** Active workspace's id. Scopes the thread-list fetch so a workspace
   *  switch shows only its threads. */
  workspaceId: string;
  /** Status filter the left rail is highlighting; drives which threads
   *  appear in the main pane. */
  filter: StatusFilter;
  onFilterChange: (filter: StatusFilter) => void;
  /** Provider filter — narrows the list to a single agent provider
   *  (e.g. {@code "claude-code"}). {@code null} means no filter. */
  provider: ProviderFilter;
  onProviderChange: (provider: ProviderFilter) => void;
  /** Group filter — narrows the list to a single user-defined group.
   *  {@code null} means show every thread across every group. */
  groupId: GroupFilter;
  onGroupChange: (group: GroupFilter) => void;
  /** Repo filter — narrows the list to threads whose working directory's
   *  last segment matches this canonical key. */
  repo: RepoFilter;
  onRepoChange: (repo: RepoFilter) => void;
  /** Routes the user to the thread detail / live conversation page. */
  onSelectTask: (threadId: string) => void;
  /** Routes to the repo's PR view (optionally a specific PR number).
   *  Used to jump from a thread tile's #PR chip into the PR domain. */
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
  /** Routes to the repo's Issues view. We don't have a deep-link
   *  per issue today; the user lands on the list and picks the row. */
  onOpenIssues: (owner: string, repo: string) => void;
  /** Routes to Settings → Integrations from the rail's footer row. */
  onOpenSettings: () => void;
  /** Navigate to the new thread-create page. {@code initialGroupId} is
   *  pre-filled in the Group dropdown when present — used when the
   *  trigger came from a group page so the new thread lands in that
   *  group by default. */
  onNewTask: (initialGroupId?: string) => void;
  /** Group-page immersive mode — lifted to App so the global topbar
   *  can also disappear underneath. The toggle is owned here at the
   *  app shell; ThreadsPage just reads & writes via the setter. */
  immersive: boolean;
  onChangeImmersive: (next: boolean) => void;
};

/**
 * AI coding threads — left rail (status / provider / recent), main
 * pane with filtered, status-grouped cards. Layout mirrors
 * {@code docs/mockups/design/threads/threads-list.png}.
 */
export default function ThreadsPage({
  workspaceId,
  filter, onFilterChange, provider, onProviderChange,
  groupId, onGroupChange,
  repo, onRepoChange,
  onSelectTask, onOpenPr, onOpenIssues, onOpenSettings, onNewTask,
  immersive, onChangeImmersive,
}: Props) {
  const [threads, setTasks] = useState<ThreadDto[] | null>(null);
  const [activeTurns, setActiveTurns] = useState<ThreadTurnDto[]>([]);
  const [groups, setGroups] = useState<ThreadGroupDto[]>([]);
  const [memberships, setMemberships] = useState<ThreadGroupMembershipDto[]>([]);
  const [watchedRepos, setWatchedRepos] = useState<WatchedRepoDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Card-grid search + sort — both client-side over the already-
  // filtered list. Search clears when the status filter changes so
  // the user doesn't get stranded with "no matches" after navigating
  // to a different bucket.
  const [search, setSearch] = useState('');
  const [sortMode, setSortMode] = useState<SortMode>('newest');
  // View-mode toggle (List / Group / Immersive). Only List has its
  // body wired today; Group and Immersive surface as placeholders so
  // the scaffold matches the design while the per-phase work lands.
  const [view, setView] = useThreadsView();
  useEffect(() => { setSearch(''); }, [filter, provider, repo, groupId]);
  // Threads with at least one UNREAD notification — populates the
  // auto* rail filter and the per-row badge. Polls in tandem with
  // the global topbar's unread badge so the two stay roughly in
  // sync without hammering the local backend.
  const [autoThreadIds, setAutoThreadIds] = useState<Set<string>>(() => new Set<string>());
  useEffect(() => {
    let cancelled = false;
    const refresh = async () => {
      try {
        const unread = await window.bridge.listUnreadNotifications();
        if (cancelled) return;
        const ids = new Set<string>();
        for (const n of unread) {
          if (n.threadId) ids.add(n.threadId);
        }
        setAutoThreadIds(ids);
      }
      catch { /* non-fatal — leave the previous set */ }
    };
    void refresh();
    const id = window.setInterval(() => { void refresh(); }, 20_000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, []);
  const [activeGroup, setActiveGroup] = useState<ThreadGroupDto | null>(null);
  const [showGroupSettings, setShowGroupSettings] = useState(false);
  const [showAddTask, setShowAddTask] = useState(false);
  const [tileMode, setTileModeState] = useState<TileMode>(loadTileMode);
  const setTileMode = useCallback((next: TileMode) => {
    setTileModeState(next);
    try { window.localStorage.setItem(TILE_MODE_KEY, next); }
    catch { /* private browsing — fine to skip */ }
  }, []);

  // When the user switches to Group view but hasn't picked a board
  // yet, auto-select the first one so the body renders the actual
  // 2x2 grid instead of the "Lands in a follow-up" placeholder. If
  // no groups exist, the placeholder still shows so the user knows
  // to create one — handled separately below.
  useEffect(() => {
    if (view !== 'group') return;
    if (groupId !== null) return;
    if (groups.length === 0) return;
    onGroupChange(groups[0].id);
  }, [view, groupId, groups, onGroupChange]);

  // Flipping the segmented control back to List should drop any
  // group filter that auto-pick set, so the list isn't stuck on a
  // single group's threads. The user clicked List explicitly, so
  // the page-wide list is what they want to see.
  useEffect(() => {
    if (view === 'list' && groupId !== null) {
      onGroupChange(null);
    }
  }, [view, groupId, onGroupChange]);

  // ⌘T toggles Chat ↔ Terminal whenever a group page is showing.
  // We DO fire even when an input is focused — chord shortcuts
  // (⌘+letter) are unambiguous, and skipping would make the toggle
  // unreachable once a tile's reply textarea has focus (which it
  // does the moment the user clicks or selects a tile).
  useEffect(() => {
    if (groupId === null) return;
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.shiftKey || e.altKey) return;
      if (e.key !== 't' && e.key !== 'T') return;
      e.preventDefault();
      setTileMode(tileMode === 'chat' ? 'terminal' : 'chat');
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [groupId, tileMode, setTileMode]);

  // ⌘\ toggles immersive whenever the user is on a group page —
  // matches the keybinding in threads-design.md (Group-page chrome
  // section). Same reasoning as ⌘T: fire even when an input has
  // focus, since the modifier chord is unambiguous and tile
  // selection auto-focuses the reply textarea.
  useEffect(() => {
    if (groupId === null) return;
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.shiftKey || e.altKey) return;
      if (e.key !== '\\') return;
      e.preventDefault();
      onChangeImmersive(!immersive);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [groupId, immersive, onChangeImmersive]);

  const refresh = useCallback(async () => {
    try {
      const [list, ats, gs, ms, wrs] = await Promise.all([
        window.bridge.listTasks({ workspaceId }),
        window.bridge.listActiveTaskTurns().catch(() => [] as ThreadTurnDto[]),
        window.bridge.listTaskGroups().catch(() => [] as ThreadGroupDto[]),
        window.bridge.listTaskGroupMemberships().catch(() => [] as ThreadGroupMembershipDto[]),
        window.bridge.getWatchedRepos().catch(() => [] as WatchedRepoDto[]),
      ]);
      setTasks(list);
      setActiveTurns(ats);
      setGroups(gs);
      setMemberships(ms);
      setWatchedRepos(wrs);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [workspaceId]);

  /** Map a thread's working dir into an owner/repo by scanning the
   *  user's watched repos for a path segment that matches the repo
   *  name (case-insensitive). Worktrees live under `<repo>/.worktrees/
   *  <branch>` so the basename-only `repoKey()` helper isn't enough;
   *  here we walk every path segment looking for a match. */
  const resolveTaskRepo = useCallback((thread: ThreadDto): { owner: string; repo: string } | null => {
    const segments = ''.split('/').filter(Boolean).map((s: string) => s.toLowerCase());
    if (segments.length === 0) return null;
    for (const wr of watchedRepos) {
      if (segments.includes(wr.repo.toLowerCase())) {
        return { owner: wr.owner, repo: wr.repo };
      }
    }
    return null;
  }, [watchedRepos]);

  const onTileOpenPr = useCallback((thread: ThreadDto, prNumber: number) => {
    const ctx = resolveTaskRepo(thread);
    if (ctx === null) {
      setError(`Couldn't resolve owner/repo for thread in (no working dir). Add the repo under Settings → Watched repos.`);
      return;
    }
    onOpenPr(ctx.owner, ctx.repo, prNumber);
  }, [resolveTaskRepo, onOpenPr]);

  const onTileOpenIssue = useCallback((thread: ThreadDto, _issueNumber: number) => {
    const ctx = resolveTaskRepo(thread);
    if (ctx === null) {
      setError(`Couldn't resolve owner/repo for thread in (no working dir). Add the repo under Settings → Watched repos.`);
      return;
    }
    void _issueNumber; // no deep-link route per issue yet — land on the list
    onOpenIssues(ctx.owner, ctx.repo);
  }, [resolveTaskRepo, onOpenIssues]);

  const toggleTaskInGroup = useCallback(
    async (threadId: string, nextGroupId: string, present: boolean) => {
      try {
        if (present) {
          await window.bridge.addTaskToGroup(nextGroupId, threadId);
        }
        else {
          await window.bridge.removeTaskFromGroup(nextGroupId, threadId);
        }
        await refresh();
      }
      catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }, [refresh]);

  // Index memberships by threadId so child components can ask "which
  // groups is thread X in?" in O(1) instead of scanning the flat list.
  const groupIdsByTaskId = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const m of memberships) {
      const list = map.get(m.threadId);
      if (list === undefined) {
        map.set(m.threadId, [m.groupId]);
      }
      else {
        list.push(m.groupId);
      }
    }
    return map;
  }, [memberships]);

  const activeTurnsByThreadId = useMemo(
    () => buildActiveTurnSummaries(activeTurns),
    [activeTurns]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // ⌘N / Ctrl+N navigates to the new-thread create page. Skip while a
  // text field has focus so a literal "n" keystroke inside the
  // search box / a thread title still types a character. The left-rail
  // button shows a "⌘N" hint, so this binding is the contract.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.shiftKey || e.altKey) return;
      if (e.key !== 'n' && e.key !== 'N') return;
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return;
      e.preventDefault();
      onNewTask(groupId ?? undefined);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onNewTask, groupId]);

  // Pull the selected group's metadata so the header can render its
  // glyph + name. The rail keeps its own copy for the listing.
  useEffect(() => {
    if (!groupId) {
      setActiveGroup(null);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const all = await window.bridge.listTaskGroups();
        if (cancelled) return;
        setActiveGroup(all.find(g => g.id === groupId) ?? null);
      }
      catch {
        if (!cancelled) setActiveGroup(null);
      }
    })();
    return () => { cancelled = true; };
  }, [groupId]);

  const filtered = useMemo(() => {
    if (!threads) return [];
    return threads.filter(t => {
      const displayStatus = displayStatusForTask(t, activeTurnsByThreadId.get(t.id));
      if (filter === 'AUTO') {
        // auto* filter: only threads carrying unread notifications.
        if (!autoThreadIds.has(t.id)) return false;
      }
      else if (filter === 'MINE') {
        // "Mine" = threads I started myself, not the auto-fix queue.
        // Approximated by auto* membership today; review threads are
        // excluded since they have their own chip.
        if (autoThreadIds.has(t.id)) return false;
        if (t.flow === 'review') return false;
      }
      else if (filter === 'AWAITING_ME') {
        // Collapsed "needs the human" filter: parked active task or a
        // permission-prompt pause on the thread itself.
        if (displayStatus !== 'AWAITING') return false;
      }
      else if (filter === 'REVIEW') {
        // Review-flow threads only — read-only PR review panels.
        if (t.flow !== 'review') return false;
      }
      else if (filter !== 'ALL') {
        if (filter === 'PENDING') {
          if (displayStatus !== 'PENDING' && displayStatus !== 'QUEUED') return false;
        }
        else if (displayStatus !== filter) {
          return false;
        }
      }
      if (provider && (t.provider || '').toLowerCase() !== provider) return false;
      if (groupId && !(groupIdsByTaskId.get(t.id) ?? []).includes(groupId)) return false;
      if (repo && repoKey('') !== repo) return false;
      return true;
    });
  }, [threads, filter, provider, groupId, repo, groupIdsByTaskId, activeTurnsByThreadId, autoThreadIds]);

  // Card-grid search narrows the already-status-filtered list by
  // title substring. Empty query passes everything through.
  const searched = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (q === '') return filtered;
    return filtered.filter(t => t.title.toLowerCase().includes(q));
  }, [filtered, search]);

  const sorted = useMemo(() => {
    const ranked = [...searched];
    if (sortMode === 'oldest') {
      ranked.sort((a, b) => a.updatedAt.localeCompare(b.updatedAt));
    }
    else if (sortMode === 'highestCost') {
      ranked.sort((a, b) => b.costUsdMilli - a.costUsdMilli);
    }
    else {
      // newest — most recently updated first.
      ranked.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    }
    return ranked;
  }, [searched, sortMode]);

  const totalCount = threads?.length ?? 0;
  const filteredCount = filtered.length;
  const visibleCount = sorted.length;

  /** Tile order: active first (RUNNING / AWAITING), then queued /
   *  pending, idle, terminal last — so the operator scans live work
   *  and scheduler backlog first. */
  const tilesOrdered = useMemo(() => {
    return [...filtered].sort((a, b) => {
      const r = threadActivityRank(a, activeTurnsByThreadId.get(a.id))
        - threadActivityRank(b, activeTurnsByThreadId.get(b.id));
      return r !== 0 ? r : b.updatedAt.localeCompare(a.updatedAt);
    });
  }, [filtered, activeTurnsByThreadId]);

  const onStop = useCallback(async (id: string) => {
    setBusyId(id);
    try {
      await window.bridge.stopTask(id);
      await refresh();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusyId(null);
    }
  }, [refresh]);

  // Per-tile interactions for the group view — pure pass-throughs to
  // the bridge so each tile can act like a mini detail page without
  // owning its own polling logic. The grid already refreshes message
  // previews on a 4s cadence, so a successful send / decide will
  // surface in the tile within that window.
  const onTileSend = useCallback(async (threadId: string, input: string) => {
    try {
      await window.bridge.sendTaskMessage(threadId, input);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);
  const onTileInterrupt = useCallback(async (threadId: string) => {
    try {
      await window.bridge.interruptTask(threadId);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);
  const onTileDecide = useCallback(
    async (
      threadId: string,
      callId: string,
      decision: 'ALLOW' | 'DENY',
      preApprove?: { toolName: string; count: number },
    ) => {
      try {
        await window.bridge.decideTaskPermission(threadId, callId, decision, preApprove);
      }
      catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }, []);

  // Inside a group → render the redesigned group page (compact
  // sidebar + tmux-style tile grid). Falls through to the default
  // list view when no group is selected or while the initial fetch
  // is still in flight. Gating on view==='group' too so that the
  // List segmented-control always shows the list even if a groupId
  // is still set in the URL state (the user can flip back to the
  // list view without first clearing the group filter).
  if (view === 'group' && activeGroup && threads !== null) {
    return (
      <>
        <ThreadGroupPage
          group={activeGroup}
          threads={tilesOrdered}
          activeTurnsByThreadId={activeTurnsByThreadId}
          busyId={busyId}
          onSelectTask={onSelectTask}
          onStop={onStop}
          onSend={onTileSend}
          onInterrupt={onTileInterrupt}
          onDecide={onTileDecide}
          onAddTask={() => setShowAddTask(true)}
          onOpenGroupSettings={() => setShowGroupSettings(true)}
          onRefresh={refresh}
          immersive={immersive}
          onChangeImmersive={onChangeImmersive}
          tileMode={tileMode}
          onChangeTileMode={setTileMode}
          onOpenPr={onTileOpenPr}
          onOpenIssue={onTileOpenIssue}
          onBackToAll={() => {
            // Switching back to the all-threads home means "leave
            // group view altogether" — drop the group filter AND
            // flip the segmented control back to List, otherwise
            // the auto-pick effect would immediately re-select a
            // group and the user would never escape the board.
            onGroupChange(null);
            setView('list');
          }}
        />

        {error && (
          <div style={errorBannerStyle}>
            <strong>Couldn't load threads.</strong> {error}
          </div>
        )}

        {showGroupSettings && activeGroup && (
          <GroupSettingsDialog
            group={activeGroup}
            pinnedTaskCount={filteredCount}
            onClose={() => setShowGroupSettings(false)}
            onSaved={updated => {
              setShowGroupSettings(false);
              setActiveGroup(updated);
              void refresh();
            }}
            onDeleted={() => {
              setShowGroupSettings(false);
              onGroupChange(null);
              void refresh();
            }}
          />
        )}

        {showAddTask && activeGroup && threads && (
          <AddThreadToGroupDialog
            group={activeGroup}
            allTasks={threads}
            groupIdsByTaskId={groupIdsByTaskId}
            onClose={() => setShowAddTask(false)}
            onCreateNew={() => onNewTask(activeGroup.id)}
            // Call the bridge directly (not toggleTaskInGroup) so a
            // backend error — e.g. group at the 4-thread cap — bubbles
            // up to the dialog instead of being swallowed into the
            // page-level banner.
            onAddExisting={async threadId => {
              await window.bridge.addTaskToGroup(activeGroup.id, threadId);
              await refresh();
            }}
          />
        )}
      </>
    );
  }

  return (
    <section style={layoutStyle}>
      <ThreadsListKeyframes />
      <div style={mainStyle}>
        <header className="threads-pageheader">
          <h1 className="threads-pageheader__title">Threads</h1>
          <ThreadsViewToggle value={view} onChange={setView} />
          <div className="threads-pageheader__spacer" />
          {view === 'group' && (
            <button
              type="button"
              onClick={() => onChangeImmersive(!immersive)}
              style={immersiveQuickBtnStyle(immersive)}
              title={immersive
                ? 'Exit immersive mode (esc)'
                : 'Enter immersive mode — drop the rail and topnav (⌘\\)'}
            >
              <span aria-hidden style={{ marginRight: 4 }}>⛶</span>
              {immersive ? 'Exit immersive' : 'Immersive'}
            </button>
          )}
          <button
            type="button"
            className="threads-pageheader__new"
            onClick={() => onNewTask(groupId ?? undefined)}
          >
            + New thread
            <span className="threads-pageheader__new-kbd" aria-hidden>⌘N</span>
          </button>
        </header>

        {view === 'group' && groups.length > 0 && (
          <div style={groupTabsBarStyle}>
            {groups.map(g => {
              const count = memberships.filter(m => m.groupId === g.id).length;
              const isActive = g.id === groupId;
              return (
                <button
                  key={g.id}
                  type="button"
                  onClick={() => onGroupChange(g.id)}
                  style={groupTabChipStyle(isActive)}
                  title={`Open the ${g.name} board`}
                >
                  <span style={groupTabDotStyle(g.color)} aria-hidden />
                  <span style={groupTabLabelStyle}>{g.name}</span>
                  <span style={groupTabCountStyle}>{count}/4</span>
                </button>
              );
            })}
            <button
              type="button"
              onClick={() => onGroupChange(null)}
              style={newGroupChipStyle}
              title="Start a new monitoring board"
            >
              + New group
            </button>
            <span style={groupTabsHintStyle}>
              tmux-tight · up to 4 panes · ⌘K jump
            </span>
          </div>
        )}

        <FilterChipsRow
          threads={threads ?? []}
          autoIds={autoThreadIds}
          value={filter}
          onChange={onFilterChange}
        />

        {error && (
          <div style={errorBannerStyle}>
            <strong>Couldn't load threads.</strong> {error}
          </div>
        )}

        {threads === null && !error && (
          <div style={mutedTextStyle}>Loading…</div>
        )}

        {threads !== null && totalCount === 0 && (
          <div style={emptyStateStyle}>
            <div style={emptyTitleStyle}>No threads yet</div>
            <div style={mutedTextStyle}>
              Threads are agent runs you delegate from the app. Use{' '}
              <strong>+ New thread</strong> on the left to start one.
            </div>
          </div>
        )}

        {threads !== null && totalCount > 0 && filteredCount === 0 && (
          <div style={emptyStateStyle}>
            <div style={emptyTitleStyle}>Nothing in {filter.toLowerCase()}</div>
            <div style={mutedTextStyle}>
              Switch to <strong>All threads</strong> on the left, or pick
              another status.
            </div>
          </div>
        )}

        {filteredCount > 0 && visibleCount === 0 && search.trim() !== '' && (
          <div style={emptyStateStyle}>
            <div style={emptyTitleStyle}>No matches for "{search}"</div>
            <div style={mutedTextStyle}>
              Try a different query, or clear the search to see all{' '}
              {filteredCount} thread{filteredCount === 1 ? '' : 's'}.
            </div>
          </div>
        )}

        {view !== 'list' && (
          <ViewModePlaceholder mode={view} onPickList={() => setView('list')} />
        )}
        {view === 'list' && visibleCount > 0 && (
          <AutoQueueList
            threads={sorted}
            filter={filter}
            onOpenThread={onSelectTask}
          />
        )}
      </div>

      {showGroupSettings && activeGroup && (
        <GroupSettingsDialog
          group={activeGroup}
          pinnedTaskCount={filteredCount}
          onClose={() => setShowGroupSettings(false)}
          onSaved={next => {
            setActiveGroup(next);
            setShowGroupSettings(false);
          }}
          onDeleted={() => {
            setShowGroupSettings(false);
            // Pop back to the all-threads view — the group is gone, the
            // threads are now ungrouped.
            onGroupChange(null);
            void refresh();
          }}
        />
      )}
    </section>
  );
}

/** Injects the keyframes the running-status pulse uses. Mounted at
 *  the page root so the rule is registered exactly once per render
 *  pass (React dedupes identical <style> nodes). */
function ThreadsListKeyframes() {
  return (
    <style>{`
      @keyframes bytequayTasksListPulse {
        0%, 100% { opacity: 1; }
        50%      { opacity: 0.35; }
      }
    `}</style>
  );
}

function filterLabel(filter: StatusFilter): string {
  if (filter === 'ALL') return 'All threads';
  // RUNNING → "Running", AWAITING → "Awaiting input", etc.
  if (filter === 'AWAITING') return 'Awaiting input';
  return filter.charAt(0) + filter.slice(1).toLowerCase();
}

function subtitleFor(filter: StatusFilter): string {
  switch (filter) {
    case 'ALL':       return 'Delegated AI coding runs · pick a status on the left to focus.';
    case 'RUNNING':   return 'Threads currently executing in the background · sessions stay alive across app restarts.';
    case 'AWAITING':  return "Paused for your approval or input · the agent's waiting on a yes/no.";
    case 'PENDING':   return 'Queued to start — usually a few seconds before the agent picks them up.';
    case 'IDLE':      return 'Open but no recent activity · waiting on your next reply.';
    case 'COMPLETED': return 'Finished runs you can re-open, re-prompt, or archive.';
    case 'ARCHIVED':  return 'Auto-archived for inactivity — not finished. Open one to resume where it left off.';
    case 'ERRORED':   return "Failed runs · open one to see logs and resume from a checkpoint.";
    default:          return '';
  }
}

/** Lozenge next to the H1 — pulse + "N LIVE" for active filters,
 *  plain "N" count for terminal / idle. Matches the mockup's
 *  status-coloured pill. */
function FilterStatusPill({ filter, count }: { filter: StatusFilter; count: number }) {
  if (filter === 'ALL') {
    return <span style={{ ...statusPillBaseStyle, ...statusPillToneStyle('grey') }}>
      <span style={statusPillNumStyle}>{count}</span> total
    </span>;
  }
  const tone: PillTone = filter === 'RUNNING' ? 'green'
    : filter === 'AWAITING' ? 'amber'
    : filter === 'ERRORED' ? 'red'
    : filter === 'COMPLETED' ? 'greenSoft'
    : 'grey';
  const label = filter === 'RUNNING' ? 'LIVE'
    : filter === 'AWAITING' ? 'AWAITING'
    : filter === 'ERRORED' ? 'ERRORED'
    : filter === 'COMPLETED' ? 'DONE'
    : filter === 'ARCHIVED' ? 'ARCHIVED'
    : filter === 'PENDING' ? 'QUEUED'
    : 'IDLE';
  return (
    <span style={{ ...statusPillBaseStyle, ...statusPillToneStyle(tone) }}>
      {(filter === 'RUNNING' || filter === 'AWAITING') && (
        <span style={{
          ...pulseDotStyle,
          background: tone === 'green' ? '#047857' : '#b45309',
          animation: filter === 'RUNNING' ? 'bytequayTasksListPulse 1.6s ease-in-out infinite' : 'none',
        }} />
      )}
      <span style={statusPillNumStyle}>{count}</span> {label}
    </span>
  );
}

function SortPill({ value, onChange }: {
  value: SortMode;
  onChange: (next: SortMode) => void;
}) {
  return (
    <label style={sortPillStyle} title="Sort cards">
      <span style={sortPillLabelStyle}>Sort:</span>
      <select
        value={value}
        onChange={e => onChange(e.target.value as SortMode)}
        style={sortPillSelectStyle}
      >
        <option value="newest">Newest</option>
        <option value="oldest">Oldest</option>
        <option value="highestCost">Most expensive</option>
      </select>
    </label>
  );
}

/** Mockup-faithful thread card. Provider glyph + title + meta on top,
 *  status pill top-right, metrics row at the bottom, click anywhere
 *  to open the detail page. The stage strip from the mockup isn't
 *  wired here — surfacing it would need per-card message polling,
 *  which is heavy for a list with dozens of threads. */
function ThreadCard({ thread, scheduler, groups, currentGroupIds, busy, hasUnread, onOpen, onStop, onToggleGroup }: {
  thread: ThreadDto;
  scheduler: ActiveTurnSummary | undefined;
  groups: ThreadGroupDto[];
  currentGroupIds: string[];
  busy: boolean;
  /** True when this thread carries at least one UNREAD notification.
   *  Renders as a small dot next to the status pill — the same auto*
   *  signal the left rail and the bell badge consume. */
  hasUnread: boolean;
  onOpen: () => void;
  onStop: () => void;
  onToggleGroup: (threadId: string, groupId: string, present: boolean) => void | Promise<void>;
}) {
  const isTerminal = thread.status === 'COMPLETED' || thread.status === 'ARCHIVED' || thread.status === 'ERRORED';
  const repoName = repoKey('');
  const provider = (thread.provider || '').toLowerCase();
  const glyph = provider.startsWith('codex') ? 'X' : 'C';
  const glyphBg = glyph === 'X'
    ? 'linear-gradient(135deg, #1f2937, #4b5563)'
    : 'linear-gradient(135deg, #d97706, #92400e)';
  const displayStatus = displayStatusForTask(thread, scheduler);
  const displayBranch = threadDisplayBranch(null);
  return (
    <article
      style={{
        ...cardStyle,
        borderLeftColor: stripeColor(displayStatus),
      }}
      onClick={onOpen}
      role="button"
      tabIndex={0}
      onKeyDown={e => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen();
        }
      }}
    >
      <div style={cardTopStyle}>
        <span style={{ ...cardProviderStyle, background: glyphBg }}>{glyph}</span>
        <div style={cardTitleBlockStyle}>
          <div style={cardTitleStyle}>{thread.title}</div>
          <div style={cardMetaLineStyle}>
            {repoName && <span style={cardMetaRepoStyle}>{repoName}</span>}
            {repoName && <span style={cardMetaSepStyle}>·</span>}
            <span>{formatRelative(thread.updatedAt)}</span>
            {displayBranch && (
              <>
                <span style={cardMetaSepStyle}>·</span>
                <span title={`branch ${displayBranch}`}>⎇ {displayBranch}</span>
              </>
            )}
            {thread.model && (
              <>
                <span style={cardMetaSepStyle}>·</span>
                <span style={cardMetaModelStyle}>{thread.model}</span>
              </>
            )}
          </div>
        </div>
        {hasUnread && (
          <span
            style={unreadDotStyle}
            title="Open notifications on this thread"
            aria-label="Unread notifications"
          />
        )}
        <RowStatusPill status={displayStatus} queued={scheduler?.queued ?? 0} />
      </div>

      <div style={cardMetricsStyle}>
        <span style={cardMetricStyle}>
          <span style={cardMetricIconStyle}>⏱</span>
          <strong style={cardMetricNumStyle}>{formatRuntime(thread)}</strong> runtime
        </span>
        <span style={cardMetricStyle}>
          <span style={cardMetricIconStyle}>💰</span>
          <strong style={cardMetricNumStyle}>{formatCost(thread.costUsdMilli)}</strong> spent
        </span>
        <span style={cardMetricStyle}>
          <span style={cardMetricIconStyle}>🔢</span>
          <strong style={cardMetricNumStyle}>{threadCompactNumber(thread.tokensIn + thread.tokensOut)}</strong> tokens
        </span>
        <span style={cardActionsStyle} onClick={e => e.stopPropagation()}>
          {!isTerminal && (
            <button
              type="button"
              onClick={onStop}
              disabled={busy}
              style={cardDangerBtnStyle}
              title="Stop and release the agent"
            >
              {busy ? 'Stopping…' : 'Stop'}
            </button>
          )}
          <GroupMenu thread={thread} groups={groups} currentGroupIds={currentGroupIds} onToggle={onToggleGroup} />
          <button
            type="button"
            onClick={onOpen}
            style={cardOpenBtnStyle}
            title="Open detail view"
          >
            Open →
          </button>
        </span>
      </div>
    </article>
  );
}

function RowStatusPill({ status, queued }: { status: SchedulerDisplayStatus; queued?: number }) {
  const palette: Record<SchedulerDisplayStatus, { fg: string; bg: string; label: string; pulse: boolean }> = {
    RUNNING:         { fg: '#047857', bg: '#d1fae5', label: 'RUNNING',          pulse: true  },
    AWAITING:        { fg: '#92400e', bg: '#fef3c7', label: 'AWAITING',         pulse: true  },
    AWAITING_REVIEW: { fg: '#92400e', bg: '#fef3c7', label: 'AWAITING REVIEW',  pulse: false },
    NEEDS_ATTENTION: { fg: '#b91c1c', bg: '#fee2e2', label: 'NEEDS ATTENTION',  pulse: true  },
    PENDING:         { fg: '#374151', bg: '#e5e7eb', label: 'PENDING',          pulse: false },
    QUEUED:          { fg: '#92400e', bg: '#fef3c7', label: 'QUEUED',           pulse: false },
    IDLE:            { fg: '#57606a', bg: '#f0f1f3', label: 'IDLE',             pulse: false },
    COMPLETED:       { fg: '#047857', bg: '#dcfce7', label: 'DONE',             pulse: false },
    ARCHIVED:        { fg: '#57606a', bg: '#eef0f2', label: 'ARCHIVED',         pulse: false },
    ERRORED:         { fg: '#991b1b', bg: '#fee2e2', label: 'ERRORED',          pulse: false },
  };
  const p = palette[status];
  return (
    <span style={{
      ...rowStatusPillStyle,
      background: p.bg,
      color: p.fg,
    }}>
      <span style={{
        ...pulseDotStyle,
        background: p.fg,
        animation: p.pulse && status === 'RUNNING'
          ? 'bytequayTasksListPulse 1.6s ease-in-out infinite'
          : 'none',
      }} />
      {p.label}{status === 'QUEUED' && queued && queued > 1 ? ` x${queued}` : ''}
    </span>
  );
}

function stripeColor(status: SchedulerDisplayStatus): string {
  switch (status) {
    case 'RUNNING':   return '#047857';
    case 'AWAITING':  return '#d97706';
    case 'PENDING':   return '#9ca3af';
    case 'QUEUED':    return '#d97706';
    case 'IDLE':      return '#cbd5e0';
    case 'COMPLETED': return '#10b981';
    case 'ARCHIVED':  return '#94a3b8';
    case 'ERRORED':   return '#dc2626';
  }
}

function formatCost(milli: number): string {
  if (!milli) return '$0.00';
  const dollars = milli / 1000;
  if (dollars >= 100) return `$${dollars.toFixed(0)}`;
  if (dollars >= 10)  return `$${dollars.toFixed(2)}`;
  return `$${dollars.toFixed(2)}`;
}

function formatRuntime(thread: ThreadDto): string {
  const start = Date.parse(thread.createdAt);
  if (!Number.isFinite(start)) return '—';
  const end = thread.endedAt !== null ? Date.parse(thread.endedAt) : Date.now();
  const sec = Math.max(0, Math.floor((end - start) / 1000));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

function formatRelative(iso: string): string {
  const d = Date.parse(iso);
  if (!Number.isFinite(d)) return iso;
  const ms = Date.now() - d;
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `started ${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `started ${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `started ${hr}h ago`;
  const day = Math.round(hr / 24);
  return `started ${day}d ago`;
}

const layoutStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'stretch',
  minHeight: 'calc(100vh - 56px)',
};

// Standalone Immersive quick toggle that lives in the threads header
// next to + New thread. Distinct from the segmented View toggle —
// this one is a one-click switch that hides the workspace rail and
// the top nav so the user can drive N agents at once.
function immersiveQuickBtnStyle(active: boolean): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '6px 12px',
    fontSize: 12,
    border: '1px solid ' + (active
        ? 'rgba(13, 148, 136, 0.40)'
        : 'rgba(0,0,0,0.10)'),
    background: active ? 'rgba(13, 148, 136, 0.10)' : '#fff',
    color: active ? '#0d9488' : 'var(--text-2)',
    borderRadius: 8,
    cursor: 'pointer',
    fontWeight: 500,
    marginRight: 8,
  };
}

// Group-board top tabs row: appears when the user has switched to
// the Group view. Picks up the mockup's "● Today 4/4 · Launch work
// 3/4 · Bug triage 2/4 · + New group" tab strip plus the tmux-tight
// hint copy on the right.
const groupTabsBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  margin: '0 0 16px',
  paddingBottom: 12,
  borderBottom: '1px solid rgba(0,0,0,0.06)',
  flexWrap: 'wrap',
};

function groupTabChipStyle(active: boolean): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    padding: '5px 12px',
    fontSize: 12,
    border: '1px solid ' + (active ? 'rgba(124, 58, 237, 0.40)' : 'rgba(0,0,0,0.08)'),
    background: active ? 'rgba(124, 58, 237, 0.10)' : '#fff',
    color: active ? '#6d28d9' : 'var(--text-2)',
    borderRadius: 8,
    cursor: 'pointer',
    fontWeight: active ? 600 : 500,
  };
}

function groupTabDotStyle(color: string): React.CSSProperties {
  // Map the workspace's named color hint to a dot tone. Fall back to
  // slate when the color is unfamiliar so the chip still reads clean.
  const swatch: Record<string, string> = {
    purple: '#7c3aed',
    teal: '#0d9488',
    blue: '#2563eb',
    amber: '#d97706',
    pink: '#db2777',
    green: '#16a34a',
    slate: '#475569',
  };
  return {
    width: 7,
    height: 7,
    borderRadius: 999,
    background: swatch[color.toLowerCase()] ?? '#7c3aed',
    flexShrink: 0,
  };
}

const groupTabLabelStyle: React.CSSProperties = {
  fontWeight: 600,
};

const groupTabCountStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  marginLeft: 2,
  fontVariantNumeric: 'tabular-nums',
};

const newGroupChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  padding: '5px 12px',
  fontSize: 12,
  border: '1px dashed rgba(0,0,0,0.18)',
  background: 'transparent',
  color: 'var(--text-3)',
  borderRadius: 8,
  cursor: 'pointer',
  fontWeight: 500,
};

const groupTabsHintStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 10,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};
const mainStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  // No horizontal padding here — .workspace-content owns the 28 px
  // side gutter the rest of the workspace surfaces align to, and
  // .threads-pageheader's negative margin counts on it. Keep a small
  // bottom padding so the last card row doesn't kiss the viewport
  // floor in the immersive case.
  padding: '0 0 64px',
};
const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 16,
  marginBottom: 24,
};
const titleStyle: React.CSSProperties = {
  margin: 0, fontSize: 24, fontWeight: 700, color: 'var(--text-1)',
};
const subtitleStyle: React.CSSProperties = {
  margin: '4px 0 0', color: 'var(--text-3)', maxWidth: 600,
};
const headerActionsStyle: React.CSSProperties = { display: 'flex', gap: 8, alignItems: 'center' };
const secondaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'var(--bg-btn-secondary)',
  color: 'var(--text-2)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  cursor: 'pointer',
};
const errorBannerStyle: React.CSSProperties = {
  padding: '12px 16px',
  background: '#FEF2F2',
  color: '#991B1B',
  border: '1px solid #FCA5A5',
  borderRadius: 6,
  marginBottom: 16,
};
const emptyStateStyle: React.CSSProperties = {
  padding: '40px 24px',
  textAlign: 'center',
  border: '1px dashed var(--border)',
  borderRadius: 8,
  color: 'var(--text-2)',
};
const emptyTitleStyle: React.CSSProperties = { fontSize: 16, fontWeight: 600, marginBottom: 4, color: 'var(--text-1)' };
const mutedTextStyle: React.CSSProperties = { color: 'var(--text-3)', fontSize: 13 };
// ─── Breadcrumb + header pill ───────────────────────────────────────

const breadcrumbRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 13,
  color: 'var(--text-3)',
  marginBottom: 10,
};
const breadcrumbRootStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  padding: 0,
  cursor: 'pointer',
  color: 'var(--accent)',
  font: 'inherit',
};
const breadcrumbSepStyle: React.CSSProperties = {
  color: 'var(--text-4, var(--text-3))',
};
const breadcrumbCurrentStyle: React.CSSProperties = {
  color: 'var(--text-2)',
};

type PillTone = 'green' | 'greenSoft' | 'amber' | 'red' | 'grey';
function statusPillToneStyle(tone: PillTone): React.CSSProperties {
  switch (tone) {
    case 'green':     return { background: '#d1fae5', color: '#047857' };
    case 'greenSoft': return { background: '#dcfce7', color: '#166534' };
    case 'amber':     return { background: '#fef3c7', color: '#b45309' };
    case 'red':       return { background: '#fee2e2', color: '#991b1b' };
    case 'grey':      return { background: 'var(--bg-elevated)', color: 'var(--text-2)', border: '1px solid var(--border-hairline)' };
  }
}
const statusPillBaseStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 5,
  padding: '3px 10px',
  marginLeft: 10,
  borderRadius: 999,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  verticalAlign: 'middle',
};
const statusPillNumStyle: React.CSSProperties = {
  fontVariantNumeric: 'tabular-nums',
};
const pulseDotStyle: React.CSSProperties = {
  width: 6, height: 6,
  borderRadius: '50%',
  display: 'inline-block',
};

// ─── Toolbar (search + sort) ────────────────────────────────────────

const toolbarRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  marginBottom: 16,
};
const searchInputStyle: React.CSSProperties = {
  flex: 1,
  maxWidth: 360,
  padding: '8px 12px',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  fontSize: 13,
  font: 'inherit',
  outline: 'none',
};
const sortPillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  padding: '4px 6px 4px 10px',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  fontSize: 12,
  color: 'var(--text-2)',
};
const sortPillLabelStyle: React.CSSProperties = {
  color: 'var(--text-3)',
};
const sortPillSelectStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--text-1)',
  fontWeight: 600,
  cursor: 'pointer',
  font: 'inherit',
  padding: '2px 4px',
  outline: 'none',
};

// ─── Card grid ──────────────────────────────────────────────────────

const cardGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fill, minmax(440px, 1fr))',
  gap: 14,
};
const cardStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  padding: '14px 16px',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderLeft: '4px solid var(--border)',
  borderRadius: 8,
  cursor: 'pointer',
  color: 'var(--text-1)',
  transition: 'border-color 0.1s ease, box-shadow 0.1s ease',
  outline: 'none',
};
const cardTopStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
};
/** Small violet dot next to the status pill when the thread carries
 *  at least one UNREAD notification. Same colour as the auto* row in
 *  the left rail so the two signals visually agree. */
const unreadDotStyle: React.CSSProperties = {
  width: 8,
  height: 8,
  borderRadius: 999,
  background: '#7c3aed',
  flexShrink: 0,
  marginTop: 6,
  marginRight: 2,
  boxShadow: '0 0 0 2px rgba(124, 58, 237, 0.18)',
};
const cardProviderStyle: React.CSSProperties = {
  width: 28, height: 28,
  borderRadius: 6,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontSize: 12,
  fontWeight: 700,
  flexShrink: 0,
};
const cardTitleBlockStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};
const cardTitleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const cardMetaLineStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 11.5,
  color: 'var(--text-3)',
  flexWrap: 'wrap',
};
const cardMetaRepoStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: 'var(--text-2)',
};
const cardMetaSepStyle: React.CSSProperties = {
  color: 'var(--text-4, var(--text-3))',
};
const cardMetaModelStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const rowStatusPillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  padding: '2px 8px',
  borderRadius: 999,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.04em',
  flexShrink: 0,
};

const cardMetricsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  paddingTop: 8,
  borderTop: '1px solid var(--border-hairline)',
  fontSize: 12,
  color: 'var(--text-3)',
};
const cardMetricStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
};
const cardMetricIconStyle: React.CSSProperties = {
  fontSize: 12,
};
const cardMetricNumStyle: React.CSSProperties = {
  color: 'var(--text-1)',
  fontVariantNumeric: 'tabular-nums',
  fontWeight: 600,
};
const cardActionsStyle: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
};
const cardDangerBtnStyle: React.CSSProperties = {
  padding: '3px 8px',
  background: 'transparent',
  color: '#dc2626',
  border: '1px solid #fca5a5',
  borderRadius: 4,
  fontSize: 11,
  cursor: 'pointer',
  font: 'inherit',
};
const cardOpenBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  background: 'var(--accent-a10)',
  color: 'var(--accent)',
  border: '1px solid var(--accent)',
  borderRadius: 4,
  fontSize: 11,
  fontWeight: 600,
  cursor: 'pointer',
  font: 'inherit',
};

const hintFooterStyle: React.CSSProperties = {
  marginTop: 18,
  padding: '12px 16px',
  background: 'var(--bg-elevated)',
  border: '1px dashed var(--border)',
  borderRadius: 8,
  fontSize: 12,
  color: 'var(--text-3)',
  lineHeight: 1.55,
};

/**
 * Coming-soon body for the Group and Immersive view modes. Phase 1
 * ships the toggle as scaffold; the actual group board and immersive
 * surface land in later phases. Renders a calm card with the mode
 * name and an escape hatch back to List so the user isn't stranded.
 */
function ViewModePlaceholder({
  mode, onPickList,
}: { mode: 'group' | 'immersive'; onPickList: () => void }) {
  const copy = mode === 'group'
      ? {
        title: 'Group board',
        body: 'Side-by-side 2×2 monitoring of pinned threads. Each board '
            + 'holds at most four threads, shown as a tmux-minimal grid.',
      }
      : {
        title: 'Immersive',
        body: 'Full-bleed group view with chromeless panes — conversation '
            + 'or terminal — for watching agents work with maximum real '
            + 'estate. Esc exits.',
      };
  return (
    <div className="threads-view-placeholder">
      <h2 className="threads-view-placeholder__title">{copy.title}</h2>
      <p className="threads-view-placeholder__body">{copy.body}</p>
      <p className="threads-view-placeholder__meta">
        Lands in a follow-up — the toggle is wired so the scaffold's in
        place.
      </p>
      <button
        type="button"
        className="threads-view-placeholder__btn"
        onClick={onPickList}
      >
        ← Back to List
      </button>
    </div>
  );
}
