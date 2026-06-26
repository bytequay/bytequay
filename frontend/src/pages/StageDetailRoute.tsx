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
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useStageDetailData } from '../threads/brain/useStageDetailData';
import { useBrainViewData } from '../threads/brain/useBrainViewData';
import { usePendingShipProposal, proposalAction } from '../threads/usePendingShipProposal';
import { useThreadStream } from '../threads/useThreadStream';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import { MarkReadyPrompt } from '../threads/MarkReadyPrompt';
import { CiStatusPanel } from './CiStatusPanel';
import { PaneDiff } from '../diff/PaneDiff';
import { DiffFileTreePane } from '../diff/DiffFileTreePane';
import { statusBadge } from '../diffStatusBadge';
import { PRTabContent } from '../ui/pane/tabs';
import type { CommentThreadData, PRMetaChip } from '../ui/pane/tabs';
import type { DiffFileDto } from '../types';
import type { StageType } from '../types/brainView';
import { Conv, EventRow, Working } from '../ui/conv';
import { DetailsTabContent } from '../ui/pane';
import { planTab } from './planTab';
import { stageRow } from './stageConversationRow';
import { StageDetailPage } from './StageDetailPage';
import type { StageKind } from './StageDetailPage';

const KIND: Partial<Record<StageType, StageKind>> = {
  PLAN_STAGE: 'plan',
  DEVELOPMENT_STAGE: 'dev',
  CI_FIXING_STAGE: 'ci-fix',
  REVIEW_MONITOR_STAGE: 'comments',
  CLEANUP_STAGE: 'cleanup',
};

/**
 * Data adapter mounting the V3 {@link StageDetailPage} on the live stage
 * detail data. Maps the stage transcript → conversation (agent turns,
 * tool blocks, your steering, iteration markers) and wires the composer to
 * the stage's agent via {@code steerStage}. The right pane carries the full
 * Plan · Changes · PR · Files · Details strip: Changes/Files render the
 * task's cumulative diff, the CI-fix stage shows the live CI check card
 * above the diff, and the PR tab surfaces the pull request + its review
 * comment threads.
 */
export function StageDetailRoute({
  threadId, taskId, stageId, onOpenCode, onOpenStage,
}: {
  threadId: string;
  taskId: string;
  stageId: string;
  onOpenCode: () => void;
  /** Jump to another stage — used after approving the plan, which closes
   *  this Plan stage and opens the Development stage. */
  onOpenStage?: (stageId: string) => void;
}) {
  const { data, refresh } = useStageDetailData(stageId);
  const shipProposal = usePendingShipProposal(threadId, taskId);
  // The plan card lives on the brain view; surface it on the Plan stage so
  // the plan (and its Approve action) shows here too, not only on the brain.
  const { data: brain, pollFast } = useBrainViewData(taskId);
  const plan = brain.rightRail.plan;
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  // The PR-tab Add-comment box (frame 7). Per the no-auto-post rule, a typed
  // comment is handed to the dev agent to post — it parks the publish for the
  // user's approval through the normal gate rather than posting directly.
  const [prComment, setPrComment] = useState('');

  const stageKind: StageKind = data ? KIND[data.stage.type] ?? 'dev' : 'dev';
  const state = data?.stage.state;
  const realtimeCi = data?.realtimeCi ?? null;
  const prNumber = data?.task.prNumber ?? null;
  const branch = data?.task.branch;
  const repoFullName = data?.task.repoFullName;

  // Changes / Files / PR tabs only apply to the work stages — the Plan stage
  // is a read-only conversation artifact with no diff of its own.
  const hasDiff = stageKind !== 'plan';

  // ── Right-pane data: the task's cumulative diff ─────────────────────────
  const [files, setFiles] = useState<DiffFileDto[] | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());

  useEffect(() => {
    if (!hasDiff) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTaskCumulativeDiff === undefined) return;
    let cancelled = false;
    void bridge.getTaskCumulativeDiff(threadId)
      .then(list => {
        if (cancelled) return;
        setFiles(list);
        setSelectedPath(prev => (prev !== null && list.some(f => f.filename === prev)
          ? prev : list[0]?.filename ?? null));
      })
      .catch(() => { if (!cancelled) setFiles([]); });
    return () => { cancelled = true; };
  }, [threadId, hasDiff]);

  const toggleDir = useCallback((path: string) => {
    setCollapsedDirs(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  const openPr = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (realtimeCi !== null) { void bridge?.openExternal(realtimeCi.prUrl); return; }
    if (repoFullName != null && prNumber !== null) {
      void bridge?.openExternal(`https://github.com/${repoFullName}/pull/${prNumber}`);
    }
  }, [realtimeCi, repoFullName, prNumber]);

  const approvePlan = () => {
    if (plan === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.approvePlan(plan.planStageId)
      .then(result => { pollFast(); onOpenStage?.(result.devStageId); })
      .catch(() => { /* poll reconciles */ });
  };

  const postPrComment = useCallback(() => {
    const body = prComment.trim();
    if (body.length === 0) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.steerStage(
      stageId,
      `Please post this comment on the pull request (park it for my approval as usual):\n\n${body}`)
      .then(() => { setPrComment(''); refresh(); })
      .catch(() => { /* poll reconciles */ });
  }, [prComment, stageId, refresh]);

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

  // Live stream of the agent working this stage: its text appears
  // token-by-token (and a non-delta event refreshes the canonical
  // transcript, which clears the live buffer). This is what makes the stage
  // feel alive between the periodic poll snapshots.
  const { liveText } = useThreadStream(
    threadId, state === 'CLOSED' ? 'COMPLETED' : 'RUNNING', refresh);

  // Poll the thread's run state. This is the signal that stays true through a
  // long, quiet tool call (e.g. a multi-minute build) where no text streams
  // and the stage-state poll lags — so the working indicator doesn't blink
  // off mid-turn. Stops once the stage is closed.
  const [threadRunning, setThreadRunning] = useState(false);
  useEffect(() => {
    if (state === 'CLOSED') { setThreadRunning(false); return; }
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTask === undefined) return;
    let cancelled = false;
    const poll = () => {
      void bridge.getTask(threadId)
        .then(t => { if (!cancelled) setThreadRunning(t.status === 'RUNNING'); })
        .catch(() => { /* transient; next tick retries */ });
    };
    poll();
    const id = window.setInterval(poll, 3000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, [threadId, state]);

  // Show the working indicator whenever a turn is executing — the thread is
  // RUNNING, the stage is ACTIVE, the user just steered, or text is streaming
  // in. Track when the working period began so the indicator can tick an
  // elapsed counter (a long, quiet turn shouldn't read as dead).
  const working = busy || threadRunning || state === 'ACTIVE' || liveText.length > 0;
  const [workingSince, setWorkingSince] = useState<number | null>(null);
  useEffect(() => {
    setWorkingSince(prev => (working ? prev ?? Date.now() : null));
  }, [working]);

  const conversation = (
    <Conv>
      {data?.conversation.map(stageRow)}
      {liveText.length > 0 && <EventRow kind="agent" who="Agent" markdown={liveText} />}
      {shipProposal !== null && (proposalAction(shipProposal) === 'mark_ready'
        ? <MarkReadyPrompt onReview={onOpenCode} />
        : <ShipReviewPrompt onReview={onOpenCode} />)}
      {working && liveText.length === 0 && (
        <Working
          label="Agent is working…"
          since={workingSince ?? undefined}
          onStop={() => {
            const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
            void bridge?.interruptTask(threadId).then(refresh).catch(() => { /* poll reconciles */ });
          }}
        />
      )}
    </Conv>
  );

  // ── Right-pane tab nodes ────────────────────────────────────────────────
  const changesNode = (
    <>
      {stageKind === 'ci-fix' && realtimeCi !== null && (
        <CiStatusPanel ci={realtimeCi} onOpenGitHub={openPr} />
      )}
      {files === null ? (
        <div className="pane-empty">Loading diff…</div>
      ) : files.length === 0 ? (
        <div className="pane-empty">No changes in this task yet.</div>
      ) : (
        <PaneDiff files={files} />
      )}
    </>
  );

  const filesNode = (
    <DiffFileTreePane<DiffFileDto>
      files={files}
      error={null}
      mode="tree"
      pathOf={(f) => f.filename}
      statusBadgeOf={(f) => statusBadge(f.status)}
      selectedPath={selectedPath}
      onSelectPath={setSelectedPath}
      collapsedDirs={collapsedDirs}
      onToggleDir={toggleDir}
    />
  );

  // PR tab content — built from the stage-detail `pr` block (status, branch
  // flow, reviewers, labels, CI check summary, and the per-line review
  // threads with the reviewer's root comment + the agent's reply).
  const pr = data?.pr ?? null;
  const threads: CommentThreadData[] = useMemo(() => (pr?.threads ?? []).map(t => {
    const root = t.messages[0];
    const reply = t.messages.length > 1 ? t.messages[t.messages.length - 1] : undefined;
    return {
      id: t.id,
      author: root?.author ?? 'reviewer',
      file: t.file === null ? undefined : (t.line !== null ? `${t.file}:${t.line}` : t.file),
      status: t.resolved ? 'resolved' as const : 'open' as const,
      body: root?.body ?? '',
      reply: reply !== undefined ? { src: reply.author, text: reply.body } : undefined,
    };
  }), [pr]);
  const openThreadCount = useMemo(() => threads.filter(t => t.status === 'open').length, [threads]);
  const prMetaChips: PRMetaChip[] = useMemo(() => {
    if (pr === null) return [];
    const chips: PRMetaChip[] = [];
    if (pr.reviewers.length > 0) chips.push({ icon: '👥', label: 'Reviewers', count: pr.reviewers.length });
    for (const label of pr.labels) chips.push({ label });
    return chips;
  }, [pr]);

  const prNode = pr !== null ? (
    <PRTabContent
      title={data?.task.title}
      prNumber={pr.number}
      status={pr.status}
      statusLabel={pr.status === 'merged' ? 'Merged' : pr.status === 'draft' ? 'Draft' : 'Open · ready for review'}
      headBranch={pr.headRef ?? branch}
      baseBranch={pr.baseRef ?? undefined}
      metaChips={prMetaChips}
      checks={pr.checks.total > 0 ? pr.checks : undefined}
      threads={threads}
      threadsHeader={threads.length > 0 ? `Open threads · ${openThreadCount}` : undefined}
      commentValue={prComment}
      onCommentChange={setPrComment}
      onAddComment={state !== 'CLOSED' ? postPrComment : undefined}
    />
  ) : null;

  const totalAdds = files?.reduce((n, f) => n + f.additions, 0) ?? 0;
  const totalDels = files?.reduce((n, f) => n + f.deletions, 0) ?? 0;

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
      tabCounts={{
        changes: files !== null && files.length > 0 ? { count: files.length, countColor: 'acc' } : undefined,
        pr: prNumber !== null ? { count: prNumber, countColor: 'muted' } : undefined,
      }}
      paneMeta={stageKind === 'ci-fix' ? {
        left: `CI fix · iter ${data?.stage.iterationCount ?? 0}`
          + (data?.stage.config.autoPushBudget != null
            ? ` · auto-push ${data.stage.config.autoPushBudget.used}/${data.stage.config.autoPushBudget.limit}`
            : ''),
        right: (
          <>
            {`+${totalAdds} −${totalDels} · `}
            <span style={{ color: 'var(--accent)', cursor: 'pointer' }} onClick={openPr}>View on GitHub</span>
          </>
        ),
      } : undefined}
      tabs={{
        // The plan is the task's, not the stage's — surface it on every
        // stage (Dev / CI-fix / …) so the user can re-read it from anywhere,
        // not only the Plan stage. Approve only while it's still awaiting.
        plan: plan !== null
          ? planTab(plan, plan.state === 'awaiting' ? approvePlan : undefined)
          : undefined,
        changes: hasDiff ? changesNode : undefined,
        pr: prNode ?? undefined,
        files: hasDiff ? filesNode : undefined,
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
