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
import { useMemo, useRef, useState, type CSSProperties } from 'react';
import type { WorkModelDto, WorkModelOptionsDto } from '../types';

/**
 * Copilot-style model picker for the composer popover: a search box over a
 * flat, icon-tagged list with a checkmark on the active pick and an "Auto"
 * row that clears the scope's override (inherit the cascade default).
 *
 * <p>Deliberately lighter than the shared {@link WorkModelPicker} used by
 * Settings and the create dialogs — no account chooser, no free-text model
 * row, no CLI readiness accordion. Those live in the full picker; here the
 * search box is the only affordance. Picking an API model uses that
 * provider's ★ default account. Options are fetched by the parent and
 * passed in so the pill's label and this list share one read.
 */
export function ComposerModelPicker({
  options, override, effective, agentLocked = false, onChange,
}: {
  options: WorkModelOptionsDto;
  /** The override set directly on this scope; null = inheriting (Auto). */
  override: WorkModelDto | null;
  /** The resolved effective pick — drives the checkmark. */
  effective: WorkModelDto;
  /** Once the first turn starts, keep the provider-native conversation on
   *  its original agent while still offering that agent's other models. */
  agentLocked?: boolean;
  onChange: (next: WorkModelDto | null) => void;
}) {
  const [query, setQuery] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);

  const rows = useMemo(() => flatten(options), [options]);
  const availableRows = agentLocked
    ? rows.filter(r => r.kind === effective.kind && r.agentId === effective.agentOrProvider)
    : rows;
  const q = query.trim().toLowerCase();
  const shown = q.length === 0
    ? availableRows
    : availableRows.filter(r => `${r.modelDisplay} ${r.agentName} ${r.description ?? ''}`.toLowerCase().includes(q));

  const isActive = (r: Row) =>
    effective.kind === r.kind
    && effective.agentOrProvider === r.agentId
    && (effective.model === r.modelId || (effective.model === null && r.isDefault));

  return (
    <div style={rootStyle}>
      <input
        ref={searchRef}
        type="text"
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search models"
        style={searchStyle}
        // Portaled popover already traps Esc to close; let it bubble.
        autoFocus
      />
      {agentLocked && (
        <div style={lockHintStyle}>
          Agent locked after the first message. You can still choose another model from this agent.
        </div>
      )}
      <div style={listStyle}>
        {!agentLocked && (
          <Item
            icon={<AutoIcon />}
            label="Auto"
            sub="Inherit the default"
            checked={override === null}
            onClick={() => onChange(null)}
          />
        )}
        {shown.map(r => (
          <Item
            key={r.key}
            icon={<BrandIcon family={r.family} />}
            label={r.modelDisplay}
            sub={`${r.agentName} · ${r.kind}${r.description === null ? '' : ` — ${r.description}`}`}
            checked={isActive(r)}
            onClick={() => onChange({
              kind: r.kind,
              agentOrProvider: r.agentId,
              // Clicking a named row pins that exact CLI model. "Auto" is
              // the separate inheritance affordance above.
              model: r.modelId,
              account: null,
            })}
          />
        ))}
        {shown.length === 0 && <div style={emptyStyle}>No models match "{query}".</div>}
      </div>
    </div>
  );
}

type Family = 'claude' | 'openai' | 'deepseek' | 'other';
type Row = {
  key: string;
  kind: 'CLI' | 'API';
  agentId: string;
  agentName: string;
  modelId: string;
  modelDisplay: string;
  isDefault: boolean;
  description: string | null;
  family: Family;
};

function flatten(options: WorkModelOptionsDto): Row[] {
  const rows: Row[] = [];
  for (const a of options.cliAgents) {
    for (const m of a.models) {
      rows.push({
        key: `CLI:${a.id}:${m.id}`, kind: 'CLI', agentId: a.id, agentName: a.displayName,
        modelId: m.id, modelDisplay: m.displayName, isDefault: m.isDefault,
        description: m.description ?? null, family: familyOf(a.id, m.id),
      });
    }
  }
  for (const p of options.apiProviders) {
    for (const m of p.models) {
      rows.push({
        key: `API:${p.id}:${m.id}`, kind: 'API', agentId: p.id, agentName: p.displayName,
        modelId: m.id, modelDisplay: m.displayName, isDefault: m.isDefault,
        description: m.description ?? null, family: familyOf(p.id, m.id),
      });
    }
  }
  return rows;
}

function familyOf(agentOrProvider: string, modelId: string): Family {
  const s = `${agentOrProvider} ${modelId}`.toLowerCase();
  if (s.includes('claude') || s.includes('anthropic')) return 'claude';
  if (s.includes('deepseek')) return 'deepseek';
  if (s.includes('codex') || s.includes('openai') || s.includes('gpt')) return 'openai';
  return 'other';
}

function Item({
  icon, label, sub, checked, onClick,
}: {
  icon: React.ReactNode; label: string; sub: string; checked: boolean; onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="option"
      aria-selected={checked}
      title={sub}
      // mousedown so the composer textarea keeps focus/caret.
      onMouseDown={e => { e.preventDefault(); onClick(); }}
      style={itemStyle(checked)}
    >
      <span style={checkColStyle}>{checked ? '✓' : ''}</span>
      <span style={iconColStyle}>{icon}</span>
      <span style={textColStyle}>
        <span style={labelStyle}>{label}</span>
        <span style={subStyle}>{sub}</span>
      </span>
    </button>
  );
}

/* ── brand glyphs — clean colored monograms, not copied logos ─────── */

const FAMILY: Record<Family, { bg: string; ch: string }> = {
  claude: { bg: '#D97757', ch: '✳' },
  openai: { bg: '#111827', ch: '◯' },
  deepseek: { bg: '#4D6BFE', ch: 'D' },
  other: { bg: '#6B7280', ch: '◆' },
};

function BrandIcon({ family }: { family: Family }) {
  const { bg, ch } = FAMILY[family];
  return <span style={{ ...glyphStyle, background: bg }}>{ch}</span>;
}

function AutoIcon() {
  return <span style={{ ...glyphStyle, background: 'linear-gradient(135deg,#7c5cff,#b794f4)' }}>✦</span>;
}

/* ── styles ───────────────────────────────────────────────────────── */

const rootStyle: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 8 };

const searchStyle: CSSProperties = {
  width: '100%',
  padding: '7px 10px',
  fontSize: 13,
  border: '1px solid var(--border, #d0d7de)',
  borderRadius: 8,
  background: 'var(--bg, #fff)',
  color: 'var(--text-1)',
  fontFamily: 'inherit',
  boxSizing: 'border-box',
};

const lockHintStyle: CSSProperties = {
  padding: '6px 8px',
  borderRadius: 7,
  background: 'var(--bg-elev, rgba(0,0,0,0.04))',
  color: 'var(--text-3)',
  fontSize: 11,
  lineHeight: 1.35,
};

const listStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  maxHeight: 300,
  overflowY: 'auto',
};

function itemStyle(checked: boolean): CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    width: '100%',
    padding: '7px 8px',
    background: checked ? 'var(--bg-elev, rgba(0,0,0,0.04))' : 'transparent',
    border: 'none',
    borderRadius: 8,
    cursor: 'pointer',
    fontFamily: 'inherit',
    textAlign: 'left',
  };
}

const checkColStyle: CSSProperties = {
  width: 14,
  flexShrink: 0,
  color: 'var(--accent, #2563eb)',
  fontSize: 12,
  textAlign: 'center',
};

const iconColStyle: CSSProperties = { flexShrink: 0, display: 'inline-flex' };

const glyphStyle: CSSProperties = {
  width: 20,
  height: 20,
  borderRadius: 6,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontSize: 12,
  lineHeight: 1,
};

const textColStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0,
  lineHeight: 1.25,
};

const labelStyle: CSSProperties = {
  fontSize: 13,
  fontWeight: 500,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const subStyle: CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const emptyStyle: CSSProperties = {
  padding: '10px 8px',
  fontSize: 12,
  color: 'var(--text-3)',
};
