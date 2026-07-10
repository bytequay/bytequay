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
import { useEffect, useState } from 'react';
import type { WatchedRepoDto, WorkModelDto } from '../types';
import { logoColorFor, monogram } from '../pages/useWorkspaceNav';
import { Logo } from '../ui/primitives';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from './dialogStyles';
import { WorkModelPicker } from './WorkModelPicker';

type Props = {
  /** Close without taking any action — fired on Cancel and the
   *  backdrop. */
  onClose: () => void;
  /** Open the freshly-created thread in the detail view. The dialog
   *  fires this once createTask returns; the parent owns navigation. */
  onCreated: (threadId: string) => void;
  /** Pre-pin the new thread into a group when the dialog is opened
   *  from a group's `+ Add` button. Defaults to no group. */
  initialGroupId?: string;
  /** Active workspace's id. The repo picker is filtered to repos
   *  pinned to this workspace — picking a repo that belongs to
   *  another workspace would land the thread in the wrong scope. */
  workspaceId: string;
  /** Active workspace's display name — surfaces on the dialog chip
   *  + the "inherits X defaults" hints so a thread created from
   *  workspace X doesn't show "ByteQuay" everywhere. */
  workspaceName: string;
};

/**
 * Workspace-scoped new-thread modal per
 * docs/mockups/design/tasks/create-thread.png — a free-form prompt
 * at the top, a chip row for Add files / Reference a PR / Skills, a
 * "Plan here, steer your wild horse" trunk hint, and an
 * ADVANCED · INHERITS BYTEQUAY DEFAULTS section that exposes the
 * repo + agent picks as inline chips with the workspace defaults
 * pre-filled. The Discussion / Start-a-task picker is removed — the
 * trunk *is* the discussion altitude, and tasks materialise from the
 * first branch-worthy turn rather than an up-front choice.
 */
function NewThreadDialog({ onClose, onCreated, initialGroupId, workspaceId, workspaceName }: Props) {
  const wsLabel = workspaceName.length > 0 ? workspaceName : 'Workspace';
  const [prompt, setPrompt] = useState('');
  const [repos, setRepos] = useState<WatchedRepoDto[] | null>(null);
  const [selectedRepo, setSelectedRepo] = useState<WatchedRepoDto | null>(null);
  const [selectedModel, setSelectedModel] = useState<WorkModelDto | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openMenu, setOpenMenu] = useState<'repo' | 'agent' | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        // Pull the global watched-repo set (carries localClonePath
        // and the other rich fields) and the per-workspace pin set,
        // then intersect by repoFullName so the picker only shows
        // repos pinned to the active workspace.
        const [all, pinned] = await Promise.all([
          window.bridge.getWatchedRepos(),
          window.bridge.listWorkspaceRepos(workspaceId),
        ]);
        if (cancelled) return;
        const pinnedSet = new Set(pinned.map(p => p.repoFullName));
        const scoped = all.filter(r => pinnedSet.has(`${r.owner}/${r.repo}`));
        setRepos(scoped);
        // Pre-select the first scoped repo with a local clone path.
        // If none qualifies, the create button stays disabled and the
        // chip surfaces an inline hint.
        const withClone = scoped.find(r => r.localClonePath != null && r.localClonePath.trim() !== '');
        setSelectedRepo(withClone ?? scoped[0] ?? null);
      }
      catch {
        if (!cancelled) setRepos([]);
      }
    })();
    return () => { cancelled = true; };
  }, [workspaceId]);

  const handleSubmit = async () => {
    const trimmed = prompt.trim();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      // 0-Task thread: no workingDir, no branchName. initialPrompt
      // feeds the server's auto-title derivation but is NOT enqueued
      // as a turn — the text is context the user prepared in the
      // dialog, staged below into the trunk composer so they review
      // and press Send when ready.
      const created = await window.bridge.createTask({
        kind: selectedModel?.kind === 'API' ? 'LOGIC_LOOP' : 'CLI_AGENT',
        provider: selectedModel?.agentOrProvider ?? 'claude-code',
        model: selectedModel?.model ?? '',
        workspaceId,
        initialPrompt: trimmed === '' ? undefined : trimmed,
        initialGroupIds: initialGroupId !== undefined ? [initialGroupId] : undefined,
        workModel: selectedModel,
      });
      if (trimmed.length > 0) {
        // Hand the text to the trunk page via sessionStorage — the
        // ThreadTrunkPage reads + clears this key on mount and seeds
        // its composer with the value. sessionStorage scopes the
        // draft to this app window so a reload won't replay it.
        try {
          window.sessionStorage.setItem(`bq:trunk-draft:${created.id}`, trimmed);
        }
        catch { /* private mode / quota — composer just starts empty */ }
      }
      onCreated(created.id);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSubmitting(false);
    }
  };

  const submitDisabled = submitting || repos === null;

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
        aria-labelledby="new-thread-title"
        onClick={e => e.stopPropagation()}
      >
        <header style={dialogStyles.header}>
          <h2 id="new-thread-title" style={dialogStyles.title}>
            New Trunk Threads
            <span style={{ ...dialogStyles.workspaceChip, display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Logo initials={monogram(wsLabel).toUpperCase()} color={logoColorFor(wsLabel)} size="sm" />
              {wsLabel}
            </span>
          </h2>
          <button
            type="button"
            style={dialogStyles.closeBtn}
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </header>

        <textarea
          autoFocus
          value={prompt}
          onChange={e => setPrompt(e.target.value)}
          placeholder="What do you want to work on or think through?"
          style={dialogStyles.textarea}
        />

        <div style={dialogStyles.chipRow}>
          <button type="button" style={dialogStyles.chip} disabled>📎 Add files</button>
          <button type="button" style={dialogStyles.chip} disabled>↗ Reference a PR</button>
          <button type="button" style={dialogStyles.chip} disabled>✦ Skills</button>
        </div>

        <div style={trunkHintRowStyle}>
          <span style={trunkHintBulletStyle} aria-hidden>●</span>
          <span style={trunkHintTextStyle}>
            Plan here, steer your wild horse.
          </span>
        </div>

        <div style={advancedHeaderStyle}>
          ADVANCED <span style={advancedMutedStyle}>· INHERITS {wsLabel.toUpperCase()} DEFAULTS</span>
          <span style={advancedOverrideHintStyle}>— override if needed</span>
        </div>
        <div style={advancedRowStyle}>
          <div style={pickerWrapStyle}>
            <button
              type="button"
              style={advancedChipStyle}
              onClick={() => setOpenMenu(openMenu === 'repo' ? null : 'repo')}
              aria-haspopup="listbox"
              aria-expanded={openMenu === 'repo'}
            >
              <span style={advChipGlyphStyle('repo')} aria-hidden>●</span>
              <span style={advChipLabelStyle}>
                {selectedRepo === null
                  ? (repos === null ? 'loading repos…' : 'no watched repo')
                  : selectedRepo.repo}
              </span>
              {selectedRepo !== null && selectedRepo.owner !== '' && (
                <span style={advChipMetaStyle}>· {selectedRepo.owner}</span>
              )}
              <span style={advChipCaretStyle}>▾</span>
            </button>
            {openMenu === 'repo' && (
              <>
                <div style={pickerScrimStyle} onClick={() => setOpenMenu(null)} />
                <ul style={pickerMenuStyle} role="listbox">
                  {repos !== null && repos.length === 0 && (
                    <li style={pickerEmptyStyle}>
                      No repos pinned to this workspace. Pin one in Workspace
                      Settings → Repositories before creating a thread.
                    </li>
                  )}
                  {repos !== null && repos.map(r => {
                    const hasClone = r.localClonePath != null
                        && r.localClonePath.trim() !== '';
                    const isActive = selectedRepo?.id === r.id;
                    return (
                      <li key={r.id}>
                        <button
                          type="button"
                          onClick={() => {
                            if (!hasClone) return;
                            setSelectedRepo(r);
                            setOpenMenu(null);
                          }}
                          style={pickerItemStyle(isActive, !hasClone)}
                          disabled={!hasClone}
                          title={hasClone
                            ? `${r.owner}/${r.repo}`
                            : `${r.owner}/${r.repo} — clone it locally before using it as a thread cwd`}
                        >
                          <span style={pickerItemDotStyle('repo')} aria-hidden />
                          <span style={pickerItemTitleStyle}>{r.repo}</span>
                          <span style={pickerItemMetaStyle}>{r.owner}</span>
                          {!hasClone && (
                            <span style={pickerItemBadgeStyle}>no local clone</span>
                          )}
                        </button>
                      </li>
                    );
                  })}
                </ul>
              </>
            )}
          </div>
        </div>

        <div style={workModelSectionStyle}>
          <WorkModelPicker value={selectedModel} onChange={setSelectedModel} />
        </div>

        {error !== null && (
          <div style={errorBannerStyle}>{error}</div>
        )}

        <footer style={dialogStyles.footer}>
          <div style={dialogStyles.footerNote}>
            Lands on the trunk · inherits {wsLabel}'s memory &amp; skills
          </div>
          <div style={dialogStyles.footerButtons}>
            <button type="button" style={dialogStyles.secondaryBtn} onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              style={dialogStyles.primaryBtn}
              onClick={() => { void handleSubmit(); }}
              disabled={submitDisabled}
            >
              {submitting ? 'Starting…' : "Let's ride"} <span style={{ marginLeft: 4 }}>⏎</span>
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}

const pickerWrapStyle: React.CSSProperties = {
  position: 'relative',
  display: 'inline-block',
};

const pickerScrimStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  zIndex: 1,
  background: 'transparent',
};

const pickerMenuStyle: React.CSSProperties = {
  position: 'absolute',
  zIndex: 2,
  top: 'calc(100% + 6px)',
  left: 0,
  minWidth: 260,
  margin: 0,
  padding: 6,
  listStyle: 'none',
  background: '#fff',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 10,
  boxShadow: '0 12px 28px rgba(0,0,0,0.18)',
  maxHeight: 240,
  overflowY: 'auto',
};

function pickerItemStyle(active: boolean, disabled: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    width: '100%',
    padding: '8px 10px',
    border: 'none',
    background: active ? 'rgba(124, 58, 237, 0.10)' : 'transparent',
    color: disabled ? 'var(--ws-text-4)' : 'var(--ws-text-1)',
    fontSize: 12,
    borderRadius: 6,
    cursor: disabled ? 'not-allowed' : 'pointer',
    textAlign: 'left',
    fontFamily: 'inherit',
  };
}

const pickerEmptyStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 11,
  color: 'var(--ws-text-3)',
  fontStyle: 'italic',
};

function pickerItemDotStyle(_kind: 'repo'): React.CSSProperties {
  return {
    width: 7,
    height: 7,
    borderRadius: 999,
    background: '#7c3aed',
    flexShrink: 0,
  };
}

function pickerItemAgentGlyphStyle(enabled: boolean): React.CSSProperties {
  return {
    width: 18,
    height: 18,
    borderRadius: 999,
    background: enabled ? '#ea580c' : 'rgba(0,0,0,0.10)',
    color: enabled ? '#fff' : 'var(--ws-text-4)',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 10,
    fontWeight: 700,
    flexShrink: 0,
  };
}

const pickerItemTitleStyle: React.CSSProperties = {
  flex: 1,
  fontWeight: 600,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const pickerItemMetaStyle: React.CSSProperties = {
  color: 'var(--ws-text-3)',
  fontSize: 11,
  flexShrink: 0,
};

const pickerItemBadgeStyle: React.CSSProperties = {
  fontSize: 9,
  letterSpacing: '0.04em',
  padding: '1px 6px',
  background: 'rgba(0,0,0,0.06)',
  borderRadius: 999,
  color: 'var(--ws-text-4)',
  fontWeight: 600,
  flexShrink: 0,
};

const errorBannerStyle: React.CSSProperties = {
  marginTop: 10,
  padding: '8px 12px',
  fontSize: 11,
  color: '#991b1b',
  background: '#fee2e2',
  border: '1px solid #fecaca',
  borderRadius: 8,
};

const trunkHintRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  marginTop: 14,
  fontSize: 11,
  color: 'var(--ws-text-3)',
  lineHeight: 1.55,
};

const trunkHintBulletStyle: React.CSSProperties = {
  color: '#7c3aed',
  fontSize: 10,
  marginTop: 2,
  flexShrink: 0,
};

const trunkHintTextStyle: React.CSSProperties = {
  flex: 1,
};

const advancedHeaderStyle: React.CSSProperties = {
  marginTop: 14,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--ws-text-2)',
};

const advancedMutedStyle: React.CSSProperties = {
  fontWeight: 500,
  color: 'var(--ws-text-3)',
};

const advancedOverrideHintStyle: React.CSSProperties = {
  marginLeft: 8,
  fontWeight: 500,
  color: 'var(--ws-text-4)',
  letterSpacing: '0.02em',
  textTransform: 'none',
  fontStyle: 'italic',
};

const advancedRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  marginTop: 8,
  flexWrap: 'wrap',
};

// WorkModelPicker renders its own header row, current-pick card, and
// full agent list — a block-level panel, not a small chip like the
// repo picker above. It needs the dialog's full width, so it gets its
// own row instead of squeezing into advancedRowStyle's inline-block
// flex slot (where it rendered squashed to content width).
const workModelSectionStyle: React.CSSProperties = {
  marginTop: 14,
};

const advancedChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 12px',
  border: '1px solid var(--ws-card-border)',
  background: 'rgba(255,255,255,0.86)',
  borderRadius: 10,
  fontSize: 12,
  color: 'var(--ws-text-1)',
  cursor: 'pointer',
};

function advChipGlyphStyle(kind: 'repo' | 'agent'): React.CSSProperties {
  if (kind === 'agent') {
    return {
      width: 18,
      height: 18,
      borderRadius: 999,
      background: '#ea580c',
      color: '#fff',
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: 11,
      fontWeight: 700,
      flexShrink: 0,
    };
  }
  return {
    color: '#7c3aed',
    fontSize: 10,
    flexShrink: 0,
  };
}

const advChipLabelStyle: React.CSSProperties = {
  fontWeight: 600,
};

const advChipMetaStyle: React.CSSProperties = {
  color: 'var(--ws-text-3)',
  fontSize: 11,
};

const advChipCaretStyle: React.CSSProperties = {
  color: 'var(--ws-text-4)',
  fontSize: 10,
  marginLeft: 4,
};

export default NewThreadDialog;
