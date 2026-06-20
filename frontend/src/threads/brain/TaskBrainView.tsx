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
import type { StageDto, StageType } from '../../types/brainView';
import { useBrainViewData } from './useBrainViewData';
import { buildStageLabels } from './stageMeta';
import { TaskIdentityBar } from './TaskIdentityBar';
import { AggregateMetricsStrip } from './AggregateMetricsStrip';
import { StageNavigatorRail } from './StageNavigatorRail';
import { BrainFeedColumn } from './BrainFeedColumn';
import { RightRail } from './RightRail';

type Props = {
  taskId: string;
  threadId: string;
  /** Thread title for the rail's "↑ Thread · …" up-link. */
  threadTitle?: string;
  onBack: () => void;
  onOpenThread: () => void;
  /** Drill into a stage's detail surface (the stage-detail page is a
   *  later milestone; until then this can no-op or redirect). */
  onOpenStage?: (stageId: string) => void;
  /** Open the linked PR in the in-app PR detail page. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
  /** Injectable clock for deterministic relative-time rendering in
   *  tests. Defaults to the real wall clock. */
  nowMs?: number;
};

function liveLabelFor(type: StageType): string {
  switch (type) {
    case 'CI_FIXING_STAGE': return 'CI FIX RUNNING';
    case 'REVIEW_MONITOR_STAGE': return 'REVIEW MONITOR RUNNING';
    case 'DEVELOPMENT_STAGE': return 'DEV RUNNING';
    case 'CLEANUP_STAGE': return 'CLEANUP RUNNING';
    case 'REVIEW_STAGE': return 'PANEL RUNNING';
  }
}

/**
 * Task brain view — the main per-task surface. Renders three stacked
 * zones (identity bar / aggregate strip / body) where the body is a
 * three-column grid: stage navigator, brain feed, action rail. Data
 * comes entirely through {@link useBrainViewData}, a mock fixture for
 * now; the real-data swap changes only that hook.
 */
export default function TaskBrainView({
  taskId, threadId: _threadId, threadTitle = 'Cost & tokens',
  onBack, onOpenThread, onOpenStage, onOpenPr, nowMs,
}: Props) {
  const data = useBrainViewData(taskId);
  const { task, aggregate, stages, subStages, brainFeed, rightRail, scrubbers } = data;
  const clock = nowMs ?? Date.now();

  const allStages: StageDto[] = [...stages, ...subStages];
  const activeStageIds = new Set(allStages.filter(s => s.state === 'ACTIVE').map(s => s.id));
  const activeStage = allStages.find(s => s.state === 'ACTIVE');
  const liveLabel = activeStage !== undefined ? liveLabelFor(activeStage.type) : null;
  const stageLabels = buildStageLabels(stages, subStages);

  const openStage = (stageId: string) => {
    if (onOpenStage !== undefined) onOpenStage(stageId);
    else console.log('[brain view] open stage (stage detail not built yet):', stageId);
  };

  const openPr = () => {
    if (task.prNumber === null || onOpenPr === undefined) return;
    const [owner, repo] = task.repoFullName.split('/');
    if (owner !== undefined && repo !== undefined) onOpenPr(owner, repo, task.prNumber);
  };

  // M2 stubs — the brain agent and the stage actions don't exist yet.
  const stub = (what: string) => () => console.log(`[brain view] ${what} (not wired in M2)`);

  return (
    <div className="task-brain">
      <div className="mesh-bg" aria-hidden />
      <div className="tbv-stack">
        <TaskIdentityBar
          task={task}
          onBack={onBack}
          onOpenPr={task.prNumber !== null && onOpenPr !== undefined ? openPr : undefined}
        />
        <AggregateMetricsStrip aggregate={aggregate} liveLabel={liveLabel} />
        {/* Grid columns are set inline as well as in CSS: this is the
            load-bearing layout value (252 / fluid center / 308) and the
            minmax(0, 1fr) center column is what stops long unbreakable
            content from shoving the rail widths around. */}
        <div className="tbv-body" style={{ gridTemplateColumns: '252px minmax(0, 1fr) 308px' }}>
          <StageNavigatorRail
            stages={stages}
            subStages={subStages}
            threadTitle={threadTitle}
            brainCount={brainFeed.length}
            onOpenThread={onOpenThread}
            onOpenStage={openStage}
          />
          <BrainFeedColumn
            feed={brainFeed}
            scrubbers={scrubbers}
            stageLabels={stageLabels}
            activeStageIds={activeStageIds}
            nowMs={clock}
            onOpenStage={openStage}
            onSubmitMessage={text => console.log('[brain view] composer submit (stub):', text)}
          />
          <RightRail
            rail={rightRail}
            nowMs={clock}
            onApprove={approval => openStage(approval.stageId)}
            onMerge={stub('merge PR')}
            onViewDiff={stub('view code diff')}
            onViewContext={stub('view full context')}
            onPause={stub('pause task')}
            onClose={stub('close task')}
          />
        </div>
      </div>
    </div>
  );
}
