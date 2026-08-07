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
import type { MemoryItemDto, WorkspaceMemoryProposalDto } from '../../types';
import { relativeTime } from '../../relativeTime';

type Props = {
  workspaceId: string;
  /** Refresh trigger from the parent — e.g. when the user just hit
   *  Distill, we want the banner to pick up the new proposal
   *  immediately rather than wait for the next 15s poll. */
  refreshKey?: number;
  /** Called after the proposal is applied so the parent can refresh
   *  its own memory_md state. Not called on discard — that one has
   *  no side effect on the workspace. */
  onApplied: () => void;
  /** Click handler for back-link chips ({@code [thread:<id>]}) in
   *  the rendered panes. When omitted, chips render as plain text so
   *  the markdown still reads the same. */
  onOpenThread?: (threadId: string) => void;
};

/** Banner that surfaces a pending workspace-memory proposal above the
 *  hand-edit textarea. The distiller can no longer overwrite
 *  WORKSPACE.md silently — it queues into this surface, and the user
 *  picks Apply or Discard. The banner polls for fresh proposals so a
 *  background distillation pass surfaces without the user having to
 *  refresh.
 *
 *  <p>Diff rendering is a simple side-by-side: current memory on the
 *  left, proposed body on the right. Anything fancier (per-line +/-,
 *  syntax highlighting) is overkill for ~2-4 kB of markdown. */
function WorkspaceMemoryProposalBanner({ workspaceId, refreshKey, onApplied, onOpenThread }: Props) {
  const [proposal, setProposal] = useState<WorkspaceMemoryProposalDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const next = await window.bridge.getWorkspaceMemoryProposal(workspaceId);
      setProposal(next);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [workspaceId]);

  useEffect(() => {
    void refresh();
    // Background distillation runs every 30 minutes; a 15s poll picks
    // it up promptly without being chatty.
    const id = window.setInterval(() => { void refresh(); }, 15_000);
    return () => window.clearInterval(id);
  }, [refresh, refreshKey]);

  if (proposal === null) return null;

  const handleApply = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await window.bridge.applyWorkspaceMemoryProposal(workspaceId);
      setProposal(null);
      setExpanded(false);
      onApplied();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusy(false);
    }
  };

  const handleDiscard = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await window.bridge.discardWorkspaceMemoryProposal(workspaceId);
      setProposal(null);
      setExpanded(false);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusy(false);
    }
  };

  const delta = proposal.proposedMd.length - proposal.currentMd.length;
  const deltaLabel = delta >= 0
      ? `+${delta.toLocaleString()}`
      : delta.toLocaleString();

  return (
    <div style={bannerStyle} data-testid="workspace-memory-proposal-banner">
      <div style={bannerHeadStyle}>
        <div>
          <div style={bannerTitleStyle}>
            Pending memory proposal — distillation wants to rewrite WORKSPACE.md
          </div>
          <div style={bannerMetaStyle}>
            {deltaLabel} chars · {proposal.summariserModel} ·
            {' '}{relativeTime(proposal.createdAt)}
          </div>
        </div>
        <button
          type="button"
          className="button"
          onClick={() => { setExpanded(prev => !prev); }}
        >
          {expanded ? 'Hide diff' : 'Show diff'}
        </button>
      </div>

      {expanded && (
        <div style={diffGridStyle}>
          <ProposalPane
            label="Current memory"
            body={proposal.currentMd}
            onOpenThread={onOpenThread}
          />
          <ProposalPane
            label="Proposed memory"
            body={proposal.proposedMd}
            onOpenThread={onOpenThread}
          />
        </div>
      )}

      {error !== null && (
        <div style={errorStyle} role="alert">{error}</div>
      )}

      <TypedMemoryItemsList
        workspaceId={workspaceId}
        refreshKey={refreshKey}
        onOpenThread={onOpenThread}
      />


      <div style={buttonsStyle}>
        <button
          type="button"
          className="button button--primary"
          onClick={() => { void handleApply(); }}
          disabled={busy}
        >
          {busy ? 'Working…' : 'Apply proposal'}
        </button>
        <button
          type="button"
          className="button"
          onClick={() => { void handleDiscard(); }}
          disabled={busy}
        >
          Discard
        </button>
      </div>
    </div>
  );
}

function ProposalPane({ label, body, onOpenThread }: {
  label: string;
  body: string;
  onOpenThread?: (threadId: string) => void;
}) {
  return (
    <div style={paneStyle}>
      <div style={paneLabelStyle}>{label}</div>
      <pre style={paneBodyStyle}>
        {body.length === 0
            ? '(empty)'
            : renderWithThreadLinks(body, onOpenThread)}
      </pre>
    </div>
  );
}

/** Splits {@code md} on the back-link token regex and intersperses
 *  clickable chips. The token comes from the distiller prompt — every
 *  bullet promoted from a thread Overall ends with
 *  {@code [thread:<id>]}, and this is where the chip rendering lives.
 *  When {@code onOpenThread} is undefined the chip renders as plain
 *  text so the markdown stays readable. */
function renderWithThreadLinks(
    md: string, onOpenThread?: (threadId: string) => void): React.ReactNode[] {
  const re = /\[thread:([A-Za-z0-9_-]+)\]/g;
  const out: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let key = 0;
  while ((match = re.exec(md)) !== null) {
    if (match.index > lastIndex) {
      out.push(md.slice(lastIndex, match.index));
    }
    const threadId = match[1];
    const label = `thread:${threadId.length > 12 ? `${threadId.slice(0, 8)}…` : threadId}`;
    if (onOpenThread) {
      out.push(
        <button
          key={`chip-${key++}`}
          type="button"
          onClick={() => onOpenThread(threadId)}
          style={chipButtonStyle}
          title={`Open thread ${threadId}`}
        >
          {label}
        </button>,
      );
    }
    else {
      out.push(
        <span key={`chip-${key++}`} style={chipTextStyle}>{label}</span>,
      );
    }
    lastIndex = re.lastIndex;
  }
  if (lastIndex < md.length) {
    out.push(md.slice(lastIndex));
  }
  return out;
}

const chipButtonStyle: React.CSSProperties = {
  display: 'inline',
  padding: '0 6px',
  margin: '0 1px',
  fontFamily: 'inherit',
  fontSize: 11,
  border: '1px solid rgba(0, 102, 204, 0.35)',
  borderRadius: 4,
  background: 'rgba(0, 102, 204, 0.08)',
  color: '#0066cc',
  cursor: 'pointer',
};

const chipTextStyle: React.CSSProperties = {
  display: 'inline',
  padding: '0 6px',
  margin: '0 1px',
  fontFamily: 'inherit',
  fontSize: 11,
  border: '1px solid var(--border)',
  borderRadius: 4,
  background: 'var(--bg-2)',
  color: 'var(--text-3)',
};

const bannerStyle: React.CSSProperties = {
  marginBottom: 16,
  padding: 14,
  background: 'rgba(217, 119, 6, 0.06)',
  border: '1px solid rgba(217, 119, 6, 0.4)',
  borderRadius: 8,
};

const bannerHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
  gap: 12,
};

const bannerTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  fontSize: 14,
  color: 'var(--text-1)',
};

const bannerMetaStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-3)',
  marginTop: 4,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const diffGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 8,
  marginTop: 12,
};

const paneStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
};

const paneLabelStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: 'var(--text-3)',
  marginBottom: 4,
};

const paneBodyStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 12,
  lineHeight: 1.5,
  background: 'var(--bg-2)',
  border: '1px solid var(--border)',
  padding: 10,
  borderRadius: 6,
  maxHeight: 360,
  overflow: 'auto',
  margin: 0,
  whiteSpace: 'pre-wrap',
};

const errorStyle: React.CSSProperties = {
  marginTop: 10,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 6,
  color: '#cf1322',
  fontSize: 13,
};

const buttonsStyle: React.CSSProperties = {
  marginTop: 12,
  display: 'flex',
  gap: 8,
};

/** Compact preview of the typed memory_item rows the distiller
 *  produced alongside the blob proposal. v1 sits below the
 *  blob diff so the user sees both shapes; once the agent's
 *  recall_memory loop is exercising the typed rows, the blob
 *  surface becomes a debug view. Per-row Apply / Discard hits the
 *  typed endpoints directly. */
function TypedMemoryItemsList({
  workspaceId, refreshKey, onOpenThread,
}: {
  workspaceId: string;
  refreshKey?: number;
  onOpenThread?: (threadId: string) => void;
}) {
  const [items, setItems] = useState<MemoryItemDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyIds, setBusyIds] = useState<Set<number>>(new Set());

  const load = useCallback(async () => {
    try {
      const rows = await window.bridge.listPendingMemoryItems(workspaceId);
      setItems(rows);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [workspaceId]);

  useEffect(() => { void load(); }, [load, refreshKey]);

  if (items === null || items.length === 0) return null;

  const withBusy = (id: number, value: boolean) => {
    setBusyIds(prev => {
      const next = new Set(prev);
      if (value) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const handleApply = async (id: number) => {
    withBusy(id, true);
    setError(null);
    try {
      await window.bridge.applyMemoryItem(workspaceId, id);
      await load();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      withBusy(id, false);
    }
  };

  const handleDiscard = async (id: number) => {
    withBusy(id, true);
    setError(null);
    try {
      await window.bridge.discardMemoryItem(workspaceId, id);
      await load();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      withBusy(id, false);
    }
  };

  return (
    <section style={typedSectionStyle} aria-label="Typed memory items pending review">
      <header style={typedHeadStyle}>
        <div style={typedTitleStyle}>Typed view · {items.length}</div>
        <div style={typedSublabelStyle}>
          One row per item. Per-row Apply lands the row in WORKSPACE.md;
          Discard drops it.
        </div>
      </header>
      {error !== null && <div style={errorStyle} role="alert">{error}</div>}
      <ul style={typedListStyle}>
        {items.map(item => (
          <li key={item.id} style={typedRowStyle}>
            <div style={typedRowMetaStyle}>
              <span style={kindChipStyle(item.kind)}>{item.kind.toLowerCase()}</span>
              <span style={confidenceChipStyle(item.confidence)}>{item.confidence.toLowerCase()}</span>
              {item.sources.map((s, idx) => {
                const threadId = s.threadId ?? null;
                const label = threadId !== null
                  ? threadId.startsWith('distill:')
                    ? 'distill'
                    : threadId
                  : (s.prRef ?? s.taskId ?? '?');
                if (threadId !== null && onOpenThread && !threadId.startsWith('distill:')) {
                  return (
                    <button
                      key={idx}
                      type="button"
                      style={sourceChipStyle}
                      onClick={() => onOpenThread(threadId)}
                      title={`Open ${threadId}`}
                    >
                      {label}
                    </button>
                  );
                }
                return (
                  <span key={idx} style={sourceChipStyle}>{label}</span>
                );
              })}
            </div>
            <div style={typedRowTextStyle}>{item.text}</div>
            <div style={typedRowButtonsStyle}>
              <button
                type="button"
                className="button button--primary"
                disabled={busyIds.has(item.id)}
                onClick={() => { void handleApply(item.id); }}
              >
                Apply
              </button>
              <button
                type="button"
                className="button"
                disabled={busyIds.has(item.id)}
                onClick={() => { void handleDiscard(item.id); }}
              >
                Discard
              </button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

const typedSectionStyle: React.CSSProperties = {
  marginTop: 16,
  paddingTop: 12,
  borderTop: '1px solid #e2e2e8',
};

const typedHeadStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  marginBottom: 8,
};

const typedTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  fontSize: 13,
};

const typedSublabelStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#6b6b78',
};

const typedListStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const typedRowStyle: React.CSSProperties = {
  border: '1px solid #d8d8e0',
  borderRadius: 6,
  padding: 10,
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const typedRowMetaStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  flexWrap: 'wrap',
};

const typedRowTextStyle: React.CSSProperties = {
  fontSize: 13,
  lineHeight: 1.4,
};

const typedRowButtonsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
};

function kindChipStyle(kind: MemoryItemDto['kind']): React.CSSProperties {
  const palette: Record<MemoryItemDto['kind'], string> = {
    DECISION: '#4a3aff',
    BLOCKER: '#d34d4d',
    CONVENTION: '#2e7d32',
    FOCUS_SHIFT: '#b56f00',
    OPEN_QUESTION: '#7b3aa6',
    RECURRING_PATTERN: '#5d5d8f',
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

function confidenceChipStyle(confidence: MemoryItemDto['confidence']): React.CSSProperties {
  return {
    border: '1px solid #c8c8d0',
    borderRadius: 4,
    padding: '1px 5px',
    fontSize: 10,
    color: confidence === 'LOW' ? '#a07000' : '#666',
  };
}

const sourceChipStyle: React.CSSProperties = {
  border: '1px solid #c8c8d0',
  background: '#fafafd',
  borderRadius: 4,
  padding: '1px 6px',
  fontSize: 10,
  cursor: 'pointer',
  fontFamily: 'monospace',
};

export default WorkspaceMemoryProposalBanner;
