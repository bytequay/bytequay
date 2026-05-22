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
  ReviewFindingStatusDto,
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
          {detail.pass.phase === 'ARBITRATE' ? (
            <ArbitrationBallotSection
              detail={detail}
              onResolved={(next) => setDetail(next)}
            />
          ) : (
            <PublishSection
              detail={detail}
              onPublished={(next) => setDetail(next)}
            />
          )}
        </>
      )}
    </section>
  );
}

function ArbitrationBallotSection({
  detail, onResolved,
}: {
  detail: ReviewPassDetailDto;
  onResolved: (next: ReviewPassDetailDto) => void;
}) {
  const disputed = detail.findings.filter(f => f.status === 'DISPUTED');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const resolve = async (findingId: string, resolution: 'include' | 'drop') => {
    if (busyId !== null) return;
    setBusyId(findingId);
    setError(null);
    try {
      const next = await window.bridge.arbitrateReviewFinding(
          detail.pass.id, findingId, resolution);
      onResolved(next);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusyId(null);
    }
  };

  return (
    <section style={cardStyle} aria-label="Arbitration">
      <h2 style={cardTitleStyle}>
        Arbitration ballot ({disputed.length} pending)
      </h2>
      <p style={publishHintStyle}>
        The panel couldn't agree on these findings. Pick one per item:{' '}
        <strong>Include</strong> to surface it in the published review;{' '}
        <strong>Drop</strong> to discard it. The publish form unlocks once
        every disputed finding is resolved.
      </p>
      {error !== null && (
        <div style={errorStyle} role="alert">{error}</div>
      )}
      <ul style={findingsListStyle}>
        {disputed.map(f => (
          <li key={f.id} style={findingRowStyle}>
            <SeverityChip severity={f.severity} />
            <StatusChip status={f.status} />
            <div style={findingBodyStyle}>
              <div style={findingAnchorStyle}>
                {f.path !== null
                    ? `${f.path}${f.line !== null ? `:${f.line}` : ''}`
                    : 'Whole PR'}
              </div>
              <div>{f.body}</div>
              <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
                <button
                  type="button"
                  className="button button--primary"
                  onClick={() => { void resolve(f.id, 'include'); }}
                  disabled={busyId !== null}
                >
                  {busyId === f.id ? 'Working…' : 'Include'}
                </button>
                <button
                  type="button"
                  className="button"
                  onClick={() => { void resolve(f.id, 'drop'); }}
                  disabled={busyId !== null}
                >
                  Drop
                </button>
              </div>
            </div>
          </li>
        ))}
      </ul>
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
              <StatusChip status={f.status} />
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

function PublishSection({
  detail, onPublished,
}: {
  detail: ReviewPassDetailDto;
  onPublished: (next: ReviewPassDetailDto) => void;
}) {
  const alreadyPublished = detail.pass.phase === 'PUBLISHED';
  const suggested: ReviewVerdictDto = detail.pass.verdict ?? 'COMMENT';
  const [verdict, setVerdict] = useState<ReviewVerdictDto>(suggested);
  // Default to including AGREED findings only — DISPUTED findings
  // start un-checked since the panel didn't reach consensus on them.
  // The user can still tick them in if they want to surface them.
  const [includedIds, setIncludedIds] = useState<Set<string>>(
      () => new Set(detail.findings
          .filter(f => f.status !== 'DISPUTED')
          .map(f => f.id)));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const toggle = (id: string) => {
    setIncludedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const handlePublish = async () => {
    if (busy || alreadyPublished) return;
    setBusy(true);
    setError(null);
    try {
      const next = await window.bridge.publishReviewPass(
          detail.pass.id, verdict, Array.from(includedIds));
      onPublished(next);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <section style={cardStyle} aria-label="Publish">
      <h2 style={cardTitleStyle}>
        Publish to PR{alreadyPublished && <PublishedBadge />}
      </h2>

      {alreadyPublished ? (
        <p style={publishHintStyle}>
          Posted to the PR as a <strong>{detail.pass.verdict}</strong> review.
          Findings flipped to POSTED above.
        </p>
      ) : (
        <>
          <p style={publishHintStyle}>
            Verdict suggestion: <strong>{suggested}</strong>. The selected
            findings post as inline review comments on GitHub; whole-PR
            notes fold into the review body so nothing you pick is dropped.
          </p>

          <fieldset style={fieldsetStyle} aria-label="Verdict">
            <legend style={legendStyle}>Verdict</legend>
            {(['APPROVE', 'COMMENT', 'REQUEST_CHANGES'] as ReviewVerdictDto[]).map(v => (
              <label key={v} style={radioLabelStyle}>
                <input
                  type="radio"
                  name="review-verdict"
                  value={v}
                  checked={verdict === v}
                  onChange={() => setVerdict(v)}
                  disabled={busy}
                />
                <span>{v}</span>
              </label>
            ))}
          </fieldset>

          {detail.findings.length > 0 && (
            <fieldset style={fieldsetStyle} aria-label="Findings to post">
              <legend style={legendStyle}>
                Findings to post ({includedIds.size}/{detail.findings.length})
              </legend>
              {detail.findings.map(f => (
                <label key={f.id} style={findingChoiceStyle}>
                  <input
                    type="checkbox"
                    checked={includedIds.has(f.id)}
                    onChange={() => toggle(f.id)}
                    disabled={busy}
                  />
                  <SeverityChip severity={f.severity} />
                  <StatusChip status={f.status} />
                  <span style={findingChoiceBodyStyle}>
                    <span style={findingAnchorStyle}>
                      {f.path !== null
                          ? `${f.path}${f.line !== null ? `:${f.line}` : ''}`
                          : 'Whole PR'}
                    </span>
                    {f.body}
                  </span>
                </label>
              ))}
            </fieldset>
          )}

          {error !== null && (
            <div style={errorStyle} role="alert">{error}</div>
          )}

          <button
            type="button"
            className="button button--primary"
            onClick={() => { void handlePublish(); }}
            disabled={busy}
          >
            {busy ? 'Posting…' : 'Post review to PR'}
          </button>
        </>
      )}
    </section>
  );
}

function PublishedBadge() {
  return (
    <span style={publishedBadgeStyle} aria-label="published">
      Published
    </span>
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

/** Status chip surfaces consensus state (AGREED / DISPUTED / POSTED).
 *  The other status values (RESOLVED / ARBITRATED / DROPPED) belong
 *  to later phases and aren't currently emitted; they render with a
 *  neutral fallback if they ever land. */
function StatusChip({ status }: { status: ReviewFindingStatusDto }) {
  const color = statusColor(status);
  return (
    <span style={severityChipStyle(color)} aria-label={`status-${status.toLowerCase()}`}>
      {status.toLowerCase()}
    </span>
  );
}

function statusColor(status: ReviewFindingStatusDto): string {
  switch (status) {
    case 'AGREED':     return '#16a34a';
    case 'DISPUTED':   return '#d97706';
    case 'POSTED':     return '#0066cc';
    case 'RESOLVED':   return '#16a34a';
    case 'ARBITRATED': return '#737373';
    case 'DROPPED':    return '#737373';
  }
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

const fieldsetStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 6,
  padding: '8px 12px 10px',
  marginBottom: 10,
};

const legendStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: 'var(--text-3)',
  padding: '0 6px',
};

const radioLabelStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  marginRight: 16,
  fontSize: 13,
};

const findingChoiceStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  padding: '6px 0',
  fontSize: 13,
};

const findingChoiceBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};

const publishedBadgeStyle: React.CSSProperties = {
  marginLeft: 8,
  fontSize: 10,
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  padding: '2px 7px',
  borderRadius: 4,
  color: '#fff',
  background: '#16a34a',
  verticalAlign: 'middle',
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
