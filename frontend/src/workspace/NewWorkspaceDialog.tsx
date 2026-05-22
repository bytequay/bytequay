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
import type { WatchedRepoDto } from '../types';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from './dialogStyles';

type Props = {
  onClose: () => void;
};

/** New-workspace modal. UI-first; the Create button is disabled
 *  while the app is single-workspace only — the backend
 *  WorkspaceStore today supports list / find but no create endpoint
 *  the dialog can route to, so wiring is gated on the multi-
 *  workspace migration. The form structure lives here now so when
 *  the backend lands, the only change is flipping the button on +
 *  forwarding the payload through a new bridge method. */
function NewWorkspaceDialog({ onClose }: Props) {
  const [name, setName] = useState('');
  const [repos, setRepos] = useState<WatchedRepoDto[]>([]);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [seedMemory, setSeedMemory] = useState(true);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      setRepos(await window.bridge.getWatchedRepos());
    }
    catch {
      // The list is informational; failure shouldn't block the modal
      // from rendering. The empty state already explains there's
      // nothing to pick.
    }
    finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const toggleRepo = (id: number) => {
    setPicked(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  return (
    <div
      style={WS_DIALOG_OVERLAY}
      onClick={onClose}
      role="presentation"
    >
      <div
        style={WS_DIALOG_PANEL}
        role="dialog"
        aria-modal="true"
        aria-labelledby="new-workspace-title"
        onClick={e => e.stopPropagation()}
      >
        <header style={dialogStyles.header}>
          <div>
            <h2 id="new-workspace-title" style={dialogStyles.title}>
              <span aria-hidden style={purpleSquareIcon}>◆</span>
              New workspace
            </h2>
            <div style={dialogStyles.helperRow}>
              A long-lived project brain — shared memory &amp; skills across its threads.
            </div>
          </div>
          <button
            type="button"
            style={dialogStyles.closeBtn}
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </header>

        <div style={dialogStyles.field}>
          <label style={dialogStyles.fieldLabel}>Name</label>
          <div style={nameRowStyle}>
            <span style={namePreviewBadgeStyle}>
              {name.slice(0, 1).toUpperCase() || 'W'}
            </span>
            <input
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="e.g. Trino-trace"
              style={{ ...dialogStyles.input, flex: 1 }}
            />
          </div>
        </div>

        <div style={dialogStyles.field}>
          <label style={dialogStyles.fieldLabel}>
            Repositories
            <span style={pickHintStyle}>watched repos · pick 1+</span>
          </label>
          {loading ? (
            <div style={emptyStyle}>Loading repos…</div>
          ) : repos.length === 0 ? (
            <div style={emptyStyle}>
              No watched repos yet — add one from Settings → Repositories first.
            </div>
          ) : (
            <ul style={repoListStyle}>
              {repos.map(r => (
                <li key={r.id} style={repoRowStyle(picked.has(r.id))}>
                  <label style={repoLabelStyle}>
                    <input
                      type="checkbox"
                      checked={picked.has(r.id)}
                      onChange={() => toggleRepo(r.id)}
                    />
                    <span style={repoMetaStyle}>
                      <span style={repoFullNameStyle}>{r.owner}/{r.repo}</span>
                      <span style={mutedStyle}>watched repo</span>
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div style={{ ...dialogStyles.field, ...seedMemoryRowStyle }}>
          <div>
            <div style={seedMemoryTitleStyle}>+ Seed memory from the repo</div>
            <div style={mutedStyle}>
              Read <code style={inlineCodeStyle}>CLAUDE.md</code> /{' '}
              <code style={inlineCodeStyle}>AGENTS.md</code> on creation to
              bootstrap <code style={inlineCodeStyle}>WORKSPACE.md</code> — then
              AI + you maintain it.
            </div>
          </div>
          <SeedToggle checked={seedMemory} onToggle={() => setSeedMemory(s => !s)} />
        </div>

        <div style={twoFieldRowStyle}>
          <div style={dialogStyles.field}>
            <label style={dialogStyles.fieldLabel}>Default coding agent</label>
            <select style={dialogStyles.input} defaultValue="claude" disabled>
              <option value="claude">Claude Code — sonnet-4.6</option>
            </select>
          </div>
          <div style={dialogStyles.field}>
            <label style={dialogStyles.fieldLabel}>Default review panel</label>
            <select style={dialogStyles.input} defaultValue="gpt-claude" disabled>
              <option value="gpt-claude">GPT-5 + Claude</option>
            </select>
          </div>
        </div>

        <footer style={dialogStyles.footer}>
          <div style={dialogStyles.footerNote}>
            Workspaces are deliberate &amp; long-lived — you'll have only a
            handful. One-offs can stay in the scratch workspace. Multi-
            workspace creation isn't wired yet.
          </div>
          <div style={dialogStyles.footerButtons}>
            <button type="button" style={dialogStyles.secondaryBtn} onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              style={dialogStyles.primaryBtnDisabled}
              disabled
              title="Multi-workspace creation isn't wired yet"
            >
              ◆ Create workspace
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}

function SeedToggle({ checked, onToggle }: { checked: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={onToggle}
      style={{
        width: 34,
        height: 20,
        padding: 2,
        borderRadius: 999,
        border: 'none',
        background: checked ? 'var(--ws-accent)' : 'rgba(124, 58, 237, 0.18)',
        cursor: 'pointer',
        transition: 'background var(--ws-fast)',
        display: 'inline-flex',
        alignItems: 'center',
        flexShrink: 0,
      }}
    >
      <span
        aria-hidden
        style={{
          width: 16,
          height: 16,
          borderRadius: '50%',
          background: '#fff',
          transform: `translateX(${checked ? 14 : 0}px)`,
          transition: 'transform var(--ws-fast)',
        }}
      />
    </button>
  );
}

const purpleSquareIcon: React.CSSProperties = {
  width: 24,
  height: 24,
  borderRadius: 6,
  background: 'var(--ws-accent)',
  color: '#fff',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 12,
  marginRight: 4,
};

const nameRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const namePreviewBadgeStyle: React.CSSProperties = {
  width: 32,
  height: 32,
  borderRadius: 8,
  background: 'linear-gradient(135deg, #a78bfa, #7c3aed)',
  color: '#fff',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 14,
  fontWeight: 700,
  flexShrink: 0,
};

const pickHintStyle: React.CSSProperties = {
  float: 'right',
  fontSize: 10,
  fontWeight: 400,
  textTransform: 'none',
  letterSpacing: 0,
  color: 'var(--ws-text-3)',
};

const repoListStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  maxHeight: 180,
  overflowY: 'auto',
};

function repoRowStyle(picked: boolean): React.CSSProperties {
  return {
    padding: '8px 10px',
    borderRadius: 8,
    background: picked ? 'var(--ws-accent-soft)' : '#fff',
    border: picked
        ? '1px solid var(--ws-accent)'
        : '1px solid var(--ws-card-border)',
    transition: 'background var(--ws-fast), border-color var(--ws-fast)',
  };
}

const repoLabelStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  cursor: 'pointer',
  width: '100%',
};

const repoMetaStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
};

const repoFullNameStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 500,
  color: 'var(--ws-text-1)',
};

const mutedStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ws-text-3)',
};

const emptyStyle: React.CSSProperties = {
  padding: 14,
  textAlign: 'center',
  fontSize: 12,
  color: 'var(--ws-text-3)',
  border: '1px dashed var(--ws-card-border)',
  borderRadius: 8,
};

const seedMemoryRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 14,
  padding: 12,
  background: 'rgba(124, 58, 237, 0.04)',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 10,
};

const seedMemoryTitleStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--ws-text-1)',
  marginBottom: 2,
};

const inlineCodeStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 10,
  padding: '1px 4px',
  background: 'rgba(124, 58, 237, 0.08)',
  borderRadius: 3,
  color: 'var(--ws-accent-deep)',
};

const twoFieldRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 10,
};

export default NewWorkspaceDialog;
