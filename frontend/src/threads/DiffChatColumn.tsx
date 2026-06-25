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
import { useStageDetailData } from './brain/useStageDetailData';
import { stageRow } from '../pages/stageConversationRow';
import { Conv, Working } from '../ui/conv';
import { Composer } from '../ui/shell';

/**
 * The code-diff page's left column: a conversation about the change.
 *
 * <p>When the diff was opened from a stage ({@code stageId} present) it
 * shows that stage's transcript and lets the user steer the stage's agent
 * inline — so opening the dev-stage diff puts the dev conversation right
 * beside the files + diff. Otherwise (e.g. a PR I opened) it renders a
 * chat scaffold; wiring an agent to a standalone PR is a later step, so
 * the composer is parked with an explanatory placeholder.
 */
export function DiffChatColumn({ stageId }: { stageId?: string }) {
  return (
    // `.shell` scopes the V3 conversation + composer styles; block display
    // overrides the shell's own 2-column grid so it lays out as a column.
    <div className="shell diff-viewer__chat">
      {stageId !== undefined ? <StageChat stageId={stageId} /> : <PrChatPlaceholder />}
    </div>
  );
}

function StageChat({ stageId }: { stageId: string }) {
  const { data, refresh } = useStageDetailData(stageId);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const closed = data?.stage.state === 'CLOSED';

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

  return (
    <>
      <div className="diff-viewer__chat-head">{data?.task.title ?? 'Conversation'}</div>
      <Conv>
        {data?.conversation.map(stageRow)}
        {(busy || data?.stage.state === 'ACTIVE') && <Working label="Agent is working…" />}
      </Conv>
      <Composer
        value={text}
        onChange={setText}
        onSubmit={submit}
        busy={busy}
        disabled={closed}
        placeholder={closed ? 'This stage is closed.' : 'Talk to the agent about this change…'}
      />
    </>
  );
}

function PrChatPlaceholder() {
  return (
    <>
      <div className="diff-viewer__chat-head">Conversation</div>
      <div className="diff-viewer__chat-empty">
        Talk to an agent about this pull request. Agent support here is coming soon.
      </div>
      <Composer
        value=""
        onChange={() => { /* parked until PR-agent support lands */ }}
        onSubmit={() => { /* parked */ }}
        disabled
        placeholder="Agent chat — coming soon"
      />
    </>
  );
}
