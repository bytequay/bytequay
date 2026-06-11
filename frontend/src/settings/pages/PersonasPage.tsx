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
import type { PersonaRequest, ReviewerPersonaDto } from '../../types';

type Role = 'LEAD' | 'REVIEWER';

/** Manages the user-defined reviewer persona library. Each persona is
 *  a (name, prompt, role) bundle the Start Review dialog picks from to
 *  seat a panel for a given PR. Roles:
 *    - LEAD: drafts consensus, gets final say
 *    - REVIEWER: contributes findings, doesn't draft consensus
 *  The Start Review dialog picks the LLM provider per pass — personas
 *  themselves are provider-agnostic. */
export default function PersonasPage() {
  const [personas, setPersonas] = useState<ReviewerPersonaDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<PersonaRequest>(EMPTY);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    try {
      const rows = await window.bridge.listPersonas();
      setPersonas(rows);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const beginCreate = () => { setEditingId('__new__'); setDraft(EMPTY); };
  const beginEdit = (p: ReviewerPersonaDto) => {
    setEditingId(p.id);
    setDraft({ name: p.name, systemPrompt: p.systemPrompt, role: p.role });
  };
  const cancel = () => { setEditingId(null); setDraft(EMPTY); };

  const trimmedName = draft.name.trim();
  const trimmedPrompt = draft.systemPrompt.trim();
  const canSubmit = trimmedName.length > 0 && trimmedPrompt.length > 0 && !submitting;

  const handleSave = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    const payload: PersonaRequest = {
      name: trimmedName,
      systemPrompt: trimmedPrompt,
      role: draft.role,
    };
    try {
      if (editingId === '__new__') {
        await window.bridge.createPersona(payload);
      } else if (editingId !== null) {
        await window.bridge.updatePersona(editingId, payload);
      }
      cancel();
      await load();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: string) => {
    setError(null);
    try {
      await window.bridge.deletePersona(id);
      if (editingId === id) cancel();
      await load();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <section className="settings-page" aria-labelledby="personas-heading">
      <header className="settings-page__head">
        <h2 id="personas-heading">Reviewer personas</h2>
        <p className="settings-page__lede">
          A persona is a name + system prompt + role. Each review pass
          picks K personas from this library to seat its panel; the LLM
          provider that serves each persona is chosen per pass in the
          Start Review dialog. LEAD drafts the consensus; REVIEWER
          contributes findings only.
        </p>
      </header>

      {error !== null && (
        <div role="alert" style={errorBoxStyle}>{error}</div>
      )}

      <div style={listStyle}>
        {personas === null && <p style={dimStyle}>Loading…</p>}
        {personas !== null && personas.length === 0 && (
          <p style={dimStyle}>No personas yet. Add one below to start
            reviewing with your own reviewing voices.</p>
        )}
        {personas !== null && personas.map(p => (
          <article key={p.id} style={cardStyle}>
            <div style={cardHeadStyle}>
              <strong style={nameStyle}>{p.name}</strong>
              <span style={p.role === 'LEAD' ? leadBadgeStyle : reviewerBadgeStyle}>
                {p.role}
              </span>
              <span style={spacerStyle} />
              <button type="button" style={linkBtnStyle} onClick={() => beginEdit(p)}>
                Edit
              </button>
              <button
                type="button"
                style={linkBtnDangerStyle}
                onClick={() => { void handleDelete(p.id); }}
              >
                Delete
              </button>
            </div>
            <p style={promptPreviewStyle}>
              {p.systemPrompt.length > 220
                ? `${p.systemPrompt.slice(0, 220)}…`
                : p.systemPrompt}
            </p>
          </article>
        ))}
      </div>

      <div style={editorWrapStyle}>
        {editingId === null ? (
          <button type="button" style={primaryBtnStyle} onClick={beginCreate}>
            + New persona
          </button>
        ) : (
          <PersonaForm
            draft={draft}
            isNew={editingId === '__new__'}
            disabled={submitting}
            canSubmit={canSubmit}
            onChange={setDraft}
            onCancel={cancel}
            onSubmit={() => { void handleSave(); }}
          />
        )}
      </div>
    </section>
  );
}

const EMPTY: PersonaRequest = { name: '', systemPrompt: '', role: 'REVIEWER' };

function PersonaForm({
  draft, isNew, disabled, canSubmit, onChange, onCancel, onSubmit,
}: {
  draft: PersonaRequest;
  isNew: boolean;
  disabled: boolean;
  canSubmit: boolean;
  onChange: (next: PersonaRequest) => void;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  return (
    <div style={formStyle}>
      <h3 style={formHeadStyle}>{isNew ? 'New persona' : 'Edit persona'}</h3>
      <label style={labelStyle}>
        <span>Name</span>
        <input
          type="text"
          value={draft.name}
          onChange={e => onChange({ ...draft, name: e.target.value })}
          placeholder="e.g. Trino, David, Dain"
          disabled={disabled}
          style={inputStyle}
        />
      </label>
      <label style={labelStyle}>
        <span>Role</span>
        <select
          value={draft.role}
          onChange={e => onChange({ ...draft, role: e.target.value as Role })}
          disabled={disabled}
          style={selectStyle}
        >
          <option value="REVIEWER">REVIEWER — contributes findings</option>
          <option value="LEAD">LEAD — drafts consensus</option>
        </select>
      </label>
      <label style={labelStyle}>
        <span>System prompt</span>
        <textarea
          value={draft.systemPrompt}
          onChange={e => onChange({ ...draft, systemPrompt: e.target.value })}
          placeholder={
            'Describe the reviewing voice. Example: "You are a Trino '
            + 'maintainer who cares about query-plan correctness, '
            + 'predicate pushdown, and connector hygiene."'
          }
          rows={8}
          disabled={disabled}
          style={textareaStyle}
        />
      </label>
      <div style={formFootStyle}>
        <button type="button" style={secondaryBtnStyle} onClick={onCancel} disabled={disabled}>
          Cancel
        </button>
        <button type="button" style={primaryBtnStyle} onClick={onSubmit} disabled={!canSubmit}>
          {isNew ? 'Create persona' : 'Save changes'}
        </button>
      </div>
    </div>
  );
}

// ─── styles ─────────────────────────────────────────────────────────
// Inline styles kept in this file so the page is self-contained;
// matches the convention used by SavedViewsPage and other settings
// pages until the project picks a single CSS approach.

const errorBoxStyle: React.CSSProperties = {
  padding: '0.75rem 1rem',
  margin: '0 0 1rem',
  background: '#fef2f2',
  border: '1px solid #fca5a5',
  borderRadius: 6,
  color: '#991b1b',
  fontSize: 13,
};

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '0.75rem',
};

const dimStyle: React.CSSProperties = { color: '#64748b', fontStyle: 'italic' };

const cardStyle: React.CSSProperties = {
  border: '1px solid #e2e8f0',
  borderRadius: 6,
  padding: '0.75rem 1rem',
  background: '#fff',
};

const cardHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '0.5rem',
};

const nameStyle: React.CSSProperties = { fontSize: 14 };

const spacerStyle: React.CSSProperties = { flex: 1 };

const promptPreviewStyle: React.CSSProperties = {
  margin: '0.5rem 0 0',
  color: '#475569',
  fontSize: 12,
  lineHeight: 1.45,
  whiteSpace: 'pre-wrap',
};

const baseBadge: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  padding: '2px 8px',
  borderRadius: 999,
  letterSpacing: '0.05em',
};

const leadBadgeStyle: React.CSSProperties = {
  ...baseBadge,
  background: '#fef3c7',
  color: '#92400e',
};

const reviewerBadgeStyle: React.CSSProperties = {
  ...baseBadge,
  background: '#dbeafe',
  color: '#1e40af',
};

const editorWrapStyle: React.CSSProperties = { marginTop: '1rem' };

const formStyle: React.CSSProperties = {
  border: '1px solid #cbd5e1',
  borderRadius: 6,
  padding: '1rem',
  background: '#f8fafc',
  display: 'flex',
  flexDirection: 'column',
  gap: '0.75rem',
};

const formHeadStyle: React.CSSProperties = { margin: 0, fontSize: 14 };

const labelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '0.25rem',
  fontSize: 12,
  color: '#334155',
};

const inputStyle: React.CSSProperties = {
  padding: '0.4rem 0.6rem',
  border: '1px solid #cbd5e1',
  borderRadius: 4,
  fontSize: 13,
};

const selectStyle: React.CSSProperties = { ...inputStyle };

const textareaStyle: React.CSSProperties = {
  ...inputStyle,
  fontFamily: 'inherit',
  resize: 'vertical',
};

const formFootStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: '0.5rem',
};

const primaryBtnStyle: React.CSSProperties = {
  padding: '0.4rem 0.85rem',
  background: '#0f172a',
  color: '#fff',
  border: '1px solid #0f172a',
  borderRadius: 4,
  fontSize: 13,
  cursor: 'pointer',
};

const secondaryBtnStyle: React.CSSProperties = {
  padding: '0.4rem 0.85rem',
  background: '#fff',
  color: '#0f172a',
  border: '1px solid #cbd5e1',
  borderRadius: 4,
  fontSize: 13,
  cursor: 'pointer',
};

const linkBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: 0,
  color: '#0f172a',
  fontSize: 12,
  cursor: 'pointer',
  textDecoration: 'underline',
};

const linkBtnDangerStyle: React.CSSProperties = {
  ...linkBtnStyle,
  color: '#b91c1c',
};
