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
import {
  CREDENTIAL_TEMPLATES,
  type CredentialDto,
  type CredentialTemplate,
  type CredentialType,
} from '../../../types';

/** Default sub-name on the backend when the user doesn't pick one. */
const DEFAULT_INSTANCE_NAME = 'default api';

export type CredentialEditorModalProps = {
  /** When set, the type dropdown is hidden and the modal is locked to
   *  this kind (the new CredentialsPage opens kind-specific modals
   *  per tab). */
  filterType?: CredentialType;
  /** When set, the modal renders as Edit (locks type+name+instance,
   *  pre-fills label/notes/default flag, places the secret field in
   *  "paste to replace" mode). */
  existing?: CredentialDto;
  /** Closes the modal without saving. */
  onClose: () => void;
  /**
   * Persist via {@code window.bridge.upsertCredential} (and optionally
   * {@code setDefaultCredential}). Resolves after the save completes so
   * the parent can refetch + close the modal.
   */
  onSave: (input: {
    type: CredentialType;
    name: string;
    instanceName: string;
    value: string;
    label: string | null;
    notes: string | null;
    setAsDefault: boolean;
  }) => Promise<void>;
  /** Run the upstream probe against the just-typed inputs (Edit only —
   *  Add can't test until Save lands the row). Returns the result so
   *  the modal can render it inline. */
  onTest?: () => Promise<{ ok: boolean; message: string; latencyMs: number | null }>;
};

function templateFor(type: CredentialType, name: string): CredentialTemplate | undefined {
  return CREDENTIAL_TEMPLATES.find(t => t.type === type && t.name === name);
}

/**
 * Modal version of the credential editor. Lifted out of CredentialsTab so
 * the new Settings → Credentials page can share the same form across all
 * kinds (LLM / Git PAT / MCP).
 *
 * <p>Secret values are never pre-filled — Edit mode keeps the field empty
 * with a "paste to replace" placeholder so a casual look at the screen
 * doesn't show even a partial key.
 */
function CredentialEditorModal({ filterType, existing, onClose, onSave, onTest }: CredentialEditorModalProps) {
  const templates = filterType
      ? CREDENTIAL_TEMPLATES.filter(t => t.type === filterType)
      : CREDENTIAL_TEMPLATES;
  const initialKey = existing
      ? `${existing.type}/${existing.name}`
      : `${templates[0]?.type ?? 'ACCOUNT'}/${templates[0]?.name ?? 'github'}`;
  const [templateKey, setTemplateKey] = useState(initialKey);
  const [instanceName, setInstanceName] = useState(existing?.instanceName ?? DEFAULT_INSTANCE_NAME);
  const [value, setValue] = useState('');
  const [label, setLabel] = useState(existing?.label ?? '');
  const [notes, setNotes] = useState(existing?.notes ?? '');
  const [setAsDefault, setSetAsDefault] = useState<boolean>(existing?.isDefault ?? false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string; latencyMs: number | null } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [tType, tName] = templateKey.split('/') as [CredentialType, string];
  const template = templateFor(tType, tName);

  const handleSave = async () => {
    // Edit allows blank (label/notes/default-only updates); Add must
    // carry a value.
    if (!existing && !value.trim()) {
      setError('Value must not be blank.');
      return;
    }
    const finalInstance = instanceName.trim() || DEFAULT_INSTANCE_NAME;
    setSaving(true);
    setError(null);
    try {
      await onSave({
        type: tType,
        name: tName,
        instanceName: finalInstance,
        value: value.trim() || (existing?.preview ?? ''),
        label: label.trim() || null,
        notes: notes.trim() || null,
        setAsDefault,
      });
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  const handleTest = async () => {
    if (onTest === undefined) return;
    setTesting(true);
    setTestResult(null);
    try {
      setTestResult(await onTest());
    }
    catch (e) {
      setTestResult({
        ok: false,
        message: e instanceof Error ? e.message : String(e),
        latencyMs: null,
      });
    }
    finally {
      setTesting(false);
    }
  };

  return (
    <div style={scrimStyle} role="presentation" onClick={onClose}>
      <div
        style={dialogStyle}
        role="dialog"
        aria-modal="true"
        aria-label={existing ? 'Edit credential' : 'Add credential'}
        onClick={e => e.stopPropagation()}
      >
        <header style={headerStyle}>
          <h2 style={titleStyle}>{existing ? 'Edit credential' : 'Add credential'}</h2>
          <button type="button" style={closeBtnStyle} onClick={onClose} aria-label="Close">✕</button>
        </header>

        {/* Type only shown when the modal isn't kind-locked. The
            CredentialsPage tabs always lock — the legacy flow that
            opens this without a filter still gets the picker. */}
        {filterType === undefined && (
          <div style={fieldStyle}>
            <label style={labelStyle}>Type</label>
            <select
              style={selectStyle}
              value={templateKey}
              onChange={e => setTemplateKey(e.target.value)}
              disabled={!!existing}
            >
              {templates.map(t => (
                <option key={`${t.type}/${t.name}`} value={`${t.type}/${t.name}`}>
                  {t.displayName}
                </option>
              ))}
            </select>
            {template && <p style={hintStyle}>{template.usageDescription}</p>}
          </div>
        )}

        {filterType !== undefined && templates.length > 1 && (
          <div style={fieldStyle}>
            <label style={labelStyle}>Provider</label>
            <select
              style={selectStyle}
              value={templateKey}
              onChange={e => setTemplateKey(e.target.value)}
              disabled={!!existing}
            >
              {templates.map(t => (
                <option key={`${t.type}/${t.name}`} value={`${t.type}/${t.name}`}>
                  {t.displayName}
                </option>
              ))}
            </select>
            {template && <p style={hintStyle}>{template.usageDescription}</p>}
          </div>
        )}

        <div style={fieldStyle}>
          <label style={labelStyle}>Name</label>
          <input
            style={inputStyle}
            type="text"
            value={instanceName}
            onChange={e => setInstanceName(e.target.value)}
            placeholder={DEFAULT_INSTANCE_NAME}
            disabled={!!existing}
          />
          <p style={hintStyle}>
            Sub-name within the provider — e.g. "personal" / "work". Must be
            unique within this group. Defaults to "{DEFAULT_INSTANCE_NAME}".
          </p>
        </div>

        <div style={fieldStyle}>
          <label style={labelStyle}>Value</label>
          <input
            style={inputStyle}
            type="password"
            value={value}
            onChange={e => setValue(e.target.value)}
            placeholder={existing ? '•••• stored — paste to replace' : 'Paste the key or token'}
            autoFocus
          />
          <p style={hintStyle}>
            Encrypted at rest on this machine (AES-256-GCM). Config files only
            reference it by name — never the key.
          </p>
        </div>

        <div style={fieldStyle}>
          <label style={labelStyle}>Label (optional)</label>
          <input
            style={inputStyle}
            type="text"
            value={label}
            onChange={e => setLabel(e.target.value)}
            placeholder="e.g. Work account"
          />
        </div>

        <div style={fieldStyle}>
          <label style={labelStyle}>Notes (optional)</label>
          <textarea
            style={textareaStyle}
            value={notes}
            onChange={e => setNotes(e.target.value)}
            rows={2}
            placeholder="e.g. scopes: repo, read:user"
          />
        </div>

        <label style={defaultRowStyle}>
          <input
            type="checkbox"
            checked={setAsDefault}
            onChange={e => setSetAsDefault(e.target.checked)}
          />
          <span style={defaultLabelStyle}>★ Set as default for {provLabel(tType, tName)}</span>
          <span style={defaultHintStyle}>
            Resolvers that name only the provider/host pick this one.
          </span>
        </label>

        {testResult !== null && (
          <div style={testResult.ok ? testOkStyle : testFailStyle}>
            {testResult.ok ? '✓ ' : '✕ '}{testResult.message}
            {testResult.latencyMs !== null && ` (${testResult.latencyMs} ms)`}
          </div>
        )}
        {error !== null && <p style={errorStyle}>{error}</p>}

        <footer style={footerStyle}>
          {onTest !== undefined && (
            <button
              type="button"
              style={secondaryBtnStyle}
              onClick={() => { void handleTest(); }}
              disabled={testing || saving}
            >
              {testing ? 'Testing…' : 'Test connection'}
            </button>
          )}
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
            {saving ? 'Saving…' : existing ? 'Save' : 'Add credential'}
          </button>
        </footer>
      </div>
    </div>
  );
}

function provLabel(type: CredentialType, name: string): string {
  const tpl = templateFor(type, name);
  return tpl?.displayName ?? `${type.toLowerCase()} / ${name}`;
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
  width: 520,
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
  marginBottom: 14,
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

const selectStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  fontSize: 13,
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  background: '#fff',
  outline: 'none',
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
  minHeight: 56,
  resize: 'vertical',
  fontFamily: 'inherit',
};

const hintStyle: React.CSSProperties = {
  margin: '4px 0 0',
  fontSize: 11,
  color: 'var(--text-3)',
};

const defaultRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '18px 1fr',
  gridTemplateRows: 'auto auto',
  columnGap: 8,
  rowGap: 2,
  alignItems: 'center',
  margin: '8px 0 12px',
  padding: '8px 10px',
  borderRadius: 8,
  border: '1px solid rgba(124, 58, 237, 0.18)',
  background: 'rgba(124, 58, 237, 0.04)',
};

const defaultLabelStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const defaultHintStyle: React.CSSProperties = {
  gridColumn: '2 / 3',
  fontSize: 11,
  color: 'var(--text-3)',
};

const errorStyle: React.CSSProperties = {
  margin: '0 0 12px',
  color: '#cf1322',
  fontSize: 12,
};

const testOkStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: '6px 10px',
  borderRadius: 8,
  background: 'rgba(22, 163, 74, 0.10)',
  color: '#15803d',
  fontSize: 12,
};

const testFailStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: '6px 10px',
  borderRadius: 8,
  background: 'rgba(207, 19, 34, 0.08)',
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

export default CredentialEditorModal;
