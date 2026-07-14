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
import { Fragment, type ReactNode } from 'react';
import { formatRelativeTime } from '../pr/utils';
import { MarkdownProse } from '../threads/MarkdownProse';
import type { LocalPRTimelineEvent } from '../types/localPr';
import type { AgentReviewData, FindingRow, ReviewRoundRow } from './agentReviewTypes';
import { formatCents, roundPlanObjectives } from './agentReviewTypes';
import { AgentReviewPlanCard } from './AgentReviewPlanCard';
import { AgentReviewRoundEpisode } from './AgentReviewRoundEpisode';
import { NeedsJudgementCard } from './NeedsJudgementCard';

type TimelineCallbacks = {
  onOpenRound?: (roundId: string) => void;
  onAnswer?: (findingId: string, text: string) => void;
  onRoundAction?: (roundId: string) => void;
};

export type AgentReviewTimelineEntry = {
  key: string;
  time: number;
  render: ReactNode;
};

function payloadString(event: LocalPRTimelineEvent, key: string): string | null {
  const value = event.payload?.[key];
  return typeof value === 'string' && value.trim().length > 0 ? value : null;
}

function payloadNumber(event: LocalPRTimelineEvent, key: string): number | null {
  const value = event.payload?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function findingLabel(data: AgentReviewData, findingId: string | null): string {
  const index = findingId === null ? -1 : data.findings.findIndex(finding => finding.id === findingId);
  return index < 0 ? 'the finding' : `F${index + 1}`;
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

function verdictLabel(value: string): string {
  if (value === 'REQUEST_CHANGES') return 'Request changes';
  if (value === 'APPROVE' || value === 'APPROVED') return 'Approve';
  return 'Comment';
}

function reviewClass(event: LocalPRTimelineEvent, capCents: number): string {
  const value = payloadString(event, 'reviewClass')?.toUpperCase();
  if (value === 'TRIVIAL' || value === 'STANDARD' || value === 'HIGH-RISK') return value;
  if (value === 'HIGH_RISK') return 'HIGH-RISK';
  return capCents === 10 ? 'TRIVIAL' : capCents === 150 ? 'HIGH-RISK' : 'STANDARD';
}

function eventIcon(kind: string): string {
  if (kind === 'submitted' || kind === 'round-complete' || kind === 'answered') return '✓';
  if (kind === 'dismissed' || kind === 'rejected-dropped' || kind === 'round-cancelled') return '✕';
  if (kind === 'synthesizer') return '✦';
  if (kind === 'addresses') return '⌥';
  if (kind === 'plan-amendment-suggested') return '⚖';
  if (kind === 'round-started') return '↑';
  return '↩';
}

function ReviewEvent({ kind, time, children, sub }: {
  kind: string;
  time: number;
  children: ReactNode;
  sub?: ReactNode;
}) {
  return (
    <div className={`agent-review-event agent-review-event--${kind}`}>
      <span className="agent-review-event__icon">{eventIcon(kind)}</span>
      <div className="agent-review-event__body">
        <div className="agent-review-event__line">
          <span>{children}</span>
          <time>{formatRelativeTime(new Date(time).toISOString())}</time>
        </div>
        {sub !== undefined && <div className="agent-review-event__sub">{sub}</div>}
      </div>
    </div>
  );
}

function entry(key: string, time: number, children: ReactNode): AgentReviewTimelineEntry {
  return {
    key,
    time,
    render: <div className="agent-review-timeline-row">{children}</div>,
  };
}

function roundForEvent(data: AgentReviewData, event: LocalPRTimelineEvent): ReviewRoundRow | undefined {
  const roundId = payloadString(event, 'roundId');
  return data.rounds.find(round => round.id === roundId);
}

function StartedGroup({ data, event, round, callbacks, includeEpisode }: {
  data: AgentReviewData;
  event: LocalPRTimelineEvent;
  round: ReviewRoundRow;
  callbacks: TimelineCallbacks;
  includeEpisode: boolean;
}) {
  const run = data.runs.find(row => row.id === round.agent_run_id);
  const objectives = roundPlanObjectives(data, round.id).length;
  const reviewers = data.assignments.filter(row => row.round_id === round.id).length;
  return (
    <>
      <div className="agent-review-event agent-review-event--started">
        <span className="agent-review-event__icon">⚖</span>
        <div className="agent-review-event__body">
          <div className="agent-review-event__line">
            <span><b>You</b> started an agent review — round 1 queued by the planner</span>
            <time>{formatRelativeTime(new Date(event.createdAt).toISOString())}</time>
          </div>
          <div className="agent-review-event__chips">
            <span>{reviewClass(event, round.budget_json.cost_cap_cents)}</span>
            <span>{plural(objectives, 'objective')}</span>
            <span>{plural(reviewers, 'reviewer')}</span>
            <span>cap {formatCents(round.budget_json.cost_cap_cents)}</span>
          </div>
        </div>
      </div>
      <AgentReviewPlanCard data={data} roundId={round.id} />
      {includeEpisode && run !== undefined && (
        <AgentReviewRoundEpisode
          data={data}
          round={round}
          run={run}
          onOpen={() => callbacks.onOpenRound?.(round.id)}
          onAction={() => callbacks.onRoundAction?.(round.id)}
        />
      )}
    </>
  );
}

function RejectedEvent({ data, event, finding }: {
  data: AgentReviewData;
  event: LocalPRTimelineEvent;
  finding: FindingRow;
}) {
  const verification = data.verifications.find(row => row.finding_id === finding.id);
  return (
    <ReviewEvent
      kind="rejected-dropped"
      time={event.createdAt}
      sub={verification?.explanation ?? finding.claim}
    >
      <b>Verifier</b> rejected <b>{findingLabel(data, finding.id)}</b> — dropped
    </ReviewEvent>
  );
}

function RoundTerminalGroup({ data, event, round, callbacks, rejectedEvents }: {
  data: AgentReviewData;
  event: LocalPRTimelineEvent;
  round: ReviewRoundRow;
  callbacks: TimelineCallbacks;
  rejectedEvents: LocalPRTimelineEvent[];
}) {
  const run = data.runs.find(row => row.id === round.agent_run_id);
  if (run === undefined) return null;
  const roundNumber = data.rounds.indexOf(round) + 1;
  const findings = data.findings.filter(finding => finding.round_id === round.id);
  const ballotFindings = findings.filter(finding => finding.verification_status !== 'rejected'
    && finding.lifecycle_status !== 'dropped');
  const questions = findings.filter(finding => finding.lifecycle_status === 'NEEDS_USER_JUDGEMENT');
  const rejected = rejectedEvents.flatMap(rejectedEvent => {
    const findingId = payloadString(rejectedEvent, 'findingId');
    const finding = findings.find(row => row.id === findingId);
    return finding === undefined ? [] : [{ event: rejectedEvent, finding }];
  });
  const summary = [
    plural(ballotFindings.length, 'finding'),
    rejected.length > 0 ? `${rejected.length} rejected` : null,
    questions.length > 0 ? `${questions.length} needs judgement` : null,
    formatCents(round.cost_cents),
  ].filter((part): part is string => part !== null).join(' · ');
  return (
    <>
      <AgentReviewRoundEpisode
        data={data}
        round={round}
        run={run}
        onOpen={() => callbacks.onOpenRound?.(round.id)}
        onAction={() => callbacks.onRoundAction?.(round.id)}
      />
      {rejected.map(row => <RejectedEvent key={row.event.id} data={data} event={row.event} finding={row.finding} />)}
      {questions.map(finding => (
        <NeedsJudgementCard
          key={finding.id}
          finding={finding}
          onAnswer={text => callbacks.onAnswer?.(finding.id, text)}
        />
      ))}
      {payloadString(event, 'reviewEvent') === 'round-complete' && (
        <ReviewEvent kind="round-complete" time={event.createdAt}>
          <b>Round {roundNumber} complete</b> — {summary}
        </ReviewEvent>
      )}
      {payloadString(event, 'reviewEvent') === 'round-cancelled' && (
        <ReviewEvent kind="round-cancelled" time={event.createdAt}>
          <b>You</b> stopped round {roundNumber} — nothing was posted to GitHub
        </ReviewEvent>
      )}
    </>
  );
}

function NarrativeEvent({ data, event, kind }: {
  data: AgentReviewData;
  event: LocalPRTimelineEvent;
  kind: string;
}) {
  const findingId = payloadString(event, 'findingId');
  const finding = findingLabel(data, findingId);
  const body = payloadString(event, 'body');
  const sha = payloadString(event, 'sha');
  const count = payloadNumber(event, 'count') ?? 0;
  let text: ReactNode;
  let sub: ReactNode | undefined;
  if (kind === 'answered') {
    text = <><b>You</b> answered {finding}'s question — recorded as E3 user evidence</>;
  }
  else if (kind === 'dismissed') {
    text = <><b>You</b> dismissed <b>{finding}</b> — dismissal and noise signal recorded</>;
  }
  else if (kind === 'finding-updated') {
    text = <><b>You</b> updated <b>{finding}</b>'s pending review state</>;
  }
  else if (kind === 'submitted') {
    text = <><b>You</b> submitted a review — <b>{verdictLabel(payloadString(event, 'verdict') ?? 'COMMENT')}</b> · {plural(count, 'comment')}</>;
    sub = 'Posted to GitHub as one review; all other review activity remains local.';
  }
  else if (kind === 'author-reply') {
    text = <>Author replied on <b>{finding}</b> — recorded as author evidence</>;
    sub = body === null ? undefined : <MarkdownProse text={body} />;
  }
  else if (kind === 'addresses') {
    text = <>Commit <code>{sha ?? 'unknown'}</code> addresses <b>{finding}</b> — relation recorded</>;
  }
  else if (kind === 'synthesizer') {
    text = <><b>Knowledge synthesizer</b> distilled {plural(count, 'pending recipe')}</>;
  }
  else if (kind === 'plan-amendment-suggested') {
    text = <><b>Planner</b> suggested an amendment for the next round</>;
    const suggestion = payloadString(event, 'suggestion');
    sub = suggestion === null ? undefined : <MarkdownProse text={suggestion} />;
  }
  else {
    return null;
  }
  return <ReviewEvent kind={kind} time={event.createdAt} sub={sub}>{text}</ReviewEvent>;
}

/**
 * Produces individually timestamped rows for the shared PR timeline. Review
 * history must not be mounted as one blob at session-start time: author
 * activity and later review milestones need to interleave chronologically.
 */
export function buildAgentReviewTimelineEntries(
  data: AgentReviewData,
  callbacks: TimelineCallbacks = {},
): AgentReviewTimelineEntry[] {
  const entries: AgentReviewTimelineEntry[] = [];
  const renderedRounds = new Set<string>();
  const sortedEvents = [...data.pr_timeline_events].sort((a, b) => a.createdAt - b.createdAt);
  const terminalKinds = new Set(['round-complete', 'round-error', 'round-budget-halted', 'round-cancelled']);
  const narrativeKinds = new Set([
    'answered', 'dismissed', 'finding-updated', 'submitted', 'author-reply',
    'addresses', 'synthesizer', 'plan-amendment-suggested',
  ]);
  const rejectedEvents = sortedEvents.filter(event => payloadString(event, 'reviewEvent') === 'rejected-dropped');

  for (const event of sortedEvents) {
    const kind = payloadString(event, 'reviewEvent');
    if (kind === null) continue;
    const round = roundForEvent(data, event);
    if (kind === 'started') {
      const startedRound = round ?? data.rounds[0];
      if (startedRound === undefined) continue;
      const hasTerminal = sortedEvents.some(row => terminalKinds.has(payloadString(row, 'reviewEvent') ?? '') && payloadString(row, 'roundId') === startedRound.id);
      const run = data.runs.find(row => row.id === startedRound.agent_run_id);
      const includeEpisode = !hasTerminal && run !== undefined;
      if (includeEpisode) renderedRounds.add(startedRound.id);
      entries.push(entry(event.id, event.createdAt, (
        <StartedGroup data={data} event={event} round={startedRound} callbacks={callbacks} includeEpisode={includeEpisode} />
      )));
      continue;
    }
    if (kind === 'round-started' && round !== undefined) {
      const run = data.runs.find(row => row.id === round.agent_run_id);
      const includeEpisode = run !== undefined && run.status === 'running';
      if (includeEpisode) renderedRounds.add(round.id);
      const number = data.rounds.indexOf(round) + 1;
      entries.push(entry(event.id, event.createdAt, (
        <>
          <ReviewEvent kind={kind} time={event.createdAt}>
            {round.trigger === 'auto_continue'
              ? <><b>Auto-continue</b> started round {number} — {round.scope} scope</>
              : <><b>You</b> continued the agent review — round {number} queued</>}
          </ReviewEvent>
          {includeEpisode && run !== undefined && (
            <AgentReviewRoundEpisode
              data={data}
              round={round}
              run={run}
              onOpen={() => callbacks.onOpenRound?.(round.id)}
              onAction={() => callbacks.onRoundAction?.(round.id)}
            />
          )}
        </>
      )));
      continue;
    }
    if (terminalKinds.has(kind) && round !== undefined) {
      renderedRounds.add(round.id);
      const roundRejected = rejectedEvents.filter(rejectedEvent => {
        const findingId = payloadString(rejectedEvent, 'findingId');
        return data.findings.some(finding => finding.id === findingId && finding.round_id === round.id);
      });
      entries.push(entry(event.id, event.createdAt, (
        <RoundTerminalGroup data={data} event={event} round={round} callbacks={callbacks} rejectedEvents={roundRejected} />
      )));
      continue;
    }
    if (kind === 'rejected-dropped') {
      const findingId = payloadString(event, 'findingId');
      const finding = data.findings.find(row => row.id === findingId);
      const hasTerminal = finding !== undefined && sortedEvents.some(row => terminalKinds.has(payloadString(row, 'reviewEvent') ?? '') && payloadString(row, 'roundId') === finding.round_id);
      if (finding !== undefined && !hasTerminal) {
        entries.push(entry(event.id, event.createdAt, <RejectedEvent data={data} event={event} finding={finding} />));
      }
      continue;
    }
    if (narrativeKinds.has(kind)) {
      entries.push(entry(event.id, event.createdAt, <NarrativeEvent data={data} event={event} kind={kind} />));
    }
  }

  for (const round of data.rounds) {
    if (renderedRounds.has(round.id)) continue;
    const run = data.runs.find(row => row.id === round.agent_run_id);
    if (run === undefined) continue;
    const timestamp = Date.parse(run.finishedAt ?? run.startedAt ?? '') || Date.now();
    entries.push(entry(`round-${round.id}`, timestamp, (
      <AgentReviewRoundEpisode
        data={data}
        round={round}
        run={run}
        onOpen={() => callbacks.onOpenRound?.(round.id)}
        onAction={() => callbacks.onRoundAction?.(round.id)}
      />
    )));
  }
  return entries.sort((a, b) => a.time - b.time);
}

export function AgentReviewTimeline({ data, onOpenRound, onAnswer, onRoundAction }: {
  data: AgentReviewData;
  onOpenRound?: (roundId: string) => void;
  onAnswer?: (findingId: string, text: string) => void;
  onRoundAction?: (roundId: string) => void;
}) {
  const entries = buildAgentReviewTimelineEntries(data, { onOpenRound, onAnswer, onRoundAction });
  return (
    <div className="agent-review-timeline">
      {entries.map(row => <Fragment key={row.key}>{row.render}</Fragment>)}
    </div>
  );
}
