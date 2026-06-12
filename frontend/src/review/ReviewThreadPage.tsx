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

  // Split findings into Agreed / Open. AGREED + RESOLVED + POSTED
  // are "done"; DISPUTED + ARBITRATED + DROPPED are still in flight
  // for the right rail's "Open" pane.
  const agreedFindings = useMemo(
    () => (detail?.findings ?? []).filter(f =>
        f.status === 'AGREED' || f.status === 'RESOLVED' || f.status === 'POSTED'),
    [detail]);
  const openFindings = useMemo(
    () => (detail?.findings ?? []).filter(f => f.status === 'DISPUTED'),
    [detail]);

  return (
    <section style={pageStyle}>
      <TopBar
        detail={detail}
        onBack={onBack}
      />

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
        <div style={bodyGridStyle}>
          <aside style={leftRailStyle}>
            <ReviewingCard detail={detail} />
            <RosterSection participants={detail.participants} />
            <FlowStepper currentPhase={detail.pass.phase} />
            <BudgetCard detail={detail} />
          </aside>
          <main style={centerColStyle}>
            <TranscriptSection
              messages={detail.messages}
              participantsById={participantsById}
            />
            <SteerComposerPlaceholder
              prNumber={detail.pass.prNumber}
              published={detail.pass.phase === 'PUBLISHED'}
            />
          </main>
          <aside style={rightRailStyle}>
            <FindingsByStatusSection
              title={`Agreed findings (${agreedFindings.length})`}
              findings={agreedFindings}
              emptyHint="Nothing locked in yet."
            />
            <FindingsByStatusSection
              title={`Open (${openFindings.length})`}
              findings={openFindings}
              emptyHint="All disagreements resolved or arbitrated."
            />
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
            <SpawnBuildSection detail={detail} onSpawned={() => void refresh()} />
          </aside>
        </div>
      )}
    </section>
  );
}

/** The review→build handoff. At TERMINATE with at least one AGREED
 *  finding at severity >= MAJOR, offers "→ Spawn build thread". Once a
 *  build thread is spawned, shows the resolution strip instead. */
function SpawnBuildSection(
  { detail, onSpawned }: { detail: ReviewPassDetailDto; onSpawned: () => void },
) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pass = detail.pass;

  if (pass.spawnedBuildThreadId !== null) {
    const applied = detail.findings.filter(
      f => f.status === 'AGREED' || f.status === 'RESOLVED');
    const resolved = applied.filter(f => f.status === 'RESOLVED').length;
    return (
      <div style={spawnStripStyle}>
        <strong>{applied.length} AGREED</strong> → {resolved} resolved
        {' '}(build thread {pass.spawnedBuildThreadId.slice(0, 8)})
        {' · '}{applied.length - resolved} unresolved
      </div>
    );
  }

  const eligible = pass.phase === 'TERMINATE'
    && detail.findings.some(f => f.status === 'AGREED'
        && (f.severity === 'BLOCKER' || f.severity === 'MAJOR'));
  if (!eligible) {
    return null;
  }

  const onSpawn = async () => {
    setBusy(true);
    setError(null);
    try {
      await window.bridge.spawnBuildFromReview(pass.id);
      onSpawned();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <div style={spawnSectionStyle}>
      <button type="button" className="button" disabled={busy} onClick={() => void onSpawn()}>
        → Spawn build thread
      </button>
      <p style={spawnHintStyle}>
        Opens a build thread pre-seeded with the AGREED findings. Your own
        PR forks off its head; someone else&apos;s gets suggested-change
        comments — both still go through the publish gate.
      </p>
      {error !== null && <div style={errorStyle} role="alert">{error}</div>}
    </div>
  );
}

/** Top bar — Back chevron · panel-title · PR ref · phase / round /
 *  cost meters on the right. Matches the panel mockup's header strip. */
function TopBar({ detail, onBack }: { detail: ReviewPassDetailDto | null; onBack: () => void }) {
  const round = detail?.pass.round ?? 0;
  const roundCap = detail?.pass.roundCap ?? 0;
  const costMilli = detail?.pass.costUsdMilli ?? 0;
  const costCapMilli = detail?.pass.costCapMilli ?? 0;
  return (
    <header style={topBarStyle}>
      <button type="button" className="button" onClick={onBack} style={backBtnStyle}>← Back</button>
      <span style={panelBadgeStyle}>Review panel</span>
      <h1 style={titleStyle}>
        {detail
            ? `Review · ${detail.pass.repoFullName}#${detail.pass.prNumber}`
            : 'Review thread'}
      </h1>
      {detail && (
        <div style={metaStyle}>
          <Meter label="Phase">
            <PhasePill phase={detail.pass.phase} />
          </Meter>
          {roundCap > 0 && (
            <Meter label="Round">
              <span style={meterValueStyle}>{round} / {roundCap}</span>
            </Meter>
          )}
          {costCapMilli > 0 && (
            <Meter label="Cost">
              <span style={meterValueStyle}>
                ${(costMilli / 1000).toFixed(2)} / ${(costCapMilli / 1000).toFixed(2)}
              </span>
            </Meter>
          )}
          {detail.pass.verdict && <VerdictPill verdict={detail.pass.verdict} />}
        </div>
      )}
    </header>
  );
}

function Meter({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <span style={meterCellStyle}>
      <span style={meterLabelStyle}>{label}</span>
      {children}
    </span>
  );
}

/** Left-rail "Reviewing" card — a compact PR summary so the user can
 *  see which PR the panel is working on without scrolling away to
 *  the PR detail page. */
function ReviewingCard({ detail }: { detail: ReviewPassDetailDto }) {
  return (
    <section style={cardStyle} aria-label="Reviewing">
      <h2 style={cardTitleStyle}>Reviewing</h2>
      <div style={reviewingTitleStyle}>
        <span style={prNumStyle}>#{detail.pass.prNumber}</span>{' '}
        {detail.pass.repoFullName}
      </div>
      <div style={reviewingMetaStyle}>
        <span style={shaChipStyle}>{detail.pass.headSha.slice(0, 8)}</span>
      </div>
    </section>
  );
}

/** Left-rail "Flow" stepper — visualises the panel's phase machine.
 *  The current phase glows; completed phases get a check; future
 *  phases stay muted. Pure presentation, no actions. */
const FLOW_PHASES: { id: string; label: string }[] = [
  { id: 'KICKOFF', label: 'Kickoff' },
  { id: 'INDEPENDENT', label: 'Independent review' },
  { id: 'CROSS_REVIEW', label: 'Cross-review' },
  { id: 'CONSENSUS', label: 'Consensus' },
  { id: 'DEBATE', label: 'Debate' },
  { id: 'ARBITRATE', label: 'Arbitrate' },
  { id: 'PUBLISHED', label: 'Publish' },
];

function FlowStepper({ currentPhase }: { currentPhase: string }) {
  const currentIdx = FLOW_PHASES.findIndex(p => p.id === currentPhase);
  return (
    <section style={cardStyle} aria-label="Flow">
      <h2 style={cardTitleStyle}>Flow</h2>
      <ol style={flowListStyle}>
        {FLOW_PHASES.map((phase, idx) => {
          const state: 'done' | 'current' | 'next' = idx < currentIdx ? 'done'
              : idx === currentIdx ? 'current'
              : 'next';
          return (
            <li key={phase.id} style={flowRowStyle}>
              <span style={flowGlyphStyle(state)} aria-hidden>
                {state === 'done' ? '✓' : state === 'current' ? '●' : '○'}
              </span>
              <span style={flowLabelStyle(state)}>{phase.label}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

/** Left-rail "Budget" card — debate-rounds + cost progress bars. */
function BudgetCard({ detail }: { detail: ReviewPassDetailDto }) {
  const roundPct = detail.pass.roundCap > 0
      ? Math.min(100, Math.round((detail.pass.round / detail.pass.roundCap) * 100))
      : 0;
  const costPct = detail.pass.costCapMilli > 0
      ? Math.min(100, Math.round((detail.pass.costUsdMilli / detail.pass.costCapMilli) * 100))
      : 0;
  return (
    <section style={cardStyle} aria-label="Budget">
      <h2 style={cardTitleStyle}>Budget</h2>
      <div style={budgetRowStyle}>
        <div style={budgetTopRowStyle}>
          <span style={budgetLabelStyle}>Debate rounds</span>
          <span style={budgetValueStyle}>{detail.pass.round} / {detail.pass.roundCap}</span>
        </div>
        <div style={progressTrackStyle}>
          <div style={progressFillStyle(roundPct, '#7c3aed')} />
        </div>
      </div>
      <div style={budgetRowStyle}>
        <div style={budgetTopRowStyle}>
          <span style={budgetLabelStyle}>Cost</span>
          <span style={budgetValueStyle}>
            ${(detail.pass.costUsdMilli / 1000).toFixed(2)} / ${(detail.pass.costCapMilli / 1000).toFixed(2)}
          </span>
        </div>
        <div style={progressTrackStyle}>
          <div style={progressFillStyle(costPct, costPct > 80 ? '#cf1322' : '#0d9488')} />
        </div>
      </div>
    </section>
  );
}

/** Right-rail findings list partitioned by status. Same shape as
 *  the legacy FindingsSection but rendered twice — Agreed (locked-in)
 *  and Open (still in flight). */
function FindingsByStatusSection({
  title, findings, emptyHint,
}: {
  title: string;
  findings: ReviewFindingDto[];
  emptyHint: string;
}) {
  return (
    <section style={cardStyle} aria-label={title}>
      <h2 style={cardTitleStyle}>{title}</h2>
      {findings.length === 0 ? (
        <div style={emptyInlineStyle}>{emptyHint}</div>
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

/** Bottom composer placeholder — surfaces the "steer the panel"
 *  affordance from the mockup. The backend hook for sending a user
 *  message into the panel isn't wired yet, so the textarea is
 *  disabled with a "decision pending" cue. */
function SteerComposerPlaceholder({
  prNumber, published,
}: {
  prNumber: number;
  published: boolean;
}) {
  return (
    <div style={composerCardStyle}>
      <textarea
        placeholder="Steer the panel, @mention a reviewer, or arbitrate the open item…"
        disabled
        rows={2}
        style={composerTextareaStyle}
      />
      <div style={composerFooterStyle}>
        <span style={composerHintStyle}>
          {published
            ? `Posted to PR #${prNumber}.`
            : 'Steering the panel from the UI is a follow-up; arbitrate from the ballot for now.'}
        </span>
      </div>
    </div>
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
  padding: '20px 24px 40px',
  background: 'var(--bg-base)',
  margin: '0 auto',
  maxWidth: 1280,
  boxSizing: 'border-box',
};

const spawnSectionStyle: React.CSSProperties = {
  marginTop: 12,
  paddingTop: 12,
  borderTop: '1px solid var(--border-subtle)',
};

const spawnHintStyle: React.CSSProperties = {
  margin: '8px 0 0',
  fontSize: 12,
  color: 'var(--text-muted)',
  lineHeight: 1.5,
};

const spawnStripStyle: React.CSSProperties = {
  marginTop: 12,
  padding: '8px 10px',
  borderRadius: 6,
  background: 'var(--bg-subtle)',
  border: '1px solid var(--border-subtle)',
  fontSize: 12,
  color: 'var(--text-default)',
};

const topBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  marginBottom: 16,
  padding: '10px 14px',
  background: 'rgba(255,255,255,0.85)',
  border: '1px solid var(--border)',
  borderRadius: 12,
  boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
};

const backBtnStyle: React.CSSProperties = {
  fontSize: 12,
  padding: '4px 8px',
};

const panelBadgeStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: '#5b21b6',
  background: 'rgba(124, 58, 237, 0.12)',
  border: '1px solid rgba(124, 58, 237, 0.22)',
  borderRadius: 999,
  padding: '3px 10px',
};

const meterCellStyle: React.CSSProperties = {
  display: 'inline-flex',
  flexDirection: 'column',
  alignItems: 'flex-end',
  gap: 2,
  paddingLeft: 12,
  borderLeft: '1px solid var(--border)',
};

const meterLabelStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'var(--text-3)',
};

const meterValueStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-1)',
  fontVariantNumeric: 'tabular-nums',
};

const bodyGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '240px minmax(0, 1fr) 320px',
  gap: 14,
  alignItems: 'flex-start',
};

const leftRailStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const centerColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  minWidth: 0,
};

const rightRailStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const reviewingTitleStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-1)',
  lineHeight: 1.35,
};

const prNumStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontVariantNumeric: 'tabular-nums',
};

const reviewingMetaStyle: React.CSSProperties = {
  marginTop: 6,
  display: 'flex',
  gap: 6,
  flexWrap: 'wrap',
};

const shaChipStyle: React.CSSProperties = {
  fontSize: 10,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  padding: '2px 6px',
  border: '1px solid var(--border)',
  borderRadius: 4,
  color: 'var(--text-3)',
  background: 'rgba(0,0,0,0.02)',
};

const flowListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const flowRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

function flowGlyphStyle(state: 'done' | 'current' | 'next'): React.CSSProperties {
  const color = state === 'done' ? '#16a34a'
      : state === 'current' ? '#7c3aed'
      : 'var(--text-4)';
  return {
    width: 16,
    height: 16,
    borderRadius: 999,
    border: state === 'current' ? '2px solid #7c3aed' : '1px solid var(--border)',
    color,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 9,
    fontWeight: 700,
    flexShrink: 0,
    background: state === 'current' ? 'rgba(124,58,237,0.08)' : 'transparent',
  };
}

function flowLabelStyle(state: 'done' | 'current' | 'next'): React.CSSProperties {
  return {
    fontSize: 12,
    color: state === 'next' ? 'var(--text-3)' : 'var(--text-1)',
    fontWeight: state === 'current' ? 600 : 400,
  };
}

const budgetRowStyle: React.CSSProperties = {
  marginTop: 8,
};

const budgetTopRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  marginBottom: 4,
};

const budgetLabelStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const budgetValueStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--text-1)',
  fontVariantNumeric: 'tabular-nums',
};

const progressTrackStyle: React.CSSProperties = {
  height: 4,
  background: 'rgba(0,0,0,0.06)',
  borderRadius: 999,
  overflow: 'hidden',
};

function progressFillStyle(pct: number, color: string): React.CSSProperties {
  return {
    width: `${pct}%`,
    height: '100%',
    background: color,
    transition: 'width 240ms ease',
  };
}

const composerCardStyle: React.CSSProperties = {
  marginTop: 6,
  padding: 12,
  background: '#fff',
  border: '1px solid var(--border)',
  borderRadius: 12,
  boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
};

const composerTextareaStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  fontSize: 13,
  lineHeight: 1.45,
  border: '1px solid var(--border)',
  borderRadius: 8,
  resize: 'none',
  fontFamily: 'inherit',
  background: 'rgba(0,0,0,0.02)',
  color: 'var(--text-2)',
  boxSizing: 'border-box',
};

const composerFooterStyle: React.CSSProperties = {
  marginTop: 6,
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
};

const composerHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontStyle: 'italic',
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
