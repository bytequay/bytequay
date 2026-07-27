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
import type { ReactNode } from 'react';
import type { BrainFeedRow, StageDto } from '../../types/brainView';
import { MarkdownProse } from '../MarkdownProse';
import {
  EventTimestamp, Headline, PullRequestCreatedEvent, Round, RuntimeKickoffCard, Spine, StageBoundaryNode,
  UserTurn, WorkFold, runtimeKickoff,
} from '../../ui/conv';
import { ClockIcon } from '../../ui/TaskBrainDesignIcons';
import type { Density } from '../../ui/conv/spine/DensityToggle';
import {
  buildBrainTimeline, headlineOf, isQnA, workOf,
} from './brainTimeline';
import type { BrainRound } from './brainTimeline';
import { formatDuration } from './format';

/** The work-fold summary, design-shaped: "Worked for 3m 12s" label plus a
 *  "· 5 steps" meta. The wall-clock runs from the first work row to the
 *  round's headline (or last work row while a round is still streaming);
 *  the label falls back to "Brain worked" when it rounds to under a second
 *  or a timestamp is missing. */
function workSummary(work: BrainFeedRow[], headline: BrainFeedRow | null): { label: string; meta: string } {
  const steps = `· ${work.length} ${work.length === 1 ? 'step' : 'steps'}`;
  const firstTs = work[0]?.ts;
  const lastTs = headline?.ts ?? work[work.length - 1]?.ts;
  if (firstTs === undefined || lastTs === undefined) return { label: 'Brain worked', meta: steps };
  const elapsedSec = (new Date(lastTs).getTime() - new Date(firstTs).getTime()) / 1000;
  return elapsedSec >= 1
    ? { label: `Worked for ${formatDuration(elapsedSec)}`, meta: steps }
    : { label: 'Brain worked', meta: steps };
}

/**
 * The brain conversation feed rendered on the timeline spine (M10). Maps the
 * flat `brainFeed` into stage segments + rounds, promotes stage boundaries to
 * labelled spine nodes, anchors the human's turns, folds the agent's work,
 * and folds closed stages in Focused density. Replaces the old flat
 * `EventRow` map. Degrades gracefully on today's prose data — the brain feed
 * carries no tool calls, so the work fold collects the round's intermediate
 * prose only.
 */
export function BrainFeed({
  feed, stages, density, foldClosedStages = true, developmentArtifact,
  spineTrailer, trailer, onOpenStage, threadId,
}: {
  feed: BrainFeedRow[];
  stages: StageDto[];
  density: Density;
  /** Focused legacy surfaces may collapse completed stages wholesale. The
   *  locked brain keeps their summary/update rows visible and folds only
   *  the Worked-for trace. */
  foldClosedStages?: boolean;
  /** Real task diff card, placed directly after the Development milestone. */
  developmentArtifact?: ReactNode;
  /** Timeline-aware tail appended inside the spine (milestones hung on the rail). */
  spineTrailer?: ReactNode;
  /** Live tail appended after the spine (queued msgs, working indicator). */
  trailer?: ReactNode;
  /** Jump into a stage's detail view when its boundary node is clicked. */
  onOpenStage?: (stageId: string) => void;
  /** Scopes attached-image thumbnail lookups — any thread id works, reads
   *  are attachments-root-scoped, not thread-scoped (see the backend's
   *  ChatAttachmentStore doc: a brain thread's own id isn't known here). */
  threadId?: string;
}) {
  const segments = buildBrainTimeline(feed, stages);
  const full = density === 'full';
  const developmentSegment = segments.findIndex(segment => segment.stage?.type === 'DEVELOPMENT_STAGE');
  // Closed stages start folded in Focused; track the ones the user expanded.
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const toggle = (id: string) => setExpanded(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return (
    <>
      <Spine>
        {segments.map((seg, si) => {
        const stage = seg.stage;
        const foldable = foldClosedStages && stage !== null && seg.closed && !full;
        const collapsed = foldable && !expanded.has(stage.id);
        let autonomous = 0;
        const rounds = seg.rounds.map(r => {
          const pullRequestMilestone = hasPullRequestMilestone(r);
          const tag = r.userTurn === null && !pullRequestMilestone ? `R${(autonomous += 1)}` : undefined;
          // When a closed stage is folded, retain durable PR preparation and
          // publication milestones alongside the user's interventions.
          if (collapsed && r.userTurn === null && !pullRequestMilestone) return null;
          return (
            <RoundView
              key={r.id}
              round={r}
              tag={tag}
              full={full}
              collapsedStage={collapsed}
              segmentStageType={stage?.type ?? null}
              threadId={threadId}
            />
          );
        });
        return (
          <div key={stage?.id ?? `seg-${si}`}>
            {stage !== null && (
              <StageBoundaryNode
                stage={stage}
                closed={seg.closed}
                collapsed={foldable ? collapsed : undefined}
                onToggle={foldable ? () => toggle(stage.id) : undefined}
                onOpen={onOpenStage !== undefined ? () => onOpenStage(stage.id) : undefined}
              />
            )}
            {rounds}
            {developmentArtifact !== undefined && si === developmentSegment && developmentArtifact}
          </div>
        );
        })}
        {developmentArtifact !== undefined && developmentSegment < 0 && developmentArtifact}
        {spineTrailer}
      </Spine>
      {trailer}
    </>
  );
}

function RoundView({ round, tag, full, collapsedStage, segmentStageType, threadId }: {
  round: BrainRound;
  tag?: string;
  full: boolean;
  collapsedStage: boolean;
  segmentStageType: StageDto['type'] | null;
  threadId?: string;
}) {
  const work = workOf(round);
  const visibleFailures = work.filter(isRemoteCiFailure);
  const foldedWork = work.filter(row => !isRemoteCiFailure(row));
  const headline = headlineOf(round);
  const qna = isQnA(round);
  const ciFailure = headline !== null && isRemoteCiFailure(headline);
  const pullRequestMilestone = headline?.type === 'PULL_REQUEST_PROGRESS'
    || headline?.type === 'PUSHED_PR_CREATED' ? headline : null;
  const kickoff = runtimeKickoff(round.userTurn?.body ?? null);
  return (
    <Round tag={tag}>
      {round.userTurn !== null && kickoff !== null && (
        <RuntimeKickoffCard
          text={round.userTurn.body}
          kickoff={kickoff}
          timestamp={<EventTimestamp iso={round.userTurn.ts} />}
          messageSeq={round.userTurn.messageSeq}
          managedSkills={round.userTurn.managedSkills}
        />
      )}
      {round.userTurn !== null && kickoff === null && (
        <UserTurn
          text={round.userTurn.body}
          timestamp={<EventTimestamp iso={round.userTurn.ts} />}
          threadId={threadId}
          images={round.userTurn.images}
          messageSeq={round.userTurn.messageSeq}
          managedSkills={round.userTurn.managedSkills}
          quiet
          clampAt={96}
        />
      )}
      {pullRequestMilestone !== null && (
        <PullRequestCreatedEvent
          pullRequest={pullRequestMilestone.pullRequest}
          timestamp={<EventTimestamp iso={pullRequestMilestone.ts} />}
        />
      )}
      {/* A folded closed stage keeps the user's intervention and the final
          answer visible, while hiding the intermediate agent work. */}
      {collapsedStage && round.userTurn !== null && headline !== null && (
        <Headline body={headline.body} reply />
      )}
      {!collapsedStage && qna && headline !== null && (
        <Headline body={headline.body} reply />
      )}
      {!collapsedStage && !qna && pullRequestMilestone === null && (
        <>
          {foldedWork.length > 0 && (
            <WorkFold
              label={workSummary(foldedWork, headline).label}
              meta={workSummary(foldedWork, headline).meta}
              icon={<ClockIcon size={14} strokeWidth={1.8} />}
              forceOpen={full}
            >
              {foldedWork.map(w => (
                <div className="sp-submsg" key={w.id}>
                  <div className="sp-submsg__who">Brain<span className="ago"><EventTimestamp iso={w.ts} /></span></div>
                  <div className="sp-submsg__tx"><MarkdownProse text={w.body} /></div>
                </div>
              ))}
            </WorkFold>
          )}
          {visibleFailures.map(row => (
            <RemoteCiFailure
              key={row.id}
              row={row}
              includeMilestone={segmentStageType !== 'CI_FIXING_STAGE'}
            />
          ))}
          {headline !== null && (ciFailure
            ? (
              <RemoteCiFailure
                row={headline}
                includeMilestone={segmentStageType !== 'CI_FIXING_STAGE'}
              />
            )
            : <Headline who="Brain" body={headline.body} timestamp={<EventTimestamp iso={headline.ts} />} />)}
        </>
      )}
    </Round>
  );
}

function hasPullRequestMilestone(round: BrainRound): boolean {
  return round.rows.some(row => row.type === 'PULL_REQUEST_PROGRESS' || row.type === 'PUSHED_PR_CREATED');
}

function isRemoteCiFailure(row: BrainFeedRow): boolean {
  if (row.type === 'NEEDS_ATTENTION' && row.stageType === 'CI_FIXING_STAGE') return true;
  return row.stageType === 'CI_FIXING_STAGE'
    && /(?:red|fail(?:ed|ing)?|error|exception)/i.test(row.body);
}

function RemoteCiFailure({ row, includeMilestone }: {
  row: BrainFeedRow;
  includeMilestone: boolean;
}) {
  const round = row.body.match(/(?:round|iter(?:ation)?)\s*#?(\d+)/i)?.[1];
  const title = round === undefined
    ? 'Remote CI failed'
    : `Remote CI failed — round ${round} awakened`;
  const body = row.body.replace(/\*\*|`/g, '');
  return (
    <div className="workspace-task-ci-failure">
      {includeMilestone && (
        <div className="workspace-task-ci-failure__milestone">
          <strong>REMOTE CI</strong>
          <span>{round === undefined ? 'awakened' : `round ${round} · awakened`}</span>
          <i aria-hidden />
          <small><EventTimestamp iso={row.ts} /></small>
        </div>
      )}
      <div className="workspace-task-ci-failure__quote">
        <div><FailureIcon /><strong>{title}</strong></div>
        <pre>{body}</pre>
      </div>
    </div>
  );
}

function FailureIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="m9 9 6 6M15 9l-6 6" />
    </svg>
  );
}
