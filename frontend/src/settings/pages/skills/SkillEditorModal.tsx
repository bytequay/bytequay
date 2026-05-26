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
import type { SkillDto, SkillInput } from '../../../types';

/** UI-side scope picker — three buckets the user thinks in. Maps onto the
 *  backend's (scope, roleTag) pair: 'global' / 'role' both store scope =
 *  'global' on the row, with roleTag null vs. set. */
export type ScopeBucket = 'global' | 'repos' | 'role';

type Kind = 'library' | 'persona' | 'rubric';

type Mode = 'manual' | 'draft';

export type SkillEditorModalProps = {
  onClose: () => void;
  onSave: (input: SkillInput) => Promise<void>;
  /** Locked scope passed in from the active SkillsPage tab so the
   *  modal opens with the right radio + form layout. */
  initialScope: ScopeBucket;
  /** Pre-populated repo slug when the user opened the modal from a
   *  specific Repos group. */
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
  onClose, onSave, initialScope, initialRepo, existing,
}: SkillEditorModalProps) {
  const [mode, setMode] = useState<Mode>('manual');

  const [scope, setScope] = useState<ScopeBucket>(() => {
    if (existing !== undefined) return classify(existing);
    return initialScope;
  });
  const [name, setName] = useState(existing?.name ?? '');
  const [repo, setRepo] = useState<string>(existing?.repo ?? initialRepo ?? '');
  const [role, setRole] = useState<string>(existing?.roleTag ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [body, setBody] = useState(existing?.body ?? '');
  const [kind, setKind] = useState<Kind>(existing?.kind ?? defaultKindFor(initialScope));
  const [isDefault, setIsDefault] = useState(existing?.isDefault ?? false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [draftPrompt, setDraftPrompt] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [draftError, setDraftError] = useState<string | null>(null);

  const [autoFocusKey, setAutoFocusKey] = useState(0);
  useEffect(() => { setAutoFocusKey(k => k + 1); }, [mode]);

  useEffect(() => {
    if (existing !== undefined) return;
    setKind(defaultKindFor(scope));
  }, [scope, existing]);

  const validate = (): string | null => {
    if (name.trim() === '') return 'Skill name is required.';
    if (scope === 'repos' && repo.trim() === '') return 'Repo is required for a per-repo skill.';
    if (scope === 'role' && role.trim() === '') return 'Role is required for a role skill.';
    return null;
  };

  const handleSave = async () => {
    const message = validate();
    if (message !== null) { setError(message); return; }
    setSaving(true);
    setError(null);
    try {
      await onSave(toPayload({
        scope, repo: repo.trim(), role: role.trim(),
        name: name.trim(), description: description.trim(),
        body: body.trim(), kind, isDefault,
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
            {(['global', 'repos', 'role'] as const).map(s => (
              <button
                key={s}
                type="button"
                style={segmentBtnStyle(scope === s)}
                onClick={() => setScope(s)}
              >
                {s === 'global' ? 'Global' : s === 'repos' ? 'Per-repo' : 'Per-role'}
              </button>
            ))}
          </div>
          <p style={hintStyle}>
            Global = available to every workspace + agent.
            Per-repo = loaded when the agent's cwd matches the repo
            below. Per-role = always-on identity for an agent role
            (trunk / task / reviewer / lead).
          </p>
        </div>

        {scope === 'repos' && (
          <div style={fieldStyle}>
            <label style={labelStyle}>Repo (owner/name)</label>
            <input
              key={`repo-${autoFocusKey}`}
              style={inputStyle}
              type="text"
              value={repo}
              onChange={e => setRepo(e.target.value)}
              placeholder="e.g. chenjian2664/ByteQuay"
              disabled={existing !== undefined}
            />
          </div>
        )}

        {scope === 'role' && (
          <div style={fieldStyle}>
            <label style={labelStyle}>Role</label>
            <select
              style={selectStyle}
              value={role}
              onChange={e => setRole(e.target.value)}
              disabled={existing !== undefined}
            >
              <option value="">Pick a role…</option>
              <option value="reviewer">reviewer</option>
              <option value="reviewee">reviewee</option>
              <option value="scheduler">scheduler (task)</option>
              <option value="trunk">trunk (planning)</option>
            </select>
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
              <label style={labelStyle}>Skill name</label>
              <input
                key={`name-${autoFocusKey}`}
                style={inputStyle}
                type="text"
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="e.g. Backend uses Java 25"
                autoFocus
              />
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
              <label style={labelStyle}>Kind</label>
              <div style={segmentRowStyle}>
                {(['library', 'persona', 'rubric'] as const).map(k => (
                  <button
                    key={k}
                    type="button"
                    style={segmentBtnStyle(kind === k)}
                    onClick={() => setKind(k)}
                  >
                    {k === 'library' ? 'Library' : k === 'persona' ? 'Persona' : 'Rubric'}
                  </button>
                ))}
              </div>
              <p style={hintStyle}>
                Library = the model picks it up via list_skills /
                load_skill. Persona = always-on identity for a role.
                Rubric = deterministic review-time rule the review
                path always applies.
              </p>
            </div>

            <div style={fieldStyle}>
              <label style={labelStyle}>Body</label>
              <textarea
                style={textareaStyle}
                rows={10}
                value={body}
                onChange={e => setBody(e.target.value)}
                placeholder="The actual instructions the agent loads when the trigger fires."
              />
            </div>

            <div style={fieldStyle}>
              <label style={checkboxLabelStyle}>
                <input
                  type="checkbox"
                  checked={isDefault}
                  onChange={e => setIsDefault(e.target.checked)}
                />
                <span>Default for this scope</span>
              </label>
              <p style={hintStyle}>
                When several rows match the same (scope, repo, kind, role),
                the default-marked row wins. Useful when you keep an
                "off-the-shelf" persona around but want a custom one in
                front of it.
              </p>
            </div>

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

export function classify(row: { scope: string; roleTag: string | null }): ScopeBucket {
  if (row.scope === 'repo') return 'repos';
  if (row.roleTag !== null && row.roleTag !== '') return 'role';
  return 'global';
}

function defaultKindFor(scope: ScopeBucket): Kind {
  if (scope === 'role') return 'persona';
  if (scope === 'repos') return 'rubric';
  return 'library';
}

function toPayload(state: {
  scope: ScopeBucket; repo: string; role: string;
  name: string; description: string; body: string;
  kind: Kind; isDefault: boolean;
}): SkillInput {
  if (state.scope === 'repos') {
    return {
      scope: 'repo',
      repo: state.repo,
      threadId: null,
      name: state.name,
      description: state.description,
      body: state.body,
      kind: state.kind,
      roleTag: null,
      isDefault: state.isDefault,
    };
  }
  return {
    scope: 'global',
    repo: null,
    threadId: null,
    name: state.name,
    description: state.description,
    body: state.body,
    kind: state.kind,
    roleTag: state.scope === 'role' ? state.role : null,
    isDefault: state.isDefault,
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
    background: active ? 'rgba(124, 58, 237, 0.10)' : '#fff',
    color: active ? '#5b21b6' : 'var(--text-2)',
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

const selectStyle: React.CSSProperties = {
  ...inputStyle,
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
    background: active ? 'rgba(124, 58, 237, 0.10)' : '#fff',
    color: active ? '#5b21b6' : 'var(--text-2)',
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
  border: '1px dashed rgba(124, 58, 237, 0.30)',
  borderRadius: 8,
  background: 'rgba(124, 58, 237, 0.04)',
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
  background: 'linear-gradient(135deg, #7c3aed, #6366f1)',
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
