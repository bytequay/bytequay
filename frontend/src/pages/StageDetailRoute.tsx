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
import { useStageDetailData } from '../threads/brain/useStageDetailData';
import { usePendingShipProposal } from '../threads/usePendingShipProposal';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import type { StageType } from '../types/brainView';
import { Conv, Working } from '../ui/conv';
import { DetailsTabContent } from '../ui/pane';
import { stageRow } from './stageConversationRow';
import { StageDetailPage } from './StageDetailPage';
import type { StageKind } from './StageDetailPage';

const KIND: Partial<Record<StageType, StageKind>> = {
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
  threadId, taskId, stageId, onOpenCode,
}: {
  threadId: string;
  taskId: string;
  stageId: string;
  onOpenCode: () => void;
}) {
  const { data, refresh } = useStageDetailData(stageId);
  const shipProposal = usePendingShipProposal(threadId, taskId);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);

  const stageKind: StageKind = data ? KIND[data.stage.type] ?? 'dev' : 'dev';
  const state = data?.stage.state;

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

  const working = busy || state === 'ACTIVE';
  const conversation = (
    <Conv>
      {data?.conversation.map(stageRow)}
      {shipProposal !== null && <ShipReviewPrompt onReview={onOpenCode} />}
      {working && <Working label="Agent is working…" />}
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
