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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ConceptRowDto } from '../../types';

type Kind = ConceptRowDto['kind'];

const KINDS: Kind[] = ['NOUN', 'STATE', 'FILTER', 'VERB'];

/** Read-only catalog of every concept the registry surfaces — the
 *  same data the agent's list_terms tool returns, but rendered for
 *  a human. Defines no edit / delete / add affordance; concepts
 *  are authored via {@code @Concept} in code or under {@code ##
 *  Glossary} in the brain. */
function ConceptsPage() {
  const [rows, setRows] = useState<ConceptRowDto[] | null>(null);
  const [activeKind, setActiveKind] = useState<Kind>('NOUN');
  const [query, setQuery] = useState('');
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const next = await window.bridge.listConcepts({});
      setRows(next);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const needle = query.trim().toLowerCase();
  const filtered = useMemo(() => {
    if (rows === null) return [];
    return rows.filter(row => {
      if (row.kind !== activeKind) return false;
      if (needle.length === 0) return true;
      if (row.name.toLowerCase().includes(needle)) return true;
      if (row.definition.toLowerCase().includes(needle)) return true;
      return row.aka.some(a => a.toLowerCase().includes(needle));
    });
  }, [rows, activeKind, needle]);

  const totalForKind = rows === null
    ? 0
    : rows.filter(r => r.kind === activeKind).length;

  return (
    <section className="settings-page" aria-labelledby="concepts-heading">
      <header className="settings-page__head">
        <h2 id="concepts-heading">Concepts</h2>
        <p className="settings-page__lede">
          Every domain term the system pins. Filter by kind on the left,
          search across name + definition + aliases at the top. Concepts
          are authored elsewhere — in code via <code>@Concept</code> or
          in the workspace brain under <code>## Glossary</code>.
        </p>
      </header>

      {error !== null && (
        <div className="settings-page__error" role="alert">
          {error}
        </div>
      )}

      <div style={layoutStyle}>
        <nav style={navStyle} aria-label="Kind filter">
          {KINDS.map(k => (
            <button
              key={k}
              type="button"
              onClick={() => setActiveKind(k)}
              style={k === activeKind ? activeNavItemStyle : navItemStyle}
            >
              {k.toLowerCase()}
              <span style={navCountStyle}>
                {rows === null ? '' : rows.filter(r => r.kind === k).length}
              </span>
            </button>
          ))}
        </nav>

        <div style={mainStyle}>
          <div style={searchBarStyle}>
            <input
              type="search"
              placeholder="Search name, definition, aliases"
              value={query}
              onChange={ev => setQuery(ev.target.value)}
              style={searchInputStyle}
              spellCheck={false}
              autoComplete="off"
            />
            <span style={countHintStyle}>
              {filtered.length} of {totalForKind} · {activeKind.toLowerCase()}
            </span>
          </div>

          {rows === null && (
            <div className="settings-page__placeholder">Loading…</div>
          )}
          {rows !== null && filtered.length === 0 && (
            <div className="settings-page__placeholder">
              No concepts match.
            </div>
          )}
          {filtered.length > 0 && (
            <ul style={listStyle}>
              {filtered.map(row => (
                <li key={row.name} style={rowStyle}>
                  <div style={rowHeadStyle}>
                    <code style={rowNameStyle}>{row.name}</code>
                    <span style={kindChipStyle(row.kind)}>{row.kind.toLowerCase()}</span>
                    <span style={scopeChipStyle}>{row.scope.toLowerCase()}</span>
                    {row.aka.length > 0 && (
                      <span style={akaStyle}>aka: {row.aka.join(', ')}</span>
                    )}
                  </div>
                  <div style={rowDefStyle}>{row.definition}</div>
                  {row.relatedTools.length > 0 && (
                    <div style={rowChipsStyle}>
                      <span style={chipLabelStyle}>tools</span>
                      {row.relatedTools.map(t => (
                        <span key={t} style={smallChipStyle}>{t}</span>
                      ))}
                    </div>
                  )}
                  {row.sources.length > 0 && (
                    <div style={rowChipsStyle}>
                      <span style={chipLabelStyle}>source</span>
                      <code style={sourceCodeStyle}>{row.sources[0]}</code>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  );
}

const layoutStyle: React.CSSProperties = {
  display: 'flex',
  gap: 16,
  marginTop: 12,
};

const navStyle: React.CSSProperties = {
  width: 140,
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  flexShrink: 0,
};

const navItemStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  padding: '6px 10px',
  background: 'transparent',
  border: '1px solid transparent',
  borderRadius: 4,
  cursor: 'pointer',
  fontSize: 12,
  textTransform: 'capitalize',
  textAlign: 'left',
};

const activeNavItemStyle: React.CSSProperties = {
  ...navItemStyle,
  background: '#eef0fd',
  borderColor: '#c7ccef',
};

const navCountStyle: React.CSSProperties = {
  fontSize: 10,
  color: '#6b6b78',
};

const mainStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  minWidth: 0,
};

const searchBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const searchInputStyle: React.CSSProperties = {
  flex: 1,
  padding: '6px 10px',
  border: '1px solid #c8c8d0',
  borderRadius: 4,
  fontSize: 12,
};

const countHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#6b6b78',
};

const listStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const rowStyle: React.CSSProperties = {
  border: '1px solid #d8d8e0',
  borderRadius: 6,
  padding: 10,
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const rowHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
};

const rowNameStyle: React.CSSProperties = {
  fontSize: 13,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontWeight: 600,
};

const akaStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#6b6b78',
};

const rowDefStyle: React.CSSProperties = {
  fontSize: 12,
  lineHeight: 1.5,
};

const rowChipsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  flexWrap: 'wrap',
  fontSize: 11,
};

const chipLabelStyle: React.CSSProperties = {
  color: '#6b6b78',
  textTransform: 'uppercase',
  fontSize: 10,
  letterSpacing: 0.4,
};

const smallChipStyle: React.CSSProperties = {
  border: '1px solid #c8c8d0',
  padding: '1px 6px',
  borderRadius: 4,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const sourceCodeStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 11,
  color: '#3b3b48',
};

function kindChipStyle(kind: Kind): React.CSSProperties {
  const palette: Record<Kind, string> = {
    NOUN: '#4a3aff',
    STATE: '#b56f00',
    FILTER: '#1f5fbf',
    VERB: '#2e7d32',
  };
  return {
    background: palette[kind],
    color: 'white',
    borderRadius: 4,
    padding: '2px 6px',
    fontSize: 10,
    fontWeight: 600,
    letterSpacing: 0.4,
    textTransform: 'uppercase',
  };
}

const scopeChipStyle: React.CSSProperties = {
  border: '1px solid #c8c8d0',
  padding: '1px 6px',
  borderRadius: 4,
  fontSize: 10,
  color: '#6b6b78',
  textTransform: 'uppercase',
  letterSpacing: 0.4,
};

export default ConceptsPage;
