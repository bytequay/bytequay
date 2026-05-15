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
import { useState } from 'react';
import type { TaskKindDto } from '../types';

type Props = {
  onClose: () => void;
  onCreated: () => void | Promise<void>;
};

/**
 * Modal that gathers the inputs for one new task and calls
 * {@code window.bridge.createTask}. The {@code initialPrompt} kicks off
 * the first turn synchronously on the backend, so a successful submit
 * returns a row that's already RUNNING.
 *
 * <p>{@code logic_loop} is shown disabled — the kind exists in the
 * backend schema but the runner lands in a later slice.
 */
export default function NewTaskDialog({ onClose, onCreated }: Props) {
  const [kind] = useState<TaskKindDto>('CLI_AGENT');
  const [title, setTitle] = useState('');
  const [workingDir, setWorkingDir] = useState('');
  const [model, setModel] = useState('claude-sonnet-4.6');
  const [initialPrompt, setInitialPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    if (!title.trim() || !workingDir.trim()) {
      setError('Title and working directory are required.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await window.bridge.createTask({
        kind,
        provider: 'claude-code',
        model: model.trim() || 'claude-sonnet-4.6',
        title: title.trim(),
        workingDir: workingDir.trim(),
        initialPrompt: initialPrompt.trim() || undefined,
      });
      await onCreated();
    }
    catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setSubmitting(false);
    }
  };

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={dialogStyle} onClick={e => e.stopPropagation()}>
        <header style={dialogHeaderStyle}>
          <h2 style={dialogTitleStyle}>New task</h2>
          <button type="button" onClick={onClose} style={closeBtnStyle} aria-label="Close">×</button>
        </header>

        <form onSubmit={submit} style={formStyle}>
          <Field label="Title">
            <input
              type="text"
              value={title}
              onChange={e => setTitle(e.target.value)}
              placeholder="Add tracing to the order pipeline"
              style={inputStyle}
              autoFocus
            />
          </Field>

          <Field label="Working directory" hint="Absolute path; the agent runs git operations from here.">
            <input
              type="text"
              value={workingDir}
              onChange={e => setWorkingDir(e.target.value)}
              placeholder="/Users/you/code/some-repo"
              style={inputStyle}
            />
          </Field>

          <Field label="Model">
            <input
              type="text"
              value={model}
              onChange={e => setModel(e.target.value)}
              style={inputStyle}
            />
          </Field>

          <Field label="Kind">
            <select value={kind} disabled style={inputStyle}>
              <option value="CLI_AGENT">Claude Code (CLI)</option>
              <option value="LOGIC_LOOP" disabled>Logic loop (coming soon)</option>
            </select>
          </Field>

          <Field label="Initial prompt" hint="Optional. Sent as the first user turn once the session is up.">
            <textarea
              value={initialPrompt}
              onChange={e => setInitialPrompt(e.target.value)}
              placeholder="Describe the change you want…"
              rows={5}
              style={{ ...inputStyle, fontFamily: 'inherit', resize: 'vertical' }}
            />
          </Field>

          {error && (
            <div style={errorBoxStyle}>{error}</div>
          )}

          <footer style={footerStyle}>
            <button type="button" onClick={onClose} style={cancelBtnStyle} disabled={submitting}>
              Cancel
            </button>
            <button type="submit" style={submitBtnStyle} disabled={submitting}>
              {submitting ? 'Starting…' : 'Start task'}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label style={fieldStyle}>
      <span style={fieldLabelStyle}>{label}</span>
      {children}
      {hint && <span style={fieldHintStyle}>{hint}</span>}
    </label>
  );
}

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(17, 24, 39, 0.5)',
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'center',
  paddingTop: 80,
  zIndex: 1000,
};
const dialogStyle: React.CSSProperties = {
  width: 'min(560px, 92vw)',
  background: '#fff',
  borderRadius: 10,
  boxShadow: '0 20px 60px rgba(0, 0, 0, 0.25)',
  overflow: 'hidden',
};
const dialogHeaderStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  padding: '16px 20px',
  borderBottom: '1px solid #E5E7EB',
};
const dialogTitleStyle: React.CSSProperties = { margin: 0, fontSize: 18, fontWeight: 600 };
const closeBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  fontSize: 28,
  lineHeight: 1,
  color: '#6B7280',
  cursor: 'pointer',
  padding: 0,
};
const formStyle: React.CSSProperties = { padding: 20, display: 'flex', flexDirection: 'column', gap: 14 };
const fieldStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4 };
const fieldLabelStyle: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: '#374151' };
const fieldHintStyle: React.CSSProperties = { fontSize: 11, color: '#6B7280' };
const inputStyle: React.CSSProperties = {
  padding: '8px 10px',
  border: '1px solid #D1D5DB',
  borderRadius: 6,
  fontSize: 14,
  width: '100%',
  boxSizing: 'border-box',
};
const errorBoxStyle: React.CSSProperties = {
  padding: '8px 12px',
  background: '#FEF2F2',
  color: '#991B1B',
  border: '1px solid #FCA5A5',
  borderRadius: 6,
  fontSize: 13,
};
const footerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
  marginTop: 4,
};
const cancelBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'transparent',
  color: '#374151',
  border: '1px solid #D1D5DB',
  borderRadius: 6,
  cursor: 'pointer',
};
const submitBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: '#7C3AED',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'pointer',
};
