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
import type { AgentRunDto, ReviewRoundDto } from '../../types/brainView';
import { SpineNode } from './spine/Spine';
import { RunEpisode } from './RunEpisode';
import { RoundGateBar } from './RoundGateBar';

function summaryMeta(round: ReviewRoundDto): string {
  if (round.origin === 'brain') {
    return `iter ${round.iteration}/${round.budget}`;
  }
  const { fixed, replied, pushedBack, open } = round.stats;
  const total = fixed + replied + pushedBack + open;
  const who = round.reviewers.length > 0 ? round.reviewers.join(', ') : 'reviewer';
  const parts = [`${total} comment${total === 1 ? '' : 's'} from ${who}`, `${fixed} fixed`, `${replied} replied`];
  if (pushedBack > 0) parts.push(`${pushedBack} pushed back`);
  if (open > 0 && round.status !== 'posted' && round.status !== 'closed') parts.push(`${open} open`);
  return parts.join(' · ');
}

/** The brain's adversarial-review badge (plan-rail-runs.md R21/R22) — reuses
 *  the #52 AGENT-badge visual family, tinted for the brain's own identity
 *  rather than the dev/fix agent. */
function BrainBadge() {
  return <span className="brain-badge">BRAIN</span>;
}

/** The brain's latest verdict, when it's reviewed at least once. */
function VerdictPill({ verdict }: { verdict: ReviewRoundDto['brainVerdict'] }) {
  if (verdict === null) return null;
  const approved = verdict === 'approved';
  return (
    <span className={`verdict-pill ${approved ? 'ok' : 'chg'}`}>
      {approved ? 'APPROVED' : 'CHANGES REQUESTED'}
    </span>
  );
}

/**
 * One reviewer batch in the Comments stage feed (plan-rail-runs.md R11-R13):
 * folded to a single "✓ Round N" summary row once posted/closed, expanded
 * with its nested re-run (if any) and gate bar while live. The itemized
 * per-comment activity lives in the round's own run log — clicking the
 * header or the nested run opens it, rather than duplicating that
 * transcript inline here.
 */
export function RoundEpisode({ round, nestedRun, onOpenRun, onApprove, approveBusy }: {
  round: ReviewRoundDto;
  /** The round's own nested `ci_fix` run (re-running CI after a fix), if live. */
  nestedRun?: AgentRunDto;
  onOpenRun?: (runId: string) => void;
  onApprove?: (roundId: string) => void;
  approveBusy?: boolean;
}) {
  const live = round.status !== 'posted' && round.status !== 'closed';
  const isBrain = round.origin === 'brain';
  return (
    <div className={`round-episode${live ? ' live' : ''}`}>
      <SpineNode
        mark={live ? '●' : '✓'}
        color={isBrain ? 'purple' : 'teal'}
        name={isBrain ? 'Brain review' : `Round ${round.idx}`}
        meta={summaryMeta(round)}
        right={isBrain && (
          <>
            <BrainBadge />
            <VerdictPill verdict={round.brainVerdict} />
          </>
        )}
        onOpen={round.runId !== null ? () => onOpenRun?.(round.runId as string) : undefined}
      />
      {live && (
        <div className="round-episode__body">
          {nestedRun !== undefined && (
            <RunEpisode run={nestedRun} onOpen={() => onOpenRun?.(nestedRun.id)} />
          )}
          <RoundGateBar
            round={round}
            busy={approveBusy}
            onApprove={() => onApprove?.(round.id)}
          />
        </div>
      )}
    </div>
  );
}
