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
import { useCallback, useEffect, useState } from 'react';
import { useStageDetailData } from './brain/useStageDetailData';
import { stageRow } from '../pages/stageConversationRow';
import type { PermissionDecideHandler } from './PermissionCard';
import { Conv, Working } from '../ui/conv';
import { Composer } from '../ui/shell';

/**
 * The code-diff page's left column: a conversation about the change. Shows
 * the task's dev-stage transcript (and lets the user steer it inline), so
 * the conversation sits beside the files + diff. The stage is the one the
 * diff was opened from ({@code stageId}); when that's absent (opened from
 * the brain or a ship prompt) it resolves the task's Development stage.
 * Falls back to a chat scaffold only when no stage can be found.
 */
export function DiffChatColumn({ stageId, taskId, threadId }: {
  stageId?: string;
  taskId?: string;
  /** The owning thread — needed to approve/deny a permission the agent is
   *  waiting on. Without it, permission rows still render but read-only. */
  threadId?: string;
}) {
  const resolved = useDiffChatStageId(stageId, taskId);
  return (
    // `.shell` scopes the V3 conversation + composer styles; block display
    // overrides the shell's own 2-column grid so it lays out as a column.
    <div className="shell diff-viewer__chat">
      {resolved !== null ? <StageChat stageId={resolved} threadId={threadId} /> : <PrChatPlaceholder />}
    </div>
  );
}

/** The stage whose transcript to show: the explicit {@code stageId}, else
 *  the task's Development stage (where the code work happens), else its
 *  latest stage. Null when none resolves. */
function useDiffChatStageId(stageId?: string, taskId?: string): string | null {
  const [resolved, setResolved] = useState<string | null>(stageId ?? null);
  useEffect(() => {
    if (stageId !== undefined) { setResolved(stageId); return; }
    if (taskId === undefined) { setResolved(null); return; }
    let cancelled = false;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.getBrainView?.(taskId)
      .then(view => {
        if (cancelled) return;
        const stages = view.stages ?? [];
        const dev = stages.find(s => s.type === 'DEVELOPMENT_STAGE');
        setResolved((dev ?? stages[stages.length - 1])?.id ?? null);
      })
      .catch(() => { /* keep the placeholder on failure */ });
    return () => { cancelled = true; };
  }, [stageId, taskId]);
  return resolved;
}

function StageChat({ stageId, threadId }: { stageId: string; threadId?: string }) {
  const { data, refresh } = useStageDetailData(stageId);
  const [busy, setBusy] = useState(false);
  const closed = data?.stage.state === 'CLOSED';

  // Sending is owned here (it touches `busy` + refreshes the feed), but the
  // draft text deliberately is NOT — see StageChatComposer.
  const send = useCallback((body: string) => {
    if (body.length === 0 || busy) return;
    setBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.steerStage(stageId, body)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setBusy(false));
  }, [stageId, busy, refresh]);

  // Approve / deny a permission the agent is blocked on, right here in the
  // code-page chat (the same path the stage-detail page uses, routed by
  // threadId). Without a threadId we can't act, so the row stays read-only.
  const onDecide = useCallback<PermissionDecideHandler>((callId, decision, preApprove) => {
    if (threadId === undefined) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.decideTaskPermission(threadId, callId, decision, preApprove)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [threadId, refresh]);

  return (
    <>
      <Conv>
        {data?.conversation.map(r => stageRow(r, threadId !== undefined ? onDecide : undefined, threadId))}
        {(busy || data?.stage.state === 'ACTIVE') && <Working label="Agent is working…" />}
      </Conv>
      <StageChatComposer
        onSend={send}
        busy={busy}
        disabled={closed}
        placeholder={closed ? 'This stage is closed.' : 'Talk to the agent about this change…'}
      />
    </>
  );
}

/**
 * The draft-text owner, split out from {@link StageChat} so a keystroke
 * re-renders only the composer — not the conversation feed beside a large
 * diff. (Keeping the text in the parent forced a full re-render + layout
 * flush of the feed on every character, which made typing crawl.)
 */
function StageChatComposer({ onSend, busy, disabled, placeholder }: {
  onSend: (body: string) => void;
  busy: boolean;
  disabled: boolean;
  placeholder: string;
}) {
  const [text, setText] = useState('');
  const submit = () => {
    const body = text.trim();
    if (body.length === 0 || busy) return;
    setText('');
    onSend(body);
  };
  return (
    <Composer
      value={text}
      onChange={setText}
      onSubmit={submit}
      busy={busy}
      disabled={disabled}
      placeholder={placeholder}
    />
  );
}

function PrChatPlaceholder() {
  return (
    <>
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
