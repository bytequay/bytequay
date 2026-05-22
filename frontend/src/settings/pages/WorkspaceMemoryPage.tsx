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
import WorkspaceMemoryProposalBanner from './WorkspaceMemoryProposalBanner';

/** v1 ships a single ambient workspace. When multi-workspace
 *  switching arrives, this constant becomes a useWorkspace() lookup. */
const DEFAULT_WORKSPACE_ID = 'ws-default';

/** Soft character target — matches the backend constant
 *  ({@code MEMORY_MD_TARGET_CHARS}). The editor renders past this
 *  but warns about budget overruns; the backend's hard cap is 32 000
 *  chars (PUT returns 413 past that). */
const TARGET_CHARS = 8_000;

type Props = {
  /** Wires up the back-link chips in the memory proposal banner so a
   *  click navigates to the source thread. Forwarded from
   *  SettingsShell, originally App.tsx's nav dispatch. */
  onOpenThread?: (threadId: string) => void;
};

function WorkspaceMemoryPage({ onOpenThread }: Props) {
  const [memory, setMemory] = useState('');
  const [original, setOriginal] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [distilling, setDistilling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState<string | null>(null);
  /** Default is the parsed section view; edit drops the user into the
   *  textarea. Save returns to view so the new sections are visible
   *  without an extra click. */
  const [mode, setMode] = useState<'view' | 'edit'>('view');
  /** Bumped to nudge the banner into an immediate refresh after a
   *  manual Distill click — without it the user would wait up to 15s
   *  for the next poll to show the new proposal. */
  const [proposalRefreshKey, setProposalRefreshKey] = useState(0);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const { memoryMd } = await window.bridge.getWorkspaceMemory(DEFAULT_WORKSPACE_ID);
      setMemory(memoryMd);
      setOriginal(memoryMd);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const dirty = memory !== original;
  const overBudget = memory.length > TARGET_CHARS;

  const save = async () => {
    setSaving(true);
    setError(null);
    setStatusMsg(null);
    try {
      const updated = await window.bridge.setWorkspaceMemory(DEFAULT_WORKSPACE_ID, memory);
      setMemory(updated.memoryMd);
      setOriginal(updated.memoryMd);
      setStatusMsg('Saved.');
      // Return to the section view after a successful save so the
      // user immediately sees the rendered structure.
      setMode('view');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const discard = () => {
    setMemory(original);
    setStatusMsg(null);
    setError(null);
  };

  const distill = async () => {
    setDistilling(true);
    setError(null);
    setStatusMsg(null);
    try {
      const proposal = await window.bridge.distillWorkspaceMemory(DEFAULT_WORKSPACE_ID);
      if (proposal === null) {
        setStatusMsg('No proposal queued — there were no Thread Overalls to fold '
            + 'in, or the distilled body matched the current memory.');
      } else {
        setStatusMsg(`Proposed ${proposal.proposedMd.length.toLocaleString()} chars `
            + '— review the banner above and apply or discard.');
        setProposalRefreshKey(k => k + 1);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setDistilling(false);
    }
  };

  const sections = parseMemorySections(memory);

  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">Memory</h1>
          <div className="workspace-pageheader__meta">
            The distilled project brain every thread inherits — distillation
            folds recent Thread Overalls in every 30 minutes, or write it by
            hand.
          </div>
        </div>
        <div style={headerActionsStyle}>
          <button
            type="button"
            style={secondaryActionStyle}
            onClick={() => { setMode(mode === 'view' ? 'edit' : 'view'); }}
            disabled={loading}
          >
            {mode === 'view' ? 'Edit raw markdown' : 'View sections'}
          </button>
          <button
            type="button"
            className="workspace-pageheader__action"
            onClick={() => { void distill(); }}
            disabled={distilling || saving}
            title="Ask Haiku to fold recent Thread Overalls into this blob"
          >
            {distilling ? 'Distilling…' : 'Distill from threads'}
          </button>
        </div>
      </header>

      <WorkspaceMemoryProposalBanner
        workspaceId={DEFAULT_WORKSPACE_ID}
        refreshKey={proposalRefreshKey}
        onApplied={() => { void load(); }}
        onOpenThread={onOpenThread}
      />

      <section className="workspace-card" style={budgetCardStyle} aria-label="Memory budget">
        <BudgetBar used={memory.length} target={TARGET_CHARS} />
        <span style={budgetTextInlineStyle}>
          {memory.length.toLocaleString()} / {TARGET_CHARS.toLocaleString()} chars
          {overBudget && <span style={budgetWarnStyle}> · over budget</span>}
        </span>
      </section>

      {loading ? (
        <section className="workspace-card" aria-label="Loading">
          <div style={loadingStyle}>Loading…</div>
        </section>
      ) : mode === 'view' ? (
        sections.length === 0 ? (
          <section className="workspace-card" aria-label="Empty memory">
            <div style={emptyStateStyle}>
              No memory yet. Distill from threads, or switch to
              "Edit raw markdown" to write the first section.
            </div>
          </section>
        ) : (
          <>
            {sections.map(s => (
              <section
                className="workspace-card"
                style={sectionCardStyle}
                key={s.heading}
                aria-label={s.heading}
              >
                <div className="workspace-card__head">
                  <div className="workspace-card__title">
                    {s.heading}
                    <span className="workspace-card__title-count">
                      {s.bullets.length}
                    </span>
                  </div>
                </div>
                <ul style={sectionBulletsStyle}>
                  {s.bullets.map((b, i) => (
                    <li key={i} style={bulletStyleFor(s.heading)}>{b}</li>
                  ))}
                </ul>
              </section>
            ))}
          </>
        )
      ) : (
        <section className="workspace-card" aria-label="WORKSPACE.md editor">
          <div className="workspace-card__head">
            <div className="workspace-card__title">WORKSPACE.md</div>
          </div>
          <textarea
            value={memory}
            onChange={e => { setMemory(e.target.value); }}
            spellCheck={false}
            style={textareaStyle}
            placeholder="## Architecture&#10;&#10;## Active work&#10;&#10;## Decisions&#10;&#10;## Blockers"
          />
          <div style={actionsRowStyle}>
            <button
              type="button"
              className="button button--primary"
              onClick={() => { void save(); }}
              disabled={!dirty || saving}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
            <button
              type="button"
              className="button"
              onClick={discard}
              disabled={!dirty || saving}
            >
              Discard changes
            </button>
            <div style={{ flex: 1 }} />
            <span style={hintStyle}>
              Target ~{TARGET_CHARS.toLocaleString()} chars; budget bar turns
              red past the soft cap.
            </span>
          </div>
        </section>
      )}

      {(statusMsg !== null || error !== null) && (
        <div style={statusRowStyle}>
          {error !== null && <span style={errorStyle}>{error}</span>}
          {statusMsg !== null && error === null && (
            <span style={statusOkStyle}>{statusMsg}</span>
          )}
        </div>
      )}
    </>
  );
}

/** Parse a markdown blob into top-level "##" sections, capturing each
 *  bullet underneath. Trailing "[thread:id]" back-link markers are
 *  stripped from the bullet text — Home renders the chip there, and
 *  this page treats them as noise inside the bullet body. */
function parseMemorySections(md: string): { heading: string; bullets: string[] }[] {
  if (md.length === 0) return [];
  const sections: { heading: string; bullets: string[] }[] = [];
  let cur: { heading: string; bullets: string[] } | null = null;
  for (const line of md.split('\n')) {
    const heading = /^##\s+(.+?)\s*$/.exec(line);
    if (heading !== null) {
      cur = { heading: heading[1].trim(), bullets: [] };
      sections.push(cur);
      continue;
    }
    if (cur === null) continue;
    const bullet = /^\s*[-*]\s+(.+)$/.exec(line);
    if (bullet !== null) {
      cur.bullets.push(bullet[1].replace(/\s*\[thread:[A-Za-z0-9_-]+\]/g, '').trim());
    }
  }
  return sections;
}

function bulletStyleFor(heading: string): React.CSSProperties {
  return heading === 'Blockers' ? blockerBulletStyle : neutralBulletStyle;
}

function BudgetBar({ used, target }: { used: number; target: number }) {
  const pct = Math.min(100, Math.max(0, Math.round((used / target) * 100)));
  const over = used > target;
  return (
    <div style={budgetBarStyle}>
      <div
        style={{
          ...budgetBarFillStyle,
          width: `${pct}%`,
          background: over ? '#dc2626' : '#16a34a',
        }}
      />
    </div>
  );
}

const headerActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'center',
};

const secondaryActionStyle: React.CSSProperties = {
  padding: '6px 11px',
  fontSize: 12,
  fontWeight: 500,
  color: 'var(--ws-accent-deep)',
  background: 'rgba(255, 255, 255, 0.5)',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 8,
  cursor: 'pointer',
};

const budgetCardStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  marginBottom: 14,
  padding: '10px 14px',
};

const budgetTextInlineStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  whiteSpace: 'nowrap',
  flexShrink: 0,
};

const sectionCardStyle: React.CSSProperties = {
  marginBottom: 12,
};

const sectionBulletsStyle: React.CSSProperties = {
  listStyle: 'disc',
  paddingLeft: 20,
  margin: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const neutralBulletStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--ws-text-2)',
  lineHeight: 1.55,
};

const blockerBulletStyle: React.CSSProperties = {
  fontSize: 13,
  color: '#dc2626',
  lineHeight: 1.55,
};

const emptyStateStyle: React.CSSProperties = {
  padding: '16px 0',
  fontSize: 13,
  color: 'var(--ws-text-3)',
  textAlign: 'center',
};

const textareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 320,
  marginTop: 10,
  padding: '12px 14px',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  lineHeight: 1.55,
  border: '1px solid var(--ws-card-border)',
  borderRadius: 10,
  background: 'rgba(255, 255, 255, 0.6)',
  color: 'var(--ws-text-1)',
  resize: 'vertical',
  boxSizing: 'border-box',
};

const budgetBarStyle: React.CSSProperties = {
  width: '100%',
  height: 5,
  background: 'rgba(124, 58, 237, 0.10)',
  borderRadius: 999,
  overflow: 'hidden',
};

const budgetBarFillStyle: React.CSSProperties = {
  height: '100%',
  transition: 'width 140ms ease',
};

const budgetTextStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  whiteSpace: 'nowrap',
};

const budgetWarnStyle: React.CSSProperties = {
  color: '#dc2626',
  fontWeight: 600,
};

const actionsRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '12px 0 0',
};

const hintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
};

const loadingStyle: React.CSSProperties = {
  padding: '20px 0',
  fontSize: 12,
  color: 'var(--ws-text-3)',
};

const statusRowStyle: React.CSSProperties = {
  padding: '8px 0 0',
  fontSize: 12,
};

const errorStyle: React.CSSProperties = {
  color: '#dc2626',
  fontStyle: 'italic',
};

const statusOkStyle: React.CSSProperties = {
  color: '#16a34a',
};

export default WorkspaceMemoryPage;
