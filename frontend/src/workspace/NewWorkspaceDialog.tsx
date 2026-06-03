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

/** New-workspace modal. UI-first; the Create button is disabled
 *  while the app is single-workspace only — the backend
 *  WorkspaceStore today supports list / find but no create endpoint
 *  the dialog can route to, so wiring is gated on the multi-
 *  workspace migration. The form structure lives here now so when
 *  the backend lands, the only change is flipping the button on +
 *  forwarding the payload through a new bridge method. */
/** Word cap on the optional prompt-context box that gets appended
 *  to WORKSPACE.md on creation. Small on purpose — it's a per-
 *  workspace nudge, not the workspace brain itself. */
const PROMPT_CONTEXT_WORD_CAP = 100;
const CODING_AGENT_OPTIONS = [
  { value: 'claude-code', label: 'Claude Code' },
  { value: 'codex', label: 'Codex' },
  { value: 'deepseek', label: 'DeepSeek API' },
] as const;

/** Frontend mirror of the backend's WorkspaceService.deriveSlug.
 *  Keep the two in sync — divergence would mean the live preview in
 *  the dialog disagrees with the slug the backend actually allocates. */
const SLUG_MAX_CHARS = 24;
function deriveSlug(name: string): string {
  if (!name) return '';
  const lowered = name.toLowerCase();
  let out = '';
  let lastDash = true;
  for (const c of lowered) {
    if (/[a-z0-9]/.test(c)) {
      out += c;
      lastDash = false;
    }
    else if (!lastDash) {
      out += '-';
      lastDash = true;
    }
  }
  while (out.endsWith('-')) out = out.slice(0, -1);
  if (out.length > SLUG_MAX_CHARS) {
    out = out.slice(0, SLUG_MAX_CHARS);
    while (out.endsWith('-')) out = out.slice(0, -1);
  }
  return out;
}

function NewWorkspaceDialog({ onClose, onCreated }: Props) {
  const [name, setName] = useState('');
  /** User-edited slug. Empty means "follow the name" — the input
   *  shows the live-derived value but doesn't write back to state
   *  until the user actually types here. Once they do, slugTouched
   *  flips and editing the name no longer overrides their choice. */
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [repos, setRepos] = useState<WatchedRepoDto[]>([]);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [seedMemory, setSeedMemory] = useState(true);
  const [codingAgent, setCodingAgent] = useState<string>(CODING_AGENT_OPTIONS[0].value);
  // Empty string = "use the default". Non-empty = the user's
  // override. We never auto-seed the textarea so the user's choice
  // not to type stays a choice, not a hidden side-effect.
  const [promptContext, setPromptContext] = useState<string>('');
  const [promptExpanded, setPromptExpanded] = useState<boolean>(false);
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
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  // The default prompt is computed from the typed name + picked
  // repos. It's never written into the textarea — it shows up as
  // the placeholder (when expanded) and the collapsed preview, so
  // an empty textarea unambiguously means "use the default".
  const pickedNames = repos
      .filter(r => picked.has(r.id))
      .map(r => r.repo);
  const defaultPrompt = buildDefaultPrompt(name, pickedNames);
  // The string that actually appends to WORKSPACE.md on create:
  // user override if they typed one, otherwise the live default.
  const effectivePrompt = promptContext.trim().length > 0
      ? promptContext
      : defaultPrompt;
  const usingDefault = promptContext.trim().length === 0;
  const promptWordCount = countWords(effectivePrompt);
  const promptOverCap = promptWordCount > PROMPT_CONTEXT_WORD_CAP;

  const trimmedName = name.trim();
  // Effective slug shown in the field and sent to the backend. Follow
  // the derived slug from the name until the user types in the slug
  // field themselves — that flips slugTouched and the input becomes
  // their override. Empty/unsluggable names produce an empty slug; the
  // backend falls back to a UUID stub in that case.
  const effectiveSlug = slugTouched ? slug.trim() : deriveSlug(trimmedName);
  const canCreate = trimmedName.length > 0
      && picked.size > 0
      && !promptOverCap
      && !submitting;

  const onSubmit = async () => {
    if (!canCreate) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const pickedRepoFullNames = repos
          .filter(r => picked.has(r.id))
          .map(r => `${r.owner}/${r.repo}`);
      const created = await window.bridge.createWorkspace({
        name: trimmedName,
        slug: effectiveSlug,
        isScratch: false,
        promptContext: effectivePrompt,
        repoFullNames: pickedRepoFullNames,
      });
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
            Slug
            <span style={pickHintStyle}>
              immutable id segment · derived from name unless overridden
            </span>
          </label>
          <div style={slugRowStyle}>
            <span style={slugPrefixStyle} aria-hidden>ws-</span>
            <input
              type="text"
              value={effectiveSlug}
              onChange={e => {
                setSlugTouched(true);
                setSlug(e.target.value);
              }}
              placeholder="bytequay"
              spellCheck={false}
              style={{ ...dialogStyles.input, flex: 1, fontFamily: 'ui-monospace, monospace' }}
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

        <div style={dialogStyles.field}>
          <label style={dialogStyles.fieldLabel}>Default coding agent</label>
          <select
            style={dialogStyles.input}
            value={codingAgent}
            onChange={e => setCodingAgent(e.target.value)}
          >
            {CODING_AGENT_OPTIONS.map(opt => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </div>

        <div style={dialogStyles.field}>
          <button
            type="button"
            onClick={() => setPromptExpanded(v => !v)}
            style={promptDisclosureStyle}
            aria-expanded={promptExpanded}
          >
            <span style={promptDisclosureChevronStyle(promptExpanded)} aria-hidden>▸</span>
            <span style={promptDisclosureLabelStyle}>Prompt context</span>
            <span style={promptDisclosureMetaStyle}>
              optional ·{' '}
              {promptExpanded && !usingDefault && (
                <span style={promptCounterStyle(promptOverCap)}>
                  {promptWordCount}/{PROMPT_CONTEXT_WORD_CAP} words ·{' '}
                </span>
              )}
              appended to <code style={inlineCodeStyle}>WORKSPACE.md</code>
            </span>
          </button>
          {promptExpanded && (
            <>
              <textarea
                value={promptContext}
                onChange={e => setPromptContext(e.target.value)}
                placeholder={defaultPrompt}
                rows={4}
                style={{ ...dialogStyles.textarea, marginTop: 8 }}
              />
              <div style={promptHelperRowStyle}>
                <span style={mutedStyle}>
                  Leave empty to use the default shown above. Typing
                  overrides it.
                </span>
                {!usingDefault && (
                  <button
                    type="button"
                    onClick={() => setPromptContext('')}
                    style={promptResetBtnStyle}
                  >
                    Clear (use default)
                  </button>
                )}
              </div>
            </>
          )}
        </div>

        {submitError !== null && (
          <div style={errorStyle} role="alert">{submitError}</div>
        )}

        <footer style={dialogStyles.footer}>
          <div style={dialogStyles.footerNote}>
            Workspaces are deliberate &amp; long-lived — you'll have only a
            handful. One-offs can stay in the scratch workspace.
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
                : trimmedName.length === 0
                  ? 'Name is required'
                  : picked.size === 0
                    ? 'Pick at least one repo'
                    : promptOverCap
                      ? `Prompt context must be ≤${PROMPT_CONTEXT_WORD_CAP} words`
                      : undefined}
            >
              ◆ {submitting ? 'Creating…' : 'Create workspace'}
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
