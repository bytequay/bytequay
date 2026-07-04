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
import { useCallback, useState } from 'react';
import type { MergeMethod } from './MergeDialog';
import type { PRViewMode } from '../../types/localPr';
import { isLocalStatus } from '../../types/localPr';
import { useLocalPr } from '../../threads/brain/useLocalPr';

/**
 * The local-PR data + user-gated actions every task surface shares: the
 * bundle poll, the PR-level comment box, inline line comments, and the
 * push / merge dialog state machines. One hook so the stage pages and
 * the task brain page drive the unified {@code <PRView>} identically —
 * nothing here auto-posts; push and merge only flip dialog state until
 * the user confirms.
 */
export function useLocalPrActions(taskId: string, opts: {
  /** Called after a push / merge lands so the host can poll its own
   *  task state faster than the regular cadence. */
  onAfterTransition?: () => void;
} = {}) {
  const { onAfterTransition } = opts;
  const { bundle, refresh } = useLocalPr(taskId);
  const localPr = bundle?.pr ?? null;
  const prMode: PRViewMode =
    localPr !== null && !isLocalStatus(localPr.status) ? 'remote' : 'local';

  const [localComment, setLocalComment] = useState('');
  const [pushOpen, setPushOpen] = useState(false);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [prBusy, setPrBusy] = useState(false);

  const submitLocalComment = useCallback(() => {
    const body = localComment.trim();
    if (body.length === 0 || localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.addLocalPrComment(localPr.id, { scope: 'pr', body })
      .then(() => { setLocalComment(''); refresh(); })
      .catch(() => { /* poll reconciles */ });
  }, [localComment, localPr, refresh]);

  const confirmPush = useCallback(() => {
    if (localPr === null) return;
    setPrBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.pushLocalPr(localPr.id)
      .then(() => { setPushOpen(false); refresh(); onAfterTransition?.(); })
      .catch(() => { /* poll reconciles; dialog stays open on failure */ })
      .finally(() => setPrBusy(false));
  }, [localPr, refresh, onAfterTransition]);

  const confirmMerge = useCallback((method: MergeMethod) => {
    if (localPr === null) return;
    setPrBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.mergeLocalPr(localPr.id, method)
      .then(() => { setMergeOpen(false); refresh(); onAfterTransition?.(); })
      .catch(() => { /* poll reconciles */ })
      .finally(() => setPrBusy(false));
  }, [localPr, refresh, onAfterTransition]);

  const addLocalLineComment = useCallback((filePath: string, lineNumber: number, body: string) => {
    if (localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.addLocalPrComment(localPr.id, { scope: 'file-line', filePath, lineNumber, body })
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [localPr, refresh]);

  const resolveLocalComment = useCallback((commentId: string) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.resolveLocalPrComment(commentId)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [refresh]);

  const dismissLocalComment = useCallback((commentId: string) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.dismissLocalPrComment(commentId)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [refresh]);

  return {
    bundle, refresh, localPr, prMode,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, addLocalLineComment, resolveLocalComment, dismissLocalComment,
    pushOpen, setPushOpen, mergeOpen, setMergeOpen,
    reviewOpen, setReviewOpen, prBusy,
  };
}
