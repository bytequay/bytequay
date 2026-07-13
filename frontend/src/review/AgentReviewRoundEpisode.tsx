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
import { useState } from 'react';
import { RunEpisode } from '../ui/conv/RunEpisode';
import { ToolBlock } from '../ui/conv/ToolBlock';
import { Spine } from '../ui/conv/spine/Spine';
import { WorkFold } from '../ui/conv/spine/WorkFold';
import type { AgentReviewData, PanelReviewRunRow, ReviewRoundRow } from './agentReviewTypes';
import { formatCents } from './agentReviewTypes';

type EpisodeState = 'complete' | 'live' | 'errored' | 'halted' | 'stale' | 'cancelled' | 'auto';

export function episodeState(
  run: PanelReviewRunRow, round: ReviewRoundRow, stale = round.trigger === 'stale',
): EpisodeState {
  if (run.status === 'running') return round.trigger === 'auto_continue' ? 'auto' : 'live';
  if (run.status === 'failed') return round.cost_cents >= round.budget_json.cost_cap_cents ? 'halted' : 'errored';
  if (run.status === 'cancelled') {
    return round.status === 'CANCELLED' && !stale ? 'cancelled' : 'stale';
  }
  return 'complete';
}

const COPY: Record<EpisodeState, { glyph: string; title: string; action?: string }> = {
  complete: { glyph: '✓', title: 'complete' },
  live: { glyph: '●', title: 'running' },
  errored: { glyph: '✕', title: 'errored' },
  halted: { glyph: '◼', title: 'halted · budget cap hit', action: 'Extend $0.50 & resume' },
  stale: { glyph: '↑', title: 'stale', action: 'Continue review' },
  cancelled: { glyph: '■', title: 'stopped' },
  auto: { glyph: '●', title: 'auto-continue running' },
};

export function AgentReviewRoundEpisode({ data, round, run, onOpen, onAction }: {
  data: AgentReviewData;
  round: ReviewRoundRow;
  run: PanelReviewRunRow;
  onOpen?: () => void;
  onAction?: () => void;
}) {
  const [expanded, setExpanded] = useState(run.status === 'running');
  const state = episodeState(
    run, round,
    data.session.status === 'STALE' && data.rounds.at(-1)?.id === round.id,
  );
  const copy = COPY[state];
  const assignments = data.assignments.filter(assignment => assignment.round_id === round.id);
  const steps = data.steps.filter(step => assignments.some(assignment => assignment.id === step.assignment_id));
  const findings = data.findings.filter(finding => finding.round_id === round.id);
  const ballotFindings = findings.filter(finding => finding.verification_status !== 'rejected'
    && finding.lifecycle_status !== 'dropped');
  const rejected = findings.filter(finding => finding.verification_status === 'rejected').length;
  const questions = findings.filter(finding => finding.lifecycle_status === 'NEEDS_USER_JUDGEMENT').length;
  const summary = state === 'complete'
    ? [
        `${ballotFindings.length} finding${ballotFindings.length === 1 ? '' : 's'}`,
        rejected > 0 ? `${rejected} rejected by verifier` : null,
        questions > 0 ? `${questions} needs judgement` : null,
      ].filter((part): part is string => part !== null).join(' · ')
    : run.headline ?? round.scope;
  const pendingComments = data.pr_comments.filter(comment => comment.findingId !== null
    && findings.some(finding => finding.id === comment.findingId)
    && comment.publishedAt === null && comment.dismissedAt === null).length;

  return (
    <div className={`agent-review-run-episode agent-review-run-episode--${state}`}>
      <Spine variant="trunk">
        <RunEpisode
          run={run}
          mark={copy.glyph}
          color={state === 'complete' ? 'green' : state === 'errored' ? 'orange' : state === 'halted' || state === 'stale' ? 'amber' : 'purple'}
          name={`Round ${data.rounds.indexOf(round) + 1} · ${copy.title}`}
          state={summary}
          meta={`${formatCents(round.cost_cents)} of ${formatCents(round.budget_json.cost_cap_cents)}`}
          collapsed={!expanded}
          onToggle={() => setExpanded(value => !value)}
        />
        {expanded && (
          <div className="agent-review-run-episode__body">
            <ToolBlock
              tag="planner"
              plan
              icon="⚑"
              desc={`dispatched ${assignments.length} reviewer${assignments.length === 1 ? '' : 's'} — objectives and fixed budgets attached`}
            />
          {assignments.map(assignment => {
            const assignmentSteps = steps.filter(step => step.assignment_id === assignment.id);
            const assignmentCost = assignmentSteps.reduce((sum, step) => sum + step.cost_cents, 0);
            return (
              <WorkFold
                key={assignment.id}
                label={`${assignment.reviewer_def_id} — investigation`}
                meta={`${assignmentSteps.length} steps · ${formatCents(assignmentCost)}`}
                forceOpen
                icon={assignment.reviewer_def_id.slice(0, 2).toUpperCase()}
              >
                {assignmentSteps.length === 0 ? <p>No step log recorded.</p> : assignmentSteps.map(step => (
                  <ToolBlock
                    key={step.id}
                    tag={step.action_type}
                    icon={step.status === 'completed' ? '✓' : step.status === 'running' ? '●' : step.status === 'skipped' ? '⊘' : '·'}
                    desc={step.reason}
                    meta={step.cost_cents > 0 ? formatCents(step.cost_cents) : step.status}
                  />
                ))}
              </WorkFold>
            );
          })}
          {findings.map(finding => (
            <ToolBlock
              key={finding.id}
              tag={`F${data.findings.indexOf(finding) + 1}`}
              icon={finding.verification_status === 'rejected' ? '✕' : finding.verification_status === 'unknown' ? '?' : finding.verification_status === 'partially' ? '◐' : '◈'}
              desc={`${finding.claim} · ${finding.verification_status === 'partially' ? 'partially verified' : finding.verification_status}`}
              meta={`priority ${finding.severity}`}
            />
          ))}
          {pendingComments > 0 && (
            <ToolBlock tag="pending" icon="→" desc={`${pendingComments} finding${pendingComments === 1 ? '' : 's'} landed as pending comments`} meta="local-only" />
          )}
          <div className="agent-review-run-episode__actions">
            {onOpen !== undefined && <button type="button" onClick={onOpen}>Open round log →</button>}
            {(copy.action !== undefined || state === 'errored') && (
              <button type="button" className="primary" onClick={onAction}>
                {state === 'errored' ? `Retry round ${data.rounds.indexOf(round) + 1}` : copy.action}
              </button>
            )}
          </div>
          </div>
        )}
      </Spine>
    </div>
  );
}
