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
import type { MergeMethod } from './MergeDialog';
import type { DiffFileDto } from '../../types';
import { derivePRCapabilities } from '../prCapabilities';
import { useExternalPr } from '../useExternalPr';

/**
 * The standalone PR-details-page counterpart to {@link useLocalPrActions}:
 * same bundle poll + user-gated action set, but keyed by (owner, repo,
 * number) instead of a task id, and derived with the {@code 'details'}
 * surface (unified-pr-view.md U10) — capabilities differ (e.g. no chat
 * agent composer; publish-review turns on for external-origin PRs).
 */
export function useExternalPrActions(owner: string, repo: string, number: number) {
  const { bundle, refresh, syncing } = useExternalPr(owner, repo, number);
  const localPr = bundle?.pr ?? null;
  const capabilities = localPr !== null ? derivePRCapabilities(localPr, 'details') : null;

  const [localComment, setLocalComment] = useState('');
  const [pushOpen, setPushOpen] = useState(false);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [prBusy, setPrBusy] = useState(false);
  const [testsBusy, setTestsBusy] = useState(false);
  const [publishBusy, setPublishBusy] = useState(false);

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
      .then(() => { setPushOpen(false); refresh(); })
      .catch(() => { /* poll reconciles; dialog stays open on failure */ })
      .finally(() => setPrBusy(false));
  }, [localPr, refresh]);

  const confirmMerge = useCallback((method: MergeMethod) => {
    if (localPr === null) return;
    setPrBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.mergeLocalPr(localPr.id, method)
      .then(() => { setMergeOpen(false); refresh(); })
      .catch(() => { /* poll reconciles */ })
      .finally(() => setPrBusy(false));
  }, [localPr, refresh]);

  const publishReview = useCallback(() => {
    if (localPr === null) return;
    setPublishBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.publishLocalPrReview(localPr.id)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setPublishBusy(false));
  }, [localPr, refresh]);

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

  const runLocalTests = useCallback(() => {
    if (localPr === null) return;
    setTestsBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.runLocalPrTests(localPr.id)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setTestsBusy(false));
  }, [localPr, refresh]);

  // The full-page Files-changed diff, fetched lazily only once the user
  // opens the review (same lazy-load shape as the task surface's
  // getTaskCumulativeDiff — see TaskBrainRoute).
  const [reviewFiles, setReviewFiles] = useState<DiffFileDto[] | null>(null);
  useEffect(() => {
    if (!reviewOpen) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.fetchPrDiffFiles === undefined) return;
    let cancelled = false;
    void bridge.fetchPrDiffFiles(repo, number)
      .then(list => { if (!cancelled) setReviewFiles(list); })
      .catch(() => { if (!cancelled) setReviewFiles([]); });
    return () => { cancelled = true; };
  }, [reviewOpen, repo, number]);

  return {
    bundle, refresh, syncing, localPr, capabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, publishReview, publishBusy,
    addLocalLineComment, resolveLocalComment, dismissLocalComment,
    pushOpen, setPushOpen, mergeOpen, setMergeOpen,
    reviewOpen, setReviewOpen, prBusy, reviewFiles,
    runLocalTests, testsBusy,
  };
}
