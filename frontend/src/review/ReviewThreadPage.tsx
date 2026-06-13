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
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import type {
  AgendaPhaseDto,
  AgendaPhaseStatusDto,
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

  // Live-follow an active pass (the agenda + transcript move while
  // the panel runs); freeze once it reaches a terminal phase so an
  // idle page costs nothing.
  const phase = detail?.pass.phase;
  useEffect(() => {
    if (phase === undefined) return;
    if (phase === 'TERMINATE' || phase === 'ARBITRATE' || phase === 'PUBLISHED') return;
    const timer = window.setInterval(() => { void refresh(); }, 5_000);
    return () => window.clearInterval(timer);
  }, [phase, refresh]);

  const participantsById = useMemo(() => {
    const map = new Map<string, ReviewParticipantDto>();
    detail?.participants.forEach(p => map.set(p.id, p));
    return map;
  }, [detail]);

  // Split findings into Agreed / Open. AGREED + RESOLVED + POSTED
  // are "done"; REPORTED (not yet classified by the lead) and
  // DISPUTED are still in flight for the right rail's "Open" pane.
  const agreedFindings = useMemo(
    () => (detail?.findings ?? []).filter(f =>
        f.status === 'AGREED' || f.status === 'RESOLVED' || f.status === 'POSTED'),
    [detail]);
  const openFindings = useMemo(
    () => (detail?.findings ?? []).filter(f =>
        f.status === 'DISPUTED' || f.status === 'REPORTED'),
    [detail]);

  // Dissents the lead recorded per finding (payload_kind='dissent'
  // messages #ref the finding) — badged on the findings checklist.
  const dissentsByFinding = useMemo(() => {
    const counts = new Map<string, number>();
    for (const m of detail?.messages ?? []) {
      if (m.payloadKind !== 'dissent') continue;
      for (const ref of m.refs) {
        if (ref.startsWith('finding:')) {
          const id = ref.slice('finding:'.length);
          counts.set(id, (counts.get(id) ?? 0) + 1);
        }
      }
    }
    return counts;
  }, [detail]);

  // First transcript message that #ref's each finding — the row's
  // "jump to source" target. Best-effort: only consensus / dissent
  // turns carry finding refs, so a plainly-reported finding may have
  // no source link (its row just isn't clickable).
  const sourceMsgIdByFinding = useMemo(() => {
    const map = new Map<string, string>();
    for (const m of detail?.messages ?? []) {
      for (const ref of m.refs) {
        if (ref.startsWith('finding:')) {
          const id = ref.slice('finding:'.length);
          if (!map.has(id)) map.set(id, m.id);
        }
      }
    }
    return map;
  }, [detail]);

  // Clicking a participant's @mention chip filters the transcript to
  // that reviewer's stream (their messages + messages addressed to
  // them). Click again — or the clear pill — to unfilter.
  const [focusParticipantId, setFocusParticipantId] = useState<string | null>(null);
  const toggleFocus = useCallback((id: string) => {
    setFocusParticipantId(prev => (prev === id ? null : id));
  }, []);

  // The LEAD participant orchestrates the panel; passes that predate
  // the lead seat fall back to whoever authored the consensus turn.
  const leadId = useMemo(
    () => detail?.participants.find(p => p.kind === 'LEAD')?.id
        ?? detail?.messages.find(m => m.payloadKind === 'consensus')?.participantId
        ?? null,
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
            <RosterSection participants={detail.participants} leadId={leadId} />
            <FlowStepper currentPhase={detail.pass.phase} />
            <BudgetCard detail={detail} />
          </aside>
          <main style={centerColStyle}>
            <AgendaSection agenda={detail.agenda} passPhase={detail.pass.phase} />
            {focusParticipantId !== null && (() => {
              const focused = participantsById.get(focusParticipantId);
              const focusColor = focused?.color ?? rosterFallbackColor(focused?.kind ?? 'REVIEWER');
              return (
                <div style={focusPillRowStyle} role="status">
                  <span style={focusPillTextStyle(focusColor)}>
                    Filtered to <strong>@{focused?.personaLabel ?? 'one reviewer'}</strong>
                  </span>
                  <button
                    type="button"
                    className="review-chip"
                    style={focusClearBtnStyle}
                    onClick={() => setFocusParticipantId(null)}
                  >
                    ✕ clear
                  </button>
                </div>
              );
            })()}
            <TranscriptSection
              messages={detail.messages}
              participantsById={participantsById}
              passPhase={detail.pass.phase}
              leadId={leadId}
              focusParticipantId={focusParticipantId}
              onMentionClick={toggleFocus}
            />
            <SteerComposerPlaceholder
              prNumber={detail.pass.prNumber}
              published={detail.pass.phase === 'PUBLISHED'}
            />
          </main>
          <aside style={rightRailStyle}>
            <FindingsByStatusSection
              label="Agreed findings"
              tone="agreed"
              findings={agreedFindings}
              dissentsByFinding={dissentsByFinding}
              sourceMsgIdByFinding={sourceMsgIdByFinding}
              spawnedBuildThreadId={detail.pass.spawnedBuildThreadId}
              emptyHint="Nothing locked in yet."
            />
            <FindingsByStatusSection
              label="Open"
              tone="open"
              findings={openFindings}
              dissentsByFinding={dissentsByFinding}
              sourceMsgIdByFinding={sourceMsgIdByFinding}
              spawnedBuildThreadId={detail.pass.spawnedBuildThreadId}
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

  // Hidden in every phase before the pass terminates — there's nothing
  // to apply until the panel has settled.
  if (pass.phase !== 'TERMINATE') {
    return null;
  }
  // At TERMINATE the affordance always shows; it's enabled only when at
  // least one AGREED finding is Major-or-higher (a nit-only review
  // isn't worth a build thread). Zero eligible → disabled + a tooltip
  // that says why.
  const eligible = detail.findings.some(
      f => f.status === 'AGREED' && isMajorOrHigher(f.severity));
  const disabledReason = 'Spawn build needs at least one AGREED finding with '
      + 'severity Major or higher.';

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
      <button
        type="button"
        className="button"
        disabled={busy || !eligible}
        title={eligible ? undefined : disabledReason}
        onClick={() => void onSpawn()}
      >
        → Spawn build thread
      </button>
      <p style={spawnHintStyle}>
        {eligible
          ? `Opens a build thread pre-seeded with the AGREED findings. Your own
             PR forks off its head; someone else's gets suggested-change
             comments — both still go through the publish gate.`
          : disabledReason}
      </p>
      {error !== null && <div style={errorStyle} role="alert">{error}</div>}
    </div>
  );
}

/** A finding worth spinning up a build thread for — Major or Blocker.
 *  Nits and questions don't clear the bar. */
function isMajorOrHigher(severity: ReviewFindingSeverityDto): boolean {
  return severity === 'MAJOR' || severity === 'BLOCKER';
}

/** Top bar — Back chevron · panel-title · PR ref · phase / cost
 *  meters on the right. The mockup's round meter is gone: the lead
 *  drives phases against the agenda now, so the agenda widget carries
 *  in-pass progress and the cost cap is the budget signal. */
function TopBar({ detail, onBack }: { detail: ReviewPassDetailDto | null; onBack: () => void }) {
  const costMilli = detail?.pass.costUsdMilli ?? 0;
  const costCapMilli = detail?.pass.costCapMilli ?? 0;
  const costPct = costCapMilli > 0 ? Math.min(100, (costMilli / costCapMilli) * 100) : 0;
  return (
    <header style={topBarStyle}>
      <button type="button" className="button" onClick={onBack} style={backBtnStyle}>← Back</button>
      <span style={panelBadgeStyle}>Review panel</span>
      <div style={titleColStyle}>
        <h1 style={titleStyle}>
          {detail
              ? (detail.prTitle ?? `${detail.pass.repoFullName}#${detail.pass.prNumber}`)
              : 'Review thread'}
        </h1>
        {detail && (
          <span style={titleRefStyle}>
            {detail.pass.repoFullName} · PR #{detail.pass.prNumber}
          </span>
        )}
      </div>
      {detail && (
        <div style={metaStyle}>
          <Meter label="Phase">
            <PhasePill phase={detail.pass.phase} />
          </Meter>
          {costCapMilli > 0 && (
            <Meter label="Cost">
              <span style={{ ...meterValueStyle, color: costColor(costPct) }}>
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

/** Cost-meter color stops shared by the top-bar meter + budget bar:
 *  teal under 80% of cap, amber at 80%, red at 95%. */
function costColor(pct: number): string {
  if (pct >= 95) return '#cf1322';
  if (pct >= 80) return '#d97706';
  return '#0d9488';
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
      <div style={prNumStyle}>#{detail.pass.prNumber}</div>
      {detail.prTitle && <div style={reviewingTitleStyle}>{detail.prTitle}</div>}
      <div style={reviewingMetaStyle}>
        <span style={reviewingRepoStyle}>{detail.pass.repoFullName}</span>
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
        <span style={flowConnectorStyle} aria-hidden />
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

/** Left-rail "Budget" card — debate-round + cost progress bars. */
function BudgetCard({ detail }: { detail: ReviewPassDetailDto }) {
  const round = detail.pass.round;
  const roundCap = detail.pass.roundCap;
  const roundPct = roundCap > 0 ? Math.min(100, Math.round((round / roundCap) * 100)) : 0;
  const costPct = detail.pass.costCapMilli > 0
      ? Math.min(100, Math.round((detail.pass.costUsdMilli / detail.pass.costCapMilli) * 100))
      : 0;
  return (
    <section style={cardStyle} aria-label="Budget">
      <h2 style={cardTitleStyle}>Budget</h2>
      {roundCap > 0 && (
        <div style={budgetRowStyle}>
          <div style={budgetTopRowStyle}>
            <span style={budgetLabelStyle}>Debate rounds</span>
            <span style={budgetValueStyle}>{round} / {roundCap}</span>
          </div>
          <div style={progressTrackStyle}>
            <div style={progressFillStyle(roundPct, 'var(--accent, #7c3aed)')} />
          </div>
        </div>
      )}
      <div style={budgetRowStyle}>
        <div style={budgetTopRowStyle}>
          <span style={budgetLabelStyle}>Cost</span>
          <span style={{ ...budgetValueStyle, color: costColor(costPct) }}>
            ${(detail.pass.costUsdMilli / 1000).toFixed(2)} / ${(detail.pass.costCapMilli / 1000).toFixed(2)}
          </span>
        </div>
        <div style={progressTrackStyle}>
          <div style={progressFillStyle(costPct, costColor(costPct))} />
        </div>
      </div>
      <p style={budgetHintStyle}>✦ stops early on convergence</p>
    </section>
  );
}

/** Right-rail findings list partitioned by status. Same shape as
 *  the legacy FindingsSection but rendered twice — Agreed (locked-in)
 *  and Open (still in flight). */
/** Right-rail findings as a clean checklist (mockup): a count badge in
 *  the header, then one row per finding led by a status glyph — a green
 *  check for locked-in (Agreed) items, an amber dot for in-flight (Open)
 *  ones — with the file:line anchor as a muted mono prefix. */
function FindingsByStatusSection({
  label, tone, findings, dissentsByFinding, sourceMsgIdByFinding,
  spawnedBuildThreadId, emptyHint,
}: {
  label: string;
  tone: 'agreed' | 'open';
  findings: ReviewFindingDto[];
  /** Recorded dissents per finding id — flagged on the row so a
   *  consensus call with a minority position stays visible. */
  dissentsByFinding: Map<string, number>;
  /** Finding id → the transcript message that #ref's it. Rows with a
   *  source become a button that scrolls + flashes that message. */
  sourceMsgIdByFinding: Map<string, string>;
  /** Set once a build thread was spawned — RESOLVED findings then link
   *  to it with a "resolved by build thread" badge. */
  spawnedBuildThreadId: string | null;
  emptyHint: string;
}) {
  const accent = tone === 'agreed' ? '#16a34a' : '#d97706';
  return (
    <section style={cardStyle} aria-label={label}>
      <h2 style={checklistHeadStyle}>
        <span>{label}</span>
        <span style={countBadgeStyle(accent)}>{findings.length}</span>
      </h2>
      {findings.length === 0 ? (
        <div style={emptyInlineStyle}>{emptyHint}</div>
      ) : (
        <ul style={checklistStyle}>
          {findings.map(f => {
            const sourceMsgId = sourceMsgIdByFinding.get(f.id) ?? null;
            const locatable = sourceMsgId !== null;
            return (
              <li
                key={f.id}
                className={locatable ? 'review-finding-row' : undefined}
                style={checklistRowStyle(locatable)}
                role={locatable ? 'button' : undefined}
                tabIndex={locatable ? 0 : undefined}
                title={locatable ? 'Jump to where the panel discussed this' : undefined}
                onClick={locatable ? () => flashReviewMessage(sourceMsgId) : undefined}
                onKeyDown={locatable
                    ? (e) => { if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault(); flashReviewMessage(sourceMsgId); } }
                    : undefined}
              >
                <span style={checklistGlyphStyle(accent)} aria-hidden>
                  {tone === 'agreed' ? '✓' : '●'}
                </span>
                <span style={checklistBodyStyle}>
                  {f.path !== null && (
                    <span style={findingAnchorInlineStyle}>
                      {f.path}{f.line !== null ? `:${f.line}` : ''}{' '}
                    </span>
                  )}
                  {f.body}
                  <span style={severityDotStyle(severityColor(f.severity))}>
                    {f.severity.toLowerCase()}
                  </span>
                  {(dissentsByFinding.get(f.id) ?? 0) > 0 && (
                    <span style={dissentFlagStyle} title="The lead recorded dissent on this finding">
                      ⚑ {dissentsByFinding.get(f.id)} dissent
                    </span>
                  )}
                  {f.status === 'RESOLVED' && spawnedBuildThreadId !== null && (
                    <span style={resolvedBadgeStyle}>
                      ✓ resolved by build thread #{spawnedBuildThreadId.slice(0, 8)}
                    </span>
                  )}
                </span>
              </li>
            );
          })}
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
  const disputed = detail.findings.filter(
      f => f.status === 'DISPUTED' || f.status === 'REPORTED');
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

function RosterSection({
  participants, leadId,
}: {
  participants: ReviewParticipantDto[];
  leadId: string | null;
}) {
  return (
    <section style={cardStyle} aria-label="Panel roster">
      <h2 style={cardTitleStyle}>Panel</h2>
      <ul style={rosterListStyle}>
        {participants.map(p => {
          const color = p.color ?? rosterFallbackColor(p.kind);
          return (
            <li key={p.id} style={rosterRowStyle}>
              <span style={{ ...rosterAvatarStyle, background: color }} aria-hidden>
                {p.personaLabel.slice(0, 1).toUpperCase()}
              </span>
              <span style={rosterIdentityStyle}>
                <span style={rosterPersonaStyle}>{p.personaLabel}</span>
                {p.model !== null && (
                  <span style={rosterModelStyle}>{p.model}</span>
                )}
              </span>
              <span style={rosterRoleBadgeStyle(p.kind, p.id === leadId)}>
                {rosterRoleLabel(p.kind, p.id === leadId)}
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** Short role tag shown on each roster row, mirroring the mockup:
 *  the LEAD seat reads LEAD, reviewers THINK, the human WATCHES. */
function rosterRoleLabel(kind: ReviewParticipantDto['kind'], isLead: boolean): string {
  if (kind === 'HUMAN') return 'WATCH';
  if (kind === 'LEAD' || isLead) return 'LEAD';
  return 'THINKS';
}

function rosterFallbackColor(kind: ReviewParticipantDto['kind']): string {
  return kind === 'LEAD' ? '#737373' : kind === 'HUMAN' ? '#16a34a' : '#0066cc';
}

const PHASE_LABELS: Record<string, string> = {
  KICKOFF: 'Kickoff',
  INDEPENDENT: 'Independent review',
  CROSS_REVIEW: 'Cross-review',
  CONSENSUS: 'Consensus',
  DEBATE: 'Debate',
  TERMINATE: 'Wrap-up',
  ARBITRATE: 'Arbitration',
  PUBLISHED: 'Published',
};

function phaseLabel(phase: string): string {
  return PHASE_LABELS[phase] ?? phase.toLowerCase();
}

/** Ordinal shown on a phase divider — INDEPENDENT is "Phase 1" so
 *  Cross-review lands on Phase 2 and Debate on Phase 4, matching the
 *  panel mockup. Setup/wrap-up phases carry no number. */
const PHASE_NUMBERS: Record<string, number> = {
  INDEPENDENT: 1,
  CROSS_REVIEW: 2,
  CONSENSUS: 3,
  DEBATE: 4,
};

/** Divider caption: "Phase 2 · Cross-review", and for debate the active
 *  round too ("Phase 4 · Debate · round 2"). */
function phaseDividerText(m: ReviewPanelMessageDto): string {
  const num = PHASE_NUMBERS[m.phase];
  const head = num !== undefined ? `Phase ${num} · ${phaseLabel(m.phase)}` : phaseLabel(m.phase);
  if (m.phase === 'DEBATE' && m.round > 0) {
    return `${head} · round ${m.round}`;
  }
  return head;
}

/** The lead's agenda — the phase TODO list set at kickoff, ticked
 *  through as the pass runs, frozen at TERMINATE. Sticky above the
 *  transcript so the watcher always sees where the panel stands. */
function AgendaSection({ agenda, passPhase }: { agenda: AgendaPhaseDto[]; passPhase: string }) {
  if (agenda.length === 0) {
    // Pass just kicked off; the lead hasn't called set_agenda yet.
    // A quiet placeholder beats an empty box — but a terminal pass
    // with no agenda (a pre-agenda historical run) shows nothing.
    const running = !['TERMINATE', 'ARBITRATE', 'PUBLISHED'].includes(passPhase);
    if (!running) return null;
    return (
      <section style={agendaCardStyle} aria-label="Agenda">
        <div style={agendaPlaceholderStyle}>
          <span style={livePulseStyle} className="review-live-dot" aria-hidden />
          Lead is laying out the agenda…
        </div>
      </section>
    );
  }
  const done = agenda.filter(p => p.status === 'DONE').length;
  const inProgress = agenda.filter(p => p.status === 'IN_PROGRESS').length;
  const open = agenda.length - done - inProgress;
  const summary = `${agenda.length} tasks (${done} done, ${inProgress} in progress, ${open} open)`;
  return (
    <section style={agendaCardStyle} aria-label="Agenda">
      <div style={agendaHeadStyle}>{summary}</div>
      <ol style={agendaListStyle}>
        {agenda.map(phase => (
          <li key={phase.id} style={agendaRowStyle}>
            <span
              style={agendaGlyphStyle(phase.status)}
              className={phase.status === 'IN_PROGRESS' ? 'review-agenda-glyph--active' : undefined}
              aria-hidden
            >
              {phase.status === 'DONE' ? '✓' : phase.status === 'IN_PROGRESS' ? '◼' : '◻'}
            </span>
            <span style={phase.status === 'DONE' ? agendaTitleDoneStyle : agendaTitleStyle}>
              {phase.title}
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}

/** Scroll a transcript message into view and flash it — the shared
 *  affordance behind dispatch-arrow "jump to response" and findings
 *  "jump to source". A pure watcher-side DOM effect; touches no data. */
function flashReviewMessage(messageId: string): void {
  if (typeof document === 'undefined') return;
  const el = document.querySelector<HTMLElement>(`[data-review-msg-id="${messageId}"]`);
  if (el === null) return;
  // jsdom throws "Not implemented" on scrollIntoView; the flash class is
  // the part that matters for tests, so never let the scroll abort it.
  try {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  } catch {
    /* no-op outside a real layout engine */
  }
  el.classList.remove('review-msg-flash');
  void el.offsetWidth; // restart the animation if it's mid-flight
  el.classList.add('review-msg-flash');
  window.setTimeout(() => el.classList.remove('review-msg-flash'), 1700);
}

/** A run of consecutive lead dispatch turns (one round's parallel
 *  fan-out) coalesced into a single render item, vs. an ordinary
 *  message. The Lead firing dispatch_to_reviewer N times in one turn
 *  is one utterance with N addressees — never N bubbles. */
type TranscriptItem =
  | { kind: 'message'; message: ReviewPanelMessageDto }
  | { kind: 'dispatch'; messages: ReviewPanelMessageDto[]; phase: string; round: number };

function itemPhase(item: TranscriptItem): string {
  return item.kind === 'message' ? item.message.phase : item.phase;
}

/** Coalesce consecutive lead @mention dispatch turns (same phase +
 *  round) into one dispatch item; everything else stays a message.
 *  A lone dispatch renders as a normal @mention bubble — the fan-out
 *  treatment only earns its keep at 2+ addressees. */
function buildTranscriptItems(
  visible: ReviewPanelMessageDto[],
  leadId: string | null,
): TranscriptItem[] {
  const isDispatch = (m: ReviewPanelMessageDto): boolean =>
    leadId !== null && m.participantId === leadId && m.mentions.length > 0
        && m.payloadKind !== 'dissent';
  const items: TranscriptItem[] = [];
  let i = 0;
  while (i < visible.length) {
    const m = visible[i];
    if (isDispatch(m)) {
      const run = [m];
      let j = i + 1;
      while (j < visible.length
          && isDispatch(visible[j])
          && visible[j].phase === m.phase
          && visible[j].round === m.round) {
        run.push(visible[j]);
        j += 1;
      }
      if (run.length >= 2) {
        items.push({ kind: 'dispatch', messages: run, phase: m.phase, round: m.round });
        i = j;
        continue;
      }
    }
    items.push({ kind: 'message', message: m });
    i += 1;
  }
  return items;
}

/** The panel transcript as a group chat: phase dividers, per-persona
 *  bubbles (the LEAD as a system voice, the human right-aligned),
 *  @mention / #ref chips, parallel-dispatch fan-out groups, and a live
 *  "reviewing…" pulse while the pass is still running. */
function TranscriptSection({
  messages,
  participantsById,
  passPhase,
  leadId,
  focusParticipantId,
  onMentionClick,
}: {
  messages: ReviewPanelMessageDto[];
  participantsById: Map<string, ReviewParticipantDto>;
  passPhase: string;
  leadId: string | null;
  focusParticipantId: string | null;
  onMentionClick: (participantId: string) => void;
}) {
  const running = !['TERMINATE', 'ARBITRATE', 'PUBLISHED'].includes(passPhase);
  // Focused view: one reviewer's stream — what they said plus what
  // was addressed to them. A watcher-side view filter only — the data
  // the page fetched is unchanged; we just hide rows here.
  const visible = focusParticipantId === null
      ? messages
      : messages.filter(m => m.participantId === focusParticipantId
          || m.mentions.includes(focusParticipantId));
  const items = buildTranscriptItems(visible, leadId);

  /** First message after the dispatch group where reviewer R replies —
   *  the target the dispatch arrow jumps to. Scoped to the visible set
   *  so a jump never lands on a row the filter is hiding. */
  const responseAfter = (groupLastId: string, reviewerId: string): string | null => {
    const start = visible.findIndex(m => m.id === groupLastId);
    if (start < 0) return null;
    for (let k = start + 1; k < visible.length; k += 1) {
      if (visible[k].participantId === reviewerId) return visible[k].id;
    }
    return null;
  };

  return (
    <section style={cardStyle} aria-label="Panel transcript">
      <h2 style={cardTitleStyle}>Transcript</h2>
      {items.length === 0 ? (
        <div style={emptyInlineStyle}>The panel is warming up…</div>
      ) : (
        <div style={chatListStyle}>
          {items.map((item, i) => {
            const showDivider = itemPhase(item) !== (items[i - 1] && itemPhase(items[i - 1]));
            const phaseSource = item.kind === 'message' ? item.message : item.messages[0];
            return (
              <Fragment key={item.kind === 'message' ? item.message.id : item.messages[0].id}>
                {showDivider && (
                  <div style={phaseDividerStyle}>
                    <span style={phaseDividerLabelStyle}>{phaseDividerText(phaseSource)}</span>
                  </div>
                )}
                {item.kind === 'dispatch' ? (
                  <DispatchGroupBubble
                    dispatches={item.messages}
                    participantsById={participantsById}
                    onMentionClick={onMentionClick}
                    onJumpToResponse={(reviewerId) => {
                      const target = responseAfter(
                          item.messages[item.messages.length - 1].id, reviewerId);
                      if (target !== null) flashReviewMessage(target);
                    }}
                  />
                ) : (
                  <MessageBubble
                    onMentionClick={onMentionClick}
                    message={item.message}
                    author={participantsById.get(item.message.participantId) ?? null}
                    isLead={item.message.participantId === leadId}
                    participantsById={participantsById}
                    focusParticipantId={focusParticipantId}
                  />
                )}
              </Fragment>
            );
          })}
          {running && (
            <div style={liveIndicatorStyle}>
              <span style={livePulseStyle} className="review-live-dot" aria-hidden /> reviewing…
            </div>
          )}
        </div>
      )}
    </section>
  );
}

/** One lead turn that fanned out to N reviewers in parallel: a single
 *  LEAD bubble with one arrow chip per addressee. Each arrow jumps to
 *  that reviewer's response below (they land in completion order as the
 *  page polls). One utterance, many addressees — not N bubbles. */
function DispatchGroupBubble({
  dispatches, participantsById, onMentionClick, onJumpToResponse,
}: {
  dispatches: ReviewPanelMessageDto[];
  participantsById: Map<string, ReviewParticipantDto>;
  onMentionClick: (participantId: string) => void;
  onJumpToResponse: (reviewerId: string) => void;
}) {
  const firstId = dispatches[0].id;
  return (
    <div style={bubbleRowStyle} data-review-msg-id={firstId}>
      <span style={{ ...avatarStyle, background: rosterFallbackColor('LEAD') }} aria-hidden>
        L
      </span>
      <div style={bubbleLeadStyle}>
        <div style={bubbleHeadStyle}>
          <strong style={{ color: 'var(--text-2)' }}>Lead</strong>
          <span style={roleTagStyle}>lead</span>
          <span style={dispatchCountStyle}>
            dispatched {dispatches.length} reviewers in parallel
          </span>
        </div>
        <ul style={dispatchListStyle}>
          {dispatches.map(d => {
            const reviewerId = d.mentions[0] ?? '';
            const reviewer = participantsById.get(reviewerId);
            const color = reviewer?.color ?? rosterFallbackColor('REVIEWER');
            return (
              <li key={d.id} style={dispatchRowStyle}>
                <span style={{ ...dispatchArrowStyle, color }} aria-hidden>→</span>
                <button
                  type="button"
                  className="review-chip review-dispatch-arrow"
                  style={mentionChipStyleFor(color, false, false)}
                  onClick={() => onJumpToResponse(reviewerId)}
                  title="Jump to this reviewer's response"
                >
                  @{reviewer?.personaLabel ?? reviewerId}
                </button>
                <span style={dispatchBodyStyle}>{stripLeadingMention(d.body, reviewer?.personaLabel)}</span>
                {reviewerId !== '' && (
                  <button
                    type="button"
                    className="review-chip"
                    style={dispatchFilterBtnStyle}
                    onClick={() => onMentionClick(reviewerId)}
                    aria-label={`Filter the transcript to ${reviewer?.personaLabel ?? 'this reviewer'}`}
                    title="Filter the transcript to this reviewer's stream"
                  >
                    filter
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}

/** Drop a leading "@Label " from a dispatch directive so the arrow
 *  chip isn't echoed in the body text beside it. */
function stripLeadingMention(body: string, label: string | undefined): string {
  if (label === undefined) return body;
  const prefix = `@${label}`;
  return body.startsWith(prefix) ? body.slice(prefix.length).replace(/^[\s,:·-]+/, '') : body;
}

function MessageBubble({
  message, author, isLead, participantsById, onMentionClick, focusParticipantId,
}: {
  message: ReviewPanelMessageDto;
  author: ReviewParticipantDto | null;
  isLead: boolean;
  participantsById: Map<string, ReviewParticipantDto>;
  onMentionClick: (participantId: string) => void;
  focusParticipantId: string | null;
}) {
  const kind = author?.kind ?? 'REVIEWER';
  const name = author?.personaLabel ?? '?';
  const color = author?.color ?? 'var(--text-muted)';
  const isYou = kind === 'HUMAN';
  const isLeadKind = kind === 'LEAD';
  const showLeadTag = isLeadKind || isLead;
  const filtering = focusParticipantId !== null;

  return (
    <div
      style={isYou ? bubbleRowYouStyle : bubbleRowStyle}
      data-review-msg-id={message.id}
    >
      {!isYou && (
        <span style={{ ...avatarStyle, background: color }} aria-hidden>
          {name.slice(0, 1).toUpperCase()}
        </span>
      )}
      <div style={isLeadKind ? bubbleLeadStyle : isYou ? bubbleYouStyle : bubbleStyle}>
        <div style={bubbleHeadStyle}>
          <strong style={isYou ? undefined : { color }}>{name}</strong>
          {showLeadTag && <span style={roleTagStyle}>lead</span>}
          {message.mentions.map(id => {
            const mentioned = participantsById.get(id);
            const chipColor = mentioned?.color ?? rosterFallbackColor(mentioned?.kind ?? 'REVIEWER');
            const selected = id === focusParticipantId;
            return (
              <button
                key={id}
                type="button"
                className="review-chip"
                style={mentionChipStyleFor(chipColor, selected, filtering && !selected)}
                aria-pressed={selected}
                onClick={() => onMentionClick(id)}
                title={selected
                    ? 'Clear the transcript filter'
                    : "Filter the transcript to this reviewer's stream"}
              >
                @{mentioned?.personaLabel ?? id}
              </button>
            );
          })}
        </div>
        <div style={bubbleBodyStyle}>{renderMessageBody(message)}</div>
        {message.refs.length > 0 && (
          <div style={refRowStyle}>
            {message.refs.map(r => (
              <span key={r} style={refChipStyle}>#{refLabel(r)}</span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/** Cross-review messages carry a raw JSON envelope as their body;
 *  surface a readable one-liner instead. Everything else shows its
 *  prose body verbatim. */
function renderMessageBody(m: ReviewPanelMessageDto): string {
  if (m.payloadKind === 'cross_review') {
    return crossReviewSummary(m.payloadJson) ?? m.body;
  }
  return m.body;
}

function crossReviewSummary(payloadJson: string | null): string | null {
  if (payloadJson === null) {
    return null;
  }
  try {
    const env = JSON.parse(payloadJson) as {
      agree?: unknown[]; dispute?: unknown[]; open_questions?: unknown[];
    };
    const a = Array.isArray(env.agree) ? env.agree.length : 0;
    const d = Array.isArray(env.dispute) ? env.dispute.length : 0;
    const q = Array.isArray(env.open_questions) ? env.open_questions.length : 0;
    const parts = [`agrees with ${a}`, `disputes ${d}`];
    if (q > 0) {
      parts.push(`${q} open question${q === 1 ? '' : 's'}`);
    }
    return parts.join(' · ');
  } catch {
    return null;
  }
}

/** "finding:abc123" → "finding-abc123" (truncated), for a #ref chip. */
function refLabel(ref: string): string {
  const sep = ref.indexOf(':');
  if (sep < 0) {
    return ref;
  }
  const kind = ref.slice(0, sep);
  const id = ref.slice(sep + 1);
  return id.length > 8 ? `${kind}-${id.slice(0, 6)}…` : `${kind}-${id}`;
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
          .filter(f => f.status !== 'DISPUTED' && f.status !== 'REPORTED')
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
    case 'LEAD': return 'Lead';
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
  marginTop: 3,
  fontSize: 14,
  fontWeight: 700,
  color: 'var(--text-1)',
  lineHeight: 1.3,
};

const prNumStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--accent, #2563eb)',
  fontVariantNumeric: 'tabular-nums',
};

const reviewingMetaStyle: React.CSSProperties = {
  marginTop: 8,
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
};

const reviewingRepoStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const budgetHintStyle: React.CSSProperties = {
  margin: '10px 0 0',
  fontSize: 11,
  color: 'var(--text-3)',
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
  position: 'relative',
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

// Vertical line threading the step glyphs. Sits behind them (the
// glyphs carry an opaque background that masks it in the gaps), running
// from the centre of the first glyph to the centre of the last.
const flowConnectorStyle: React.CSSProperties = {
  position: 'absolute',
  left: 7,
  top: 12,
  bottom: 12,
  width: 2,
  background: 'var(--border)',
  zIndex: 0,
};

const flowRowStyle: React.CSSProperties = {
  position: 'relative',
  zIndex: 1,
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
    // Opaque so the connector line behind the column is hidden under the
    // glyph and only shows in the gaps between steps.
    background: state === 'current' ? '#f3eefe' : 'var(--bg-1)',
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

const titleColStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
};

const titleStyle: React.CSSProperties = {
  fontSize: 18,
  fontWeight: 600,
  letterSpacing: '-0.01em',
  color: 'var(--text-1)',
  margin: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const titleRefStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontVariantNumeric: 'tabular-nums',
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
  alignItems: 'center',
  gap: 8,
  fontSize: 13,
};

const rosterAvatarStyle: React.CSSProperties = {
  flex: '0 0 auto',
  width: 26,
  height: 26,
  borderRadius: '50%',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontSize: 12,
  fontWeight: 700,
};

const rosterIdentityStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
};

function rosterRoleBadgeStyle(
  kind: ReviewParticipantDto['kind'], isLead: boolean,
): React.CSSProperties {
  const lead = kind === 'LEAD' || isLead;
  const human = kind === 'HUMAN';
  const color = lead ? '#b45309' : human ? '#15803d' : '#1d4ed8';
  const bg = lead ? 'rgba(217,119,6,0.12)' : human ? 'rgba(22,163,74,0.12)' : 'rgba(37,99,235,0.10)';
  return {
    flexShrink: 0,
    fontSize: 9,
    fontWeight: 700,
    letterSpacing: '0.06em',
    textTransform: 'uppercase',
    color,
    background: bg,
    borderRadius: 999,
    padding: '2px 7px',
  };
}

const rosterPersonaStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const rosterModelStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 10,
  color: 'var(--text-3)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const chatListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const phaseDividerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  margin: '6px 0 2px',
};

const phaseDividerLabelStyle: React.CSSProperties = {
  fontSize: 10,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
  background: 'var(--bg-1)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  padding: '2px 10px',
};

const bubbleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
};

const bubbleRowYouStyle: React.CSSProperties = {
  ...bubbleRowStyle,
  justifyContent: 'flex-end',
};

const avatarStyle: React.CSSProperties = {
  flex: '0 0 auto',
  width: 24,
  height: 24,
  borderRadius: '50%',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontSize: 12,
  fontWeight: 600,
  marginTop: 2,
};

const bubbleStyle: React.CSSProperties = {
  maxWidth: '88%',
  padding: '8px 11px',
  background: 'var(--bg-2)',
  borderRadius: 10,
  border: '1px solid var(--border)',
};

const bubbleLeadStyle: React.CSSProperties = {
  ...bubbleStyle,
  maxWidth: '100%',
  width: '100%',
  background: 'rgba(124, 58, 237, 0.04)',
  borderStyle: 'dashed',
  borderColor: 'rgba(124, 58, 237, 0.28)',
};

const bubbleYouStyle: React.CSSProperties = {
  ...bubbleStyle,
  background: 'rgba(16, 185, 129, 0.14)',
  border: '1px solid rgba(16, 185, 129, 0.4)',
};

const bubbleHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  flexWrap: 'wrap',
  gap: 6,
  marginBottom: 4,
  fontSize: 12.5,
};

const roleTagStyle: React.CSSProperties = {
  fontSize: 10,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: 'var(--text-3)',
  border: '1px solid var(--border)',
  borderRadius: 4,
  padding: '0 5px',
};

/** A mention chip tinted with the addressed reviewer's persona color.
 *  Selected → filled (the active filter); dimmed → faded (a filter is
 *  on and this isn't the selected chip); otherwise the soft tint. */
function mentionChipStyleFor(
  color: string, selected: boolean, dimmed: boolean,
): React.CSSProperties {
  return {
    fontSize: 11,
    fontWeight: 600,
    color: selected ? '#fff' : color,
    background: selected ? color : `${color}1f`,
    border: `1px solid ${selected ? color : 'transparent'}`,
    borderRadius: 5,
    padding: '0 6px',
    cursor: 'pointer',
    opacity: dimmed ? 0.45 : 1,
    transition: 'opacity 140ms ease, background 140ms ease',
  };
}

const dispatchCountStyle: React.CSSProperties = {
  fontSize: 10.5,
  color: 'var(--text-3)',
  fontStyle: 'italic',
};

const dispatchListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 5,
};

const dispatchRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 6,
  fontSize: 12.5,
  lineHeight: 1.45,
};

const dispatchArrowStyle: React.CSSProperties = {
  flexShrink: 0,
  fontWeight: 700,
};

const dispatchBodyStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  color: 'var(--text-2)',
};

const dispatchFilterBtnStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 9.5,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: 'var(--text-3)',
  background: 'transparent',
  border: '1px solid var(--border)',
  borderRadius: 4,
  padding: '0 5px',
  cursor: 'pointer',
};

const bubbleBodyStyle: React.CSSProperties = {
  fontSize: 13,
  lineHeight: 1.55,
  color: 'var(--text-1)',
  whiteSpace: 'pre-wrap',
};

const refRowStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 5,
  marginTop: 6,
};

const refChipStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  background: 'var(--bg-1)',
  border: '1px solid var(--border)',
  borderRadius: 4,
  padding: '0 5px',
};

const liveIndicatorStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 7,
  alignSelf: 'center',
  marginTop: 4,
  fontSize: 12,
  color: 'var(--text-3)',
};

const livePulseStyle: React.CSSProperties = {
  width: 7,
  height: 7,
  borderRadius: '50%',
  background: '#0ea5e9',
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

const checklistHeadStyle: React.CSSProperties = {
  ...cardTitleStyle,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
};

function countBadgeStyle(color: string): React.CSSProperties {
  return {
    fontSize: 11,
    fontWeight: 700,
    color: '#fff',
    background: color,
    borderRadius: 999,
    minWidth: 18,
    height: 18,
    padding: '0 6px',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
  };
}

const checklistStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

function checklistRowStyle(locatable: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'flex-start',
    gap: 8,
    cursor: locatable ? 'pointer' : 'default',
    borderRadius: 6,
    margin: '0 -4px',
    padding: '2px 4px',
  };
}

const resolvedBadgeStyle: React.CSSProperties = {
  display: 'inline-block',
  marginLeft: 6,
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.03em',
  textTransform: 'uppercase',
  color: '#15803d',
  background: 'rgba(22, 163, 74, 0.12)',
  borderRadius: 4,
  padding: '1px 6px',
  whiteSpace: 'nowrap',
};

function checklistGlyphStyle(color: string): React.CSSProperties {
  return {
    flexShrink: 0,
    marginTop: 1,
    fontSize: 11,
    fontWeight: 700,
    color,
  };
}

const checklistBodyStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 12.5,
  lineHeight: 1.5,
  color: 'var(--text-1)',
};

const findingAnchorInlineStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
  color: 'var(--text-3)',
};

function severityDotStyle(color: string): React.CSSProperties {
  return {
    marginLeft: 6,
    fontSize: 9,
    fontWeight: 700,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    color,
    whiteSpace: 'nowrap',
  };
}

const agendaCardStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 10,
  background: 'var(--bg-1)',
  padding: '10px 14px',
  position: 'sticky',
  top: 0,
  zIndex: 5,
  // The sticky strip floats over scrolling transcript bubbles, so it
  // needs an opaque-ish backdrop + a soft shadow to stay legible.
  boxShadow: '0 4px 10px rgba(15, 23, 42, 0.05)',
};

const agendaPlaceholderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 12.5,
  color: 'var(--text-3)',
  fontStyle: 'italic',
};

const agendaHeadStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
  color: 'var(--text-3)',
  marginBottom: 6,
};

const agendaListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const agendaRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 13,
};

function agendaGlyphStyle(status: AgendaPhaseStatusDto): React.CSSProperties {
  return {
    flexShrink: 0,
    width: 14,
    textAlign: 'center',
    fontWeight: 700,
    color: status === 'DONE' ? '#16a34a' : status === 'IN_PROGRESS' ? '#d97706' : 'var(--text-3)',
  };
}

const agendaTitleStyle: React.CSSProperties = {
  color: 'var(--text-1)',
};

const agendaTitleDoneStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  textDecoration: 'line-through',
};

const focusPillRowStyle: React.CSSProperties = {
  alignSelf: 'flex-start',
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  padding: '4px 6px 4px 12px',
  borderRadius: 999,
  background: 'var(--bg-1)',
  border: '1px solid var(--border)',
};

function focusPillTextStyle(color: string): React.CSSProperties {
  return {
    fontSize: 12,
    color: 'var(--text-2)',
    borderLeft: `3px solid ${color}`,
    paddingLeft: 8,
  };
}

const focusClearBtnStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  background: 'transparent',
  border: '1px solid var(--border)',
  borderRadius: 999,
  padding: '1px 8px',
  cursor: 'pointer',
};

const dissentFlagStyle: React.CSSProperties = {
  marginLeft: 6,
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
  color: '#b91c1c',
  whiteSpace: 'nowrap',
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
