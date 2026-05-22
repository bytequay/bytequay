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
import type {
  ReviewFindingDto,
  ReviewFindingSeverityDto,
  ReviewPanelMessageDto,
  ReviewParticipantDto,
  ReviewPassDetailDto,
  ReviewVerdictDto,
} from '../types';

type Props = {
  threadId: string;
  onBack: () => void;
};

/** Read-only view of a review pass: the panel roster, the
 *  transcript (kickoff + reviewer summary as bubbles), and the
 *  structured findings with severity chips. Phase 1 = single
 *  reviewer + suggested verdict; the gated "Post review to PR"
 *  affordance is a disabled placeholder here and lands in a follow-up
 *  commit. */
function ReviewThreadPage({ threadId, onBack }: Props) {
  const [detail, setDetail] = useState<ReviewPassDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await window.bridge.getReviewPassByThread(threadId);
      setDetail(next);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, [threadId]);

  useEffect(() => { void refresh(); }, [refresh]);

  const participantsById = useMemo(() => {
    const map = new Map<string, ReviewParticipantDto>();
    detail?.participants.forEach(p => map.set(p.id, p));
    return map;
  }, [detail]);

  return (
    <section style={pageStyle}>
      <header style={headerStyle}>
        <button type="button" className="button" onClick={onBack}>← Back</button>
        <h1 style={titleStyle}>
          {detail
              ? `Review · ${detail.pass.repoFullName}#${detail.pass.prNumber}`
              : 'Review thread'}
        </h1>
        {detail && (
          <div style={metaStyle}>
            <PhasePill phase={detail.pass.phase} />
            {detail.pass.verdict && <VerdictPill verdict={detail.pass.verdict} />}
          </div>
        )}
      </header>

      {error !== null && (
        <div style={errorStyle} role="alert">{error}</div>
      )}

      {loading && detail === null && (
        <div style={emptyStyle}>Loading review…</div>
      )}

      {!loading && detail === null && error === null && (
        <div style={emptyStyle}>
          No review pass found for this thread yet. Open one from a PR row.
        </div>
      )}

      {detail !== null && (
        <>
          <RosterSection participants={detail.participants} />
          <TranscriptSection
            messages={detail.messages}
            participantsById={participantsById}
          />
          <FindingsSection findings={detail.findings} />
          <PublishGatePlaceholder verdict={detail.pass.verdict} />
        </>
      )}
    </section>
  );
}

function RosterSection({ participants }: { participants: ReviewParticipantDto[] }) {
  return (
    <section style={cardStyle} aria-label="Panel roster">
      <h2 style={cardTitleStyle}>Panel</h2>
      <ul style={rosterListStyle}>
        {participants.map(p => (
          <li key={p.id} style={rosterRowStyle}>
            <span style={rosterKindStyle(p.kind)}>{kindLabel(p.kind)}</span>
            <span style={rosterPersonaStyle}>{p.personaLabel}</span>
            {p.model !== null && (
              <span style={rosterModelStyle}>{p.model}</span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function TranscriptSection({
  messages,
  participantsById,
}: {
  messages: ReviewPanelMessageDto[];
  participantsById: Map<string, ReviewParticipantDto>;
}) {
  return (
    <section style={cardStyle} aria-label="Panel transcript">
      <h2 style={cardTitleStyle}>Transcript</h2>
      {messages.length === 0 ? (
        <div style={emptyInlineStyle}>No messages yet.</div>
      ) : (
        <ol style={transcriptListStyle}>
          {messages.map(m => {
            const author = participantsById.get(m.participantId);
            return (
              <li key={m.id} style={transcriptRowStyle}>
                <div style={transcriptHeadStyle}>
                  <strong>{author?.personaLabel ?? '?'}</strong>
                  <span style={transcriptPhaseStyle}>{m.phase.toLowerCase()}</span>
                </div>
                <div style={transcriptBodyStyle}>{m.body}</div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

function FindingsSection({ findings }: { findings: ReviewFindingDto[] }) {
  return (
    <section style={cardStyle} aria-label="Findings">
      <h2 style={cardTitleStyle}>Findings ({findings.length})</h2>
      {findings.length === 0 ? (
        <div style={emptyInlineStyle}>
          No findings — the reviewer didn't flag anything.
        </div>
      ) : (
        <ul style={findingsListStyle}>
          {findings.map(f => (
            <li key={f.id} style={findingRowStyle}>
              <SeverityChip severity={f.severity} />
              <div style={findingBodyStyle}>
                <div style={findingAnchorStyle}>
                  {f.path !== null
                      ? `${f.path}${f.line !== null ? `:${f.line}` : ''}`
                      : 'Whole PR'}
                </div>
                <div>{f.body}</div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function PublishGatePlaceholder({ verdict }: { verdict: ReviewVerdictDto | null }) {
  return (
    <section style={cardStyle} aria-label="Publish">
      <h2 style={cardTitleStyle}>Publish to PR</h2>
      <p style={publishHintStyle}>
        Suggested verdict: <strong>{verdict ?? 'pending'}</strong>.
        Posting to GitHub goes through the publish gate; the UI for
        confirming and posting findings as PR comments lands in a
        follow-up commit.
      </p>
      <button
        type="button"
        className="button"
        disabled
        title="Coming soon"
      >
        Post review to PR (coming soon)
      </button>
    </section>
  );
}

function PhasePill({ phase }: { phase: string }) {
  return <span style={pillStyle('#0066cc')}>{phase.toLowerCase()}</span>;
}

function VerdictPill({ verdict }: { verdict: ReviewVerdictDto }) {
  const color = verdict === 'APPROVE' ? '#16a34a'
      : verdict === 'REQUEST_CHANGES' ? '#cf1322'
      : '#737373';
  return <span style={pillStyle(color)}>{verdict.toLowerCase()}</span>;
}

function SeverityChip({ severity }: { severity: ReviewFindingSeverityDto }) {
  const color = severityColor(severity);
  return (
    <span style={severityChipStyle(color)} aria-label={`severity-${severity.toLowerCase()}`}>
      {severity.toLowerCase()}
    </span>
  );
}

function severityColor(severity: ReviewFindingSeverityDto): string {
  switch (severity) {
    case 'BLOCKER':  return '#cf1322';
    case 'MAJOR':    return '#d97706';
    case 'NIT':      return '#737373';
    case 'QUESTION': return '#0066cc';
  }
}

function kindLabel(kind: ReviewParticipantDto['kind']): string {
  switch (kind) {
    case 'MODERATOR': return 'Moderator';
    case 'REVIEWER':  return 'Reviewer';
    case 'HUMAN':     return 'You';
  }
}

const pageStyle: React.CSSProperties = {
  height: '100%',
  overflowY: 'auto',
  padding: '24px 32px',
  background: 'var(--bg-base)',
  maxWidth: 960,
  margin: '0 auto',
  boxSizing: 'border-box',
};

const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  marginBottom: 16,
};

const titleStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 20,
  fontWeight: 600,
  letterSpacing: '-0.01em',
  color: 'var(--text-1)',
  margin: 0,
};

const metaStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
};

const cardStyle: React.CSSProperties = {
  marginBottom: 14,
  padding: 16,
  background: 'var(--bg-1)',
  border: '1px solid var(--border)',
  borderRadius: 8,
};

const cardTitleStyle: React.CSSProperties = {
  margin: '0 0 10px',
  fontSize: 13,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: 'var(--text-3)',
};

const rosterListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const rosterRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 10,
  fontSize: 13,
};

function rosterKindStyle(kind: ReviewParticipantDto['kind']): React.CSSProperties {
  const color = kind === 'MODERATOR' ? '#737373'
      : kind === 'REVIEWER' ? '#0066cc'
      : '#16a34a';
  return {
    minWidth: 88,
    fontSize: 11,
    fontWeight: 600,
    textTransform: 'uppercase',
    letterSpacing: '0.04em',
    color,
  };
}

const rosterPersonaStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
};

const rosterModelStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
  color: 'var(--text-3)',
};

const transcriptListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

const transcriptRowStyle: React.CSSProperties = {
  padding: 12,
  background: 'var(--bg-2)',
  borderRadius: 6,
  border: '1px solid var(--border)',
};

const transcriptHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  marginBottom: 6,
  fontSize: 13,
};

const transcriptPhaseStyle: React.CSSProperties = {
  fontSize: 11,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: 'var(--text-3)',
};

const transcriptBodyStyle: React.CSSProperties = {
  fontSize: 13,
  lineHeight: 1.55,
  color: 'var(--text-1)',
  whiteSpace: 'pre-wrap',
};

const findingsListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const findingRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 10,
  padding: 10,
  background: 'var(--bg-2)',
  borderRadius: 6,
  border: '1px solid var(--border)',
};

const findingBodyStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 13,
  lineHeight: 1.5,
};

const findingAnchorStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
  color: 'var(--text-3)',
  marginBottom: 4,
};

const publishHintStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--text-2)',
  marginTop: 0,
  marginBottom: 10,
  lineHeight: 1.5,
};

const errorStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 6,
  color: '#cf1322',
  fontSize: 13,
};

const emptyStyle: React.CSSProperties = {
  padding: 16,
  fontSize: 13,
  color: 'var(--text-3)',
  textAlign: 'center',
};

const emptyInlineStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--text-3)',
};

function pillStyle(color: string): React.CSSProperties {
  return {
    fontSize: 11,
    fontWeight: 600,
    textTransform: 'uppercase',
    letterSpacing: '0.04em',
    padding: '2px 8px',
    borderRadius: 999,
    color,
    border: `1px solid ${color}`,
    background: 'transparent',
  };
}

function severityChipStyle(color: string): React.CSSProperties {
  return {
    fontSize: 10,
    fontWeight: 700,
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    padding: '3px 7px',
    borderRadius: 4,
    color: '#fff',
    background: color,
    height: 'fit-content',
    flexShrink: 0,
  };
}

export default ReviewThreadPage;
