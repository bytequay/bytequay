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
import { useEffect, useState } from 'react';
import { useStageDetailData } from '../threads/brain/useStageDetailData';
import { useBrainViewData } from '../threads/brain/useBrainViewData';
import { usePendingShipProposal } from '../threads/usePendingShipProposal';
import { useThreadStream } from '../threads/useThreadStream';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import type { StageType } from '../types/brainView';
import { Conv, EventRow, Working } from '../ui/conv';
import { DetailsTabContent } from '../ui/pane';
import { planTab } from './planTab';
import { stageRow } from './stageConversationRow';
import { StageDetailPage } from './StageDetailPage';
import type { StageKind } from './StageDetailPage';

const KIND: Partial<Record<StageType, StageKind>> = {
  PLAN_STAGE: 'plan',
  DEVELOPMENT_STAGE: 'dev',
  CI_FIXING_STAGE: 'ci-fix',
  REVIEW_MONITOR_STAGE: 'comments',
  CLEANUP_STAGE: 'cleanup',
};

/**
 * Data adapter mounting the V3 {@link StageDetailPage} on the live stage
 * detail data. Maps the stage transcript → conversation (agent turns,
 * tool blocks, your steering, iteration markers) and wires the composer to
 * the stage's agent via {@code steerStage}. Plan/PR tabs are backfilled
 * later.
 */
export function StageDetailRoute({
  threadId, taskId, stageId, onOpenCode, onOpenStage,
}: {
  threadId: string;
  taskId: string;
  stageId: string;
  onOpenCode: () => void;
  /** Jump to another stage — used after approving the plan, which closes
   *  this Plan stage and opens the Development stage. */
  onOpenStage?: (stageId: string) => void;
}) {
  const { data, refresh } = useStageDetailData(stageId);
  const shipProposal = usePendingShipProposal(threadId, taskId);
  // The plan card lives on the brain view; surface it on the Plan stage so
  // the plan (and its Approve action) shows here too, not only on the brain.
  const { data: brain, pollFast } = useBrainViewData(taskId);
  const plan = brain.rightRail.plan;
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);

  const stageKind: StageKind = data ? KIND[data.stage.type] ?? 'dev' : 'dev';
  const state = data?.stage.state;

  const approvePlan = () => {
    if (plan === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.approvePlan(plan.planStageId)
      .then(result => { pollFast(); onOpenStage?.(result.devStageId); })
      .catch(() => { /* poll reconciles */ });
  };

  const submit = () => {
    const body = text.trim();
    if (body.length === 0 || busy) return;
    setText('');
    setBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.steerStage(stageId, body)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setBusy(false));
  };

  // Live stream of the agent working this stage: its text appears
  // token-by-token (and a non-delta event refreshes the canonical
  // transcript, which clears the live buffer). This is what makes the stage
  // feel alive between the periodic poll snapshots.
  const { liveText } = useThreadStream(
    threadId, state === 'CLOSED' ? 'COMPLETED' : 'RUNNING', refresh);

  // Poll the thread's run state. This is the signal that stays true through a
  // long, quiet tool call (e.g. a multi-minute build) where no text streams
  // and the stage-state poll lags — so the working indicator doesn't blink
  // off mid-turn. Stops once the stage is closed.
  const [threadRunning, setThreadRunning] = useState(false);
  useEffect(() => {
    if (state === 'CLOSED') { setThreadRunning(false); return; }
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTask === undefined) return;
    let cancelled = false;
    const poll = () => {
      void bridge.getTask(threadId)
        .then(t => { if (!cancelled) setThreadRunning(t.status === 'RUNNING'); })
        .catch(() => { /* transient; next tick retries */ });
    };
    poll();
    const id = window.setInterval(poll, 3000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, [threadId, state]);

  // Show the working indicator whenever a turn is executing — the thread is
  // RUNNING, the stage is ACTIVE, the user just steered, or text is streaming
  // in. Track when the working period began so the indicator can tick an
  // elapsed counter (a long, quiet turn shouldn't read as dead).
  const working = busy || threadRunning || state === 'ACTIVE' || liveText.length > 0;
  const [workingSince, setWorkingSince] = useState<number | null>(null);
  useEffect(() => {
    setWorkingSince(prev => (working ? prev ?? Date.now() : null));
  }, [working]);

  const conversation = (
    <Conv>
      {data?.conversation.map(stageRow)}
      {liveText.length > 0 && <EventRow kind="agent" who="Agent" markdown={liveText} />}
      {shipProposal !== null && <ShipReviewPrompt onReview={onOpenCode} />}
      {working && liveText.length === 0 && (
        <Working
          label="Agent is working…"
          since={workingSince ?? undefined}
          onStop={() => {
            const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
            void bridge?.interruptTask(threadId).then(refresh).catch(() => { /* poll reconciles */ });
          }}
        />
      )}
    </Conv>
  );

  return (
    <StageDetailPage
      stageKind={stageKind}
      stage={{ title: data?.task.title ?? 'Stage', branch: data?.task.branch }}
      conversation={conversation}
      composer={{
        value: text,
        onChange: setText,
        onSubmit: submit,
        busy,
        placeholder: state === 'CLOSED' ? 'This stage is closed.' : 'Steer this stage…',
      }}
      run={{ paused: state === 'PAUSED', terminal: state === 'CLOSED', statusLabel: state ?? 'Running' }}
      tabs={{
        // The plan is the task's, not the stage's — surface it on every
        // stage (Dev / CI-fix / …) so the user can re-read it from anywhere,
        // not only the Plan stage. Approve only while it's still awaiting.
        plan: plan !== null
          ? planTab(plan, plan.state === 'awaiting' ? approvePlan : undefined)
          : undefined,
        details: (
          <DetailsTabContent sections={[{
            title: 'Stage',
            rows: [
              { label: 'State', value: state ?? '—' },
              { label: 'Iterations', value: String(data?.stage.iterationCount ?? 0) },
            ],
          }]}
          />
        ),
      }}
      onOpenChanges={onOpenCode}
    />
  );
}
