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
import useWorkspaces from './useWorkspaces';

type Props = {
  /** Workspace the user most recently entered, persisted in
   *  localStorage. Drives the CURRENT chip + the primary ring on the
   *  matching card. Empty / null when nothing has been entered yet. */
  currentWorkspaceId: string | null;
  /** Called when the user picks a workspace. The caller bumps
   *  activeWorkspaceId and routes into that workspace's Home. */
  onEnterWorkspace: (workspaceId: string) => void;
};

/** Top-level Workspaces page. Lives "above" any workspace — no
 *  workspace nav rail — and answers the "which project brain do I
 *  enter?" question. Renders the grid of WorkspaceCards plus a
 *  dashed "+ New workspace" tile. */
function WorkspacesLandingPage({
  currentWorkspaceId, onEnterWorkspace,
}: Props) {
  const { cards, loading, error, reload } = useWorkspaces();
  const [filter, setFilter] = useState('');
  const [newWorkspaceOpen, setNewWorkspaceOpen] = useState(false);
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
    if (!needle) {
      return cards;
    }
    return cards.filter(c => c.name.toLowerCase().includes(needle));
  }, [cards, filter]);

  return (
    <section className="workspace-landing">
      <header className="workspace-landing__appbar">
        <span className="workspace-landing__brand">
          <span className="workspace-landing__brand-badge" aria-hidden>B</span>
          <span className="workspace-landing__brand-name">ByteQuay</span>
        </span>
        <input
          type="search"
          className="workspace-landing__search"
          placeholder="Search workspaces or jump to…"
          value={filter}
          onChange={e => setFilter(e.target.value)}
          aria-label="Filter workspaces"
        />
        <span className="workspace-landing__account" aria-hidden>
          <span className="workspace-landing__account-avatar">JC</span>
          <span className="workspace-landing__account-name">Jian</span>
          <span className="workspace-landing__account-chevron">▾</span>
        </span>
      </header>

      <div className="workspace-landing__hero">
        <h1 className="workspace-landing__title">Workspaces</h1>
        <p className="workspace-landing__subtitle">
          Each workspace is a long-lived project brain — its own repos, memory,
          and threads. Pick one to drop into.
        </p>
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
          />
        ))}
        {!loading && !error && (
          <button
            type="button"
            className="workspace-landing-card workspace-landing-card--new"
            onClick={() => setNewWorkspaceOpen(true)}
            aria-label="New workspace"
          >
            <span className="workspace-landing-card__new-plus" aria-hidden>+</span>
            <span className="workspace-landing-card__new-label">New workspace</span>
            <span className="workspace-landing-card__new-blurb">
              Deliberate &amp; long-lived — you'll have only a handful. Seed
              it from a repo's CLAUDE.md.
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
    </section>
  );
}

export default WorkspacesLandingPage;
