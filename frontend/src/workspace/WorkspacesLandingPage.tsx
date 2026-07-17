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
import { useEffect, useMemo, useState } from 'react';
import AddRepoModal from '../AddRepoModal';
import WorkspaceCard from './WorkspaceCard';
import { ConfirmDialog } from './ConfirmDialog';
import useWorkspaces from './useWorkspaces';
import { WORKSPACES_ICON } from '../ui/workspace/WorkspaceNavSidebar';
import type { WatchedRepoDto } from '../types';

type Props = {
  /** Workspace the user most recently entered, persisted in
   *  localStorage. Drives the CURRENT chip + the primary ring on the
   *  matching card. Empty / null when nothing has been entered yet. */
  currentWorkspaceId: string | null;
  /** Called when the user picks a workspace. The caller bumps
   *  activeWorkspaceId and routes into that workspace's Home. */
  onEnterWorkspace: (workspaceId: string) => void;
};

type SortKey = 'recent' | 'active' | 'name';

/** Top-level Workspaces page. Lives "above" any workspace — no
 *  workspace nav rail — and answers the "which project brain do I
 *  enter?" question. Renders the grid of WorkspaceCards plus a
 *  dashed "+ New workspace" tile. */
function WorkspacesLandingPage({
  currentWorkspaceId, onEnterWorkspace,
}: Props) {
  const { cards, loading, error, reload } = useWorkspaces();
  const [filter, setFilter] = useState('');
  const [sort, setSort] = useState<SortKey>('recent');
  const [connectOpen, setConnectOpen] = useState(false);
  const [watchedRepos, setWatchedRepos] = useState<WatchedRepoDto[]>([]);
  // Id of the workspace the delete-confirm dialog is asking about, or
  // null when closed; `deleting` disables the button while the request
  // is in flight so a double-click can't fire two deletes.
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  // decision pending — the ambient "auto-enter the only non-scratch
  // workspace" redirect lives elsewhere once multi-workspace creation
  // ships. While we're single-workspace the landing always renders so
  // the user can see + verify the card grid; revisit when the grid is
  // visually settled.
  useEffect(() => {
    void window.bridge.getWatchedRepos().then(setWatchedRepos).catch(() => {});
  }, []);

  const filtered = useMemo(() => {
    if (!cards) {
      return [];
    }
    const needle = filter.trim().toLowerCase();
    const matched = needle
      ? cards.filter(c => c.name.toLowerCase().includes(needle))
      : cards;
    // "recent" trusts the backend's own recency ordering; the other two
    // sort client-side over the already-filtered list.
    if (sort === 'name') {
      return [...matched].sort((a, b) => a.name.localeCompare(b.name));
    }
    if (sort === 'active') {
      return [...matched].sort((a, b) => (
        (b.activeThreadCount > 0 ? 1 : 0) - (a.activeThreadCount > 0 ? 1 : 0)
      ) || b.activeThreadCount - a.activeThreadCount);
    }
    return matched;
  }, [cards, filter, sort]);

  const activeCount = cards?.filter(c => c.activeThreadCount > 0).length ?? 0;

  // Delete a workspace from its card — opens the in-app confirm dialog.
  // The backend cascades (purges every thread and its tasks, history,
  // worktrees), so the dialog warns loudly when the workspace has some.
  const deleteCard = deleteId === null ? null : cards?.find(c => c.id === deleteId) ?? null;
  const deleteThreads = deleteCard?.activeThreadCount ?? 0;
  const deleteBody =
    (deleteThreads > 0
      ? `Its ${deleteThreads} thread${deleteThreads === 1 ? '' : 's'} and all their tasks and history go with it. `
      : '')
    + 'This permanently removes its threads, tasks, messages, backlog, and '
    + 'worktrees, and stops any running agents.\n\nThis cannot be undone.';

  const confirmDelete = () => {
    if (deleteId === null) {
      return;
    }
    setDeleting(true);
    void window.bridge.deleteWorkspace(deleteId)
      .then(() => {
        setDeleteId(null);
        reload();
      })
      .catch((e: unknown) => {
        window.alert(`Couldn't delete workspace: ${e instanceof Error ? e.message : String(e)}`);
      })
      .finally(() => setDeleting(false));
  };

  return (
    <section className="workspace-landing">
      <header className="workspace-landing__appbar">
        <span className="workspace-landing__title-group">
          <span className="workspace-landing__title-icon" aria-hidden>{WORKSPACES_ICON}</span>
          Workspaces
        </span>
        <span className="workspace-landing__title-count">· {activeCount} active</span>
        <span className="workspace-landing__appbar-spacer" />
        <div className="workspace-landing__search-box">
          <svg className="workspace-landing__search-icon" width="13" height="13"
            viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.8" strokeLinecap="round" aria-hidden>
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <span className="workspace-landing__search-field">
            {filter.length === 0 && (
              <span className="workspace-landing__search-placeholder" aria-hidden>
                Search workspaces or jump to…
              </span>
            )}
            <input
              type="search"
              className="workspace-landing__search"
              value={filter}
              onChange={e => setFilter(e.target.value)}
              aria-label="Filter workspaces"
            />
          </span>
          <span className="workspace-landing__search-kbd" aria-hidden>⌘K</span>
        </div>
        <button
          type="button"
          className="workspace-landing__new-btn"
          onClick={() => setConnectOpen(true)}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden>
            <path d="M12 5v14M5 12h14" />
          </svg>
          New workspace
        </button>
      </header>

      <div className="workspace-landing__hero">
        <h1 className="workspace-landing__title">Workspaces</h1>
        <p className="workspace-landing__subtitle">
          A workspace is a repo plus everything around it — threads, reviews,
          memory, agents.
        </p>
        <div className="workspace-landing__sort">
          <span className="workspace-landing__sort-label">Sort</span>
          <div className="workspace-landing__sort-track">
            {(['recent', 'active', 'name'] as const).map(key => (
              <div
                key={key}
                role="button"
                tabIndex={0}
                className={`workspace-landing__sort-btn${
                  sort === key ? ' workspace-landing__sort-btn--active' : ''}`}
                onClick={() => setSort(key)}
                onKeyDown={event => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setSort(key);
                  }
                }}
              >
                {key === 'recent' ? 'Recent' : key === 'active' ? 'Active' : 'Name'}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="workspace-landing__grid">
        {loading && (
          <p className="workspace-landing__placeholder">Loading workspaces…</p>
        )}
        {!loading && error && (
          <div className="workspace-landing__error">
            <p>Couldn't load workspaces: {error}</p>
            <button type="button" onClick={() => { void reload(); }}>
              Retry
            </button>
          </div>
        )}
        {!loading && !error && filtered.map(card => (
          <WorkspaceCard
            key={card.id}
            card={card}
            isCurrent={card.id === currentWorkspaceId}
            onEnter={onEnterWorkspace}
            onDelete={setDeleteId}
          />
        ))}
        {!loading && !error && (
          <div
            role="button"
            tabIndex={0}
            className="workspace-landing-card workspace-landing-card--new"
            onClick={() => setConnectOpen(true)}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                setConnectOpen(true);
              }
            }}
            aria-label="Connect a repository"
          >
            <span className="workspace-landing-card__new-plus" aria-hidden>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
                stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden>
                <path d="M12 5v14M5 12h14" />
              </svg>
            </span>
            <span className="workspace-landing-card__new-label">Connect a repository</span>
            <span className="workspace-landing-card__new-blurb">
              A workspace is a repo clone plus its threads, reviews, memory
              and agents. One repo, one workspace.
            </span>
          </div>
        )}
        {!loading && !error && cards && cards.length > 0 && filtered.length === 0 && (
          <p className="workspace-landing__placeholder">
            No workspace matches "{filter}".
          </p>
        )}
      </div>

      {connectOpen && (
        <AddRepoModal
          watchedRepos={watchedRepos}
          onClose={() => setConnectOpen(false)}
          onAdded={() => {
            setConnectOpen(false);
            void window.bridge.getWatchedRepos().then(setWatchedRepos).catch(() => {});
            void reload();
          }}
        />
      )}
      {deleteCard !== null && (
        <ConfirmDialog
          title={`Delete workspace "${deleteCard.name}"?`}
          body={deleteBody}
          confirmLabel={deleting ? 'Deleting…' : 'Delete workspace'}
          destructive
          busy={deleting}
          onConfirm={confirmDelete}
          onCancel={() => setDeleteId(null)}
        />
      )}
    </section>
  );
}

export default WorkspacesLandingPage;
