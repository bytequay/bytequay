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
import { GitPullRequestArrow, Undo2 } from 'lucide-react';
import IdeSurface from './IdeSurface';
import type { CodeTestsBridge, CodeTestsHost } from '../pulls/code-tests/CodeTestsTab';
import type { CodeHostRef, ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import type { CodeCommentsOptions } from '../pulls/code-tests/useCodeComments';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import type { AgentTaskDto } from '../types';
import { latestRun } from '../pulls/agent-review/model';
import { runGate } from '../pulls/code-tests/runModel';
import { ConfirmDialog } from '../ConfirmDialog';

export default function IdeWorkingCopy({ host, onReviewChange, onUnsavedChange, comments, active, ownReviewStream, bridge }: {
  host: CodeTestsHost; onReviewChange: (review: AgentReviewDto) => void; onExit?: () => void;
  onUnsavedChange?: (count: number) => void; comments?: CodeCommentsOptions; active: boolean; ownReviewStream: boolean; bridge: CodeTestsBridge;
}) {
  const [hostRef] = useState<CodeHostRef>(() => host.kind === 'review' ? { kind: 'review', reviewId: host.review.id }
    : { kind: 'task', taskId: host.task.id, workspaceId: host.workspaceId });
  const [task, setTask] = useState<AgentTaskDto | null>(host.kind === 'task' ? host.task : null);
  const review = host.kind === 'review' ? host.review : null;
  const [files, setFiles] = useState<ReviewCodeFilesDto | null>(null);
  const [dialog, setDialog] = useState<{ kind: 'reset' | 'discard'; revision: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editor, setEditor] = useState<string | null>(null);
  const [dirty, setDirty] = useState(0);
  const [reload, setReload] = useState(0);
  const callback = useRef(onReviewChange); callback.current = onReviewChange;
  const reviewId = review?.id ?? null;
  const open = review?.state === 'OPEN';
  const unsavedCallback = useRef(onUnsavedChange); unsavedCallback.current = onUnsavedChange;
  useEffect(() => () => unsavedCallback.current?.(0), []);
  useEffect(() => {
    if (!active) return;
    let live = true;
    const load = async () => {
      try {
        const next = await bridge.listCodeFiles(hostRef);
        if (live) setFiles(next);
        if (hostRef.kind === 'task') {
          const task = await bridge.getAgentTask(hostRef.taskId, hostRef.workspaceId);
          if (live) setTask(task);
        }
      } catch (failure) { if (live) setError(String(failure)); }
    };
    void load(); const timer = setInterval(() => { void load(); }, 5000);
    return () => { live = false; clearInterval(timer); };
  }, [active, bridge, hostRef, reload, review?.worktreeSync?.headSha, review?.task.version]);
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
      if (next.activeTurn === null && next.task.mutationClaim === null && latestRun(next)?.state === 'RUNNING') {
        clearTimeout(timer); timer = setTimeout(() => { void read().catch(() => {}); }, 200);
      }
    };
    const stream = bridge.subscribeAgentReview(reviewId, 0, event => {
      if (event.kind === 'changed' || event.kind === 'events' || event.state === 'open') void read().catch(() => {});
    }, true);
    return () => { live = false; clearTimeout(timer); void stream.then(stop => stop(), () => {}); };
  }, [active, open, reviewId, ownReviewStream, bridge]);
  const gate = review ? runGate(review, files, []) : { hidden: true, blocked: null };
  const stale = open && review?.sourceTaskId == null && review?.worktreeSync && review.worktreeSync.state !== 'CURRENT';
  const syncLabel = review?.worktreeSync?.state === 'UNAVAILABLE' ? 'Working copy sync unavailable — retry' : 'Local branch is stale — reset to remote';
  const mutate = async () => {
    if (!dialog || !review || busy || gate.hidden || gate.blocked) return;
    setBusy(true); setError(null);
    try {
      if (dialog.kind === 'reset') onReviewChange(await bridge.syncReviewWorktree(review.id, dialog.revision));
      else setFiles(await bridge.discardReviewCodeChanges(review.id, dialog.revision));
      setDialog(null);
      // Discard draft buffers only after the backend operation succeeds; failures preserve the current editor.
      if (editor) { await window.ide.discardSourceDrafts(editor); await window.ide.close(editor); }
      setDirty(0); onUnsavedChange?.(0); setReload(value => value + 1);
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  };
  return <div className="ct">
    {review && <div className="ct-toolbar">
      {stale && <button className="ct-icon-button" aria-label={syncLabel} title={syncLabel} disabled={busy || gate.hidden || gate.blocked !== null}
        onClick={() => { if (files) { setError(null); setDialog({ kind: 'reset', revision: files.revision }); } }}><GitPullRequestArrow size={15} /></button>}
      <button className="ct-icon-button" aria-label="Discard local changes" title={gate.blocked ?? 'Discard local changes'} disabled={busy || gate.hidden || gate.blocked !== null}
        onClick={() => { if (files) { setError(null); setDialog({ kind: 'discard', revision: files.revision }); } }}><Undo2 size={14} /></button>
    </div>}
    {error && <div className="ct-banner is-error" role="alert">{error}<button onClick={() => { setError(null); setReload(value => value + 1); }}>Retry</button></div>}
    <IdeSurface host={hostRef} active={active && !busy} review={review} task={task} comments={comments} onReviewChange={onReviewChange} onReady={setEditor}
      onUnsavedChange={count => { setDirty(count); onUnsavedChange?.(count); }} />
    {dialog && <ConfirmDialog title={dialog.kind === 'reset' ? 'Reset working copy to remote?' : 'Discard local changes?'}
      confirmLabel={busy ? 'Working…' : dialog.kind === 'reset' ? 'Discard and reset' : 'Discard'} destructive busy={busy} confirmDisabled={gate.hidden || gate.blocked !== null}
      onCancel={() => setDialog(null)} onConfirm={() => { void mutate(); }}>
      <p>{dialog.kind === 'reset' ? 'Discard saved local changes, untracked files, local commits and unsaved source drafts, then reset to the latest remote PR commit.'
        : 'Discard every saved local change, untracked file and unsaved source draft in this review working copy.'}</p>
      <p>{dirty > 0 ? `${dirty} unsaved edits or replies are open. ` : ''}Private reply drafts, existing review results and run history are retained. This does not start a review.</p>
      {(error || gate.blocked) && <p role="alert">{error ?? gate.blocked}</p>}
    </ConfirmDialog>}
  </div>;
}
