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
  /** Fires after the workspace is created; the parent typically
   *  reloads its workspace list (and may route the user into the
   *  new workspace). Optional — the dialog still closes either way. */
  onCreated?: (workspaceId: string) => void;
};

function NewWorkspaceDialog({ onClose, onCreated }: Props) {
  const [repos, setRepos] = useState<WatchedRepoDto[]>([]);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

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
      return prev.has(id) ? new Set() : new Set([id]);
    });
  };

  const localRepos = repos.filter(repo => repo.localClonePath !== null);
  const canCreate = picked.size === 1 && !submitting;

  const onSubmit = async () => {
    if (!canCreate) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const repo = localRepos.find(candidate => picked.has(candidate.id));
      if (!repo) return;
      const created = await window.bridge.ensureWorkspaceForRepo(repo.owner, repo.repo);
      onCreated?.(created.id);
      onClose();
    }
    catch (e) {
      setSubmitError(e instanceof Error ? e.message : String(e));
      setSubmitting(false);
    }
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
              Add repository
            </h2>
            <div style={dialogStyles.helperRow}>
              Choose one verified local clone. It gets one shared workspace.
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
          <label style={dialogStyles.fieldLabel}>
            Repositories
            <span style={pickHintStyle}>local clones · pick exactly one</span>
          </label>
          {loading ? (
            <div style={emptyStyle}>Loading repos…</div>
          ) : localRepos.length === 0 ? (
            <div style={emptyStyle}>
              No local clones yet — clone or map a repository first.
            </div>
          ) : (
            <ul style={repoListStyle}>
              {localRepos.map(r => (
                <li key={r.id} style={repoRowStyle(picked.has(r.id))}>
                  <label style={repoLabelStyle}>
                    <input
                      type="radio"
                      checked={picked.has(r.id)}
                      onChange={() => toggleRepo(r.id)}
                    />
                    <span style={repoMetaStyle}>
                      <span style={repoFullNameStyle}>{r.owner}/{r.repo}</span>
                      <span style={mutedStyle}>verified local clone</span>
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
        </div>

        {submitError !== null && (
          <div style={errorStyle} role="alert">{submitError}</div>
        )}

        <footer style={dialogStyles.footer}>
          <div style={dialogStyles.footerNote}>
            A workspace always belongs to exactly one local repository.
          </div>
          <div style={dialogStyles.footerButtons}>
            <button type="button" style={dialogStyles.secondaryBtn} onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              style={canCreate ? dialogStyles.primaryBtn : dialogStyles.primaryBtnDisabled}
              disabled={!canCreate}
              onClick={() => { void onSubmit(); }}
              title={canCreate
                ? undefined
                : 'Pick one verified local clone'}
            >
              ◆ {submitting ? 'Adding…' : 'Add repository'}
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

const slugRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'stretch',
  gap: 0,
};

const slugPrefixStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  padding: '0 10px',
  fontSize: 12,
  fontFamily: 'ui-monospace, monospace',
  color: 'var(--ws-text-3)',
  background: 'rgba(124, 58, 237, 0.06)',
  border: '1px solid var(--ws-card-border)',
  borderRight: 'none',
  borderRadius: '8px 0 0 8px',
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

const errorStyle: React.CSSProperties = {
  marginTop: 12,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 8,
  color: '#cf1322',
  fontSize: 12,
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

const promptDisclosureStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  width: '100%',
  padding: '6px 4px',
  background: 'transparent',
  border: 'none',
  cursor: 'pointer',
  color: 'var(--ws-text-2)',
  textAlign: 'left',
};

function promptDisclosureChevronStyle(expanded: boolean): React.CSSProperties {
  return {
    fontSize: 10,
    color: 'var(--ws-text-3)',
    display: 'inline-block',
    transform: expanded ? 'rotate(90deg)' : 'rotate(0deg)',
    transition: 'transform var(--ws-fast)',
    width: 12,
  };
}

const promptDisclosureLabelStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  color: 'var(--ws-text-3)',
};

const promptDisclosureMetaStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ws-text-3)',
  marginLeft: 'auto',
};

const promptHelperRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 10,
  marginTop: 4,
};

function promptCounterStyle(over: boolean): React.CSSProperties {
  return {
    fontWeight: 600,
    color: over ? '#cf1322' : 'var(--ws-text-3)',
    fontVariantNumeric: 'tabular-nums',
  };
}

const promptResetBtnStyle: React.CSSProperties = {
  fontSize: 10,
  padding: '2px 8px',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 6,
  background: '#fff',
  color: 'var(--ws-text-2)',
  cursor: 'pointer',
};

/** Default workspace-context nudge. Interpolates the selected repo
 *  short names and the typed workspace name; falls back to inline
 *  placeholders when the user hasn't picked / named anything yet so
 *  the textarea still hints at the shape. */
function buildDefaultPrompt(workspaceName: string, repoNames: string[]): string {
  const trimmedName = workspaceName.trim();
  const wsLabel = trimmedName.length > 0 ? `"${trimmedName}"` : '"<workspace name>"';
  const repoLabel = repoNames.length > 0
      ? repoNames.map(r => `"${r}"`).join(', ')
      : '"<repo>"';
  return `You are working inside ByteQuay app, assisting the user with development of the repo: ${repoLabel}. You are always inside the ByteQuay workspace ${wsLabel}.`;
}

function countWords(text: string): number {
  const trimmed = text.trim();
  if (trimmed.length === 0) return 0;
  return trimmed.split(/\s+/).length;
}

export default NewWorkspaceDialog;
