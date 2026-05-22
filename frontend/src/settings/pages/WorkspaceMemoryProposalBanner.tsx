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
import { relativeTime } from '../../notificationDisplay';
import type { WorkspaceMemoryProposalDto } from '../../types';

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
function WorkspaceMemoryProposalBanner({ workspaceId, refreshKey, onApplied }: Props) {
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
          <ProposalPane label="Current memory" body={proposal.currentMd} />
          <ProposalPane label="Proposed memory" body={proposal.proposedMd} />
        </div>
      )}

      {error !== null && (
        <div style={errorStyle} role="alert">{error}</div>
      )}

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

function ProposalPane({ label, body }: { label: string; body: string }) {
  return (
    <div style={paneStyle}>
      <div style={paneLabelStyle}>{label}</div>
      <pre style={paneBodyStyle}>{body.length === 0 ? '(empty)' : body}</pre>
    </div>
  );
}

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

export default WorkspaceMemoryProposalBanner;
