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
import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { MarkdownProse } from '../threads/MarkdownProse';
import type {
  AgendaPhaseDto,
  AgendaPhaseStatusDto,
  PullRequestCommitDto,
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
  /** Open the reviewed PR in the app's PR detail view. Optional so the
   *  page still renders standalone (the PR ref just isn't clickable). */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
  /** Open the reviewed PR straight into the code-diff view, where the
   *  agreed findings overlay the diff as inline comments. */
  onOpenDiff?: (owner: string, repo: string, prNumber: number) => void;
};

/** Read-only view of a review pass: the panel roster, the
 *  transcript (kickoff + reviewer summary as bubbles), and the
 *  structured findings with severity chips. Phase 1 = single
 *  reviewer + suggested verdict; the gated "Post review to PR"
 *  affordance is a disabled placeholder here and lands in a follow-up
 *  commit. */
function ReviewThreadPage({ threadId, onBack, onOpenPr, onOpenDiff }: Props) {
  const [detail, setDetail] = useState<ReviewPassDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Wall-clock until which we keep polling even on a terminal pass: a
  // steer runs its reply asynchronously on the backend, so a "continue
  // reviewing" on a finished pass needs the transcript poll alive long
  // enough for the lead/reviewer reply to land. Each steer extends it.
  const [steerPollUntil, setSteerPollUntil] = useState(0);

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
    const terminal = phase === 'TERMINATE' || phase === 'ARBITRATE' || phase === 'PUBLISHED';
    const steering = Date.now() < steerPollUntil;
    if (terminal && !steering) return;
    const timer = window.setInterval(() => { void refresh(); }, 5_000);
    // When the only reason we're polling is a pending steer, stop once
    // the window elapses so an idle terminal page costs nothing again.
    const stop = terminal && steering
      ? window.setTimeout(() => setSteerPollUntil(0), steerPollUntil - Date.now())
      : undefined;
    return () => {
      window.clearInterval(timer);
      if (stop !== undefined) window.clearTimeout(stop);
    };
  }, [phase, refresh, steerPollUntil]);

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
        f.status === 'AGREED' || f.status === 'RESOLVED' || f.status === 'POSTED'
        // ARBITRATED = a disputed finding the human chose "Include" for; it's
        // locked in and will publish, so it belongs in the Agreed list rather
        // than vanishing once it leaves the arbitration ballot.
        || f.status === 'ARBITRATED'),
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
      <div style={meshBgStyle} aria-hidden />
      <div style={noiseBgStyle} aria-hidden />
      <TopBar
        detail={detail}
        onBack={onBack}
        onOpenPr={onOpenPr}
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
            <ReviewingCard detail={detail} onOpenPr={onOpenPr} />
            <RosterSection participants={detail.participants} leadId={leadId} />
            <FlowStepper currentPhase={detail.pass.phase} />
            <BudgetCard
              detail={detail}
              onRaised={(next) => setDetail(next)}
              onResumed={(next) => {
                setDetail(next);
                // The pass transitions out of its terminal phase once the
                // executor starts; keep polling until that's observed (then
                // the normal non-terminal poll takes over).
                setSteerPollUntil(Date.now() + 180_000);
              }}
            />
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
            <SteerComposer
              passId={detail.pass.id}
              participants={detail.participants}
              leadId={leadId}
              published={detail.pass.phase === 'PUBLISHED'}
              onSent={(next) => {
                setDetail(next);
                // The reply runs async on the backend; keep the transcript
                // poll alive (even on a terminal pass) so it lands.
                setSteerPollUntil(Date.now() + 180_000);
              }}
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
            {onOpenDiff !== undefined && agreedFindings.length > 0 && (() => {
              const [owner, repo] = detail.pass.repoFullName.split('/');
              return (
                <button
                  type="button"
                  style={viewOnDiffStyle}
                  onClick={() => onOpenDiff(owner, repo, detail.pass.prNumber)}
                  title="Open the PR's code diff with these agreed findings overlaid inline at their lines"
                >
                  ⌖ View findings on the diff
                </button>
              );
            })()}
            {/* During ARBITRATE the ballot below lists the same disputed
                findings with actions, so the read-only Open panel would
                just duplicate it — hide it then. */}
            {detail.pass.phase !== 'ARBITRATE' && (
              <FindingsByStatusSection
                label="Open"
                tone="open"
                findings={openFindings}
                dissentsByFinding={dissentsByFinding}
                sourceMsgIdByFinding={sourceMsgIdByFinding}
                spawnedBuildThreadId={detail.pass.spawnedBuildThreadId}
                emptyHint="All disagreements resolved or arbitrated."
              />
            )}
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
      f => (f.status === 'AGREED' || f.status === 'ARBITRATED') && isMajorOrHigher(f.severity));
  const disabledReason = 'Spawn build needs at least one agreed or arbitrated-in finding '
      + 'with severity Major or higher.';

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
        style={(busy || !eligible) ? { ...btnApplyStyle, ...btnApplyDisabledStyle } : btnApplyStyle}
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
/** Build a click handler that opens the reviewed PR in-app, or undefined
 *  when there's no navigation callback / a malformed repo slug. */
function prOpener(
  detail: ReviewPassDetailDto,
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void,
): (() => void) | undefined {
  if (onOpenPr === undefined) return undefined;
  const slash = detail.pass.repoFullName.indexOf('/');
  if (slash <= 0) return undefined;
  const owner = detail.pass.repoFullName.slice(0, slash);
  const repo = detail.pass.repoFullName.slice(slash + 1);
  return () => onOpenPr(owner, repo, detail.pass.prNumber);
}

function TopBar({ detail, onBack, onOpenPr }: {
  detail: ReviewPassDetailDto | null;
  onBack: () => void;
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
}) {
  const open = detail !== null ? prOpener(detail, onOpenPr) : undefined;
  const costMilli = detail?.pass.costUsdMilli ?? 0;
  const costCapMilli = detail?.pass.costCapMilli ?? 0;
  const costPct = costCapMilli > 0 ? Math.min(100, (costMilli / costCapMilli) * 100) : 0;
  const findings = detail?.findings ?? [];
  const agreedCount = findings.filter(
      f => f.status === 'AGREED' || f.status === 'RESOLVED' || f.status === 'POSTED'
          || f.status === 'ARBITRATED').length;
  const openCount = findings.filter(
      f => f.status === 'DISPUTED' || f.status === 'REPORTED').length;
  return (
    <header style={topBarStyle}>
      <button type="button" className="button button--secondary" onClick={onBack} style={backBtnStyle}>← Back</button>
      <span style={panelBadgeStyle}>⚖ Review panel</span>
      <div style={breadcrumbStyle}>
        {detail ? (
          <>
            <span style={breadcrumbLeadStyle}>{detail.pass.repoFullName} · </span>
            {open !== undefined ? (
              <button type="button" style={prLinkStyle} onClick={open} title="Open this PR">
                PR #{detail.pass.prNumber}
              </button>
            ) : (
              <span style={breadcrumbLeadStyle}>PR #{detail.pass.prNumber}</span>
            )}
            <span style={breadcrumbLeadStyle}> · </span>
            <span style={breadcrumbTitleStyle}>
              {detail.prTitle ?? `${detail.pass.repoFullName}#${detail.pass.prNumber}`}
            </span>
          </>
        ) : (
          <span style={breadcrumbTitleStyle}>Review thread</span>
        )}
      </div>
      {detail && (
        <div style={metaStyle}>
          <PhasePill phase={detail.pass.phase} />
          <span style={countMetaStyle}>
            {agreedCount} agreed · {openCount} open
          </span>
          {costCapMilli > 0 && (
            <span style={{ ...costMetaStyle, color: costColor(costPct) }}>
              ${(costMilli / 1000).toFixed(2)} / ${(costCapMilli / 1000).toFixed(2)}
            </span>
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

/** Left-rail "Reviewing" card — a compact PR summary so the user can
 *  see which PR the panel is working on without scrolling away to
 *  the PR detail page. */
function ReviewingCard({ detail, onOpenPr }: {
  detail: ReviewPassDetailDto;
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
}) {
  const open = prOpener(detail, onOpenPr);
  const repo = detail.pass.repoFullName;
  const prNumber = detail.pass.prNumber;
  const [commits, setCommits] = useState<PullRequestCommitDto[] | null>(null);
  useEffect(() => {
    let cancelled = false;
    const pending = window.bridge.fetchPrCommits?.(repo, prNumber);
    if (pending === undefined) {
      setCommits([]);
      return;
    }
    void pending
      .then(c => { if (!cancelled) setCommits(c); })
      .catch(() => { if (!cancelled) setCommits([]); });
    return () => { cancelled = true; };
  }, [repo, prNumber]);

  return (
    <section style={cardStyle} aria-label="Reviewing">
      <h2 style={cardTitleStyle}>Reviewing</h2>
      {open !== undefined ? (
        <button type="button" style={prNumLinkStyle} onClick={open} title="Open this PR">
          #{prNumber}
        </button>
      ) : (
        <div style={prNumStyle}>#{prNumber}</div>
      )}
      {detail.prTitle && <div style={reviewingTitleStyle}>{detail.prTitle}</div>}
      <div style={reviewingMetaStyle}>
        <span style={reviewingRepoStyle}>{repo}</span>
      </div>
      <div style={commitsHeadStyle}>
        Commits{commits !== null && commits.length > 0 ? ` · ${commits.length}` : ''}
      </div>
      <ul style={commitsListStyle}>
        {commits === null ? (
          <li style={commitsEmptyStyle}>Loading commits…</li>
        ) : commits.length === 0 ? (
          <li style={commitsEmptyStyle}>No commits found.</li>
        ) : commits.map(c => (
          <li key={c.sha} style={commitItemStyle} title={c.message ?? c.sha}>
            <div style={commitRowStyle}>
              <span style={commitShaStyle}>{c.sha.slice(0, 7)}</span>
              <span style={commitMsgStyle}>{firstLine(c.message)}</span>
            </div>
            {(commitAuthorLabel(c) !== '' || c.authoredAt !== null) && (
              <div style={commitSubRowStyle}>
                {[commitAuthorLabel(c), commitTimeAgo(c.authoredAt)]
                    .filter(s => s !== '').join(' · ')}
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** First line of a commit message (the subject), or a placeholder. */
function firstLine(message: string | null): string {
  const line = (message ?? '').split('\n', 1)[0].trim();
  return line.length > 0 ? line : '(no message)';
}

/** A commit's author — login first, then the git name, else blank. */
function commitAuthorLabel(c: PullRequestCommitDto): string {
  return c.authorLogin ?? c.authorName ?? '';
}

/** Compact relative time for a commit's authored timestamp. */
function commitTimeAgo(iso: string | null): string {
  if (iso === null) return '';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const sec = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (sec < 60) return 'just now';
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `${day}d ago`;
  const mo = Math.floor(day / 30);
  return mo < 12 ? `${mo}mo ago` : `${Math.floor(mo / 12)}y ago`;
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
  // TERMINATE is the post-debate "results finalized" state in the backend
  // ReviewPhase enum. Without it here, a finished pass (phase=TERMINATE)
  // matched no row and the whole stepper rendered greyed-out.
  { id: 'TERMINATE', label: 'Wrap-up' },
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
              <span
                style={flowGlyphStyle(state)}
                className={state === 'current' ? 'review-agenda-glyph--active' : undefined}
                aria-hidden
              />
              <span style={flowLabelStyle(state)}>{phase.label}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

/** Left-rail "Budget" card — debate-round + cost progress bars. */
function BudgetCard({
  detail,
  onRaised,
  onResumed,
}: {
  detail: ReviewPassDetailDto;
  onRaised: (next: ReviewPassDetailDto) => void;
  onResumed: (next: ReviewPassDetailDto) => void;
}) {
  const round = detail.pass.round;
  const roundCap = detail.pass.roundCap;
  const costPct = detail.pass.costCapMilli > 0
      ? Math.min(100, Math.round((detail.pass.costUsdMilli / detail.pass.costCapMilli) * 100))
      : 0;
  // Mirror the backend's $10 ceiling so the cost button greys out once
  // the cap can't go any higher.
  const costCapMaxed = detail.pass.costCapMilli >= 10_000;
  const [raising, setRaising] = useState(false);
  const raise = async (addCostMilli: number, addRounds: number) => {
    if (raising) {
      return;
    }
    setRaising(true);
    try {
      const next = await window.bridge.raiseReviewBudget(detail.pass.id, addCostMilli, addRounds);
      onRaised(next);
    } catch (err) {
      console.error('raise review budget failed', err);
    } finally {
      setRaising(false);
    }
  };
  // The review is actively running whenever the pass isn't in a terminal
  // phase — so Resume must stay disabled through the whole run (initial or
  // resumed), not just for the instant the resume request takes.
  const running = !['TERMINATE', 'ARBITRATE', 'PUBLISHED'].includes(detail.pass.phase);
  const [resuming, setResuming] = useState(false);
  // Drop the local "resuming" flag once the backend has actually moved the
  // pass into a running phase; `running` then governs the disabled state,
  // bridging the gap between the click and the first non-terminal poll.
  useEffect(() => {
    if (running) {
      setResuming(false);
    }
  }, [running]);
  const resume = async () => {
    if (resuming || running) {
      return;
    }
    setResuming(true);
    try {
      const next = await window.bridge.resumeReview(detail.pass.id);
      onResumed(next);
    } catch (err) {
      console.error('resume review failed', err);
      setResuming(false);   // re-enable on failure; success holds until `running`
    }
  };
  const resumeDisabled = resuming || running;
  const resumeLabel = running ? 'Reviewing…' : resuming ? 'Resuming…' : '▶ Resume review';
  return (
    <section style={cardStyle} aria-label="Budget">
      <h2 style={cardTitleStyle}>Budget</h2>
      {roundCap > 0 && (
        <div style={budgetRowStyle}>
          <div style={budgetTopRowStyle}>
            <span style={budgetLabelStyle}>Debate rounds</span>
            <span style={budgetValueStyle}>{round} / {roundCap}</span>
          </div>
          <div style={pipsRowStyle} aria-hidden>
            {Array.from({ length: roundCap }, (_, i) => (
              <span key={i} style={i < round ? pipOnStyle : pipOffStyle} />
            ))}
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
      <div style={raiseRowStyle}>
        <button
          type="button"
          style={costCapMaxed ? raiseBtnDisabledStyle : raiseBtnStyle}
          disabled={raising || costCapMaxed}
          onClick={() => raise(500, 0)}
          title={costCapMaxed
            ? 'Cost cap is at the $10 maximum'
            : 'Raise the cost cap by $0.50 so the panel keeps reviewing'}
        >
          {costCapMaxed ? '$10 max' : '+ $0.50'}
        </button>
        <button
          type="button"
          style={raiseBtnStyle}
          disabled={raising}
          onClick={() => raise(0, 1)}
          title="Add one debate round so disputed findings get more discussion"
        >
          + 1 round
        </button>
      </div>
      <button
        type="button"
        style={resumeDisabled ? { ...resumeBtnStyle, opacity: 0.6, cursor: 'default' } : resumeBtnStyle}
        disabled={resumeDisabled}
        onClick={() => void resume()}
        title="Re-run the panel: reviewers re-review, then cross-review, consensus, debate, and wrap-up"
      >
        {resumeLabel}
      </button>
      <p style={budgetHintStyle}>✦ stops early on convergence — raise, then resume to keep reviewing</p>
    </section>
  );
}

const resumeBtnStyle: React.CSSProperties = {
  width: '100%',
  marginTop: 6,
  padding: '7px 8px',
  fontSize: 11.5,
  fontWeight: 700,
  color: '#fff',
  background: 'linear-gradient(180deg, #7c3aed, #6d28d9)',
  border: '1px solid #6d28d9',
  borderRadius: 8,
  cursor: 'pointer',
};

const raiseRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  marginTop: 4,
};

const raiseBtnStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 8px',
  fontSize: 11,
  fontWeight: 600,
  color: '#15803d',
  background: 'rgba(22,163,74,0.08)',
  border: '1px solid rgba(22,163,74,0.28)',
  borderRadius: 7,
  cursor: 'pointer',
};

const raiseBtnDisabledStyle: React.CSSProperties = {
  ...raiseBtnStyle,
  color: '#9ca3af',
  background: 'rgba(148,163,184,0.10)',
  border: '1px solid rgba(148,163,184,0.28)',
  cursor: 'default',
};

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
        <div style={emptyStateStyle}>
          <div style={emptyGlyphStyle} aria-hidden>◇</div>
          <div style={emptyLabelStyle}>{emptyHint}</div>
        </div>
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

/** Bottom composer — send a human message into the panel addressed to a
 *  reviewer or the lead via @mention. The addressed seat replies
 *  unbudgeted (the steer endpoint); a plain message defaults to the lead. */
function SteerComposer({
  passId, participants, leadId, published, onSent,
}: {
  passId: string;
  participants: ReviewParticipantDto[];
  leadId: string | null;
  published: boolean;
  onSent: (next: ReviewPassDetailDto) => void;
}) {
  const [text, setText] = useState('');
  const [targetId, setTargetId] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Auto-grow the input as the user types newlines (Shift+Enter), up to a
  // cap after which it scrolls — so earlier lines stay visible instead of
  // a single clipped row.
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = textareaRef.current;
    if (el === null) {
      return;
    }
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [text]);

  // Addressable seats: reviewers + the lead, never the human "You".
  const addressable = useMemo(
    () => participants.filter(p => p.kind !== 'HUMAN'),
    [participants]);
  // The active @token at the end of the text drives the autocomplete.
  const mentionQuery = /@([^\s@]*)$/.exec(text)?.[1] ?? null;
  const suggestions = mentionQuery === null ? [] : addressable.filter(p =>
    p.personaLabel.toLowerCase().includes(mentionQuery.toLowerCase()));
  // Send target: the explicitly @mentioned seat, else the lead.
  const effectiveTarget = targetId
    ?? addressable.find(p => p.id === leadId)?.id
    ?? addressable[0]?.id
    ?? null;
  const targetLabel = addressable.find(p => p.id === effectiveTarget)?.personaLabel ?? null;

  const pick = (p: ReviewParticipantDto) => {
    setText(t => t.replace(/@[^\s@]*$/, `@${p.personaLabel} `));
    setTargetId(p.id);
  };

  const send = async () => {
    const body = text.trim();
    if (body.length === 0 || effectiveTarget === null || sending) return;
    setSending(true);
    setError(null);
    try {
      const next = await window.bridge.steerReview(passId, effectiveTarget, body);
      setText('');
      setTargetId(null);
      onSent(next);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSending(false);
    }
  };

  if (published) {
    return (
      <div style={composerCardStyle}>
        <div style={composerFooterStyle}>
          <span style={composerHintStyle}>This pass is published — the conversation is closed.</span>
        </div>
      </div>
    );
  }

  return (
    <div style={composerCardStyle}>
      {suggestions.length > 0 && (
        <div style={mentionMenuStyle} role="listbox">
          {suggestions.map(p => (
            <button
              key={p.id}
              type="button"
              role="option"
              aria-selected={p.id === targetId}
              style={mentionOptionStyle}
              onClick={() => pick(p)}
            >
              <span style={mentionOptionLabelStyle}>@{p.personaLabel}</span>
              <span style={mentionOptionKindStyle}>{p.kind === 'LEAD' ? 'lead' : 'reviewer'}</span>
            </button>
          ))}
        </div>
      )}
      <div style={composerInboxStyle}>
        <span style={composerPromptStyle} aria-hidden>›</span>
        <textarea
          ref={textareaRef}
          placeholder="Message the panel — @mention a reviewer or the lead…"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              if (suggestions.length > 0) {
                pick(suggestions[0]);
              }
              else {
                void send();
              }
            }
          }}
          rows={1}
          style={composerTextareaStyle}
          disabled={sending}
        />
        <button
          type="button"
          style={(sending || text.trim().length === 0) ? { ...steerSendStyle, opacity: 0.5 } : steerSendStyle}
          onClick={() => void send()}
          disabled={sending || text.trim().length === 0}
        >
          {sending ? '…' : 'Send'}
        </button>
      </div>
      <div style={composerFooterStyle}>
        <span style={error !== null ? { ...composerHintStyle, color: '#cf1322' } : composerHintStyle}>
          {error !== null
            ? error
            : targetLabel !== null
              ? `↵ sends to @${targetLabel} (unbudgeted) · ⇧↵ newline`
              : 'Type @ to address a reviewer or the lead.'}
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
        {disputed.map(f => {
          const { reviewer, rest } = splitReviewerPrefix(f.body);
          return (
            <li key={f.id} style={ballotItemStyle}>
              <div style={ballotHeadStyle}>
                <SeverityChip severity={f.severity} />
                {reviewer !== null && <span style={ballotReviewerStyle}>{reviewer}</span>}
              </div>
              <div style={ballotAnchorStyle}>
                {f.path !== null
                    ? `${f.path}${f.line !== null ? `:${f.line}` : ''}`
                    : 'Whole PR'}
              </div>
              <div style={ballotBodyStyle}>
                <MarkdownProse text={rest} />
              </div>
              <div style={ballotActionsStyle}>
                <button
                  type="button"
                  style={busyId !== null ? { ...ballotIncludeStyle, opacity: 0.5 } : ballotIncludeStyle}
                  onClick={() => { void resolve(f.id, 'include'); }}
                  disabled={busyId !== null}
                >
                  {busyId === f.id ? 'Working…' : 'Include'}
                </button>
                <button
                  type="button"
                  style={busyId !== null ? { ...ballotDropStyle, opacity: 0.5 } : ballotDropStyle}
                  onClick={() => { void resolve(f.id, 'drop'); }}
                  disabled={busyId !== null}
                >
                  Drop
                </button>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** Split a leading "[Reviewer name] " tag off a finding body so the
 *  ballot can chip the author and markdown-render the rest. */
function splitReviewerPrefix(body: string): { reviewer: string | null; rest: string } {
  const m = /^\s*\[([^\]]+)\]\s*/.exec(body);
  if (m !== null) {
    return { reviewer: m[1], rest: body.slice(m[0].length) };
  }
  return { reviewer: null, rest: body };
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
          const color = seatColor(p);
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

/** Per-persona avatar gradients so a five-seat panel reads at a glance —
 *  each named reviewer keeps a stable identity colour across the roster,
 *  dispatch chips, and @mention chips. Keyed by the persona name
 *  normalised to lowercase alphanumerics ("GPT-5" → "gpt5"). The DTO's
 *  own {@code color} still wins when the backend sends one; this is the
 *  fallback that paints the well-known seats. */
const PERSONA_GRADIENTS: Record<string, string> = {
  lead: 'linear-gradient(135deg,#fbbf24,#d97706)',
  claude: 'linear-gradient(135deg,#d97706,#92400e)',
  gpt5: 'linear-gradient(135deg,#10b981,#0d9488)',
  deepseek: 'linear-gradient(135deg,#2563eb,#1e3a8a)',
  sonnet: 'linear-gradient(135deg,#a78bfa,#7c3aed)',
  gemini: 'linear-gradient(135deg,#34d399,#0d9488)',
  you: 'linear-gradient(135deg,#34d399,#0d9488)',
};

function personaGradient(personaLabel: string): string | null {
  const key = personaLabel.toLowerCase().replace(/[^a-z0-9]/g, '');
  return PERSONA_GRADIENTS[key] ?? null;
}

/** Solid identity colour for a seat — used for the bubble's tinted
 *  border and the author name in the transcript head (a gradient can't
 *  paint a 1px border or text). Mirrors the avatar gradient's lead hue. */
const PERSONA_SOLID: Record<string, string> = {
  lead: '#d97706',
  claude: '#d97706',
  gpt5: '#10b981',
  deepseek: '#2563eb',
  sonnet: '#7c3aed',
  gemini: '#34d399',
  you: '#0d9488',
};
function personaSolid(participant: ReviewParticipantDto): string | null {
  if (participant.color !== null && participant.color.startsWith('#')) return participant.color;
  const key = participant.personaLabel.toLowerCase().replace(/[^a-z0-9]/g, '');
  return PERSONA_SOLID[key] ?? null;
}

/** The avatar background for a roster/dispatch seat: an explicit DTO
 *  colour first, then the persona gradient for a known seat, then the
 *  by-kind fallback. */
function seatColor(participant: ReviewParticipantDto): string {
  return participant.color
    ?? personaGradient(participant.personaLabel)
    ?? rosterFallbackColor(participant.kind);
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
  const summary = `${agenda.length} phases (${done} done · ${inProgress} in progress · ${open} open)`;
  return (
    <section style={agendaCardStyle} aria-label="Agenda">
      <div style={agendaHeadStyle}>{summary}</div>
      <ol style={agendaListStyle}>
        {agenda.map(phase => (
          <li key={phase.id} style={agendaItemStyle}>
            <span
              style={agendaCheckStyle(phase.status)}
              className={phase.status === 'IN_PROGRESS' ? 'review-agenda-glyph--active' : undefined}
              aria-hidden
            >
              {phase.status === 'DONE' ? '✓' : ''}
            </span>
            <span style={agendaPhaseStyle(phase.status)}>{phase.title}</span>
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
    <section
      style={transcriptCardStyle}
      aria-label="Panel transcript"
    >
      {items.length === 0 ? (
        <div style={transcriptEmptyStyle}>
          {running ? (
            <>
              <span style={livePulseStyle} className="review-live-dot" aria-hidden />
              {' '}The panel is warming up…
            </>
          ) : (
            'No panel conversation was recorded for this pass.'
          )}
        </div>
      ) : (
        <div style={transcriptScrollStyle}>
          {items.map((item, i) => {
            const showDivider = itemPhase(item) !== (items[i - 1] && itemPhase(items[i - 1]));
            const phaseSource = item.kind === 'message' ? item.message : item.messages[0];
            return (
              <Fragment key={item.kind === 'message' ? item.message.id : item.messages[0].id}>
                {showDivider && (
                  <div style={phaseDividerStyle}>
                    <span style={phaseLineStyle} aria-hidden />
                    <span style={phaseDividerLabelStyle}>{phaseDividerText(phaseSource)}</span>
                    <span style={phaseLineStyle} aria-hidden />
                  </div>
                )}
                {item.kind === 'dispatch' ? (
                  <DispatchGroupBubble
                    dispatches={item.messages}
                    participantsById={participantsById}
                    onMentionClick={onMentionClick}
                    hasResponse={(reviewerId) => responseAfter(
                        item.messages[item.messages.length - 1].id, reviewerId) !== null}
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
  dispatches, participantsById, onMentionClick, onJumpToResponse, hasResponse,
}: {
  dispatches: ReviewPanelMessageDto[];
  participantsById: Map<string, ReviewParticipantDto>;
  onMentionClick: (participantId: string) => void;
  onJumpToResponse: (reviewerId: string) => void;
  /** Whether the addressed reviewer has already posted a reply below —
   *  drives the per-chip responded / waiting status. */
  hasResponse: (reviewerId: string) => boolean;
}) {
  const firstId = dispatches[0].id;
  const lead = participantsById.get(dispatches[0].participantId);
  const leadBg = lead !== undefined ? seatColor(lead) : 'linear-gradient(135deg,#fbbf24,#d97706)';
  return (
    <div style={bubbleRowStyle} data-review-msg-id={firstId}>
      <span style={{ ...avatarStyle, background: leadBg }} aria-hidden>
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
                <div style={dispatchMentionRowStyle}>
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
                  {reviewerId !== '' && (
                    <span style={hasResponse(reviewerId) ? dispatchGotDoneStyle : dispatchGotWaitStyle}>
                      {hasResponse(reviewerId) ? '✓ responded' : '· waiting'}
                    </span>
                  )}
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
                </div>
                {/* The directive itself drops to its own line under the
                    @mention so it reads as a message, not "@DeepSeek …". */}
                <div style={dispatchBodyStyle}>
                  <MarkdownProse text={stripLeadingMention(d.body, reviewer?.personaLabel)} />
                </div>
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
  const avatarBg = author !== null ? seatColor(author) : rosterFallbackColor(kind);
  const solid = author !== null ? personaSolid(author) : null;
  const isYou = kind === 'HUMAN';
  const isLeadKind = kind === 'LEAD';
  // Reviewer bubbles take a faint border in the seat's own colour (8-digit
  // hex alpha); lead + you keep their dedicated variants.
  const reviewerBubbleStyle = solid !== null
      ? { ...bubbleStyle, borderColor: `${solid}33` }
      : bubbleStyle;
  const showLeadTag = isLeadKind || isLead;
  const filtering = focusParticipantId !== null;

  return (
    <div
      style={isYou ? bubbleRowYouStyle : bubbleRowStyle}
      data-review-msg-id={message.id}
    >
      {!isYou && (
        <span style={{ ...avatarStyle, background: avatarBg }} aria-hidden>
          {name.slice(0, 1).toUpperCase()}
        </span>
      )}
      <div style={isLeadKind ? bubbleLeadStyle : isYou ? bubbleYouStyle : reviewerBubbleStyle}>
        <div style={bubbleHeadStyle}>
          <strong style={isYou ? undefined : { color: solid ?? color }}>{name}</strong>
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
        <div style={bubbleBodyStyle}><MessageBody message={message} /></div>
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
/** A bubble's body: the prose (markdown) plus any tool-call invocations
 *  the model emitted, rendered as clean cards rather than raw tokens. */
function MessageBody({ message }: { message: ReviewPanelMessageDto }) {
  if (message.payloadKind === 'cross_review') {
    const summary = crossReviewSummary(message.payloadJson);
    if (summary !== null) {
      return <MarkdownProse text={summary} />;
    }
  }
  const { prose, toolCalls } = parseToolCalls(message.body);
  return (
    <>
      {prose.trim().length > 0 && <MarkdownProse text={prose} />}
      {toolCalls.length > 0 && <ToolCallList calls={toolCalls} />}
    </>
  );
}

type ToolCall = { name: string; params: { name: string; value: string }[] };

// DSML tool-call tokens use fullwidth bars; tolerate one-or-more of either
// bar character around the "DSML" marker so a normalised "|" still matches.
const BAR = '[｜|]+';
const TOOL_BLOCK_RE = new RegExp(`<${BAR}DSML${BAR}tool_calls>([\\s\\S]*?)</${BAR}DSML${BAR}tool_calls>`, 'g');
const INVOKE_RE = new RegExp(`<${BAR}DSML${BAR}invoke name="([^"]*)">([\\s\\S]*?)</${BAR}DSML${BAR}invoke>`, 'g');
const PARAM_RE = new RegExp(`<${BAR}DSML${BAR}parameter name="([^"]*)"[^>]*>([\\s\\S]*?)</${BAR}DSML${BAR}parameter>`, 'g');

/** Split a message body into its prose and the structured tool calls the
 *  model emitted (DeepSeek-style {@code <｜｜DSML｜｜invoke …>} tokens). */
function parseToolCalls(body: string): { prose: string; toolCalls: ToolCall[] } {
  if (!body.includes('DSML')) {
    return { prose: body, toolCalls: [] };
  }
  const toolCalls: ToolCall[] = [];
  TOOL_BLOCK_RE.lastIndex = 0;
  let blockMatch: RegExpExecArray | null;
  while ((blockMatch = TOOL_BLOCK_RE.exec(body)) !== null) {
    const inner = blockMatch[1];
    INVOKE_RE.lastIndex = 0;
    let invokeMatch: RegExpExecArray | null;
    while ((invokeMatch = INVOKE_RE.exec(inner)) !== null) {
      const params: { name: string; value: string }[] = [];
      PARAM_RE.lastIndex = 0;
      let paramMatch: RegExpExecArray | null;
      while ((paramMatch = PARAM_RE.exec(invokeMatch[2])) !== null) {
        params.push({ name: paramMatch[1], value: paramMatch[2].trim() });
      }
      toolCalls.push({ name: invokeMatch[1], params });
    }
  }
  // Prose is whatever's left once the tool-call blocks are removed. Also
  // sweep any stray DSML tags so a malformed block never leaks raw tokens.
  const prose = body
    .replace(TOOL_BLOCK_RE, '')
    .replace(new RegExp(`</?${BAR}DSML${BAR}[^>]*>`, 'g'), '')
    .trim();
  return { prose, toolCalls };
}

/** Renders parsed tool invocations as compact wrench cards. The tool name
 *  heads the card; each parameter's value renders as markdown, both the
 *  label and the value flush to the card's left edge. */
function ToolCallList({ calls }: { calls: ToolCall[] }) {
  return (
    <div style={toolCallListStyle}>
      {calls.map((call, i) => (
        <div key={i} style={toolCallRowStyle}>
          <div style={toolCallHeadStyle}>
            <span style={toolCallIconStyle} aria-hidden>🔧</span>
            <span style={toolCallNameStyle}>{call.name}</span>
          </div>
          {call.params.map((p, j) => (
            <div key={j} style={toolCallParamStyle}>
              <span style={toolCallParamNameStyle}>{p.name}</span>
              <div style={toolCallParamValStyle}>
                <MarkdownProse text={p.value} variant="card" />
              </div>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
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
  // Default to including findings the panel (or the human ballot) kept:
  // AGREED / ARBITRATED-in / RESOLVED / POSTED. DISPUTED + REPORTED start
  // un-checked (no consensus), and DROPPED stays out (the human or panel
  // discarded it) — the user can still tick any of them in manually.
  const [includedIds, setIncludedIds] = useState<Set<string>>(
      () => new Set(detail.findings
          .filter(f => f.status !== 'DISPUTED' && f.status !== 'REPORTED' && f.status !== 'DROPPED')
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
            <div style={verdictRowStyle}>
              {(['APPROVE', 'COMMENT', 'REQUEST_CHANGES'] as ReviewVerdictDto[]).map(v => (
                <label key={v} style={verdictPillStyle(v, verdict === v)}>
                  <input
                    type="radio"
                    name="review-verdict"
                    value={v}
                    aria-label={v}
                    checked={verdict === v}
                    onChange={() => setVerdict(v)}
                    disabled={busy}
                    style={srOnlyStyle}
                  />
                  <span aria-hidden>{verdictGlyph(v)}</span>
                  {verdictLabel(v)}
                </label>
              ))}
            </div>
          </fieldset>

          {detail.findings.length > 0 && (
            <fieldset style={fieldsetStyle} aria-label="Findings to post">
              <legend style={legendStyle}>
                Findings to post ({includedIds.size}/{detail.findings.length})
              </legend>
              {detail.findings.map(f => (
                <label key={f.id} style={findingChoiceStyle}>
                  <div style={findingChoiceTopRowStyle}>
                    <input
                      type="checkbox"
                      checked={includedIds.has(f.id)}
                      onChange={() => toggle(f.id)}
                      disabled={busy}
                    />
                    <SeverityChip severity={f.severity} />
                    <StatusChip status={f.status} />
                    <span
                      style={findingAnchorStyle}
                      title={f.path !== null
                          ? `${f.path}${f.line !== null ? `:${f.line}` : ''}`
                          : undefined}
                    >
                      {f.path !== null
                          ? `${f.path.split('/').pop()}${f.line !== null ? `:${f.line}` : ''}`
                          : 'Whole PR'}
                    </span>
                  </div>
                  {/* Body drops to its own full-width line beneath the
                      chips, left-aligned, so long code tokens wrap inside
                      the form instead of spilling past its edge. */}
                  <div style={findingChoiceTextStyle}>
                    <MarkdownProse text={f.body} variant="card" />
                  </div>
                </label>
              ))}
            </fieldset>
          )}

          {error !== null && (
            <div style={errorStyle} role="alert">{error}</div>
          )}

          <button
            type="button"
            style={busy ? { ...btnPubStyle, ...btnPubDisabledStyle } : btnPubStyle}
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
  return (
    <span style={pillStyle('#0066cc')}>
      <span style={phaseDotStyle} className="review-live-dot" aria-hidden />
      {phase.toLowerCase()}
    </span>
  );
}

function VerdictPill({ verdict }: { verdict: ReviewVerdictDto }) {
  const color = verdict === 'APPROVE' ? '#16a34a'
      : verdict === 'REQUEST_CHANGES' ? '#cf1322'
      : '#737373';
  return <span style={pillStyle(color)}>{verdict.toLowerCase()}</span>;
}

/** Friendly label + leading glyph for a verdict pill. The radio keeps
 *  its raw value as the accessible name (aria-label), so these are
 *  presentation-only. */
function verdictLabel(v: ReviewVerdictDto): string {
  return v === 'REQUEST_CHANGES' ? 'REQUEST CHANGES' : v;
}
function verdictGlyph(v: ReviewVerdictDto): string {
  return v === 'APPROVE' ? '✓' : v === 'REQUEST_CHANGES' ? '⚑' : '◆';
}

/** Verdict picker pill — green / amber / red per the design. The
 *  selected pill fills with its color's gradient; the rest stay tinted. */
function verdictPillStyle(v: ReviewVerdictDto, selected: boolean): React.CSSProperties {
  const base: React.CSSProperties = {
    flex: 1,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    padding: '7px 6px',
    borderRadius: 9,
    fontSize: 10.5,
    fontWeight: 800,
    letterSpacing: '0.03em',
    border: '1.5px solid transparent',
    cursor: 'pointer',
  };
  const tone = v === 'APPROVE'
      ? { tint: 'rgba(16,185,129,0.06)', fg: '#047857', bd: 'rgba(16,185,129,0.20)',
          grad: 'linear-gradient(135deg,#d1fae5,#a7f3d0)', glow: 'rgba(16,185,129,0.22)' }
      : v === 'REQUEST_CHANGES'
      ? { tint: 'rgba(239,68,68,0.06)', fg: '#b91c1c', bd: 'rgba(239,68,68,0.20)',
          grad: 'linear-gradient(135deg,#fee2e2,#fecaca)', glow: 'rgba(239,68,68,0.22)' }
      : { tint: 'rgba(245,158,11,0.10)', fg: '#92400e', bd: '#fcd34d',
          grad: 'linear-gradient(135deg,#fef3c7,#fde68a)', glow: 'rgba(245,158,11,0.22)' };
  if (selected) {
    return { ...base, background: tone.grad, color: tone.fg, borderColor: tone.bd,
        boxShadow: `0 2px 8px ${tone.glow}` };
  }
  return { ...base, background: tone.tint, color: tone.fg, borderColor: tone.bd };
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
  position: 'relative',
  zIndex: 1,
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  // Fill the window — no centered max-width cap, so wide displays use
  // the full width instead of leaving large side gutters.
  padding: '14px 20px 16px',
  background: 'transparent',
  boxSizing: 'border-box',
};

// Atmospheric mesh + grain — "this is an app, not a form". Values lifted
// verbatim from the polished design source so the React surface and the
// mockup read identically.
const meshBgStyle: React.CSSProperties = {
  // Absolute + z-index:-1 so the opaque mesh sits BEHIND every in-flow
  // element. With z-index:0 it painted over plain static content (the
  // transcript bubbles have no stacking context, unlike the glass cards),
  // hiding the whole conversation under opaque off-white.
  position: 'absolute', inset: 0, zIndex: -1, pointerEvents: 'none',
  background:
    'radial-gradient(40% 50% at 8% 12%, rgba(124,92,255,0.18), transparent 70%),'
    + 'radial-gradient(38% 46% at 92% 6%, rgba(56,189,248,0.14), transparent 70%),'
    + 'radial-gradient(45% 55% at 84% 94%, rgba(244,114,182,0.12), transparent 70%),'
    + 'radial-gradient(40% 50% at 12% 92%, rgba(52,211,153,0.10), transparent 70%),'
    + '#fafafe',
};
const noiseBgStyle: React.CSSProperties = {
  position: 'absolute', inset: 0, zIndex: -1, pointerEvents: 'none',
  opacity: 0.045, mixBlendMode: 'overlay',
  backgroundImage: "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg'"
    + " width='220' height='220'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise'"
    + " baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect"
    + " width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E\")",
};

// Atmospheric backdrop: a soft multi-hue mesh + a faint noise overlay
// behind the glass surfaces, so the panel reads as a polished surface
// rather than a flat wireframe.

const spawnSectionStyle: React.CSSProperties = {
  marginTop: 12,
  paddingTop: 12,
  borderTop: '1px solid var(--border-subtle)',
};
// Dashed-purple "apply findings to the diff" CTA from the design.
const btnApplyStyle: React.CSSProperties = {
  width: '100%',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 8,
  padding: 9,
  border: '1.5px dashed rgba(124,92,255,0.4)',
  background: 'linear-gradient(135deg, rgba(124,92,255,0.04), rgba(56,189,248,0.06))',
  color: '#5b21b6',
  borderRadius: 11,
  fontSize: 11.5,
  fontWeight: 800,
  letterSpacing: '0.03em',
  cursor: 'pointer',
};
const btnApplyDisabledStyle: React.CSSProperties = {
  opacity: 0.5,
  cursor: 'not-allowed',
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
  padding: '11px 22px',
  background: 'rgba(255,255,255,0.72)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(124,92,255,0.12)',
  borderRadius: 14,
  boxShadow: '0 2px 10px rgba(15,23,42,0.04)',
};

const backBtnStyle: React.CSSProperties = {
  fontSize: 12,
  padding: '4px 8px',
};

const panelBadgeStyle: React.CSSProperties = {
  fontSize: 10.5,
  fontWeight: 800,
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
  whiteSpace: 'nowrap',
  color: '#5b21b6',
  background: 'linear-gradient(135deg, rgba(56,189,248,0.18), rgba(124,92,255,0.14))',
  border: '1px solid rgba(124,92,255,0.22)',
  borderRadius: 999,
  padding: '4px 11px',
};


const bodyGridStyle: React.CSSProperties = {
  // Flex row, not grid: a flex item stretched on the cross axis gets a
  // definite height its own flex:1 children (the transcript scroll) can
  // resolve against. Grid's auto row left the center column with no
  // definite height, so the transcript collapsed to ~0 and the
  // conversation never showed even though the messages were in the DOM.
  display: 'flex',
  gap: 10,
  flex: 1,
  minHeight: 0,
  alignItems: 'stretch',
};

// The rails scroll on their own so a tall panel never pushes the page —
// the whole review surface stays one fixed-height window.
const leftRailStyle: React.CSSProperties = {
  width: 252,
  flexShrink: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  overflowY: 'auto',
  minHeight: 0,
  paddingRight: 2,
};

const centerColStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  overflow: 'hidden',
  // Small inset so the now-borderless transcript isn't flush to the panel
  // edge.
  padding: '8px 10px',
  // Soft translucent surface so the transcript bubbles read as a panel
  // floating on the mesh, matching the design's center column.
  background: 'rgba(255,255,255,0.34)',
  borderRadius: 14,
};

const rightRailStyle: React.CSSProperties = {
  width: 440,
  flexShrink: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  overflowY: 'auto',
  minHeight: 0,
  paddingRight: 2,
};

const viewOnDiffStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  fontSize: 12,
  fontWeight: 600,
  color: '#15803d',
  background: 'rgba(22,163,74,0.08)',
  border: '1px solid rgba(22,163,74,0.30)',
  borderRadius: 9,
  cursor: 'pointer',
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
// Clickable PR number (left-rail) — same look as prNumStyle, button reset.
const prNumLinkStyle: React.CSSProperties = {
  ...prNumStyle,
  background: 'none',
  border: 0,
  padding: 0,
  cursor: 'pointer',
  textDecoration: 'underline',
  textUnderlineOffset: 2,
};
// Clickable "PR #N" in the top-bar breadcrumb — GitHub-green link.
const prLinkStyle: React.CSSProperties = {
  font: 'inherit',
  color: '#16a34a',
  fontWeight: 700,
  background: 'none',
  border: 0,
  padding: 0,
  cursor: 'pointer',
  textDecoration: 'underline',
  textUnderlineOffset: 2,
  whiteSpace: 'nowrap',
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

const commitsHeadStyle: React.CSSProperties = {
  marginTop: 10,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: 'var(--text-3)',
};
const commitsListStyle: React.CSSProperties = {
  margin: '6px 0 0',
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 5,
  maxHeight: 220,
  overflowY: 'auto',
};
const commitsEmptyStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontStyle: 'italic',
};
const commitItemStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  minWidth: 0,
};
const commitRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 7,
  fontSize: 11.5,
  minWidth: 0,
};
const commitSubRowStyle: React.CSSProperties = {
  fontSize: 10.5,
  color: 'var(--text-3)',
  paddingLeft: 2,
  fontVariantNumeric: 'tabular-nums',
};
const commitShaStyle: React.CSSProperties = {
  flexShrink: 0,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 10.5,
  color: '#5b21b6',
  background: 'rgba(124,92,255,0.08)',
  border: '1px solid rgba(124,92,255,0.18)',
  borderRadius: 4,
  padding: '0 5px',
};
const commitMsgStyle: React.CSSProperties = {
  minWidth: 0,
  color: 'var(--text-2)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
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
  left: 6,
  top: 12,
  bottom: 12,
  width: 1.5,
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
  const base: React.CSSProperties = {
    width: 13,
    height: 13,
    borderRadius: 999,
    flexShrink: 0,
    boxSizing: 'border-box',
    // Opaque so the connector line behind the column only shows in the
    // gaps between the markers.
    marginTop: 1,
  };
  if (state === 'done') {
    return { ...base, background: '#10b981', border: '2px solid #10b981', boxShadow: 'inset 0 0 0 2px #fff' };
  }
  if (state === 'current') {
    return { ...base, background: '#7c5cff', border: '2px solid #7c5cff', boxShadow: '0 0 0 3px rgba(124,92,255,0.22)' };
  }
  return { ...base, background: 'var(--bg-elevated, #fff)', border: '2px solid var(--border-mid, var(--border))' };
}

function flowLabelStyle(state: 'done' | 'current' | 'next'): React.CSSProperties {
  return {
    fontSize: 11.5,
    color: state === 'current' ? '#5b21b6'
        : state === 'next' ? 'var(--text-3)' : 'var(--text-2)',
    fontWeight: state === 'current' ? 800 : 400,
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
  fontSize: 13,
  fontWeight: 800,
  color: 'var(--text-1)',
  fontVariantNumeric: 'tabular-nums',
  letterSpacing: '-0.01em',
};
const pipsRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 4,
  marginTop: 2,
};
const pipBaseStyle: React.CSSProperties = {
  flex: 1,
  height: 6,
  borderRadius: 999,
};
const pipOffStyle: React.CSSProperties = {
  ...pipBaseStyle,
  background: 'rgba(124,92,255,0.14)',
};
const pipOnStyle: React.CSSProperties = {
  ...pipBaseStyle,
  background: 'linear-gradient(90deg,#34d399,#7c5cff)',
  boxShadow: '0 1px 4px rgba(124,92,255,0.3)',
};

const progressTrackStyle: React.CSSProperties = {
  height: 7,
  background: 'rgba(124,92,255,0.14)',
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
  padding: '11px 14px 12px',
  borderTop: '1px solid rgba(124,92,255,0.12)',
  background: 'rgba(255,255,255,0.62)',
  backdropFilter: 'blur(10px)',
  WebkitBackdropFilter: 'blur(10px)',
  borderRadius: '0 0 14px 14px',
};
const mentionMenuStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  marginBottom: 8,
  padding: 4,
  background: 'rgba(255,255,255,0.97)',
  border: '1px solid var(--border)',
  borderRadius: 10,
  boxShadow: '0 6px 20px rgba(15,23,42,0.12)',
  maxHeight: 180,
  overflowY: 'auto',
};
const mentionOptionStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  padding: '6px 10px',
  borderRadius: 7,
  border: 0,
  background: 'transparent',
  cursor: 'pointer',
  textAlign: 'left',
  width: '100%',
};
const mentionOptionLabelStyle: React.CSSProperties = {
  fontSize: 12.5,
  fontWeight: 700,
  color: '#5b21b6',
};
const mentionOptionKindStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.05em',
};
const steerSendStyle: React.CSSProperties = {
  flexShrink: 0,
  alignSelf: 'flex-start',
  padding: '6px 14px',
  border: 0,
  borderRadius: 9,
  background: 'linear-gradient(135deg,#8b6dff,#7c5cff)',
  color: '#fff',
  fontSize: 12,
  fontWeight: 700,
  cursor: 'pointer',
};
const composerInboxStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  background: 'rgba(255,255,255,0.92)',
  border: '1px solid var(--border)',
  borderRadius: 13,
  padding: '10px 14px',
  boxShadow: '0 1px 2px rgba(15,23,42,0.04)',
};
const composerPromptStyle: React.CSSProperties = {
  color: '#7c5cff',
  fontWeight: 800,
  fontFamily: 'var(--font-mono, ui-monospace, monospace)',
  fontSize: 14,
  lineHeight: 1.45,
};

const composerTextareaStyle: React.CSSProperties = {
  flex: 1,
  padding: 0,
  fontSize: 12.5,
  lineHeight: 1.5,
  border: 0,
  outline: 'none',
  resize: 'none',
  fontFamily: 'inherit',
  background: 'transparent',
  color: 'var(--text-2)',
  boxSizing: 'border-box',
  // Open at a couple of lines so it reads as a real message box, not a
  // one-line field. The effect auto-grows from here up to the cap, then
  // scrolls so earlier lines stay reachable.
  minHeight: 44,
  maxHeight: 160,
  overflowY: 'auto',
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


const metaStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  flexShrink: 0,
};
const breadcrumbStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 13,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const breadcrumbLeadStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontVariantNumeric: 'tabular-nums',
};
const breadcrumbTitleStyle: React.CSSProperties = {
  color: 'var(--text-1)',
  fontWeight: 600,
};
const countMetaStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-3)',
  fontVariantNumeric: 'tabular-nums',
  whiteSpace: 'nowrap',
};
const costMetaStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  fontVariantNumeric: 'tabular-nums',
  whiteSpace: 'nowrap',
};

// Visually-hidden radio: the pill label carries the look, the input keeps
// the role + accessible name so keyboard + tests still drive it.
const srOnlyStyle: React.CSSProperties = {
  position: 'absolute',
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: 'hidden',
  clipPath: 'inset(50%)',
  whiteSpace: 'nowrap',
  border: 0,
};
const verdictRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 5,
  marginTop: 4,
};
// Publish CTA — the design's purple gradient button.
const btnPubStyle: React.CSSProperties = {
  width: '100%',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 8,
  padding: 10,
  border: 0,
  borderRadius: 11,
  background: 'linear-gradient(135deg,#8b6dff,#7c5cff)',
  color: '#fff',
  fontSize: 12.5,
  fontWeight: 800,
  letterSpacing: '0.02em',
  cursor: 'pointer',
  boxShadow: '0 4px 14px rgba(124,92,255,0.34)',
};
const btnPubDisabledStyle: React.CSSProperties = {
  background: 'linear-gradient(135deg,#cbd5e1,#94a3b8)',
  cursor: 'not-allowed',
  boxShadow: 'none',
};

const cardStyle: React.CSSProperties = {
  marginBottom: 14,
  padding: '13px 14px',
  background: 'rgba(255,255,255,0.72)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(124,92,255,0.14)',
  borderRadius: 14,
  boxShadow: '0 2px 10px rgba(15,23,42,0.04)',
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
  gap: 9,
  fontSize: 13,
  padding: '7px 9px',
  borderRadius: 11,
  background: 'rgba(255,255,255,0.62)',
  border: '1px solid rgba(124,92,255,0.08)',
};

const rosterAvatarStyle: React.CSSProperties = {
  flex: '0 0 auto',
  width: 28,
  height: 28,
  borderRadius: 9,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontSize: 11.5,
  fontWeight: 800,
  boxShadow: '0 1px 3px rgba(15,23,42,0.12)',
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

// The transcript is a fixed-height window: the card fills the centre
// column's remaining height and the messages scroll inside it, so a long
// history never grows the page.
// Borderless: just the conversation window. The center column already
// provides the translucent panel surface, so the transcript drops its own
// card chrome (border / background / header / collapse) and is only the
// scrollable message list.
const transcriptCardStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
};

const transcriptScrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  // Never scroll the conversation sideways — content wraps within the
  // column instead (bubbles carry minWidth:0 + overflow-wrap).
  overflowX: 'hidden',
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  padding: '12px 14px',
  background: 'var(--bg-2, rgba(0,0,0,0.025))',
  border: '1px solid var(--border)',
  borderRadius: 10,
};

const phaseDividerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  margin: '6px 0 2px',
};
const phaseLineStyle: React.CSSProperties = {
  flex: 1,
  height: 1,
  background:
    'linear-gradient(90deg, transparent, rgba(124,92,255,0.28) 30%,'
    + ' rgba(124,92,255,0.28) 70%, transparent)',
};

const phaseDividerLabelStyle: React.CSSProperties = {
  fontSize: 10.5,
  fontWeight: 800,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: '#5b21b6',
  background: 'rgba(124,92,255,0.08)',
  border: '1px solid rgba(124,92,255,0.22)',
  borderRadius: 999,
  padding: '3px 12px',
  whiteSpace: 'nowrap',
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
  width: 32,
  height: 32,
  borderRadius: 10,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontSize: 12.5,
  fontWeight: 800,
  boxShadow: '0 1px 3px rgba(15,23,42,0.12)',
};

const bubbleStyle: React.CSSProperties = {
  maxWidth: '88%',
  padding: '10px 14px',
  background: 'rgba(255,255,255,0.92)',
  borderRadius: '4px 14px 14px 14px',
  border: '1px solid rgba(124,92,255,0.10)',
  boxShadow: '0 2px 10px rgba(15,23,42,0.05)',
  minWidth: 0,
};

const bubbleLeadStyle: React.CSSProperties = {
  ...bubbleStyle,
  // Take the row's remaining width (after the avatar) and shrink rather
  // than overflow — width:100% pushed it past the avatar and forced a
  // horizontal scrollbar.
  flex: 1,
  maxWidth: '100%',
  minWidth: 0,
  background: 'rgba(245,158,11,0.06)',
  borderStyle: 'dashed',
  borderColor: 'rgba(245,158,11,0.32)',
};

const bubbleYouStyle: React.CSSProperties = {
  ...bubbleStyle,
  background: '#95ec69',
  borderRadius: '14px 4px 14px 14px',
  border: '1px solid #7fd957',
  boxShadow: '0 2px 8px rgba(127,217,87,0.18)',
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
  fontSize: 9.5,
  fontWeight: 800,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  color: '#92400e',
  background: 'rgba(245,158,11,0.16)',
  border: '1px solid rgba(245,158,11,0.34)',
  borderRadius: 999,
  padding: '1px 8px',
};

/** A mention chip tinted with the addressed reviewer's persona color.
 *  Selected → filled (the active filter); dimmed → faded (a filter is
 *  on and this isn't the selected chip); otherwise the soft tint. */
function mentionChipStyleFor(
  color: string, selected: boolean, dimmed: boolean,
): React.CSSProperties {
  return {
    fontSize: 10.5,
    fontWeight: 800,
    color: selected ? '#fff' : color,
    background: selected ? color : `${color}22`,
    border: `1px solid ${selected ? color : `${color}55`}`,
    borderRadius: 999,
    padding: '1px 7px',
    cursor: 'pointer',
    opacity: dimmed ? 0.45 : 1,
    transition: 'opacity 140ms ease, background 140ms ease, transform 120ms ease',
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
  flexDirection: 'column',
  alignItems: 'stretch',
  gap: 3,
  fontSize: 12.5,
  lineHeight: 1.45,
};

const dispatchMentionRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  flexWrap: 'wrap',
};

const dispatchArrowStyle: React.CSSProperties = {
  flexShrink: 0,
  fontWeight: 700,
};

const dispatchBodyStyle: React.CSSProperties = {
  minWidth: 0,
  color: 'var(--text-2)',
  // Flush to the left edge under the @mention, not indented, matching the
  // other transcript content.
  marginLeft: 0,
};
const dispatchGotBaseStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 10,
  fontWeight: 800,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  whiteSpace: 'nowrap',
};
const dispatchGotDoneStyle: React.CSSProperties = {
  ...dispatchGotBaseStyle,
  color: '#22c55e',
};
const dispatchGotWaitStyle: React.CSSProperties = {
  ...dispatchGotBaseStyle,
  color: 'var(--text-3)',
  fontWeight: 600,
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
  overflowWrap: 'anywhere',
};
const toolCallListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  marginTop: 6,
};
const toolCallRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 5,
  padding: '7px 10px',
  background: 'rgba(124,92,255,0.06)',
  border: '1px solid rgba(124,92,255,0.18)',
  borderRadius: 9,
};
const toolCallHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
};
const toolCallIconStyle: React.CSSProperties = {
  fontSize: 12,
  lineHeight: 1.5,
  flexShrink: 0,
};
const toolCallNameStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 12,
  fontWeight: 700,
  color: '#5b21b6',
};
const toolCallParamStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
};
const toolCallParamNameStyle: React.CSSProperties = {
  // A blue label chip that hugs its text (doesn't stretch the flex column).
  alignSelf: 'flex-start',
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 10,
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: '0.03em',
  color: '#1d4ed8',
  background: 'rgba(37,99,235,0.12)',
  border: '1px solid rgba(37,99,235,0.25)',
  borderRadius: 4,
  padding: '1px 6px',
};
const toolCallParamValStyle: React.CSSProperties = {
  fontSize: 12.5,
  color: 'var(--text-1)',
  minWidth: 0,
  overflowWrap: 'break-word',
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
  minWidth: 0,
  fontSize: 13,
  lineHeight: 1.5,
  overflowWrap: 'anywhere',
  wordBreak: 'break-word',
};
// Arbitration ballot item — compact vertical card: a head row with the
// severity badge + reviewer chip, the file anchor, the markdown body,
// then the Include / Drop actions.
const ballotItemStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 7,
  padding: 12,
  background: 'rgba(255,255,255,0.72)',
  border: '1px solid var(--border)',
  borderRadius: 12,
};
const ballotHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  flexWrap: 'wrap',
  gap: 7,
};
const ballotReviewerStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  color: '#5b21b6',
  background: 'rgba(124,92,255,0.12)',
  border: '1px solid rgba(124,92,255,0.22)',
  borderRadius: 999,
  padding: '1px 9px',
};
const ballotAnchorStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
  color: 'var(--text-3)',
  overflowWrap: 'anywhere',
  wordBreak: 'break-all',
};
const ballotBodyStyle: React.CSSProperties = {
  fontSize: 12.5,
  lineHeight: 1.55,
  color: 'var(--text-1)',
  overflowWrap: 'anywhere',
  wordBreak: 'break-word',
};
const ballotActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  marginTop: 2,
};
const ballotIncludeStyle: React.CSSProperties = {
  flex: 1,
  padding: '7px 10px',
  border: 0,
  borderRadius: 9,
  background: 'linear-gradient(135deg,#8b6dff,#7c5cff)',
  color: '#fff',
  fontSize: 12,
  fontWeight: 700,
  cursor: 'pointer',
  boxShadow: '0 2px 8px rgba(124,92,255,0.28)',
};
const ballotDropStyle: React.CSSProperties = {
  flex: 1,
  padding: '7px 10px',
  border: '1px solid var(--border)',
  borderRadius: 9,
  background: 'var(--bg-elevated, #fff)',
  color: 'var(--text-2)',
  fontSize: 12,
  fontWeight: 700,
  cursor: 'pointer',
};

const findingAnchorStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
  color: 'var(--text-3)',
  marginBottom: 4,
  // Filename:line only (full path on hover) on a single clipped line —
  // the full path broke mid-segment and dominated the row.
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const findingChoiceTextStyle: React.CSSProperties = {
  fontSize: 12.5,
  color: 'var(--text-1)',
  minWidth: 0,
  overflowWrap: 'break-word',
  wordBreak: 'break-word',
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
  minWidth: 0,
  fontSize: 12.5,
  lineHeight: 1.5,
  color: 'var(--text-1)',
  // Long code identifiers + file paths have no natural break points;
  // let them wrap anywhere so nothing spills past the rail edge.
  overflowWrap: 'anywhere',
  wordBreak: 'break-word',
};

const findingAnchorInlineStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
  color: 'var(--text-3)',
  overflowWrap: 'anywhere',
  wordBreak: 'break-all',
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
  borderRadius: '14px 14px 0 0',
  // Gold-tinted gradient that fades into the transcript — the lead's
  // agenda owns the top of the column, per the design.
  background: 'linear-gradient(180deg, rgba(245,158,11,0.10), rgba(255,255,255,0))',
  borderBottom: '1px solid rgba(245,158,11,0.28)',
  padding: '10px 18px 11px',
  position: 'sticky',
  top: 0,
  zIndex: 5,
  backdropFilter: 'blur(10px) saturate(125%)',
  WebkitBackdropFilter: 'blur(10px) saturate(125%)',
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
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: '6px 18px',
};

const agendaItemStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 7,
};
/** Checkbox glyph for a phase: filled-green check when done, a gold
 *  box while in progress, an empty box when still pending. */
function agendaCheckStyle(status: AgendaPhaseStatusDto): React.CSSProperties {
  const base: React.CSSProperties = {
    flexShrink: 0,
    width: 14,
    height: 14,
    marginTop: 1,
    borderRadius: 4,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 9,
    fontWeight: 800,
    boxSizing: 'border-box',
  };
  if (status === 'DONE') {
    return { ...base, background: '#10b981', color: '#fff', border: '1px solid #10b981' };
  }
  if (status === 'IN_PROGRESS') {
    return { ...base, background: 'rgba(245,158,11,0.18)', border: '1px solid rgba(245,158,11,0.5)' };
  }
  return { ...base, background: 'transparent', border: '1px solid var(--border-mid, var(--border))' };
}
/** Phase cell in the 2-column agenda grid: the active phase reads gold,
 *  done phases mute out with a strike, pending phases sit grey. */
function agendaPhaseStyle(status: AgendaPhaseStatusDto): React.CSSProperties {
  if (status === 'IN_PROGRESS') {
    return { fontSize: 13, lineHeight: 1.35, fontWeight: 600, color: '#b45309' };
  }
  if (status === 'DONE') {
    return { fontSize: 13, lineHeight: 1.35, color: 'var(--text-3)', textDecoration: 'line-through' };
  }
  return { fontSize: 13, lineHeight: 1.35, color: 'var(--text-2)' };
}

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


const findingChoiceStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'stretch',
  gap: 3,
  padding: '7px 0',
  fontSize: 13,
};

const findingChoiceTopRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
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
const transcriptEmptyStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 7,
  padding: '40px 16px',
  fontSize: 12.5,
  fontStyle: 'italic',
  color: 'var(--text-3)',
  textAlign: 'center',
};
const emptyStateStyle: React.CSSProperties = {
  padding: '14px 8px 16px',
  textAlign: 'center',
};
const emptyGlyphStyle: React.CSSProperties = {
  fontSize: 18,
  color: 'var(--border-mid, var(--border))',
  marginBottom: 5,
};
const emptyLabelStyle: React.CSSProperties = {
  fontSize: 11,
  fontStyle: 'italic',
  color: 'var(--text-3)',
};

function pillStyle(color: string): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 5,
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
const phaseDotStyle: React.CSSProperties = {
  width: 5,
  height: 5,
  borderRadius: '50%',
  background: 'currentColor',
  flexShrink: 0,
};

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
