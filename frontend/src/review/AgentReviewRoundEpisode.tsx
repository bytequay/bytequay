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
import type { AgentReviewData, PanelReviewRunRow, ReviewRoundRow } from './agentReviewTypes';
import { formatCents } from './agentReviewTypes';
import {
  CloseIcon, ReviewRoundIcon, SparkIcon, UpArrowIcon, WarnTriangleIcon,
} from '../ui/TaskBrainDesignIcons';

type EpisodeState = 'complete' | 'queued' | 'live' | 'errored' | 'halted' | 'stale' | 'cancelled' | 'auto';

export function episodeState(
  run: PanelReviewRunRow, round: ReviewRoundRow, stale = round.trigger === 'stale',
): EpisodeState {
  if (round.status === 'QUEUED') return 'queued';
  if (run.status === 'running') return round.trigger === 'auto_continue' ? 'auto' : 'live';
  if (run.status === 'failed') return round.cost_cents >= round.budget_json.cost_cap_cents ? 'halted' : 'errored';
  if (run.status === 'cancelled') {
    return round.status === 'CANCELLED' && !stale ? 'cancelled' : 'stale';
  }
  return 'complete';
}

const COPY: Record<EpisodeState, { title: string; action?: string }> = {
  complete: { title: 'complete' },
  queued: { title: 'queued' },
  live: { title: 'running' },
  errored: { title: 'errored' },
  halted: { title: 'halted · budget cap hit', action: 'Start follow-up round' },
  stale: { title: 'stale', action: 'Continue review' },
  cancelled: { title: 'stopped' },
  auto: { title: 'auto-continue running' },
};

function EpisodeIcon({ state }: { state: EpisodeState }) {
  if (state === 'live' || state === 'auto') return <SparkIcon size={11} />;
  if (state === 'errored' || state === 'cancelled') return <CloseIcon />;
  if (state === 'halted') return <WarnTriangleIcon size={12} />;
  if (state === 'stale') return <UpArrowIcon />;
  return <ReviewRoundIcon />;
}

export function AgentReviewRoundEpisode({ data, round, run, onOpen, onAction }: {
  data: AgentReviewData;
  round: ReviewRoundRow;
  run: PanelReviewRunRow;
  onOpen?: () => void;
  onAction?: () => void;
}) {
  const state = episodeState(
    run, round,
    data.review.status === 'STALE' && data.rounds.at(-1)?.id === round.id,
  );
  const copy = COPY[state];
  const findings = data.findings.filter(finding => finding.round_id === round.id);
  const ballotFindings = findings.filter(finding => finding.verification_status !== 'rejected'
    && finding.lifecycle_status !== 'dropped');
  const rejected = findings.filter(finding => finding.verification_status === 'rejected').length;
  const questions = findings.filter(finding => finding.verification_status === 'unknown'
    || finding.lifecycle_status === 'NEEDS_USER_JUDGEMENT'
    || finding.lifecycle_status === 'NEEDS_AUTHOR_INPUT').length;
  const summary = state === 'complete'
    ? [
        `${ballotFindings.length} finding${ballotFindings.length === 1 ? '' : 's'}`,
        rejected > 0 ? `${rejected} rejected by verifier` : null,
        questions > 0 ? `${questions} needs judgement` : null,
      ].filter((part): part is string => part !== null).join(' · ')
    : run.headline ?? round.scope;
  const roundNumber = data.rounds.indexOf(round) + 1;

  return (
    <div className={`pr-tl-icon-row agent-review-round-row agent-review-round-row--${state}`}>
      <span className="tic agent-review-round-card__marker" aria-hidden="true"><EpisodeIcon state={state} /></span>
      <section className={`agent-review-round-card agent-review-round-card--${state}`}>
        <button
          type="button"
          className="agent-review-round-card__open"
          onClick={onOpen}
          disabled={onOpen === undefined}
          aria-label={`Open round ${roundNumber}: ${copy.title}, ${summary}, ${formatCents(round.cost_cents)} of ${formatCents(round.budget_json.cost_cap_cents)}`}
        >
          <span className="agent-review-round-card__copy">
            <b>Round {roundNumber} · {copy.title}</b>
            <small>{summary}</small>
          </span>
          <span className="agent-review-round-card__cost">
            {formatCents(round.cost_cents)} / {formatCents(round.budget_json.cost_cap_cents)}
          </span>
          <span className="agent-review-round-card__arrow" aria-hidden="true">→</span>
        </button>
        {(copy.action !== undefined || state === 'errored') && onAction !== undefined && (
          <button type="button" className="agent-review-round-card__action" onClick={onAction}>
            {state === 'errored' ? `Retry round ${roundNumber}` : copy.action}
          </button>
        )}
      </section>
    </div>
  );
}
