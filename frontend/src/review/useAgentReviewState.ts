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
import { isPendingLocalComment } from '../diff/DiffInlineComments';
import type { ReviewVerdict } from '../pages/SubmitReviewDrawer';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import type { AgentReviewHeaderState, AgentReviewStartOptions } from './AgentReviewHeaderAction';
import type { AgentReviewData } from './agentReviewTypes';

type ReviewIdentity = { prId: string | null; generation: number };
type PendingEdit = {
  findingId: string;
  body: string;
  origin: ReviewIdentity;
  timer: number | null;
};

function keepPendingEditBodies(
  next: AgentReviewData | null,
  edits: Map<string, PendingEdit>,
  current: ReviewIdentity,
): AgentReviewData | null {
  if (next === null || edits.size === 0) return next;
  return {
    ...next,
    pr_comments: next.pr_comments.map(comment => {
      const edit = edits.get(comment.id);
      return edit === undefined || edit.origin.prId !== current.prId
        || edit.origin.generation !== current.generation
        ? comment
        : { ...comment, body: edit.body };
    }),
  };
}

/** Live AgentReview state shared by the PR page, Files view, and round
 * page. The backend aggregate is the sole source of review artifacts; this
 * hook only holds temporary UI state while mutations are in flight. */
export function useAgentReviewState(
  bundle: LocalPRBundle | null | undefined,
  refreshPr: () => void,
  beforePublish?: (verdict: ReviewVerdict, comments: LocalPRComment[]) => Promise<void>,
  workspaceId?: string | null,
) {
  const prId = bundle?.pr.id ?? null;
  const [storedData, setStoredData] = useState<AgentReviewData | null>(null);
  const [loading, setLoading] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [publishedCommentIds, setPublishedCommentIds] = useState(() => new Set<string>());
  const pendingEdits = useRef(new Map<string, PendingEdit>());
  const identity = useRef({ prId, generation: 0 });
  const dataOrigin = useRef<ReviewIdentity | null>(null);
  const loadSequence = useRef(0);
  const mutationStarted = useRef(0);
  const mutationCompleted = useRef(0);
  const pendingMutations = useRef(0);
  const concurrentMutationBatch = useRef(false);
  if (identity.current.prId !== prId) {
    identity.current = { prId, generation: identity.current.generation + 1 };
    loadSequence.current += 1;
    mutationStarted.current = 0;
    mutationCompleted.current = 0;
    pendingMutations.current = 0;
    concurrentMutationBatch.current = false;
  }
  const data = dataOrigin.current?.prId === identity.current.prId
      && dataOrigin.current.generation === identity.current.generation
    ? storedData
    : null;

  const load = useCallback(async (preserveOnNull = false) => {
    if (prId === null || window.bridge?.getAgentReview === undefined) return;
    const generation = identity.current.generation;
    const sequence = ++loadSequence.current;
    const startedEpoch = mutationStarted.current;
    const completedEpoch = mutationCompleted.current;
    try {
      const next = await window.bridge.getAgentReview(prId);
      if (identity.current.prId !== prId || identity.current.generation !== generation
        || loadSequence.current !== sequence || mutationStarted.current !== startedEpoch
        || mutationCompleted.current !== completedEpoch) return;
      if (next === null && preserveOnNull) return;
      dataOrigin.current = { ...identity.current };
      setStoredData(keepPendingEditBodies(next, pendingEdits.current, identity.current));
      setError(null);
    }
    catch (cause) {
      if (identity.current.prId !== prId || identity.current.generation !== generation
        || loadSequence.current !== sequence || mutationStarted.current !== startedEpoch
        || mutationCompleted.current !== completedEpoch) return;
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [prId]);

  useEffect(() => {
    pendingEdits.current.forEach(edit => {
      if (edit.timer !== null) window.clearTimeout(edit.timer);
    });
    pendingEdits.current.clear();
    dataOrigin.current = null;
    setStoredData(null);
    setError(null);
    setStarting(false);
    setPublishedCommentIds(new Set());
    if (prId === null) return;
    const generation = identity.current.generation;
    setLoading(true);
    void load().finally(() => {
      if (identity.current.prId === prId && identity.current.generation === generation
        && pendingMutations.current === 0) setLoading(false);
    });
  }, [load, prId]);

  const running = data?.rounds.some(
    round => round.status === 'RUNNING' || round.status === 'QUEUED',
  ) === true || data?.runs.some(run => run.status === 'running') === true;
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval((): void => { void load(); }, 1_000);
    return () => window.clearInterval(timer);
  }, [load, running]);

  useEffect(() => () => {
    pendingEdits.current.forEach(edit => {
      if (edit.timer !== null) window.clearTimeout(edit.timer);
    });
  }, []);

  const mutate = useCallback(async (operation: () => Promise<AgentReviewData>) => {
    const generation = identity.current.generation;
    const requestPrId = identity.current.prId;
    const mutation = ++mutationStarted.current;
    pendingMutations.current += 1;
    if (pendingMutations.current > 1) concurrentMutationBatch.current = true;
    setLoading(true);
    try {
      const next = await operation();
      if (identity.current.prId !== requestPrId || identity.current.generation !== generation
        || mutation !== mutationStarted.current) return { data: null, succeeded: true };
      const current = keepPendingEditBodies(next, pendingEdits.current, identity.current);
      dataOrigin.current = { ...identity.current };
      setStoredData(current);
      setError(null);
      return { data: current, succeeded: true };
    }
    catch (cause) {
      if (identity.current.prId !== requestPrId || identity.current.generation !== generation
        || mutation !== mutationStarted.current) return { data: null, succeeded: false };
      setError(cause instanceof Error ? cause.message : String(cause));
      return { data: null, succeeded: false };
    }
    finally {
      if (identity.current.prId === requestPrId && identity.current.generation === generation) {
        mutationCompleted.current = Math.max(mutationCompleted.current, mutation);
        pendingMutations.current -= 1;
        if (pendingMutations.current === 0) {
          if (concurrentMutationBatch.current) {
            // A later mutation may have completed before an older one. Its
            // response cannot include that older write, so reconcile once all
            // concurrent writes have settled. A single response is already
            // authoritative and does not need an immediate duplicate read.
            concurrentMutationBatch.current = false;
            void load().finally(() => {
              if (identity.current.prId === requestPrId
                && identity.current.generation === generation
                && pendingMutations.current === 0) setLoading(false);
            });
          }
          else setLoading(false);
        }
      }
    }
  }, [load]);

  const startReview = useCallback((options?: AgentReviewStartOptions) => {
    if (prId === null) return;
    const origin = { ...identity.current };
    setStarting(true);
    const finish = () => {
      if (identity.current.prId === origin.prId
        && identity.current.generation === origin.generation) setStarting(false);
    };
    if (data !== null) {
      void mutate(() => window.bridge.continueAgentReview(data.review.id, {
        kind: data.review.status === 'STALE' ? 're-review' : 'continue',
        ...options,
      })).finally(finish);
      return;
    }
    void mutate(() => window.bridge.startAgentReview(prId, {
      ...options,
      workspaceId: workspaceId ?? undefined,
    })).finally(finish);
  }, [data, mutate, prId, workspaceId]);

  const roundAction = useCallback((roundId: string) => {
    if (data === null) return;
    const round = data.rounds.find(row => row.id === roundId);
    void mutate(() => window.bridge.continueAgentReview(data.review.id, {
      kind: round?.status === 'CANCELLED' ? 're-review' : 'continue',
    }));
  }, [data, mutate]);

  const startRound = useCallback(async (seed: string, costCapCents?: number): Promise<boolean> => {
    if (data === null) return false;
    const result = await mutate(() => window.bridge.continueAgentReview(data.review.id, {
      kind: 'continuation', seed, costCapCents,
    }));
    return result.succeeded;
  }, [data, mutate]);

  const sendRoundMessage = useCallback(async (roundId: string, target: string, text: string): Promise<boolean> => {
    const result = await mutate(() => window.bridge.sendAgentReviewRoundMessage(roundId, { target, text }));
    return result.succeeded;
  }, [mutate]);

  const updateRoundBudget = useCallback(async (roundId: string, costCapCents: number): Promise<boolean> => {
    const result = await mutate(() => window.bridge.updateAgentReviewRoundBudget(roundId, { costCapCents }));
    return result.succeeded;
  }, [mutate]);

  const answerFinding = useCallback(async (findingId: string, text: string): Promise<boolean> => {
    const result = await mutate(() => window.bridge.answerAgentReviewFinding(findingId, text));
    return result.succeeded;
  }, [mutate]);

  const cancelRound = useCallback((roundId: string) => {
    void mutate(() => window.bridge.cancelAgentReviewRound(roundId));
  }, [mutate]);

  const reopenFinding = useCallback((findingId: string) => {
    void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, { action: 'reopen' }));
  }, [mutate]);

  const setFindingResolved = useCallback(async (findingId: string, resolved: boolean): Promise<boolean> => {
    const result = await mutate(() => window.bridge.mutateAgentReviewFinding(findingId, {
      action: resolved ? 'resolve' : 'reopen',
    }));
    return result.succeeded;
  }, [mutate]);

  const dismissComment = useCallback((commentId: string) => {
    const findingId = data?.pr_comments.find(comment => comment.id === commentId)?.findingId;
    if (findingId === null || findingId === undefined) return;
    const edit = pendingEdits.current.get(commentId);
    if (edit?.timer !== null && edit?.timer !== undefined) window.clearTimeout(edit.timer);
    pendingEdits.current.delete(commentId);
    void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, { action: 'dismiss' }));
  }, [data, mutate]);

  const updateComment = useCallback((commentId: string, body: string) => {
    const findingId = data?.pr_comments.find(comment => comment.id === commentId)?.findingId;
    if (findingId === null || findingId === undefined) return;
    setStoredData(current => current === null ? current : ({
      ...current,
      pr_comments: current.pr_comments.map(comment => comment.id === commentId ? { ...comment, body } : comment),
    }));
    const oldEdit = pendingEdits.current.get(commentId);
    if (oldEdit?.timer !== null && oldEdit?.timer !== undefined) window.clearTimeout(oldEdit.timer);
    const origin = { ...identity.current };
    const edit: PendingEdit = { findingId, body, origin, timer: null };
    edit.timer = window.setTimeout(() => {
      if (pendingEdits.current.get(commentId) !== edit) return;
      edit.timer = null;
      if (identity.current.prId !== origin.prId
        || identity.current.generation !== origin.generation) return;
      void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, { action: 'editDraft', text: body }))
        .then(result => {
          if (result.succeeded && pendingEdits.current.get(commentId) === edit) {
            pendingEdits.current.delete(commentId);
          }
        });
    }, 350);
    pendingEdits.current.set(commentId, edit);
  }, [data, mutate]);

  const toggleFinding = useCallback((findingId: string) => {
    const included = data?.findings.find(finding => finding.id === findingId)?.lifecycle_status === 'included';
    void mutate(() => window.bridge.mutateAgentReviewFinding(findingId, {
      action: included ? 'exclude' : 'include',
    }));
  }, [data, mutate]);

  const submitReview = useCallback((verdict: ReviewVerdict) => {
    if (prId === null || data === null) return;
    const includedFindings = data.pr_comments.filter(comment => {
      const finding = data.findings.find(row => row.id === comment.findingId);
      return comment.parentCommentId === null && comment.findingId != null
        && !publishedCommentIds.has(comment.id) && isPendingLocalComment(comment)
        && finding?.lifecycle_status === 'included';
    });
    const manualDrafts = bundle?.comments.filter(comment => comment.findingId == null
      && comment.parentCommentId === null
      && !publishedCommentIds.has(comment.id)
      && isPendingLocalComment(comment)) ?? [];
    const included = [...includedFindings, ...manualDrafts];
    const origin = { ...identity.current };
    void mutate(async () => {
      const assertOrigin = () => {
        if (identity.current.prId !== origin.prId
          || identity.current.generation !== origin.generation) {
          throw new Error('pull request changed while publishing review');
        }
      };
      for (const comment of includedFindings) {
        const edit = pendingEdits.current.get(comment.id);
        if (edit === undefined) continue;
        if (edit.timer !== null) window.clearTimeout(edit.timer);
        edit.timer = null;
        assertOrigin();
        await window.bridge.mutateAgentReviewFinding(edit.findingId, {
          action: 'editDraft', text: edit.body,
        });
        if (pendingEdits.current.get(comment.id) === edit) pendingEdits.current.delete(comment.id);
      }
      assertOrigin();
      await beforePublish?.(verdict, included);
      assertOrigin();
      await window.bridge.publishLocalPrReview(prId, {
        verdict,
        findingIds: includedFindings.flatMap(comment => comment.findingId == null ? [] : [comment.findingId]),
        comments: included.map(comment => comment.id),
      });
      assertOrigin();
      const publishedAt = Date.now();
      setPublishedCommentIds(current => new Set([
        ...current,
        ...included.map(comment => comment.id),
      ]));
      refreshPr();
      return {
        ...data,
        pr_comments: data.pr_comments.map(comment => includedFindings.some(row => row.id === comment.id)
          ? { ...comment, publishedAt }
          : comment),
      };
    }).then(result => {
      if (result.succeeded && identity.current.prId === origin.prId
        && identity.current.generation === origin.generation) void load(true);
    });
  }, [beforePublish, bundle, data, load, mutate, prId, publishedCommentIds, refreshPr]);

  const displayedBundle = useMemo(() => {
    if (bundle == null || data === null) return bundle;
    const commentIds = new Set(data.pr_comments.map(comment => comment.id));
    const eventIds = new Set(data.pr_timeline_events.map(event => event.id));
    return {
      ...bundle,
      comments: [
        ...bundle.comments.filter(comment => !commentIds.has(comment.id)).map(comment =>
          publishedCommentIds.has(comment.id) && comment.publishedAt === null
            ? { ...comment, publishedAt: Date.now() }
            : comment),
        ...data.pr_comments,
      ],
      timeline: [...bundle.timeline.filter(event => !eventIds.has(event.id)), ...data.pr_timeline_events],
    };
  }, [bundle, data, publishedCommentIds]);

  const excludedFindings = useMemo(() => new Set(data?.findings
    .filter(finding => finding.lifecycle_status !== 'included')
    .map(finding => finding.id) ?? []), [data]);
  const agentPendingComments = data?.pr_comments.filter(comment => {
    const finding = data.findings.find(row => row.id === comment.findingId);
    return comment.findingId != null && !publishedCommentIds.has(comment.id) && isPendingLocalComment(comment)
      && finding?.lifecycle_status !== 'dismissed' && finding?.lifecycle_status !== 'dropped';
  }) ?? [];
  const manualDrafts = data === null ? [] : bundle?.comments.filter(comment => comment.findingId == null
    && comment.parentCommentId === null
    && !publishedCommentIds.has(comment.id)
    && isPendingLocalComment(comment)) ?? [];
  const pendingComments = [...agentPendingComments, ...manualDrafts];
  const latestRound = data?.rounds.reduceRight<AgentReviewData['rounds'][number] | undefined>(
    (selected, round) => selected ?? (round.status === 'RUNNING' || round.status === 'QUEUED' ? round : undefined),
    undefined,
  ) ?? data?.rounds.at(-1);
  const latestRoundNumber = latestRound === undefined || data === null ? 1 : data.rounds.indexOf(latestRound) + 1;
  const latestRun = latestRound === undefined ? undefined : data?.runs.find(run => run.id === latestRound.agent_run_id);
  const head = bundle?.commits.at(-1)?.sha;
  const stale = data !== null && (data.review.status === 'STALE'
    || (head !== undefined && data.review.reviewed_head_commit !== head));
  const headerState: AgentReviewHeaderState = starting ? 'running' : data === null ? 'never'
    : stale ? 'stale'
      : latestRound?.status === 'RUNNING' || latestRound?.status === 'QUEUED'
        || latestRun?.status === 'running' ? 'running' : 'done';

  return {
    data, displayedBundle, excludedFindings, pendingComments, latestRound, latestRoundNumber, headerState, loading, error,
    startReview, startRound, sendRoundMessage, updateRoundBudget,
    updateComment, dismissComment, submitReview, answerFinding, roundAction, cancelRound, reopenFinding, setFindingResolved, toggleFinding,
    hasAgentComment: (commentId: string) => data?.pr_comments.some(comment => comment.id === commentId) === true,
  };
}
