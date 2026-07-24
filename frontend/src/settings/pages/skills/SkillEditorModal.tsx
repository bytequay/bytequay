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
import type { SkillDto, SkillInput, WatchedRepoDto } from '../../../types';

/** UI-side scope picker. Maps onto the backend's scope column: 'global'
 *  or 'repo'. (Role is no longer a skill axis — applicability is derived
 *  from usage.) */
export type ScopeBucket = 'global' | 'repos';

type Kind = 'library' | 'persona' | 'rubric';

type Mode = 'manual' | 'draft';

export type SkillEditorModalProps = {
  onClose: () => void;
  onSave: (input: SkillInput) => Promise<void>;
  /** Locked scope passed in from the active SkillsPage sub-nav so the
   *  modal opens with the right radio + form layout. */
  initialScope: ScopeBucket;
  /** Which surface the new skill serves — set by the Development /
   *  Review branch the modal was opened from. Not user-editable here. */
  initialUsage: 'build' | 'review';
  /** Pre-populated repo slug when the user opened the modal from a
   *  specific repo group. */
  initialRepo?: string;
  /** Edit-mode existing row, or undefined for Add. */
  existing?: SkillDto;
};

/**
 * Add / Edit modal for a skill, with two modes:
 * - Write manually: the same fields the legacy editor had, restyled and
 *   labelled to make the trigger nature of the description clear.
 * - Draft with AI: a single prompt textarea + scope picker; the
 *   draftSkill endpoint proposes name + trigger + body. The proposal
 *   lands in the manual fields for review-and-edit; nothing is saved
 *   until the user presses Create.
 */
function SkillEditorModal({
  onClose, onSave, initialScope, initialUsage, initialRepo, existing,
}: SkillEditorModalProps) {
  const [mode, setMode] = useState<Mode>('manual');

  // Surface (development / review) is fixed by the branch the modal
  // opened from — not editable here. It drives the default kind.
  const usage: 'build' | 'review' = existing?.usage ?? initialUsage;
  const isReview = usage === 'review';

  const [scope, setScope] = useState<ScopeBucket>(() => {
    if (existing !== undefined) return classify(existing);
    return initialScope;
  });
  const [name, setName] = useState(existing?.name ?? '');
  const [repo, setRepo] = useState<string>(existing?.repo ?? initialRepo ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [body, setBody] = useState(existing?.body ?? '');
  const [kind] = useState<Kind>(existing?.kind ?? (isReview ? 'rubric' : 'library'));
  const [isDefault, setIsDefault] = useState(existing?.isDefault ?? false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [draftPrompt, setDraftPrompt] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [draftError, setDraftError] = useState<string | null>(null);
  /** When the user lands a /skills/draft proposal into the manual
   *  fields and then hits Save, we tag the new row source='ai_drafted'
   *  and stash the prompt as provenance. Cleared when the user types
   *  manually so a heavily-edited draft still saves as authored. */
  const [draftedFromPrompt, setDraftedFromPrompt] = useState<string | null>(null);

  const [autoFocusKey, setAutoFocusKey] = useState(0);
  useEffect(() => { setAutoFocusKey(k => k + 1); }, [mode]);

  // Per-repo scope picks from the watched-repo list rather than a
  // free-typed slug. An existing skill's repo is kept selectable even if
  // it's no longer watched, so editing never silently drops it.
  const [watchedRepos, setWatchedRepos] = useState<WatchedRepoDto[]>([]);
  useEffect(() => {
    let cancelled = false;
    window.bridge.getWatchedRepos()
      .then(rs => { if (!cancelled) setWatchedRepos(rs); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);
  const repoOptions = (() => {
    const slugs = watchedRepos.map(r => `${r.owner}/${r.repo}`).sort();
    // Keep a preselected repo (editing an existing row, or adding from a
    // specific repo group) selectable even if it isn't currently watched.
    const preset = existing?.repo ?? (repo !== '' ? repo : null);
    if (preset !== null && !slugs.includes(preset)) slugs.unshift(preset);
    return slugs;
  })();

  const validate = (): string | null => {
    if (name.trim() === '') return 'Skill name is required.';
    if (scope === 'repos' && repo.trim() === '') return 'Repo is required for a per-repo skill.';
    return null;
  };

  const handleSave = async () => {
    const message = validate();
    if (message !== null) { setError(message); return; }
    setSaving(true);
    setError(null);
    try {
      await onSave(toPayload({
        scope, repo: repo.trim(),
        name: name.trim(), description: description.trim(),
        body: body.trim(), kind, usage, isDefault,
        draftedFromPrompt,
      }));
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  const handleDraft = async () => {
    if (draftPrompt.trim() === '') {
      setDraftError('Describe the skill so the model has something to work with.');
      return;
    }
    setDrafting(true);
    setDraftError(null);
    try {
      const draft = await window.bridge.draftSkill(draftPrompt.trim(), scope);
      setName(draft.name);
      setDescription(draft.description);
      setBody(draft.body);
      setDraftedFromPrompt(draftPrompt.trim());
      setMode('manual');
    }
    catch (e) {
      setDraftError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setDrafting(false);
    }
  };

  return (
    <div style={scrimStyle} role="presentation" onClick={onClose}>
      <div
        style={dialogStyle}
        role="dialog"
        aria-modal="true"
        aria-label={existing ? 'Edit skill' : 'Add skill'}
        onClick={e => e.stopPropagation()}
      >
        <header style={headerStyle}>
          <h2 style={titleStyle}>{existing ? 'Edit skill' : 'New skill'}</h2>
          <button type="button" style={closeBtnStyle} onClick={onClose} aria-label="Close">✕</button>
        </header>

        {!existing && (
          <div style={modeRowStyle}>
            <button
              type="button"
              style={modeBtnStyle(mode === 'manual')}
              onClick={() => setMode('manual')}
            >
              Write manually
            </button>
            <button
              type="button"
              style={modeBtnStyle(mode === 'draft')}
              onClick={() => setMode('draft')}
            >
              ✨ Draft with AI
            </button>
          </div>
        )}

        <div style={fieldStyle}>
          <label style={labelStyle}>Scope</label>
          <div style={segmentRowStyle}>
            {(['global', 'repos'] as const).map(s => (
              <button
                key={s}
                type="button"
                style={segmentBtnStyle(scope === s)}
                onClick={() => setScope(s)}
              >
                {s === 'global' ? (isReview ? 'All repos' : 'Global') : 'Per-repo'}
              </button>
            ))}
          </div>
          <p style={hintStyle}>
            {isReview
              ? 'All repos = selectable on any PR review. Per-repo = offered only on PRs in the repo below.'
              : 'Global = available to every workspace + agent. Per-repo = loaded when the agent’s cwd matches the repo below.'}
          </p>
        </div>

        {scope === 'repos' && (
          <div style={fieldStyle}>
            <label style={labelStyle}>Repo</label>
            {repoOptions.length === 0 ? (
              <p style={hintStyle}>
                No watched repos yet — add one in Settings → Watched repos first.
              </p>
            ) : (
              <select
                style={inputStyle}
                value={repo}
                onChange={e => setRepo(e.target.value)}
                disabled={existing !== undefined}
              >
                <option value="">Pick a repo…</option>
                {repoOptions.map(slug => (
                  <option key={slug} value={slug}>{slug}</option>
                ))}
              </select>
            )}
          </div>
        )}

        {mode === 'draft' && !existing ? (
          <>
            <div style={fieldStyle}>
              <label style={labelStyle}>Describe the skill</label>
              <textarea
                key={`prompt-${autoFocusKey}`}
                style={textareaStyle}
                rows={4}
                value={draftPrompt}
                onChange={e => setDraftPrompt(e.target.value)}
                placeholder={'Describe the skill in your own words, or paste the instructions you find yourself repeating.\n\ne.g. "When reviewing a PR that touches authentication, remind me to check for token expiry handling and rate limit propagation."'}
                autoFocus
              />
            </div>
            {draftError !== null && <p style={errorStyle}>{draftError}</p>}
            <div style={draftHintStyle}>
              The active LLM provider drafts a name + trigger + body
              from your prompt. You'll be able to review and edit
              everything before saving.
            </div>
            <footer style={footerStyle}>
              <div style={{ flex: 1 }} />
              <button type="button" style={secondaryBtnStyle} onClick={onClose} disabled={drafting}>
                Cancel
              </button>
              <button
                type="button"
                style={drafting ? primaryBtnDisabledStyle : primaryBtnStyle}
                onClick={() => { void handleDraft(); }}
                disabled={drafting}
              >
                {drafting ? 'Drafting…' : 'Draft it →'}
              </button>
            </footer>
          </>
        ) : (
          <>
            <div style={fieldStyle}>
              <label style={labelStyle}>
                {isReview ? 'Voice name' : 'Skill name'}
                {isReview && <span style={nameChipStyle}>@mention identity</span>}
              </label>
              <input
                key={`name-${autoFocusKey}`}
                style={inputStyle}
                type="text"
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder={isReview ? 'e.g. Concurrency Hawk' : 'e.g. Backend uses Java 25'}
                autoFocus
              />
              {isReview && (
                <p style={hintStyle}>
                  This name is how the Lead @mentions this reviewer in the panel
                  transcript — make it short and distinct.
                </p>
              )}
            </div>

            <div style={fieldStyle}>
              <label style={labelStyle}>The trigger</label>
              <input
                style={inputStyle}
                type="text"
                value={description}
                onChange={e => setDescription(e.target.value)}
                placeholder="loads when … (what the agent matches on)"
              />
              <p style={hintStyle}>
                Skills are <strong>model-triggered</strong>, not always-on.
                Phrase this as a condition the agent reads to decide
                whether to load the body — e.g. "loads when reviewing
                a PR that touches authentication code."
              </p>
            </div>

            <div style={fieldStyle}>
              <label style={labelStyle}>
                Body
                {isReview && (
                  <button
                    type="button"
                    style={tokenBtnStyle}
                    onClick={() => setBody(b => (b.endsWith('\n') || b === '' ? b : b + '\n') + '{{pr_summary}}')}
                    title="Insert the PR-summary placeholder"
                  >
                    + {'{{pr_summary}}'}
                  </button>
                )}
              </label>
              <textarea
                style={textareaStyle}
                rows={10}
                value={body}
                onChange={e => setBody(e.target.value)}
                placeholder={isReview
                  ? 'How this reviewer should think. Drop {{pr_summary}} where you want the PR title + description injected at review time.'
                  : 'The actual instructions the agent loads when the trigger fires.'}
              />
              {isReview && (
                <p style={hintStyle}>
                  <code>{'{{pr_summary}}'}</code> is replaced with the PR&apos;s title +
                  description when the Lead dispatches this reviewer, so the body becomes a
                  complete, PR-specific prompt.
                </p>
              )}
            </div>

            {isReview && (
              <div style={fieldStyle}>
                <label style={checkboxLabelStyle}>
                  <input
                    type="checkbox"
                    checked={isDefault}
                    onChange={e => setIsDefault(e.target.checked)}
                  />
                  <span>Default review voice for this scope</span>
                </label>
                <p style={hintStyle}>
                  The ★ default is auto-seated when a review starts without a
                  hand-picked panel. At most one default per repo.
                </p>
              </div>
            )}

            {error !== null && <p style={errorStyle}>{error}</p>}

            <footer style={footerStyle}>
              <div style={{ flex: 1 }} />
              <button type="button" style={secondaryBtnStyle} onClick={onClose} disabled={saving}>
                Cancel
              </button>
              <button
                type="button"
                style={saving ? primaryBtnDisabledStyle : primaryBtnStyle}
                onClick={() => { void handleSave(); }}
                disabled={saving}
              >
                {saving ? 'Saving…' : existing ? 'Save' : 'Create skill'}
              </button>
            </footer>
          </>
        )}
      </div>
    </div>
  );
}

export function classify(row: { scope: string }): ScopeBucket {
  return row.scope === 'repo' ? 'repos' : 'global';
}

function toPayload(state: {
  scope: ScopeBucket; repo: string;
  name: string; description: string; body: string;
  kind: Kind; usage: 'build' | 'review'; isDefault: boolean;
  draftedFromPrompt: string | null;
}): SkillInput {
  const sourceFields = state.draftedFromPrompt === null
      ? {}
      : { source: 'ai_drafted' as const, provenance: state.draftedFromPrompt };
  const repoScoped = state.scope === 'repos';
  return {
    scope: repoScoped ? 'repo' : 'global',
    repo: repoScoped ? state.repo : null,
    threadId: null,
    name: state.name,
    description: state.description,
    body: state.body,
    kind: state.kind,
    usage: state.usage,
    roleTag: null,
    isDefault: state.isDefault,
    ...sourceFields,
  };
}

const scrimStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(31, 27, 46, 0.20)',
  backdropFilter: 'blur(4px)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 100,
};

const dialogStyle: React.CSSProperties = {
  width: 560,
  maxWidth: 'calc(100vw - 40px)',
  maxHeight: 'calc(100vh - 60px)',
  overflowY: 'auto',
  background: '#fff',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 14,
  boxShadow: '0 18px 60px rgba(67, 56, 202, 0.25), 0 4px 12px rgba(0,0,0,0.08)',
  padding: '18px 20px',
  color: 'var(--text-1)',
};

const headerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  marginBottom: 12,
};

const titleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 16,
  fontWeight: 700,
};

const closeBtnStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--text-3)',
  cursor: 'pointer',
  fontSize: 14,
  padding: 4,
};

const modeRowStyle: React.CSSProperties = {
  display: 'inline-flex',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  overflow: 'hidden',
  marginBottom: 12,
};

function modeBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '6px 14px',
    fontSize: 12,
    fontWeight: active ? 600 : 500,
    border: 'none',
    background: active ? 'var(--accent-a10)' : '#fff',
    color: active ? 'var(--accent-deep)' : 'var(--text-2)',
    cursor: 'pointer',
  };
}

const fieldStyle: React.CSSProperties = {
  marginBottom: 12,
};

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 10,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
  marginBottom: 4,
};

const nameChipStyle: React.CSSProperties = {
  marginLeft: 8,
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--accent-deep)',
  background: 'var(--accent-a10)',
  borderRadius: 999,
  padding: '1px 7px',
};

const tokenBtnStyle: React.CSSProperties = {
  marginLeft: 8,
  fontSize: 10,
  fontWeight: 600,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  color: 'var(--accent-deep)',
  background: 'var(--accent-a10)',
  border: '1px solid var(--accent-border)',
  borderRadius: 6,
  padding: '1px 6px',
  cursor: 'pointer',
  textTransform: 'none',
  letterSpacing: 0,
};

const checkboxLabelStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 12,
  color: 'var(--text-2)',
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  fontSize: 13,
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  background: '#fff',
  color: 'var(--text-1)',
  boxSizing: 'border-box',
  outline: 'none',
};

const textareaStyle: React.CSSProperties = {
  ...inputStyle,
  minHeight: 72,
  resize: 'vertical',
  fontFamily: 'inherit',
};

const segmentRowStyle: React.CSSProperties = {
  display: 'inline-flex',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  overflow: 'hidden',
  background: '#fff',
};

function segmentBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '6px 12px',
    fontSize: 12,
    fontWeight: active ? 600 : 500,
    border: 'none',
    background: active ? 'var(--accent-a10)' : '#fff',
    color: active ? 'var(--accent-deep)' : 'var(--text-2)',
    cursor: 'pointer',
  };
}

const hintStyle: React.CSSProperties = {
  margin: '4px 0 0',
  fontSize: 11,
  color: 'var(--text-3)',
};

const draftHintStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: '10px 12px',
  fontSize: 11,
  border: '1px dashed var(--accent-border)',
  borderRadius: 8,
  background: 'var(--accent-a4)',
  color: 'var(--text-2)',
};

const errorStyle: React.CSSProperties = {
  margin: '0 0 12px',
  color: '#cf1322',
  fontSize: 12,
};

const footerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  paddingTop: 10,
  borderTop: '1px solid rgba(0,0,0,0.06)',
};

const primaryBtnStyle: React.CSSProperties = {
  padding: '7px 14px',
  fontSize: 12,
  fontWeight: 600,
  border: 'none',
  borderRadius: 8,
  background: 'var(--accent)',
  color: '#fff',
  cursor: 'pointer',
};

const primaryBtnDisabledStyle: React.CSSProperties = {
  ...primaryBtnStyle,
  opacity: 0.6,
  cursor: 'not-allowed',
};

const secondaryBtnStyle: React.CSSProperties = {
  padding: '7px 14px',
  fontSize: 12,
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  background: '#fff',
  color: 'var(--text-2)',
  cursor: 'pointer',
};

export default SkillEditorModal;
