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
import type { SkillDto } from '../../types';

/** The four agent roles, with their fixed (app-owned, non-editable)
 *  capability templates. Roles are identity the runtime owns — not a
 *  skill kind — so this surface is read-only. Which skills a role
 *  resolves is derived from a skill's usage: Trunk / Task see
 *  development skills; Reviewer / Lead see review skills. */
const ROLE_CARDS: {
  id: string;
  label: string;
  kind: string;
  usage: 'build' | 'review';
  can: string[];
  cant: string[];
  blurb: string;
}[] = [
  {
    id: 'trunk',
    label: 'Trunk',
    kind: 'fixed template',
    usage: 'build',
    can: ['create_task', 'search', 'recall'],
    cant: ['edit files', 'push'],
    blurb: 'Orchestrates planning; cuts tasks but never writes code or pushes. '
        + 'Ships with the app — not editable here.',
  },
  {
    id: 'task',
    label: 'Task',
    kind: 'generated per task · frozen',
    usage: 'build',
    can: ['edit files', 'push (gated)', 'comment'],
    cant: ['create_task', 'change role'],
    blurb: 'Composed at task creation from the task\'s repo / branch / PR; '
        + 'frozen onto the task so behaviour is reproducible.',
  },
  {
    id: 'reviewer',
    label: 'Reviewer',
    kind: 'review panel seat',
    usage: 'review',
    can: ['read diff', 'comment'],
    cant: ['edit files', 'push', 'create_task'],
    blurb: 'A panel seat\'s reviewing voice — a review skill the Lead can '
        + '@mention. Reads the diff and reports findings; never writes.',
  },
  {
    id: 'lead',
    label: 'Lead',
    kind: 'review panel orchestrator',
    usage: 'review',
    can: ['arbitrate panel', 'publish review'],
    cant: ['edit files', 'push code'],
    blurb: 'Final arbiter on the review panel — drives the agenda and '
        + 'dispatches reviewers. Same review-skill voice as a Reviewer.',
  },
];

/**
 * Settings → Agent roles. Read-only view of the Trunk / Task / Reviewer
 * / Lead permission templates and, per role, how many vault skills would
 * resolve for it (derived from each skill's usage — the user never tags
 * a skill with a role). Users can see why an agent loads a given skill
 * but can't edit the roles themselves.
 */
function AgentRolesPage() {
  const [skills, setSkills] = useState<SkillDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    window.bridge.listSkills()
      .then(list => { if (!cancelled) setSkills(list); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // A role resolves the enabled skills whose usage matches its surface.
  const resolvedFor = useMemo(() => {
    const byUsage = (usage: 'build' | 'review') =>
      skills.filter(s => s.enabled && s.usage === usage);
    return (usage: 'build' | 'review') => byUsage(usage);
  }, [skills]);

  return (
    <section style={pageStyle}>
      <header style={headStyle}>
        <h2 style={titleStyle}>Agent roles</h2>
        <p style={subtitleStyle}>
          The runtime owns four roles. Their capabilities are fixed templates that
          ship with the app — read-only here. Which skills a role loads is derived
          from each skill&apos;s usage, so you never tag a skill with a role.
        </p>
      </header>

      {error !== null && <div style={errorStyle} role="alert">{error}</div>}

      <div style={gridStyle}>
        {ROLE_CARDS.map(card => {
          const resolved = resolvedFor(card.usage);
          const open = expanded === card.id;
          return (
            <article key={card.id} style={cardStyle}>
              <header style={cardHeadStyle}>
                <div>
                  <div style={cardLabelStyle}>{card.label}</div>
                  <div style={cardKindStyle}>{card.kind}</div>
                </div>
                <span style={cardTagStyle}>role</span>
              </header>
              <p style={blurbStyle}>{card.blurb}</p>
              <div style={chipColStyle}>
                <div style={chipRowStyle}>
                  <span style={chipLabelOkStyle}>can</span>
                  {card.can.map(c => <span key={c} style={chipOkStyle}>{c}</span>)}
                </div>
                <div style={chipRowStyle}>
                  <span style={chipLabelNoStyle}>can&apos;t</span>
                  {card.cant.map(c => <span key={c} style={chipNoStyle}>{c}</span>)}
                </div>
              </div>
              <button
                type="button"
                style={resolveLineStyle}
                onClick={() => setExpanded(open ? null : card.id)}
                disabled={resolved.length === 0}
                aria-expanded={open}
              >
                {loading
                  ? 'resolving…'
                  : `would resolve ${resolved.length} skill${resolved.length === 1 ? '' : 's'}`}
                {resolved.length > 0 && <span style={caretStyle}>{open ? '▾' : '▸'}</span>}
              </button>
              {open && (
                <ul style={resolvedListStyle}>
                  {resolved.map(s => (
                    <li key={s.id} style={resolvedItemStyle}>
                      <span style={resolvedNameStyle}>{s.name}</span>
                      <span style={resolvedScopeStyle}>
                        {s.scope === 'repo' ? (s.repo ?? 'repo') : s.scope}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}

const pageStyle: React.CSSProperties = { padding: '4px 2px 40px' };

const headStyle: React.CSSProperties = { marginBottom: 18 };

const titleStyle: React.CSSProperties = {
  margin: '0 0 6px',
  fontSize: 18,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const subtitleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 13,
  lineHeight: 1.5,
  color: 'var(--text-3)',
  maxWidth: 720,
};

const errorStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 8,
  color: '#cf1322',
  fontSize: 13,
};

const gridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
  gap: 14,
};

const cardStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 12,
  padding: 16,
  background: 'var(--bg-1)',
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const cardHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
};

const cardLabelStyle: React.CSSProperties = {
  fontSize: 15,
  fontWeight: 700,
  color: 'var(--text-1)',
};

const cardKindStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  marginTop: 2,
};

const cardTagStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: 'var(--text-3)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  padding: '2px 8px',
};

const blurbStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 12.5,
  lineHeight: 1.5,
  color: 'var(--text-2)',
};

const chipColStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6 };

const chipRowStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 5,
  alignItems: 'center',
};

const chipLabelOkStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: '#15803d',
  minWidth: 34,
};

const chipLabelNoStyle: React.CSSProperties = { ...chipLabelOkStyle, color: '#b91c1c' };

const chipOkStyle: React.CSSProperties = {
  fontSize: 11,
  padding: '2px 7px',
  borderRadius: 6,
  background: 'rgba(22,163,74,0.10)',
  color: '#15803d',
};

const chipNoStyle: React.CSSProperties = {
  fontSize: 11,
  padding: '2px 7px',
  borderRadius: 6,
  background: 'rgba(185,28,28,0.08)',
  color: '#b91c1c',
};

const resolveLineStyle: React.CSSProperties = {
  marginTop: 4,
  alignSelf: 'flex-start',
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '4px 8px',
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--accent, #2563eb)',
  background: 'transparent',
  border: '1px solid var(--border)',
  borderRadius: 8,
  cursor: 'pointer',
};

const caretStyle: React.CSSProperties = { fontSize: 10 };

const resolvedListStyle: React.CSSProperties = {
  margin: '4px 0 0',
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const resolvedItemStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 8,
  padding: '4px 8px',
  borderRadius: 6,
  background: 'var(--bg-2)',
  fontSize: 12,
};

const resolvedNameStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const resolvedScopeStyle: React.CSSProperties = {
  flexShrink: 0,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 10,
  color: 'var(--text-3)',
};

export default AgentRolesPage;
