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
  Headline, Round, Spine, StageBoundaryNode, UserTurn, WorkFold,
} from '../../ui/conv';
import type { Density } from '../../ui/conv/spine/DensityToggle';
import { EventTimestamp } from '../../ui/conv';
import {
  buildBrainTimeline, headlineOf, isQnA, workOf,
} from './brainTimeline';
import type { BrainRound } from './brainTimeline';
import { formatDuration } from './format';

/** The work-fold summary: step count plus the wall-clock the brain spent —
 *  from its first work row to the round's headline (or last work row while a
 *  round is still streaming). The duration is dropped when it rounds to under
 *  a second or a timestamp is missing. */
function workMeta(work: BrainFeedRow[], headline: BrainFeedRow | null): string {
  const steps = `${work.length} ${work.length === 1 ? 'step' : 'steps'}`;
  const firstTs = work[0]?.ts;
  const lastTs = headline?.ts ?? work[work.length - 1]?.ts;
  if (firstTs === undefined || lastTs === undefined) return steps;
  const elapsedSec = (new Date(lastTs).getTime() - new Date(firstTs).getTime()) / 1000;
  return elapsedSec >= 1 ? `${steps} · ${formatDuration(elapsedSec)}` : steps;
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
export function BrainFeed({ feed, stages, density, trailer, onOpenStage, threadId }: {
  feed: BrainFeedRow[];
  stages: StageDto[];
  density: Density;
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
        const foldable = stage !== null && seg.closed && !full;
        const collapsed = foldable && !expanded.has(stage.id);
        let autonomous = 0;
        const rounds = seg.rounds.map(r => {
          const tag = r.userTurn === null ? `R${(autonomous += 1)}` : undefined;
          // When a closed stage is folded, keep the user's interventions
          // visible but drop the agent chatter (design.md #20).
          if (collapsed && r.userTurn === null) return null;
          return <RoundView key={r.id} round={r} tag={tag} full={full} collapsedStage={collapsed} threadId={threadId} />;
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
          </div>
        );
        })}
      </Spine>
      {trailer}
    </>
  );
}

function RoundView({ round, tag, full, collapsedStage, threadId }: {
  round: BrainRound;
  tag?: string;
  full: boolean;
  collapsedStage: boolean;
  threadId?: string;
}) {
  const work = workOf(round);
  const headline = headlineOf(round);
  const qna = isQnA(round);
  return (
    <Round tag={tag}>
      {round.userTurn !== null && (
        <UserTurn
          text={round.userTurn.body}
          timestamp={<EventTimestamp iso={round.userTurn.ts} />}
          threadId={threadId}
          images={round.userTurn.images}
          messageSeq={round.userTurn.messageSeq}
        />
      )}
      {/* A folded closed stage keeps only the user turn visible. */}
      {!collapsedStage && qna && headline !== null && (
        <Headline body={headline.body} reply />
      )}
      {!collapsedStage && !qna && (
        <>
          {work.length > 0 && (
            <WorkFold meta={workMeta(work, headline)} forceOpen={full}>
              {work.map(w => (
                <div className="sp-submsg" key={w.id}>
                  <div className="sp-submsg__who">Brain<span className="ago"><EventTimestamp iso={w.ts} /></span></div>
                  <div className="sp-submsg__tx"><MarkdownProse text={w.body} /></div>
                </div>
              ))}
            </WorkFold>
          )}
          {headline !== null && (
            <Headline who="Brain" body={headline.body} timestamp={<EventTimestamp iso={headline.ts} />} />
          )}
        </>
      )}
    </Round>
  );
}
