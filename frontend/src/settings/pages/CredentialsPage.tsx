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
import { useEffect, useMemo, useState } from 'react';
import {
  CREDENTIAL_TEMPLATES,
  type CredentialDto,
  type CredentialTemplate,
  type CredentialTestResult,
  type CredentialType,
  type McpCredentialConfig,
} from '../../types';
import CredentialEditorModal from './credentials/CredentialEditorModal';

/** Three top-level groups for the kind nav (LLM keys, Git PATs, and
 *  MCP server secrets) — each supports add / edit / delete. */
type Tab = 'llm' | 'pat' | 'tools';

const TAB_DEFS: { id: Tab; label: string; meta: string; addLabel: string; emptyHint: string }[] = [
  {
    id: 'llm',
    label: 'LLM providers',
    meta: 'Anthropic, OpenAI, DeepSeek…',
    addLabel: '+ Add credential',
    emptyHint: 'No provider keys yet. Add one so the AI features have something to call.',
  },
  {
    id: 'pat',
    label: 'Git PAT',
    meta: 'Personal access tokens — GitHub today',
    addLabel: '+ Add token',
    emptyHint:
        'No Git tokens yet. Add your GitHub PAT so the app can sync PRs and issues.',
  },
  {
    id: 'tools',
    label: 'Tools / MCP',
    meta: 'MCP server secrets',
    addLabel: '+ Add server',
    emptyHint: 'No MCP server secrets yet. Add a remote (URL + bearer/OAuth) or local (launch command) server.',
  },
];

type RowTestState = {
  loading: boolean;
  result: CredentialTestResult | null;
  error: string | null;
};

type Props = {
  /** Called the first time a credential is added (any kind). Wired
   *  by the first-run onboarding so adding the user's GitHub PAT here
   *  flips them out of first-run mode. */
  onFirstCredentialAdded?: () => void;
};

function CredentialsPage({ onFirstCredentialAdded }: Props) {
  const [tab, setTab] = useState<Tab>('llm');
  const [credentials, setCredentials] = useState<CredentialDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<CredentialDto | 'new' | null>(null);
  const [testStates, setTestStates] = useState<Record<string, RowTestState>>({});

  const tabType: CredentialType | null = tab === 'llm' ? 'AI'
      : tab === 'pat' ? 'ACCOUNT'
      : tab === 'tools' ? 'MCP'
      : null;

  const visible = useMemo(
      () => tabType === null ? [] : credentials.filter(c => c.type === tabType),
      [credentials, tabType]);

  const grouped = useMemo(() => groupByName(visible), [visible]);

  const counts = useMemo(() => ({
    llm: credentials.filter(c => c.type === 'AI').length,
    pat: credentials.filter(c => c.type === 'ACCOUNT').length,
    tools: credentials.filter(c => c.type === 'MCP').length,
  }), [credentials]);

  const load = async () => {
    setLoading(true);
    setError(null);
    setTestStates({});
    try {
      // Fetch the whole vault once so the count badges on the nav are
      // accurate without per-tab refetches.
      const all = await window.bridge.listCredentials();
      setCredentials(all);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const handleSave = async (input: {
    type: CredentialType;
    name: string;
    instanceName: string;
    value: string;
    label: string | null;
    notes: string | null;
    setAsDefault: boolean;
    configJson: string | null;
  }) => {
    const wasEmpty = credentials.length === 0;
    // Editing without a fresh secret keeps the existing one — the
    // backend's upsert needs a value, so we only call it when the
    // user actually typed something. OAuth MCP rows never carry a
    // secret value on this surface, so the modal stamps a "·"
    // placeholder there to keep the existing row.
    if (input.value.length > 0) {
      await window.bridge.upsertCredential({
        type: input.type,
        name: input.name,
        instanceName: input.instanceName,
        value: input.value,
        label: input.label,
        notes: input.notes,
        configJson: input.configJson,
      });
    }
    if (input.setAsDefault) {
      await window.bridge.setDefaultCredential(input.type, input.name, input.instanceName);
    }
    setEditing(null);
    await load();
    if (wasEmpty && onFirstCredentialAdded) onFirstCredentialAdded();
  };

  const handleDelete = async (cred: CredentialDto) => {
    const tpl = templateFor(cred.type, cred.name);
    const label = tpl?.displayName ?? `${cred.type.toLowerCase()} / ${cred.name}`;
    if (!confirm(`Delete the stored ${label} ("${cred.instanceName}")?`)) return;
    await window.bridge.deleteCredential(cred.type, cred.name, cred.instanceName);
    await load();
  };

  const handleTest = async (cred: CredentialDto) => {
    setTestStates(s => ({ ...s, [cred.id]: { loading: true, result: null, error: null } }));
    try {
      const result = await window.bridge.testCredential(cred.type, cred.name, cred.instanceName);
      setTestStates(s => ({ ...s, [cred.id]: { loading: false, result, error: null } }));
    }
    catch (e) {
      setTestStates(s => ({
        ...s,
        [cred.id]: { loading: false, result: null, error: e instanceof Error ? e.message : String(e) },
      }));
    }
  };

  const handleSetDefault = async (cred: CredentialDto) => {
    if (cred.isDefault) return;
    try {
      await window.bridge.setDefaultCredential(cred.type, cred.name, cred.instanceName);
      await load();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const activeDef = TAB_DEFS.find(t => t.id === tab)!;

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Credentials</h2>
          <div className="settings-shell-page__subtitle">
            One vault for the keys ByteQuay needs. Encrypted at rest on this
            machine (AES-256-GCM); config files only reference rows by
            name — never the key.
          </div>
        </div>
      </div>

      <div style={layoutStyle}>
        <nav style={navStyle} aria-label="Credential kinds">
          {TAB_DEFS.map(def => {
            const active = tab === def.id;
            const count = counts[def.id];
            return (
              <button
                key={def.id}
                type="button"
                onClick={() => setTab(def.id)}
                style={navItemStyle(active)}
              >
                <span style={navLabelStyle}>{def.label}</span>
                <span style={navCountStyle(active, count)}>{count}</span>
                <span style={navMetaStyle}>{def.meta}</span>
              </button>
            );
          })}
        </nav>

        <section style={bodyStyle}>
          <div style={bodyHeadStyle}>
            <div>
              <div style={bodyTitleStyle}>{activeDef.label}</div>
              <div style={bodyMetaStyle}>{activeDef.meta}</div>
            </div>
            {tabType !== null && (
              <button
                type="button"
                className="button button--primary"
                onClick={() => setEditing('new')}
              >
                {activeDef.addLabel}
              </button>
            )}
          </div>

          {error !== null && <div className="repo-error">{error}</div>}


          {tabType !== null && loading && <div className="settings-loading">Loading…</div>}

          {tabType !== null && !loading && visible.length === 0 && error === null && (
            <div style={emptyStyle}>{activeDef.emptyHint}</div>
          )}

          {tabType !== null && grouped.map(group => (
            <div key={`${group.type}/${group.name}`} style={groupStyle}>
              <div style={groupHeadStyle}>
                <span style={groupNameStyle}>{group.displayName}</span>
                <span style={groupMetaStyle}>
                  {group.rows.length} {group.rows.length === 1 ? 'instance' : 'instances'}
                </span>
              </div>
              <ul style={listStyle}>
                {group.rows.map(c => {
                  const t = testStates[c.id];
                  const mcp = c.type === 'MCP' ? parseMcpConfigSafe(c.configJson) : null;
                  const isOAuth = mcp !== null && mcp.transport === 'remote' && mcp.authKind === 'oauth';
                  return (
                    <li key={c.id} style={rowStyle(c.isDefault)}>
                      <div style={rowMainStyle}>
                        <div style={rowTitleRowStyle}>
                          <button
                            type="button"
                            onClick={() => { void handleSetDefault(c); }}
                            style={starBtnStyle(c.isDefault)}
                            title={c.isDefault
                                ? 'This instance is the default for its group.'
                                : 'Promote this instance to the default.'}
                          >
                            {c.isDefault ? '★' : '☆'}
                          </button>
                          <span style={rowInstanceStyle}>{c.instanceName}</span>
                          {c.label !== null && c.label !== '' && (
                            <span style={rowLabelStyle}>{c.label}</span>
                          )}
                          <span style={authBadgeStyle(c.type)}>{mcp !== null ? mcpAuthLabel(mcp) : authLabel(c.type)}</span>
                          {isOAuth ? (
                            <span style={connectedChipStyle}>⬡ connected</span>
                          ) : (
                            <span style={previewStyle}>{c.preview}</span>
                          )}
                        </div>
                        {mcp !== null && (
                          <div style={mcpMetaStyle}>
                            {mcp.transport === 'remote'
                                ? `remote · ${mcp.serverUrl ?? '<no url>'}`
                                : `local · ${mcp.command ?? '<no command>'} · env ${mcp.envVarName ?? '?'}`}
                          </div>
                        )}
                        {c.notes !== null && c.notes !== '' && (
                          <div style={rowNotesStyle}>{c.notes}</div>
                        )}
                        <div style={rowMetaStyle}>
                          Added {formatTs(c.createdAt)} · updated {formatTs(c.updatedAt)}
                          {c.lastUsedAt !== null && <> · last used {formatTs(c.lastUsedAt)}</>}
                        </div>
                        {t !== undefined && !t.loading && (t.result !== null || t.error !== null) && (
                          <div style={t.error !== null || t.result?.ok === false ? testFailStyle : testOkStyle}>
                            {t.error !== null ? `✕ ${t.error}` : t.result!.ok
                                ? `✓ ${t.result!.message}${t.result!.latencyMs !== null ? ` (${t.result!.latencyMs} ms)` : ''}`
                                : `✕ ${t.result!.message}`}
                          </div>
                        )}
                      </div>
                      <div style={rowActionsStyle}>
                        {isOAuth ? (
                          <button
                            type="button"
                            className="button button--secondary button--small"
                            onClick={() => setEditing(c)}
                            title="Re-run the OAuth dance (placeholder — opens the editor)."
                          >
                            Re-auth
                          </button>
                        ) : (
                          <button
                            type="button"
                            className="button button--secondary button--small"
                            onClick={() => { void handleTest(c); }}
                            disabled={t?.loading === true}
                          >
                            {t?.loading ? 'Testing…' : 'Test'}
                          </button>
                        )}
                        <button
                          type="button"
                          className="button button--secondary button--small"
                          onClick={() => setEditing(c)}
                        >
                          Edit
                        </button>
                        <button
                          type="button"
                          className="button button--danger button--small"
                          onClick={() => { void handleDelete(c); }}
                        >
                          Delete
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </section>
      </div>

      {editing !== null && tabType !== null && (
        <CredentialEditorModal
          filterType={tabType}
          existing={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSave={handleSave}
          onTest={editing !== 'new' ? async () => {
            const cred = editing as CredentialDto;
            return window.bridge.testCredential(cred.type, cred.name, cred.instanceName);
          } : undefined}
        />
      )}
    </>
  );
}

function templateFor(type: CredentialType, name: string): CredentialTemplate | undefined {
  return CREDENTIAL_TEMPLATES.find(t => t.type === type && t.name === name);
}

function authLabel(type: CredentialType): string {
  switch (type) {
    case 'AI': return 'API key';
    case 'ACCOUNT': return 'PAT';
    case 'REPO': return 'PAT';
    case 'INTEGRATION': return 'OAuth';
    case 'MCP': return 'MCP';
  }
}

function mcpAuthLabel(cfg: McpCredentialConfig): string {
  if (cfg.transport === 'local') return 'MCP · local';
  return cfg.authKind === 'oauth' ? 'MCP · OAuth' : 'MCP · bearer';
}

/** Lenient parse — bad JSON or a missing column falls back to null so
 *  the row still renders. */
function parseMcpConfigSafe(raw: string | null): McpCredentialConfig | null {
  if (raw === null || raw === '') return null;
  try {
    const obj = JSON.parse(raw) as Partial<McpCredentialConfig>;
    if (obj.transport !== 'remote' && obj.transport !== 'local') return null;
    return {
      transport: obj.transport,
      authKind: obj.authKind === 'oauth' ? 'oauth' : obj.authKind === 'bearer' ? 'bearer' : undefined,
      serverUrl: typeof obj.serverUrl === 'string' ? obj.serverUrl : undefined,
      command: typeof obj.command === 'string' ? obj.command : undefined,
      envVarName: typeof obj.envVarName === 'string' ? obj.envVarName : undefined,
    };
  }
  catch {
    return null;
  }
}

function formatTs(iso: string | null): string {
  if (iso === null || iso === '') return 'never';
  const d = new Date(iso);
  const delta = Date.now() - d.getTime();
  const mins = Math.round(delta / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

function groupByName(rows: CredentialDto[]): {
  type: CredentialType;
  name: string;
  displayName: string;
  rows: CredentialDto[];
}[] {
  const map = new Map<string, { type: CredentialType; name: string; displayName: string; rows: CredentialDto[] }>();
  for (const c of rows) {
    const key = `${c.type}/${c.name}`;
    let entry = map.get(key);
    if (entry === undefined) {
      const tpl = templateFor(c.type, c.name);
      entry = {
        type: c.type,
        name: c.name,
        displayName: tpl?.displayName ?? `${c.type.toLowerCase()} / ${c.name}`,
        rows: [],
      };
      map.set(key, entry);
    }
    entry.rows.push(c);
  }
  // Default row first inside the group; everything else by id.
  for (const entry of map.values()) {
    entry.rows.sort((a, b) => {
      if (a.isDefault !== b.isDefault) return a.isDefault ? -1 : 1;
      return a.id - b.id;
    });
  }
  return Array.from(map.values());
}

const layoutStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '220px 1fr',
  gap: 16,
  alignItems: 'flex-start',
};

const navStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

function navItemStyle(active: boolean): React.CSSProperties {
  return {
    display: 'grid',
    gridTemplateColumns: '1fr auto',
    gridTemplateRows: 'auto auto',
    gap: 2,
    padding: '10px 12px',
    textAlign: 'left',
    border: active ? '1px solid var(--ws-accent, #7c3aed)' : '1px solid transparent',
    background: active ? 'var(--accent-a7)' : 'transparent',
    borderRadius: 8,
    cursor: 'pointer',
    color: 'var(--text-1)',
  };
}

const navLabelStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 600,
};

function navCountStyle(active: boolean, count: number): React.CSSProperties {
  return {
    fontSize: 11,
    fontWeight: 600,
    padding: '1px 8px',
    borderRadius: 999,
    background: active
        ? 'var(--ws-accent, #7c3aed)'
        : count > 0 ? 'rgba(0,0,0,0.06)' : 'transparent',
    color: active ? '#fff' : 'var(--text-3)',
    minWidth: 18,
    textAlign: 'center',
  };
}

const navMetaStyle: React.CSSProperties = {
  gridColumn: '1 / 3',
  fontSize: 11,
  color: 'var(--text-3)',
};

const bodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  minWidth: 0,
};

const bodyHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 12,
};

const bodyTitleStyle: React.CSSProperties = {
  fontSize: 16,
  fontWeight: 700,
  color: 'var(--text-1)',
};

const bodyMetaStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-3)',
  marginTop: 2,
};

const emptyStyle: React.CSSProperties = {
  padding: '20px 16px',
  textAlign: 'center',
  fontSize: 13,
  color: 'var(--text-3)',
  border: '1px dashed rgba(0,0,0,0.10)',
  borderRadius: 10,
  background: 'rgba(0,0,0,0.02)',
};

const comingSoonStyle: React.CSSProperties = {
  padding: '12px 14px',
  fontSize: 12,
  border: '1px dashed var(--accent-border)',
  borderRadius: 10,
  background: 'var(--accent-a4)',
  color: 'var(--text-2)',
};

const groupStyle: React.CSSProperties = {
  marginTop: 6,
};

const groupHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  marginBottom: 6,
};

const groupNameStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 700,
  color: 'var(--text-1)',
};

const groupMetaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const listStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

function rowStyle(isDefault: boolean): React.CSSProperties {
  return {
    display: 'flex',
    gap: 12,
    padding: '10px 12px',
    border: isDefault
        ? '1px solid var(--accent-border)'
        : '1px solid rgba(0,0,0,0.08)',
    borderRadius: 10,
    background: isDefault ? 'var(--accent-a4)' : '#fff',
  };
}

const rowMainStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
};

const rowTitleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
};

function starBtnStyle(isDefault: boolean): React.CSSProperties {
  return {
    border: 'none',
    background: 'transparent',
    fontSize: 16,
    lineHeight: 1,
    padding: 0,
    cursor: isDefault ? 'default' : 'pointer',
    color: isDefault ? '#d97706' : 'rgba(0,0,0,0.30)',
  };
}

const rowInstanceStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const rowLabelStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  padding: '1px 6px',
  background: 'rgba(0,0,0,0.04)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 999,
};

function authBadgeStyle(type: CredentialType): React.CSSProperties {
  const palette: Record<CredentialType, { fg: string; bg: string }> = {
    AI: { fg: 'var(--accent-deep)', bg: 'var(--accent-a10)' },
    ACCOUNT: { fg: '#15803d', bg: 'rgba(22, 163, 74, 0.10)' },
    REPO: { fg: '#15803d', bg: 'rgba(22, 163, 74, 0.10)' },
    INTEGRATION: { fg: '#1d4ed8', bg: 'rgba(37, 99, 235, 0.10)' },
    MCP: { fg: '#0d9488', bg: 'rgba(13, 148, 136, 0.10)' },
  };
  const p = palette[type];
  return {
    fontSize: 10,
    fontWeight: 700,
    letterSpacing: '0.04em',
    padding: '1px 6px',
    borderRadius: 4,
    color: p.fg,
    background: p.bg,
  };
}

const previewStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 11,
  color: 'var(--text-3)',
};

const rowNotesStyle: React.CSSProperties = {
  marginTop: 4,
  fontSize: 11,
  color: 'var(--text-3)',
};

const rowMetaStyle: React.CSSProperties = {
  marginTop: 4,
  fontSize: 11,
  color: 'var(--text-4, #94a3b8)',
};

const rowActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  alignSelf: 'flex-start',
};

const testOkStyle: React.CSSProperties = {
  marginTop: 6,
  padding: '4px 8px',
  borderRadius: 6,
  background: 'rgba(22, 163, 74, 0.10)',
  color: '#15803d',
  fontSize: 11,
  fontWeight: 500,
};

const testFailStyle: React.CSSProperties = {
  marginTop: 6,
  padding: '4px 8px',
  borderRadius: 6,
  background: 'rgba(207, 19, 34, 0.08)',
  color: '#cf1322',
  fontSize: 11,
  fontWeight: 500,
};

const connectedChipStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  padding: '1px 8px',
  borderRadius: 999,
  background: 'rgba(13, 148, 136, 0.10)',
  color: '#0d9488',
  letterSpacing: '0.02em',
};

const mcpMetaStyle: React.CSSProperties = {
  marginTop: 4,
  fontSize: 11,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: 'var(--text-3)',
};

export default CredentialsPage;
