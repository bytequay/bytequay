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
import { useMemo, useState } from 'react';
import NewWorkspaceDialog from './NewWorkspaceDialog';
import WorkspaceCard from './WorkspaceCard';
import { ConfirmDialog } from './ConfirmDialog';
import useWorkspaces from './useWorkspaces';
import { WORKSPACES_ICON } from '../ui/workspace/WorkspaceNavSidebar';

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
  const [newWorkspaceOpen, setNewWorkspaceOpen] = useState(false);
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
          <span className="workspace-landing__title-count">· {activeCount} active</span>
        </span>
        <span className="workspace-landing__appbar-spacer" />
        <div className="workspace-landing__search-box">
          <span className="workspace-landing__search-icon" aria-hidden>⌕</span>
          <input
            type="search"
            className="workspace-landing__search"
            placeholder="Search workspaces or jump to…"
            value={filter}
            onChange={e => setFilter(e.target.value)}
            aria-label="Filter workspaces"
          />
          <span className="workspace-landing__search-kbd" aria-hidden>⌘K</span>
        </div>
        <button
          type="button"
          className="workspace-landing__new-btn"
          onClick={() => setNewWorkspaceOpen(true)}
        >
          + New workspace
        </button>
      </header>

      <div className="workspace-landing__hero">
        <h1 className="workspace-landing__title">Workspaces</h1>
        <p className="workspace-landing__subtitle">
          Each workspace is a long-lived project brain — its own repos, memory,
          and threads. Pick one to drop into.
        </p>
        <div className="workspace-landing__sort">
          <span className="workspace-landing__sort-label">Sort</span>
          <div className="workspace-landing__sort-track">
            {(['recent', 'active', 'name'] as const).map(key => (
              <button
                key={key}
                type="button"
                className={`workspace-landing__sort-btn${
                  sort === key ? ' workspace-landing__sort-btn--active' : ''}`}
                onClick={() => setSort(key)}
              >
                {key === 'recent' ? 'Recent' : key === 'active' ? 'Active' : 'Name'}
              </button>
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
          <button
            type="button"
            className="workspace-landing-card workspace-landing-card--new"
            onClick={() => setNewWorkspaceOpen(true)}
            aria-label="Add repository"
          >
            <span className="workspace-landing-card__new-plus" aria-hidden>+</span>
            <span className="workspace-landing-card__new-label">Add repository</span>
            <span className="workspace-landing-card__new-blurb">
              Connect one verified local clone. It becomes that repository's
              shared workspace.
            </span>
          </button>
        )}
        {!loading && !error && cards && cards.length > 0 && filtered.length === 0 && (
          <p className="workspace-landing__placeholder">
            No workspace matches "{filter}".
          </p>
        )}
      </div>

      {newWorkspaceOpen && (
        <NewWorkspaceDialog
          onClose={() => {
            setNewWorkspaceOpen(false);
            // Refresh the grid so a newly-created workspace appears
            // without a full reload — the dialog wires its own Create
            // path; we just trust the close hook fired post-success.
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
