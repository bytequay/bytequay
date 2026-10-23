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
import { useEffect, useRef, useState } from 'react';
import IdeSurface from './IdeSurface';
import type { CodeTestsBridge, CodeTestsHost } from '../pulls/code-tests/CodeTestsTab';
import type { CodeHostRef } from '../pulls/code-tests/contracts';
import type { CodeCommentsOptions } from '../pulls/code-tests/useCodeComments';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import type { AgentTaskDto } from '../types';
import { pollVisible } from './pollVisible';

export default function IdeWorkingCopy({ host, onReviewChange, onUnsavedChange, onAgentDraft, comments, active, ownReviewStream, bridge, startRequested = false }: {
  host: CodeTestsHost; onReviewChange: (review: AgentReviewDto) => void; onExit?: () => void;
  onUnsavedChange?: (count: number) => void; onAgentDraft?: (taskId: string) => boolean; comments?: CodeCommentsOptions; active: boolean; ownReviewStream: boolean; bridge: CodeTestsBridge;
  startRequested?: boolean;
}) {
  const [hostRef] = useState<CodeHostRef>(() => host.kind === 'review' ? { kind: 'review', reviewId: host.review.id }
    : { kind: 'task', taskId: host.task.id, workspaceId: host.workspaceId });
  const [task, setTask] = useState<AgentTaskDto | null>(host.kind === 'task' ? host.task : null);
  const review = host.kind === 'review' ? host.review : null;
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const callback = useRef(onReviewChange); callback.current = onReviewChange;
  const reviewId = review?.id ?? null;
  const open = review?.state === 'OPEN';
  const unsavedCallback = useRef(onUnsavedChange); unsavedCallback.current = onUnsavedChange;
  useEffect(() => () => unsavedCallback.current?.(0), []);
  useEffect(() => {
    if (!active || hostRef.kind !== 'task') return;
    let live = true;
    const load = async () => {
      try {
        const task = await bridge.getAgentTask(hostRef.taskId, hostRef.workspaceId);
        if (live) setTask(task);
        if (live) setLoadError(null);
      } catch (failure) { if (live) setLoadError(`Could not refresh task: ${String(failure)}`); }
    };
    const stop = pollVisible(load, 5000);
    return () => { live = false; stop(); };
  }, [active, bridge, hostRef, reload]);
  useEffect(() => {
    if (!active || !open || !reviewId || !ownReviewStream) return;
    let live = true;
    let revision = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const read = async () => {
      const request = ++revision;
      const next = await bridge.getAgentReview(reviewId);
      if (!live || request !== revision) return;
      callback.current(next);
      if (next.activeTurn === null && next.task.mutationClaim === null && next.runs.at(-1)?.state === 'RUNNING') {
        clearTimeout(timer); timer = setTimeout(() => { void read().catch(() => {}); }, 200);
      }
    };
    const stream = bridge.subscribeAgentReview(reviewId, 0, event => {
      if (event.kind === 'changed' || event.kind === 'events' || event.state === 'open') void read().catch(() => {});
    }, true);
    return () => { live = false; clearTimeout(timer); void stream.then(stop => stop(), () => {}); };
  }, [active, open, reviewId, ownReviewStream, bridge]);
  return <div className="ct">
    {loadError && <div className="ct-banner is-error" role="alert">{loadError}<button onClick={() => { setLoadError(null); setReload(value => value + 1); }}>Retry</button></div>}
    <IdeSurface host={hostRef} active={active} review={review} task={task} comments={comments} onReviewChange={onReviewChange}
      onUnsavedChange={onUnsavedChange} onAgentDraft={onAgentDraft} startRequested={startRequested} />
  </div>;
}
