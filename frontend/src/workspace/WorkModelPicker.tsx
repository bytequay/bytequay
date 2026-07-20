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
import type {
  WorkModelAgentOptionDto,
  WorkModelDto,
  WorkModelOptionsDto,
  WorkModelProviderOptionDto,
} from '../types';

type Props = {
  /** The current pick — null means the cascade is inheriting from a
   *  parent scope / global default. */
  value: WorkModelDto | null;
  onChange: (next: WorkModelDto | null) => void;
};

/**
 * Two-level drill-in for the model & provider axis. Top level lists
 * CLI agents and API providers as equal peers; clicking an option
 * expands it to its model list. Selecting a model commits the pick;
 * the parent owns persistence.
 *
 * <p>Data is fetched on mount and again whenever the user hits the
 * refresh affordance — both reads merge the curated catalog with the
 * user's credentials (gates API providers) and the local CLI
 * detection (drives the readiness badge on CLI rows).
 */
export function WorkModelPicker({ value, onChange }: Props) {
  const [options, setOptions] = useState<WorkModelOptionsDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = async (refresh = false) => {
    try {
      const next = refresh
        ? await window.bridge.refreshWorkModelOptions()
        : await window.bridge.getWorkModelOptions();
      setOptions(next);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => { void load(false); }, []);

  const onRefresh = async () => {
    setRefreshing(true);
    try { await load(true); }
    finally { setRefreshing(false); }
  };

  const selectCli = (agentId: string, modelId: string | null, reasoningEffort: string | null) => {
    onChange({
      kind: 'CLI',
      agentOrProvider: agentId,
      model: modelId,
      account: null,
      reasoningEffort,
    });
  };

  const selectApi = (providerId: string, modelId: string | null, account: string | null) => {
    onChange({
      kind: 'API',
      agentOrProvider: providerId,
      model: modelId,
      account,
    });
  };

  if (options === null && error === null) {
    return <div style={mutedStyle}>Loading work models…</div>;
  }

  return (
    <div style={rootStyle}>
      <div style={headRowStyle}>
        <span style={labelStyle}>DEFAULT WORK MODEL</span>
        <button
          type="button"
          onClick={() => { void onRefresh(); }}
          disabled={refreshing}
          style={refreshBtnStyle}
          title="Re-probe CLI agents and re-read credentials"
        >
          {refreshing ? 'Refreshing…' : '⟳ Refresh'}
        </button>
      </div>

      <CurrentCard value={value} options={options} onClear={() => onChange(null)} />

      {error !== null && (
        <div style={errStyle}>Couldn't load options: {error}</div>
      )}

      {options !== null && (
        <div style={listStyle}>
          {options.cliAgents.map(agent => (
            <CliRow
              key={agent.id}
              agent={agent}
              expanded={expandedId === cliKey(agent.id)}
              selected={value !== null && value.kind === 'CLI' && value.agentOrProvider === agent.id}
              activeModel={value !== null && value.kind === 'CLI' && value.agentOrProvider === agent.id ? value.model : null}
              activeEffort={value !== null && value.kind === 'CLI' && value.agentOrProvider === agent.id
                ? value.reasoningEffort ?? null : null}
              onToggle={() => setExpandedId(prev => prev === cliKey(agent.id) ? null : cliKey(agent.id))}
              onPick={(modelId, effort) => selectCli(agent.id, modelId, effort)}
            />
          ))}
          {options.apiProviders.map(provider => (
            <ApiRow
              key={provider.id}
              provider={provider}
              expanded={expandedId === apiKey(provider.id)}
              selected={value !== null && value.kind === 'API' && value.agentOrProvider === provider.id}
              activeModel={value !== null && value.kind === 'API' && value.agentOrProvider === provider.id ? value.model : null}
              activeAccount={value !== null && value.kind === 'API' && value.agentOrProvider === provider.id ? value.account : null}
              onToggle={() => setExpandedId(prev => prev === apiKey(provider.id) ? null : apiKey(provider.id))}
              onPick={(modelId, account) => selectApi(provider.id, modelId, account)}
            />
          ))}
          {options.cliAgents.length === 0 && options.apiProviders.length === 0 && (
            <div style={emptyStyle}>
              No CLI agents installed and no API credentials configured.
              Wire one up in <strong>Settings → AI → Credentials</strong>
              {' '}or install a CLI agent and click refresh.
            </div>
          )}
        </div>
      )}

    </div>
  );
}

const cliKey = (id: string) => `cli:${id}`;
const apiKey = (id: string) => `api:${id}`;

function CurrentCard({
  value, options, onClear,
}: {
  value: WorkModelDto | null;
  options: WorkModelOptionsDto | null;
  onClear: () => void;
}) {
  if (value === null) {
    return (
      <div style={currentCardStyle}>
        <div style={currentNameStyle}>
          <em style={mutedStyle}>No override — inheriting the global default.</em>
        </div>
      </div>
    );
  }
  const label = labelForChoice(value, options);
  return (
    <div style={currentCardStyle}>
      <div style={currentNameStyle}>{label}</div>
      <button type="button" onClick={onClear} style={clearBtnStyle} title="Clear the override and inherit">
        Clear
      </button>
    </div>
  );
}

function labelForChoice(value: WorkModelDto, options: WorkModelOptionsDto | null): string {
  const fallback = `${value.agentOrProvider}${value.model !== null ? ' · ' + value.model : ''}`;
  if (options === null) return fallback;
  if (value.kind === 'CLI') {
    const agent = options.cliAgents.find(a => a.id === value.agentOrProvider);
    if (agent === undefined) return fallback;
    const modelId = value.model ?? agent.defaultModel;
    const model = agent.models.find(m => m.id === modelId);
    const modelLabel = model !== undefined ? model.displayName : modelId;
    const effort = value.reasoningEffort ?? model?.defaultReasoningEffort;
    return `${agent.displayName} · ${modelLabel} · CLI${effort === null || effort === undefined ? '' : ` · ${effort}`}`;
  }
  const provider = options.apiProviders.find(p => p.id === value.agentOrProvider);
  if (provider === undefined) return fallback;
  const modelId = value.model ?? provider.defaultModel;
  const model = provider.models.find(m => m.id === modelId);
  const modelLabel = model !== undefined ? model.displayName : modelId;
  const accountLabel = value.account !== null ? ` · ${value.account}` : '';
  return `${provider.displayName} · ${modelLabel} · API${accountLabel}`;
}

function CliRow({
  agent, expanded, selected, activeModel, activeEffort, onToggle, onPick,
}: {
  agent: WorkModelAgentOptionDto;
  expanded: boolean;
  selected: boolean;
  activeModel: string | null;
  activeEffort: string | null;
  onToggle: () => void;
  onPick: (modelId: string | null, reasoningEffort: string | null) => void;
}) {
  const readinessChip = agent.installed && agent.authed
    ? <span style={chipOkStyle}>✓ installed &amp; authed</span>
    : agent.installed
      ? <span style={chipWarnStyle}>installed · not authed</span>
      : <span style={chipMutedStyle}>set up →</span>;
  // Whether the active model is one the catalog knows about. If not,
  // we surface it in the "Other" row so the user sees the custom id
  // they typed instead of it appearing unselected.
  const activeIsCustom = activeModel !== null
      && !agent.models.some(m => m.id === activeModel);
  const selectedModel = agent.models.find(m => m.id === (activeModel ?? agent.defaultModel));
  const efforts = selectedModel?.supportedReasoningEfforts ?? [];
  return (
    <div style={rowStyle(selected)}>
      <button type="button" onClick={onToggle} style={rowHeadStyle}>
        <span style={kindBadgeStyle('cli')}>CLI</span>
        <span style={rowTitleStyle}>{agent.displayName}</span>
        {readinessChip}
        <span style={chevStyle}>{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div style={modelsStyle}>
          {agent.models.map(m => (
            <button
              key={m.id}
              type="button"
              onClick={() => onPick(m.id, null)}
              style={modelBtnStyle(
                (activeModel === null && m.isDefault) || activeModel === m.id,
              )}
              disabled={!agent.installed}
              title={!agent.installed ? 'Install the CLI to pick a model' : m.description ?? undefined}
            >
              <span>{m.displayName}</span>
              {m.isDefault && <span style={defaultTagStyle}>Default</span>}
            </button>
          ))}
          <OtherModelRow
            disabled={!agent.installed}
            active={activeIsCustom}
            value={activeIsCustom && activeModel !== null ? activeModel : ''}
            onCommit={(value) => onPick(value, null)}
          />
          {selected && efforts.length > 0 && (
            <label style={effortRowStyle}>
              <span style={mutedStyle}>Reasoning effort:</span>
              <select
                value={activeEffort ?? selectedModel?.defaultReasoningEffort ?? ''}
                onChange={(event) => onPick(activeModel, event.target.value)}
                style={effortSelectStyle}
              >
                {efforts.map(effort => (
                  <option key={effort.id} value={effort.id} title={effort.description ?? undefined}>
                    {effort.id}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
      )}
    </div>
  );
}

function ApiRow({
  provider, expanded, selected, activeModel, activeAccount, onToggle, onPick,
}: {
  provider: WorkModelProviderOptionDto;
  expanded: boolean;
  selected: boolean;
  activeModel: string | null;
  activeAccount: string | null;
  onToggle: () => void;
  onPick: (modelId: string | null, account: string | null) => void;
}) {
  const defaultAccount = provider.accounts.find(a => a.isDefault) ?? provider.accounts[0] ?? null;
  const accountChip = defaultAccount === null
    ? <span style={chipMutedStyle}>no accounts</span>
    : <span style={chipOkStyle}>★ {defaultAccount.name}</span>;
  return (
    <div style={rowStyle(selected)}>
      <button type="button" onClick={onToggle} style={rowHeadStyle}>
        <span style={kindBadgeStyle('api')}>API</span>
        <span style={rowTitleStyle}>{provider.displayName}</span>
        {accountChip}
        <span style={chevStyle}>{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div style={modelsStyle}>
          {provider.models.map(m => {
            const isActive = (activeModel === null && m.isDefault) || activeModel === m.id;
            return (
              <button
                key={m.id}
                type="button"
                onClick={() => onPick(m.id, activeAccount)}
                style={modelBtnStyle(isActive)}
                title={m.description ?? undefined}
              >
                <span>{m.displayName}</span>
                {m.isDefault && <span style={defaultTagStyle}>Default</span>}
              </button>
            );
          })}
          <OtherModelRow
            active={activeModel !== null && !provider.models.some(m => m.id === activeModel)}
            value={activeModel !== null
                && !provider.models.some(m => m.id === activeModel)
                  ? activeModel : ''}
            onCommit={(value) => onPick(value, activeAccount)}
          />
          {provider.accounts.length > 1 && (
            <div style={accountsRowStyle}>
              <span style={mutedStyle}>Account:</span>
              {provider.accounts.map(a => {
                const isActive = (activeAccount === null && a.isDefault) || activeAccount === a.name;
                return (
                  <button
                    key={a.name}
                    type="button"
                    onClick={() => onPick(activeModel, a.isDefault ? null : a.name)}
                    style={accountBtnStyle(isActive)}
                    title={a.valid === true ? 'Reachable' : a.valid === false ? 'Last probe failed' : 'Never probed'}
                  >
                    {a.isDefault && <span style={defaultTagStyle}>★</span>}
                    {a.name}
                  </button>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Free-text input for a model id the catalog doesn't list yet.
 *  Commits on blur or Enter; passing an empty value commits null
 *  (which reads as "use the agent / provider default" on the parent). */
function OtherModelRow({
  active, value, onCommit, disabled = false,
}: {
  active: boolean;
  value: string;
  onCommit: (next: string | null) => void;
  disabled?: boolean;
}) {
  const [draft, setDraft] = useState(value);
  // Sync the draft when the parent's active model changes (e.g. the
  // user picked a catalog row after typing) so the input doesn't
  // hold stale text.
  useEffect(() => { setDraft(value); }, [value]);
  const commit = () => {
    const trimmed = draft.trim();
    onCommit(trimmed.length === 0 ? null : trimmed);
  };
  return (
    <div style={otherRowStyle(active)}>
      <span style={otherLabelStyle}>Other…</span>
      <input
        type="text"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            commit();
            (e.target as HTMLInputElement).blur();
          }
        }}
        disabled={disabled}
        placeholder="e.g. claude-opus-5"
        style={otherInputStyle}
      />
    </div>
  );
}

/* ── styles ─────────────────────────────────────────────────────── */

const rootStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const headRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
};

const labelStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
};

const refreshBtnStyle: React.CSSProperties = {
  padding: '3px 9px',
  fontSize: 11,
  border: '1px solid var(--border)',
  background: 'transparent',
  color: 'var(--text-2)',
  borderRadius: 999,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const currentCardStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '10px 14px',
  border: '1px solid var(--border)',
  borderRadius: 10,
  background: 'var(--bg-elevated)',
};

const currentNameStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const clearBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  fontSize: 11,
  border: '1px solid var(--border)',
  background: '#fff',
  color: 'var(--text-2)',
  borderRadius: 999,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const errStyle: React.CSSProperties = {
  padding: '6px 10px',
  fontSize: 11,
  color: '#b91c1c',
  background: 'rgba(185,28,28,0.06)',
  border: '1px solid rgba(185,28,28,0.20)',
  borderRadius: 6,
};

const emptyStyle: React.CSSProperties = {
  padding: 12,
  fontSize: 12,
  color: 'var(--text-3)',
  border: '1px dashed var(--border)',
  borderRadius: 8,
  lineHeight: 1.5,
};

function rowStyle(selected: boolean): React.CSSProperties {
  return {
    border: selected ? '1px solid rgba(124,58,237,0.40)' : '1px solid var(--border)',
    background: selected ? 'rgba(124,58,237,0.06)' : '#fff',
    borderRadius: 10,
    overflow: 'hidden',
  };
}

const rowHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  width: '100%',
  padding: '8px 12px',
  background: 'transparent',
  border: 'none',
  cursor: 'pointer',
  fontFamily: 'inherit',
  textAlign: 'left',
};

const rowTitleStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const chevStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  flexShrink: 0,
};

function kindBadgeStyle(kind: 'cli' | 'api'): React.CSSProperties {
  return {
    fontSize: 9,
    fontWeight: 700,
    letterSpacing: '0.06em',
    padding: '1px 6px',
    borderRadius: 4,
    background: kind === 'cli' ? 'rgba(124,58,237,0.10)' : 'rgba(2,132,199,0.10)',
    color: kind === 'cli' ? '#6d28d9' : '#0369a1',
  };
}

const chipBaseStyle: React.CSSProperties = {
  fontSize: 10,
  padding: '1px 8px',
  borderRadius: 999,
  whiteSpace: 'nowrap',
  flexShrink: 0,
};

const chipOkStyle: React.CSSProperties = {
  ...chipBaseStyle,
  background: 'rgba(22,163,74,0.10)',
  color: '#15803d',
};

const chipWarnStyle: React.CSSProperties = {
  ...chipBaseStyle,
  background: 'rgba(245,158,11,0.10)',
  color: '#b45309',
};

const chipMutedStyle: React.CSSProperties = {
  ...chipBaseStyle,
  background: 'var(--bg-elevated)',
  color: 'var(--text-3)',
};

const modelsStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  padding: '4px 8px 8px',
  background: 'rgba(0,0,0,0.02)',
  borderTop: '1px solid var(--border)',
};

function modelBtnStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '6px 12px',
    background: active ? 'rgba(124,58,237,0.10)' : 'transparent',
    border: 'none',
    borderRadius: 6,
    cursor: 'pointer',
    fontFamily: 'inherit',
    fontSize: 12,
    color: active ? '#6d28d9' : 'var(--text-2)',
    fontWeight: active ? 600 : 500,
    textAlign: 'left',
  };
}

const defaultTagStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.04em',
  color: 'var(--text-3)',
  background: 'var(--bg-elevated)',
  padding: '1px 6px',
  borderRadius: 4,
};

const accountsRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 12px',
  flexWrap: 'wrap',
  fontSize: 11,
};

const effortRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '6px 12px',
};

const effortSelectStyle: React.CSSProperties = {
  padding: '3px 8px',
  fontSize: 11,
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: '#fff',
  color: 'var(--text-2)',
  fontFamily: 'inherit',
};

function accountBtnStyle(active: boolean): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    padding: '3px 10px',
    fontSize: 11,
    border: active ? '1px solid rgba(124,58,237,0.40)' : '1px solid var(--border)',
    background: active ? 'rgba(124,58,237,0.06)' : '#fff',
    color: active ? '#6d28d9' : 'var(--text-2)',
    borderRadius: 999,
    cursor: 'pointer',
    fontFamily: 'inherit',
  };
}

const mutedStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontSize: 12,
};

function otherRowStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '6px 12px',
    border: active ? '1px dashed rgba(124,58,237,0.40)' : '1px dashed transparent',
    borderRadius: 6,
    background: active ? 'rgba(124,58,237,0.04)' : 'transparent',
  };
}

const otherLabelStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontStyle: 'italic',
  flexShrink: 0,
};

const otherInputStyle: React.CSSProperties = {
  flex: 1,
  padding: '4px 8px',
  fontSize: 11,
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: '#fff',
  color: 'var(--text-1)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};
