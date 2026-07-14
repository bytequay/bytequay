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
import { useState, type ReactElement } from 'react';
import { formatRelativeTime } from '../pr/utils';
import { MarkdownProse } from '../threads/MarkdownProse';
import { ToolBlock } from '../ui/conv/ToolBlock';
import { WorkFold } from '../ui/conv/spine/WorkFold';
import { AgentFindingContent, findingSummary, presentFinding } from './AgentEvidence';
import type { AgentReviewData, InvestigationStepRow, ReviewAssignmentRow } from './agentReviewTypes';
import { findingComment, formatCents, roundPlanObjectives } from './agentReviewTypes';

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/gi;

function conciseAssignmentSummary(data: AgentReviewData, assignmentId: string, summary: string): string {
  const compact = summary.replaceAll(/\s+/g, ' ').trim();
  const internal = compact.includes('<｜｜DSML｜｜')
    || compact.includes('<tool_call>')
    || /(?:re-?recording|record)_assignment|objective ids?/i.test(compact)
    || (compact.match(UUID_PATTERN)?.length ?? 0) > 1;
  if (!internal && compact.length > 0) {
    const firstSentence = compact.match(/^.*?[.!?](?:\s|$)/)?.[0]?.trim() ?? compact;
    return firstSentence.length <= 240 ? firstSentence : `${firstSentence.slice(0, 237).trimEnd()}…`;
  }
  const hypotheses = data.hypotheses.filter(row => row.assignment_id === assignmentId);
  const objectiveCount = new Set(hypotheses.map(row => row.objective_id).filter(Boolean)).size;
  return hypotheses.length > 0
    ? `Investigating ${hypotheses.length} ${hypotheses.length === 1 ? 'hypothesis' : 'hypotheses'} across ${objectiveCount || 1} review ${objectiveCount === 1 ? 'objective' : 'objectives'}.`
    : 'Reviewing the changed code against the assigned objectives.';
}

function criterionLabel(kind: string | undefined): string {
  if (kind === 'hard-invariant') return 'INVARIANT';
  if (kind === 'engineering-principle') return 'PRINCIPLE';
  return 'CONVENTION';
}

function stepTarget(argumentsJson: Record<string, unknown> | null | undefined): string {
  if (argumentsJson == null) return 'review context';
  const values = Object.values(argumentsJson).flatMap(value => {
    if (typeof value === 'string' || typeof value === 'number') return [String(value)];
    if (Array.isArray(value)) return value.filter(item => typeof item === 'string' || typeof item === 'number').map(String);
    return [];
  });
  return values.join(' · ') || 'review context';
}

function displayedStepStatus(status: string, parentRunning: boolean): string {
  return status === 'running' && !parentRunning ? 'skipped' : status;
}

function assignmentLabel(assignment: ReviewAssignmentRow, delta: boolean): string {
  if (assignment.reviewer_def_id === 'independent-verifier') return 'independent verification';
  if (delta) return assignment.status === 'running' ? 'verifying the fix' : 'fix verification';
  return assignment.status === 'running' ? 'investigating' : 'investigation';
}

function capabilityLabel(capability: string): string {
  const labels: Record<string, string> = {
    repository_callers: 'repository callers',
    code_graph: 'code graph',
    local_tests: 'local tests',
    git_history: 'git history',
  };
  return labels[capability] ?? capability.replaceAll('_', ' ');
}

function InvestigationGroup({ assignment, steps, delta }: {
  assignment: ReviewAssignmentRow;
  steps: InvestigationStepRow[];
  delta: boolean;
}) {
  if (steps.length === 0) return null;
  return (
    <WorkFold
      label={`${assignment.reviewer_def_id} — ${assignmentLabel(assignment, delta)}`}
      meta={`· ${steps.length} steps`}
      forceOpen={assignment.status === 'running'}
      failed={steps.filter(step => displayedStepStatus(step.status, assignment.status === 'running') === 'failed').length}
      icon={assignment.reviewer_def_id.slice(0, 2).toUpperCase()}
    >
      {steps.map(step => {
        const status = displayedStepStatus(step.status, assignment.status === 'running');
        return (
          <ToolBlock
            key={step.id}
            tag={step.action_type}
            icon={status === 'completed' ? '✓' : status === 'running' ? '●' : '⊘'}
            desc={<span className="tool-arg">{stepTarget(step.arguments_json)}</span>}
            meta={formatCents(step.cost_cents)}
          >
            {step.reason}
          </ToolBlock>
        );
      })}
    </WorkFold>
  );
}

function eventCopy(data: AgentReviewData, roundId: string) {
  const findingIds = new Set(data.findings.filter(finding => finding.round_id === roundId).map(finding => finding.id));
  return data.pr_timeline_events.flatMap(event => {
    const payload = event.payload;
    const kind = payload?.reviewEvent;
    const eventRoundId = payload?.roundId;
    const findingId = typeof payload?.findingId === 'string' ? payload.findingId : null;
    if (typeof kind !== 'string'
      || (eventRoundId !== roundId && (findingId === null || !findingIds.has(findingId)))) return [];
    const body = typeof payload?.body === 'string' ? payload.body : null;
    const sha = typeof payload?.sha === 'string' ? payload.sha : null;
    const suggestion = typeof payload?.suggestion === 'string' ? payload.suggestion : null;
    const rows: Record<string, { glyph: string; text: string }> = {
      'author-reply': { glyph: '↩', text: `${event.actor} (author) replied on ${findingId ?? 'the finding'}${body === null ? '' : ` — “${body}”`}` },
      addresses: { glyph: '⌥', text: `${event.actor} pushed ${sha ?? 'a commit'} — addresses ${findingId ?? 'the finding'}` },
      answered: { glyph: '↩', text: `You answered ${findingId ?? 'the review question'} — recorded as review evidence` },
      'plan-amendment-suggested': { glyph: '⚖', text: `Planner suggested a plan amendment${suggestion === null ? '' : ` — ${suggestion}`}` },
    };
    const row = rows[kind];
    if (row === undefined) return [];
    const time = formatRelativeTime(new Date(event.createdAt).toISOString());
    if (kind === 'author-reply') {
      return [
        { ...row, id: event.id, time },
        {
          glyph: '⚖', id: `${event.id}-criterion`, time,
          text: `Reviewer recorded the author reply as evidence on ${findingId ?? 'the finding'} — the acceptance criterion now includes the author’s clarification.`,
        },
      ];
    }
    return [{ ...row, id: event.id, time }];
  });
}

export function AgentReviewRoundPage({ data, roundId, prView, onBack, onSelectRound, onOpenFinding, onOpenReviewList, onReopenFinding, onStopRound }: {
  data: AgentReviewData;
  roundId: string;
  /** Must be the shared PRView element constructed by the owning PR page. */
  prView: ReactElement;
  onBack: () => void;
  onSelectRound?: (roundId: string) => void;
  onOpenFinding: (findingId: string, filePath: string | null, lineNumber: number | null) => void;
  onOpenReviewList?: (findingId: string) => void;
  onReopenFinding?: (findingId: string) => void;
  onStopRound?: (roundId: string) => void;
}) {
  const [prOpen, setPrOpen] = useState(true);
  const round = data.rounds.find(row => row.id === roundId) ?? data.rounds[0];
  const objectives = roundPlanObjectives(data, round.id);
  const assignments = data.assignments.filter(row => row.round_id === round.id);
  const steps = data.steps.filter(step => assignments.some(assignment => assignment.id === step.assignment_id));
  const roundFindings = data.findings.filter(row => row.round_id === round.id);
  const findings = roundFindings.filter(row => row.lifecycle_status !== 'dropped');
  const roundNumber = data.rounds.indexOf(round) + 1;
  const reviewSpend = data.rounds.reduce((sum, row) => sum + row.cost_cents, 0);
  const running = round.status === 'RUNNING';
  const localSource = round.capabilities_json.source_mode === 'local-source';
  const delta = round.scope !== 'full';
  const narrativeEvents = eventCopy(data, round.id);
  const started = data.pr_timeline_events.find(event => event.payload?.roundId === round.id
    && (event.payload?.reviewEvent === 'started' || event.payload?.reviewEvent === 'round-started'));
  const plannerTime = started === undefined ? '' : formatRelativeTime(new Date(started.createdAt).toISOString());
  const reviewers = [...new Set(assignments.map(assignment => assignment.reviewer_def_id).filter(name => name !== 'independent-verifier'))];
  const latestOutcomes = new Map<string, AgentReviewData['outcomes'][number]>();
  data.outcomes.forEach(outcome => latestOutcomes.set(outcome.finding_id, outcome));
  const fixed = [...latestOutcomes.values()].flatMap(outcome => {
    if (outcome.author_response !== 'fixed') return [];
    const finding = data.findings.find(row => row.id === outcome.finding_id);
    if (finding === undefined || (finding.round_id !== round.id && !delta)) return [];
    return [finding];
  });
  const fixedIds = new Set(fixed.map(finding => finding.id));
  const pendingFindings = findings.filter(finding => !fixedIds.has(finding.id));
  const verifiedCount = data.verifications.filter(row => roundFindings.some(finding => finding.id === row.finding_id) && row.status === 'verified').length;
  const terminalSummary = round.status === 'ERRORED'
    ? `✕ Round ${roundNumber} errored · ${formatCents(round.cost_cents)}`
    : round.status === 'CANCELLED'
      ? `■ Round ${roundNumber} stopped · ${formatCents(round.cost_cents)}`
      : `✓ Round ${roundNumber} complete · ${formatCents(round.cost_cents)} · ${roundFindings.length} ${roundFindings.length === 1 ? 'finding' : 'findings'}${round.status === 'COMPLETED_WITH_QUESTIONS' ? ' · questions remain' : ''}${verifiedCount > 0 ? ' · verifier ✓' : ''}`;
  return (
    <div className={`agent-round-page${prOpen ? '' : ' agent-round-page--pr-closed'}`}>
      <aside className="agent-round-rail">
        <div className="agent-round-rail__top">
          <button type="button" className="agent-round-back" onClick={onBack}>← Back to PR conversation</button>
          <div className="agent-round-identity"><span>⚖</span><div><b>Agent review</b><small>Round {roundNumber} · {round.scope} scope</small></div></div>
        </div>
        <div className="agent-round-rail__section"><span>Rounds</span><small>{data.rounds.length}</small></div>
        <div className="agent-round-list">
          {data.rounds.map((row, index) => {
            const count = data.findings.filter(finding => finding.round_id === row.id && finding.lifecycle_status !== 'dropped').length;
            return (
              <button type="button" className={row.id === round.id ? 'active' : ''} key={row.id} onClick={() => onSelectRound?.(row.id)} disabled={onSelectRound === undefined}>
                <span>{row.status === 'RUNNING' ? '●' : row.status === 'ERRORED' ? '!' : '✓'}</span>
                <div><b>Round {index + 1}</b><small>{row.scope} · {count} {count === 1 ? 'finding' : 'findings'}</small></div>
                <small>{formatCents(row.cost_cents)}</small>
              </button>
            );
          })}
        </div>
        <details className="agent-round-rail__details">
          <summary><span>Review plan</span><small>{delta ? 'delta scope' : `${objectives.length} objectives`}</small></summary>
          <div className="agent-round-objectives">
            {objectives.map(objective => {
              const criterion = data.criteria.find(row => row.id === objective.criterion_id);
              return (
                <div className={`agent-round-objective agent-round-objective--${objective.resolution_status}`} key={objective.id}>
                  <span>{objective.resolution_status === 'finding' || objective.resolution_status === 'investigated-clean'
                    ? '✓' : objective.resolution_status === 'unknown' ? '?' : '○'}</span>
                  <div>{objective.statement}<small><span className={`agent-round-kind agent-round-kind--${criterion?.kind ?? 'repo-convention'}`}>{criterionLabel(criterion?.kind)}</span>{objective.resolution_status}</small></div>
                </div>
              );
            })}
          </div>
        </details>
        <div className="agent-round-rail__summary">
          <span>Source coverage</span>
          <b>{localSource ? 'Local source at reviewed SHA' : 'Remote-only review'}</b>
          <small>{localSource
            ? 'Repository source and direct caller search were available.'
            : `Not assessed: ${round.capabilities_json.unavailable.map(capabilityLabel).join(', ')}.`}</small>
        </div>
        <div className="agent-round-rail__summary">
          <span>Run evidence</span>
          <b>{assignments.length} reviewer{assignments.length === 1 ? '' : 's'} · {steps.length} steps</b>
          <small>Open a reviewer in the main inspector to see its tool evidence.</small>
        </div>
        <div className="agent-round-rail__spacer" />
        <div className="agent-round-budget">
          <span>THIS ROUND</span><b>{formatCents(round.cost_cents)}</b>
          <span>AGENT REVIEW</span><b>{formatCents(reviewSpend)} · round cap {formatCents(round.budget_json.cost_cap_cents)}</b>
          <div><span style={{ width: `${round.budget_json.cost_cap_cents === 0 ? 0 : Math.min(100, round.cost_cents / round.budget_json.cost_cap_cents * 100)}%` }} /></div>
          <small>wall {round.budget_json.wall_clock_minutes}m · runs never post (R9)</small>
        </div>
      </aside>
      <main className="agent-round-conversation">
        <div className="agent-round-conversation__head">
          <span className="agent-round-conversation__label">⚖ REVIEW ROUND</span>
          <b>Round {roundNumber} · {round.scope}{delta && objectives[0] !== undefined ? ` — ${objectives[0].statement}` : ''}</b>
          <span className={`agent-round-conversation__status ${running ? 'running' : 'terminal'}`}><i />{round.status.replaceAll('_', ' ')} · {formatCents(round.cost_cents)}</span>
          <span className={`agent-round-capability agent-round-capability--${localSource ? 'local' : 'remote'}`}>
            {localSource ? 'LOCAL SOURCE' : 'REMOTE ONLY'}
          </span>
          {running && onStopRound !== undefined && <button type="button" className="agent-round-stop" onClick={() => onStopRound(round.id)}>Stop round</button>}
          <button type="button" className={`agent-round-pr-toggle${prOpen ? ' active' : ''}`} onClick={() => setPrOpen(value => !value)} aria-label={prOpen ? 'Hide PR panel' : 'Show PR panel'} title={prOpen ? 'Hide PR panel' : 'Show PR panel'}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M15 4v16" /></svg>
          </button>
        </div>
        <div className="agent-round-feed">
          <div className="agent-round-feed__inner">
            <div className="agent-round-scope">⚖ Round {roundNumber} · {round.scope} scope{delta ? ` — cut by ${round.start_commit.slice(0, 7)}` : localSource ? ' — changed code and repository references' : ' — GitHub diff and file blobs'}</div>
            <section className="agent-round-reviewer agent-round-reviewer--planner">
              <div className="agent-round-message">
                <span>PL</span>
                <div>
                  <div className="agent-round-message__head"><b>planner</b><span>AGENT</span>{plannerTime !== '' && <small>{plannerTime}</small>}</div>
                  <p>{delta ? 'Delta' : 'Full'} scope: {objectives.length} {objectives.length === 1 ? 'objective' : 'objectives'} assigned{reviewers.length > 0 ? ` to ${reviewers.join(', ')}` : ''}. {delta ? 'Only affected findings and their dependency spans are being re-verified.' : 'The panel is reviewing the changed code against the deterministic plan.'}</p>
                </div>
              </div>
            </section>
            {narrativeEvents.map(event => (
              <div className="agent-round-event" key={event.id}>
                <span>{event.glyph}</span><MarkdownProse text={event.text} /><time>{event.time}</time>
              </div>
            ))}
            {assignments.map(assignment => {
              const assignmentSteps = steps.filter(step => step.assignment_id === assignment.id);
              return (
                <section className="agent-round-reviewer" key={assignment.id}>
                  <div className="agent-round-message">
                    <span>{assignment.reviewer_def_id.slice(0, 2).toUpperCase()}</span>
                    <div>
                      <div className="agent-round-message__head"><b>{assignment.reviewer_def_id}</b><span>AGENT</span><small>{assignment.status}</small></div>
                      <p>{conciseAssignmentSummary(data, assignment.id, assignment.understanding_summary)}</p>
                    </div>
                  </div>
                  <InvestigationGroup assignment={assignment} steps={assignmentSteps} delta={delta} />
                </section>
              );
            })}
            {fixed.map(finding => {
              const comment = findingComment(data, finding.id);
              const pendingDraft = comment !== undefined && comment.publishedAt === null && comment.dismissedAt === null;
              return (
                <div className="agent-round-resolution" key={`fixed-${finding.id}`}>
                  <div className="agent-round-resolution__head"><span>✓</span><b>{finding.id} fixed — fix verified{pendingDraft ? ' · resolve + reply drafted' : ''}</b>{pendingDraft && <small>resolve · pending</small>}</div>
                  <p>Finding status <code>open → fixed</code>{comment?.filePath === null || comment?.filePath === undefined ? '' : ` · review thread at ${comment.filePath}:${comment.lineNumber ?? 1}`}. Nothing posts to GitHub until you submit.</p>
                  <div className="agent-round-resolution__actions">
                    <button type="button" onClick={() => onOpenReviewList?.(finding.id)}>View in review list →</button>
                    <button type="button" onClick={() => onOpenFinding(finding.id, comment?.filePath ?? null, comment?.lineNumber ?? null)}>View on diff →</button>
                    <button type="button" className="danger" onClick={() => onReopenFinding?.(finding.id)}>Reopen finding</button>
                  </div>
                  {pendingDraft && <strong>1 draft rides your next Submit review →</strong>}
                </div>
              );
            })}
            {pendingFindings.map((finding, index) => {
              const comment = findingComment(data, finding.id);
              const presentation = presentFinding(data, finding.id);
              const location = comment?.filePath == null ? 'PR-level comment' : `${comment.filePath}:${comment.lineNumber ?? 1}`;
              return (
                <details className="agent-round-finding-card" key={finding.id}>
                  <summary>
                    <span>F{index + 1}</span>
                    <b title={findingSummary(finding.claim)}>{findingSummary(finding.claim)}</b>
                    <small>Pending · {location}</small>
                    <i aria-hidden>⌄</i>
                  </summary>
                  <div className="agent-round-finding-card__body">
                    {presentation === undefined
                      ? <MarkdownProse text={comment?.body ?? finding.claim} />
                      : <AgentFindingContent view={presentation} body={comment?.body ?? finding.claim} pending />}
                    <button
                      type="button"
                      aria-label={comment?.filePath == null ? `View ${finding.id} in PR conversation` : `View ${finding.id} on diff`}
                      onClick={() => onOpenFinding(finding.id, comment?.filePath ?? null, comment?.lineNumber ?? null)}
                    >
                      {comment?.filePath == null ? 'View in PR conversation →' : 'View on diff →'}
                    </button>
                  </div>
                </details>
              );
            })}
            {round.status !== 'RUNNING' && (
              <div className={`agent-round-complete agent-round-complete--${round.status.toLowerCase()}`}>
                {terminalSummary}
              </div>
            )}
          </div>
        </div>
      </main>
      {prOpen && <aside className="agent-round-pr-panel">{prView}</aside>}
    </div>
  );
}
