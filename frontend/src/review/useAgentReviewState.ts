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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReviewVerdict } from '../pages/SubmitReviewDrawer';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import type { AgentReviewHeaderState, AgentReviewStartOptions } from './AgentReviewHeaderAction';
import type { AgentReviewData } from './agentReviewTypes';

/** Live review-session state shared by the PR page, Files view, and round
 * page. The backend aggregate is the sole source of review artifacts; this
 * hook only holds temporary UI state while mutations are in flight. */
export function useAgentReviewState(
  bundle: LocalPRBundle | null | undefined,
  refreshPr: () => void,
  beforePublish?: (verdict: ReviewVerdict, comments: LocalPRComment[]) => Promise<void>,
) {
  const prId = bundle?.pr.id ?? null;
  const [data, setData] = useState<AgentReviewData | null>(null);
  const [loading, setLoading] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const editTimers = useRef(new Map<string, number>());

  const load = useCallback(async () => {
    if (prId === null || window.bridge?.getAgentReviewSession === undefined) return;
    try {
      const next = await window.bridge.getAgentReviewSession(prId);
      setData(next);
      setError(null);
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [prId]);

  useEffect(() => {
    setData(null);
    setError(null);
    if (prId === null) return;
    setLoading(true);
    void load().finally(() => setLoading(false));
  }, [load, prId]);

  const running = data?.rounds.some(round => round.status === 'RUNNING') === true;
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval((): void => { void load(); }, 1_000);
    return () => window.clearInterval(timer);
  }, [load, running]);

  useEffect(() => () => {
    editTimers.current.forEach(timer => window.clearTimeout(timer));
  }, []);

  const mutate = useCallback(async (operation: () => Promise<AgentReviewData>) => {
    setLoading(true);
    try {
      const next = await operation();
      setData(next);
      setError(null);
      return next;
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
      return null;
    }
    finally {
      setLoading(false);
    }
  }, []);

  const startReview = useCallback((options?: AgentReviewStartOptions) => {
    if (prId === null) return;
    setStarting(true);
    if (data !== null) {
      void mutate(() => window.bridge.continueAgentReviewSession(data.session.id, {
        kind: data.session.status === 'STALE' ? 're-review' : 'continue',
        ...options,
      })).finally(() => setStarting(false));
      return;
    }
    void mutate(() => options === undefined
      ? window.bridge.startAgentReviewSession(prId)
      : window.bridge.startAgentReviewSession(prId, options)).finally(() => setStarting(false));
  }, [data, mutate, prId]);

  const roundAction = useCallback((roundId: string) => {
    if (data === null) return;
    const round = data.rounds.find(row => row.id === roundId);
    void mutate(() => window.bridge.continueAgentReviewSession(data.session.id, {
      kind: round?.status === 'CANCELLED' ? 're-review' : 'continue',
    }));
  }, [data, mutate]);

  const answerFinding = useCallback((findingId: string, text: string) => {
    void mutate(() => window.bridge.answerAgentReviewFinding(findingId, text));
  }, [mutate]);

  const cancelRound = useCallback((roundId: string) => {
    void mutate(() => window.bridge.cancelAgentReviewRound(roundId));
  }, [mutate]);

  const reopenFinding = useCallback((findingId: string) => {
    void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, { action: 'reopen' }));
  }, [mutate]);

  const dismissComment = useCallback((commentId: string) => {
    const findingId = data?.pr_comments.find(comment => comment.id === commentId)?.findingId;
    if (findingId === null || findingId === undefined) return;
    void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, { action: 'dismiss' }));
  }, [data, mutate]);

  const updateComment = useCallback((commentId: string, body: string) => {
    const findingId = data?.pr_comments.find(comment => comment.id === commentId)?.findingId;
    if (findingId === null || findingId === undefined) return;
    setData(current => current === null ? current : ({
      ...current,
      pr_comments: current.pr_comments.map(comment => comment.id === commentId ? { ...comment, body } : comment),
    }));
    const oldTimer = editTimers.current.get(commentId);
    if (oldTimer !== undefined) window.clearTimeout(oldTimer);
    editTimers.current.set(commentId, window.setTimeout(() => {
      editTimers.current.delete(commentId);
      void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, { action: 'editDraft', text: body }));
    }, 350));
  }, [data, mutate]);

  const toggleFinding = useCallback((findingId: string) => {
    const excluded = data?.findings.find(finding => finding.id === findingId)?.lifecycle_status === 'excluded';
    void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, {
      action: excluded ? 'include' : 'exclude',
    }));
  }, [data, mutate]);

  const submitReview = useCallback((verdict: ReviewVerdict) => {
    if (prId === null || data === null) return;
    const includedFindings = data.pr_comments.filter(comment => {
      const finding = data.findings.find(row => row.id === comment.findingId);
      return comment.findingId != null && comment.publishedAt === null && comment.dismissedAt === null
        && finding?.lifecycle_status !== 'excluded' && finding?.lifecycle_status !== 'dismissed';
    });
    const manualDrafts = bundle?.comments.filter(comment => comment.findingId == null
      && comment.origin === 'local' && comment.publishedAt === null
      && comment.resolvedAt === null && comment.dismissedAt === null) ?? [];
    const included = [...includedFindings, ...manualDrafts];
    setLoading(true);
    void Promise.resolve(beforePublish?.(verdict, included)).then(() => window.bridge.publishLocalPrReview(prId, {
      verdict,
      findingIds: includedFindings.flatMap(comment => comment.findingId == null ? [] : [comment.findingId]),
      comments: included.map(comment => comment.id),
    })).then(async () => {
      refreshPr();
      await load();
      setError(null);
    }).catch(cause => {
      setError(cause instanceof Error ? cause.message : String(cause));
    }).finally(() => setLoading(false));
  }, [beforePublish, bundle, data, load, prId, refreshPr]);

  const displayedBundle = useMemo(() => {
    if (bundle == null || data === null) return bundle;
    const commentIds = new Set(data.pr_comments.map(comment => comment.id));
    const eventIds = new Set(data.pr_timeline_events.map(event => event.id));
    return {
      ...bundle,
      comments: [...bundle.comments.filter(comment => !commentIds.has(comment.id)), ...data.pr_comments],
      timeline: [...bundle.timeline.filter(event => !eventIds.has(event.id)), ...data.pr_timeline_events],
    };
  }, [bundle, data]);

  const excludedFindings = useMemo(() => new Set(data?.findings
    .filter(finding => finding.lifecycle_status === 'excluded')
    .map(finding => finding.id) ?? []), [data]);
  const pendingComments = data?.pr_comments.filter(comment => {
    const finding = data.findings.find(row => row.id === comment.findingId);
    return comment.findingId != null && comment.publishedAt === null && comment.dismissedAt === null
      && finding?.lifecycle_status !== 'dismissed' && finding?.lifecycle_status !== 'dropped';
  }) ?? [];
  const latestRound = data?.rounds.at(-1);
  const latestRun = latestRound === undefined ? undefined : data?.runs.find(run => run.id === latestRound.agent_run_id);
  const head = bundle?.commits.at(-1)?.sha;
  const stale = data !== null && (data.session.status === 'STALE'
    || (head !== undefined && data.session.reviewed_head_commit !== head));
  const headerState: AgentReviewHeaderState = starting ? 'running' : data === null ? 'never'
    : stale ? 'stale'
      : latestRound?.status === 'RUNNING' || latestRun?.status === 'running' ? 'running' : 'done';

  return {
    data, displayedBundle, excludedFindings, pendingComments, latestRound, headerState, loading, error,
    startReview, updateComment, dismissComment, submitReview, answerFinding, roundAction, cancelRound, reopenFinding, toggleFinding,
    hasAgentComment: (commentId: string) => data?.pr_comments.some(comment => comment.id === commentId) === true,
  };
}
