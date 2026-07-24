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
  type McpCredentialConfig,
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
    configJson: string | null;
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
  const isMcp = filterType === 'MCP' || existing?.type === 'MCP';
  const templates = filterType
      ? CREDENTIAL_TEMPLATES.filter(t => t.type === filterType)
      : CREDENTIAL_TEMPLATES;
  const initialKey = existing
      ? `${existing.type}/${existing.name}`
      : isMcp
        ? 'MCP/'
        : `${templates[0]?.type ?? 'ACCOUNT'}/${templates[0]?.name ?? 'github'}`;
  const [templateKey, setTemplateKey] = useState(initialKey);
  // MCP-specific: a free-text service id (slack / linear / …). The
  // modal lifts this into the (type, name) pair on save so the row
  // groups behave the same as the templated kinds.
  const initialMcpName = existing?.type === 'MCP' ? existing.name : '';
  const [mcpName, setMcpName] = useState<string>(initialMcpName);
  // MCP-specific: structured config. Parsed back from existing rows
  // so Edit pre-fills correctly; defaults to a remote+bearer add.
  const initialMcpConfig = parseMcpConfig(existing?.configJson ?? null);
  const [mcp, setMcp] = useState<McpCredentialConfig>(initialMcpConfig);
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
  // OAuth MCP rows don't have a raw key to paste — the "secret" we
  // store is just a placeholder so the row exists; Re-auth is what
  // actually exchanges tokens.
  const isOAuth = isMcp && mcp.transport === 'remote' && mcp.authKind === 'oauth';

  const handleSave = async () => {
    let effectiveName = tName;
    if (isMcp) {
      const trimmedService = mcpName.trim();
      if (!existing && trimmedService.length === 0) {
        setError('Service id must not be blank (e.g. "slack", "linear").');
        return;
      }
      effectiveName = existing?.name ?? trimmedService;
      if (mcp.transport === 'remote') {
        if (!mcp.serverUrl || mcp.serverUrl.trim().length === 0) {
          setError('Server URL is required for a remote MCP server.');
          return;
        }
      }
      else {
        if (!mcp.command || mcp.command.trim().length === 0) {
          setError('Launch command is required for a local MCP server.');
          return;
        }
        if (!mcp.envVarName || mcp.envVarName.trim().length === 0) {
          setError('Env var name is required so the launcher knows where to inject the secret.');
          return;
        }
      }
    }
    // OAuth MCP rows don't carry a raw secret to test/paste — stamp a
    // placeholder so the row persists. Re-auth would exchange the
    // real token (out of scope for this surface).
    const requireValue = !existing && !(isMcp && isOAuth);
    if (requireValue && !value.trim()) {
      setError('Value must not be blank.');
      return;
    }
    const finalInstance = instanceName.trim() || DEFAULT_INSTANCE_NAME;
    const finalValue = value.trim()
        || (existing?.preview ?? '')
        || (isMcp && isOAuth ? 'oauth' : '');
    const finalConfigJson = isMcp ? serialiseMcpConfig(mcp) : null;
    setSaving(true);
    setError(null);
    try {
      await onSave({
        type: tType,
        name: effectiveName,
        instanceName: finalInstance,
        value: finalValue,
        label: label.trim() || null,
        notes: notes.trim() || null,
        setAsDefault,
        configJson: finalConfigJson,
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
        {filterType === undefined && !isMcp && (
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

        {!isMcp && filterType !== undefined && templates.length > 1 && (
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

        {isMcp && (
          <div style={fieldStyle}>
            <label style={labelStyle}>Service id</label>
            <input
              style={inputStyle}
              type="text"
              value={mcpName}
              onChange={e => setMcpName(e.target.value)}
              placeholder="e.g. slack, linear, github"
              disabled={!!existing}
            />
            <p style={hintStyle}>
              Short identifier for the MCP service. Multiple accounts of
              the same service share this id and get distinguished by
              the instance name below (e.g. <code>slack</code> ×{' '}
              <code>personal</code> + <code>work</code>).
            </p>
          </div>
        )}

        {isMcp && (
          <div style={fieldStyle}>
            <label style={labelStyle}>Transport</label>
            <div style={segmentRowStyle}>
              <button
                type="button"
                style={segmentBtnStyle(mcp.transport === 'remote')}
                onClick={() => setMcp(prev => ({ ...prev, transport: 'remote' }))}
              >
                Remote (HTTP)
              </button>
              <button
                type="button"
                style={segmentBtnStyle(mcp.transport === 'local')}
                onClick={() => setMcp(prev => ({ ...prev, transport: 'local' }))}
              >
                Local (stdio)
              </button>
            </div>
          </div>
        )}

        {isMcp && mcp.transport === 'remote' && (
          <>
            <div style={fieldStyle}>
              <label style={labelStyle}>Server URL</label>
              <input
                style={inputStyle}
                type="text"
                value={mcp.serverUrl ?? ''}
                onChange={e => setMcp(prev => ({ ...prev, serverUrl: e.target.value }))}
                placeholder="https://mcp.example.com/v1"
              />
            </div>
            <div style={fieldStyle}>
              <label style={labelStyle}>Auth kind</label>
              <div style={segmentRowStyle}>
                <button
                  type="button"
                  style={segmentBtnStyle(mcp.authKind === 'oauth')}
                  onClick={() => setMcp(prev => ({ ...prev, authKind: 'oauth' }))}
                >
                  OAuth
                </button>
                <button
                  type="button"
                  style={segmentBtnStyle(mcp.authKind === 'bearer' || mcp.authKind === undefined)}
                  onClick={() => setMcp(prev => ({ ...prev, authKind: 'bearer' }))}
                >
                  Bearer token
                </button>
              </div>
              <p style={hintStyle}>
                OAuth rows show <strong>⬡ connected</strong> + a Re-auth
                action; bearer rows show <strong>✓/⚠</strong> + Test.
              </p>
            </div>
          </>
        )}

        {isMcp && mcp.transport === 'local' && (
          <>
            <div style={fieldStyle}>
              <label style={labelStyle}>Launch command</label>
              <input
                style={inputStyle}
                type="text"
                value={mcp.command ?? ''}
                onChange={e => setMcp(prev => ({ ...prev, command: e.target.value }))}
                placeholder="e.g. mcp-server-slack"
              />
            </div>
            <div style={fieldStyle}>
              <label style={labelStyle}>Env var name</label>
              <input
                style={inputStyle}
                type="text"
                value={mcp.envVarName ?? ''}
                onChange={e => setMcp(prev => ({ ...prev, envVarName: e.target.value }))}
                placeholder="e.g. SLACK_BOT_TOKEN"
              />
              <p style={hintStyle}>
                The secret value below is injected into the child
                process under this env var at launch — never written
                to disk in plaintext.
              </p>
            </div>
          </>
        )}

        <div style={fieldStyle}>
          <label style={labelStyle}>{isMcp ? 'Account / instance' : 'Name'}</label>
          <input
            style={inputStyle}
            type="text"
            value={instanceName}
            onChange={e => setInstanceName(e.target.value)}
            placeholder={DEFAULT_INSTANCE_NAME}
            disabled={!!existing}
          />
          <p style={hintStyle}>
            Sub-name within the {isMcp ? 'service' : 'provider'} — e.g.
            "personal" / "work". Must be unique within this group.
            Defaults to "{DEFAULT_INSTANCE_NAME}".
          </p>
        </div>

        {!isOAuth && (
          <div style={fieldStyle}>
            <label style={labelStyle}>{isMcp ? 'Secret' : 'Value'}</label>
            <input
              style={inputStyle}
              type="password"
              value={value}
              onChange={e => setValue(e.target.value)}
              placeholder={existing ? '•••• stored — paste to replace' : 'Paste the key or token'}
              autoFocus
            />
            <p style={hintStyle}>
              Encrypted at rest on this machine (AES-256-GCM). Config files
              only reference it by name — never the key.
            </p>
          </div>
        )}

        {isOAuth && (
          <div style={oauthHintStyle}>
            ⬡ OAuth flow — the actual token exchange happens on Re-auth
            (from the row). This dialog just registers the row so the
            launcher knows the service id, server URL and instance
            name. No raw key to paste.
          </div>
        )}

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
  if (type === 'MCP') {
    return name.length > 0 ? `${name} (MCP)` : 'this MCP service';
  }
  const tpl = templateFor(type, name);
  return tpl?.displayName ?? `${type.toLowerCase()} / ${name}`;
}

/** Default MCP config used when adding a fresh row. Remote + bearer
 *  is the common case for hosted MCP servers behind an API token. */
function defaultMcpConfig(): McpCredentialConfig
{
  return { transport: 'remote', authKind: 'bearer', serverUrl: '' };
}

function parseMcpConfig(raw: string | null): McpCredentialConfig
{
  if (raw === null || raw === '') return defaultMcpConfig();
  try {
    const obj = JSON.parse(raw) as Partial<McpCredentialConfig>;
    return {
      transport: obj.transport === 'local' ? 'local' : 'remote',
      authKind: obj.authKind === 'oauth' ? 'oauth' : 'bearer',
      serverUrl: typeof obj.serverUrl === 'string' ? obj.serverUrl : '',
      command: typeof obj.command === 'string' ? obj.command : '',
      envVarName: typeof obj.envVarName === 'string' ? obj.envVarName : '',
    };
  }
  catch {
    return defaultMcpConfig();
  }
}

function serialiseMcpConfig(cfg: McpCredentialConfig): string
{
  // Drop empty transport-specific fields so the persisted blob
  // doesn't carry stale fields when the user flips remote ↔ local.
  const out: McpCredentialConfig = { transport: cfg.transport };
  if (cfg.transport === 'remote') {
    out.authKind = cfg.authKind === 'oauth' ? 'oauth' : 'bearer';
    if (cfg.serverUrl !== undefined && cfg.serverUrl.length > 0) out.serverUrl = cfg.serverUrl.trim();
  }
  else {
    if (cfg.command !== undefined && cfg.command.length > 0) out.command = cfg.command.trim();
    if (cfg.envVarName !== undefined && cfg.envVarName.length > 0) out.envVarName = cfg.envVarName.trim();
  }
  return JSON.stringify(out);
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
  border: '1px solid var(--accent-border)',
  background: 'var(--accent-a4)',
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
    background: active ? 'rgba(13, 148, 136, 0.10)' : '#fff',
    color: active ? '#0d9488' : 'var(--text-2)',
    cursor: 'pointer',
  };
}

const oauthHintStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: '10px 12px',
  fontSize: 12,
  border: '1px dashed rgba(13, 148, 136, 0.30)',
  borderRadius: 8,
  background: 'rgba(13, 148, 136, 0.05)',
  color: 'var(--text-2)',
};

export default CredentialEditorModal;
