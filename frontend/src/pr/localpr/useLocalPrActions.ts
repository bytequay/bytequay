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
import { derivePRCapabilities } from '../prCapabilities';
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
  const { bundle, refresh, syncing } = useLocalPr(taskId);
  const localPr = bundle?.pr ?? null;
  const capabilities = localPr !== null ? derivePRCapabilities(localPr, 'task') : null;

  const [localComment, setLocalComment] = useState('');
  const [pushOpen, setPushOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [prBusy, setPrBusy] = useState(false);
  const [testsBusy, setTestsBusy] = useState(false);

  const submitLocalComment = useCallback(() => {
    const body = localComment.trim();
    if (body.length === 0 || localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge === undefined) return;
    const request = capabilities?.postRemoteComment === true
      ? bridge.postRemotePrComment(localPr.id, body)
      : bridge.addLocalPrComment(localPr.id, { scope: 'pr', body });
    void request
      .then(() => { setLocalComment(''); refresh(); })
      .catch(() => { /* poll reconciles */ });
  }, [capabilities?.postRemoteComment, localComment, localPr, refresh]);

  const confirmPush = useCallback(() => {
    if (localPr === null) return;
    setPrBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.pushLocalPr(localPr.id)
      .then(() => { setPushOpen(false); refresh(); onAfterTransition?.(); })
      .catch(() => { /* poll reconciles; dialog stays open on failure */ })
      .finally(() => setPrBusy(false));
  }, [localPr, refresh, onAfterTransition]);

  const confirmMerge = useCallback((method: string) => {
    if (localPr === null) return;
    setPrBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.mergeLocalPr(localPr.id, method)
      .then(() => { refresh(); onAfterTransition?.(); })
      .catch(() => { /* poll reconciles */ })
      .finally(() => setPrBusy(false));
  }, [localPr, refresh, onAfterTransition]);

  const dequeuePr = useCallback(() => {
    if (localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.dequeueLocalPr(localPr.id)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [localPr, refresh]);

  const deleteBranch = useCallback(() => {
    if (localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.deleteLocalPrBranch(localPr.id)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [localPr, refresh]);

  const addLocalLineComment = useCallback((
    filePath: string, side: 'LEFT' | 'RIGHT', lineNumber: number,
    startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string,
  ) => {
    if (localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.addLocalPrComment(
      localPr.id, { scope: 'file-line', filePath, lineNumber, side, startLine, startSide, body },
    )
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [localPr, refresh]);

  const replyLocalLineComment = useCallback((
    parentCommentId: string, filePath: string, side: 'LEFT' | 'RIGHT', lineNumber: number,
    startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string,
  ) => {
    if (localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    return bridge?.addLocalPrComment(
      localPr.id, { scope: 'file-line', filePath, lineNumber, side, startLine, startSide, body, parentCommentId },
    )
      .then(() => refresh());
  }, [localPr, refresh]);

  const replyLocalPrComment = useCallback((parentCommentId: string, body: string) => {
    if (localPr === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    return bridge?.addLocalPrComment(localPr.id, { scope: 'pr', body, parentCommentId })
      .then(() => refresh());
  }, [localPr, refresh]);

  const resolveLocalComment = useCallback((commentId: string) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.resolveLocalPrComment(commentId)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [refresh]);

  const deleteLocalComment = useCallback((commentId: string) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.deleteLocalPrComment(commentId)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [refresh]);

  const dismissLocalComment = useCallback((commentId: string) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.dismissLocalPrComment(commentId)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ });
  }, [refresh]);

  const runLocalTests = useCallback(() => {
    if (localPr === null) return;
    setTestsBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.runLocalPrTests(localPr.id)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setTestsBusy(false));
  }, [localPr, refresh]);

  return {
    bundle, refresh, syncing, localPr, capabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, dequeuePr, deleteBranch,
    addLocalLineComment, replyLocalLineComment, replyLocalPrComment, resolveLocalComment, deleteLocalComment, dismissLocalComment,
    pushOpen, setPushOpen,
    reviewOpen, setReviewOpen, prBusy,
    runLocalTests, testsBusy,
  };
}
