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
import type { ReactNode } from 'react';
import type {
  DiffFileDto, ThreadCommitDto, ThreadMessageDto, TrunkTraceEventDto, WorkUnitTaskDto,
} from '../types';
import { TaskChangedFilesCard } from '../pages/TaskChangedFilesCard';
import {
  ActivityStrip, EventTimestamp, Headline, Round, Spine, SpineBreak, TaskCutNode, TaskFold, UserTurn, WorkFold,
} from '../ui/conv';
import type { Density, TaskStatus, ToolGroup } from '../ui/conv';
import { AskQuestionCard } from './AskQuestionCard';
import { MarkdownProse } from './MarkdownProse';
import { cardStatus, TERMINAL_TASK_STATUSES, toTaskCard } from './taskCardData';
import { taskLabel } from './taskLabel';
import {
  buildTrunkTimeline, extractImages, extractText, parsePermissionRequest, parseToolCall, trunkHeadline, trunkWork,
} from './trunkTimeline';
import type { TrunkRound, TrunkSummary } from './trunkTimeline';
import { PermissionCard } from './PermissionCard';
import type { PermissionDecideHandler } from './PermissionCard';
import { formatDuration } from './brain/format';

/** Short status word for a folded-but-not-done task's bar (tone 'running') —
 *  it has no completion summary yet, so this previews *why* it's folded. */
const FOLD_STATUS_LABEL: Record<TaskStatus, string> = {
  shipped: 'in review', pending: 'queued', review: 'awaiting review', paused: 'paused',
  errored: 'errored', foreground: 'in progress', closed: 'closed',
};

/** The raw tool-call input, for tools whose input the feed renders
 *  directly (AskUserQuestion's question/options payload). */
function toolCallInput(contentJson: string): unknown {
  try {
    return (JSON.parse(contentJson) as { input?: unknown }).input;
  }
  catch {
    return undefined;
  }
}

type AskHandling = {
  /** The one AskUserQuestion still awaiting an answer, or null. */
  pendingId: string | null;
  /** Sends the composed answer as the next user turn. */
  onAnswer?: (text: string) => void;
};

export type TrunkTaskArtifacts = {
  files: DiffFileDto[];
  commits: ThreadCommitDto[];
  onReview?: () => void;
  onUndo?: () => void;
};

/** Batch a round's work rows into folded sub-messages + tool activity strips,
 *  preserving order (consecutive tool calls collapse into one strip).
 *  AskUserQuestion tool calls surface as a question card instead of a strip
 *  row — the CLI runs headless, so this card IS the tool's UI. A pending
 *  `permission_request` surfaces as a clickable {@link PermissionCard} —
 *  the trunk's own approval gate, previously answerable only by waiting out
 *  the backend's timeout. */
function renderWork(
  rows: ThreadMessageDto[], full: boolean, ask: AskHandling, onDecidePermission?: PermissionDecideHandler,
): ReactNode[] {
  const out: ReactNode[] = [];
  let toolBatch: ThreadMessageDto[] = [];
  const flush = () => {
    if (toolBatch.length === 0) return;
    const byName = new Map<string, { label: string }[]>();
    for (const m of toolBatch) {
      const { name, summary } = parseToolCall(m.contentJson);
      const list = byName.get(name) ?? [];
      list.push({ label: summary.length > 0 ? `${name} — ${summary}` : name });
      byName.set(name, list);
    }
    const groups: ToolGroup[] = [...byName.entries()].map(([kind, r]) => ({ kind, rows: r }));
    out.push(<ActivityStrip key={`act-${toolBatch[0].id}`} groups={groups} forceOpen={full} />);
    toolBatch = [];
  };
  for (const m of rows) {
    if (m.type === 'permission_request') {
      flush();
      const { callId, toolName, summary } = parsePermissionRequest(m.contentJson);
      if (callId.length > 0 && onDecidePermission) {
        out.push(
          <PermissionCard key={m.id} permission={{ callId, toolName, summary }} onDecide={onDecidePermission} />,
        );
      }
      continue;
    }
    if (m.type === 'tool_call') {
      if (parseToolCall(m.contentJson).name === 'AskUserQuestion') {
        flush();
        out.push(
          <AskQuestionCard
            key={m.id}
            input={toolCallInput(m.contentJson)}
            onAnswer={m.id === ask.pendingId ? ask.onAnswer : undefined}
          />,
        );
        continue;
      }
      toolBatch.push(m);
      continue;
    }
    flush();
    const text = extractText(m.contentJson);
    if (text.trim().length === 0) continue;
    const error = m.type === 'error';
    out.push(
      <div
        className={`sp-submsg${m.type === 'thinking' ? ' sp-submsg--think' : ''}${error ? ' sp-submsg--error' : ''}`}
        key={m.id}
        role={error ? 'alert' : undefined}
      >
        <div className="sp-submsg__tx"><MarkdownProse text={text} /></div>
      </div>,
    );
  }
  flush();
  return out;
}

function parseTraceResult(contentJson: string): { callId: string; isError: boolean; text: string } {
  try {
    const content = JSON.parse(contentJson) as Record<string, unknown>;
    const output = content.output;
    let text = '';
    if (typeof output === 'string') text = output;
    else if (output !== undefined) text = JSON.stringify(output);
    return {
      callId: typeof content.callId === 'string' ? content.callId : '',
      isError: content.isError === true,
      text,
    };
  }
  catch {
    return { callId: '', isError: false, text: '' };
  }
}

/** Render provider logs without converting them into conversation rows. */
function renderTrace(rows: TrunkTraceEventDto[], full: boolean): ReactNode[] {
  const out: ReactNode[] = [];
  const results = new Map(rows
    .filter(row => row.type === 'tool_result')
    .map(row => {
      const result = parseTraceResult(row.contentJson);
      return [result.callId, result] as const;
    })
    .filter(([callId]) => callId.length > 0));
  let toolBatch: TrunkTraceEventDto[] = [];
  const flushTools = () => {
    if (toolBatch.length === 0) return;
    const byName = new Map<string, { label: string }[]>();
    for (const row of toolBatch) {
      const { name, summary } = parseToolCall(row.contentJson);
      let callId = '';
      try {
        const content = JSON.parse(row.contentJson) as Record<string, unknown>;
        callId = typeof content.callId === 'string' ? content.callId : '';
      }
      catch { /* malformed trace envelope */ }
      const failed = results.get(callId)?.isError === true;
      const detail = summary.length > 0 ? ` — ${summary}` : '';
      const list = byName.get(name) ?? [];
      list.push({ label: `${name}${detail}${failed ? ' · failed' : ''}` });
      byName.set(name, list);
    }
    const groups: ToolGroup[] = [...byName.entries()].map(([kind, grouped]) => ({ kind, rows: grouped }));
    out.push(<ActivityStrip key={`trace-act-${toolBatch[0].id}`} groups={groups} forceOpen={full} />);
    toolBatch = [];
  };
  for (const row of rows) {
    if (row.type === 'tool_call') {
      toolBatch.push(row);
      continue;
    }
    if (row.type === 'tool_result') {
      const result = parseTraceResult(row.contentJson);
      if (!result.isError) continue;
      flushTools();
      out.push(
        <div className="sp-submsg sp-submsg--error" key={row.id} role="alert">
          <div className="sp-submsg__tx">
            <MarkdownProse text={result.text.length > 0 ? result.text : 'Tool call failed.'} />
          </div>
        </div>,
      );
      continue;
    }
    flushTools();
    const text = extractText(row.contentJson);
    if (text.trim().length === 0) continue;
    const error = row.type === 'error';
    out.push(
      <div
        className={`sp-submsg${row.type === 'thinking' ? ' sp-submsg--think' : ''}${error ? ' sp-submsg--error' : ''}`}
        key={row.id}
        role={error ? 'alert' : undefined}
      >
        <div className="sp-submsg__tx"><MarkdownProse text={text} /></div>
      </div>,
    );
  }
  flushTools();
  return out;
}

function traceFailure(row: TrunkTraceEventDto): boolean {
  return row.type === 'error'
    || (row.type === 'tool_result' && parseTraceResult(row.contentJson).isError);
}

function workedFor(
  work: ThreadMessageDto[], headline: ThreadMessageDto | null, trace: TrunkTraceEventDto[] = [],
): string {
  const measuredMs = work.reduce((sum, row) => sum + Math.max(0, row.durationMs ?? 0), 0);
  const first = Math.min(...[...work, ...trace]
    .map(row => Date.parse(row.ts)).filter(Number.isFinite));
  const last = Date.parse(headline?.ts ?? work[work.length - 1]?.ts ?? trace[trace.length - 1]?.ts ?? '');
  const elapsedMs = Number.isNaN(first) || Number.isNaN(last) ? 0 : Math.max(0, last - first);
  return `Worked for ${formatDuration(Math.max(measuredMs, elapsedMs) / 1000)}`;
}

function BranchRailRow({ kind, children }: {
  kind: 'cut' | 'detail' | 'merge';
  children: ReactNode;
}) {
  return (
    <div className={`trunk-page-v2__branch-row trunk-page-v2__branch-row--${kind}`}>
      <div className="trunk-page-v2__branch-rail" aria-hidden>
        {kind === 'cut' && (
          <>
            <svg width="42" height="32" viewBox="0 0 42 32" fill="none">
              <path d="M14 2 C14 20, 30 12, 30 32" stroke="#8250df55" strokeWidth="2" />
            </svg>
            <span className="trunk-page-v2__branch-dot" />
            <span className="trunk-page-v2__branch-line" />
          </>
        )}
        {kind === 'detail' && <span className="trunk-page-v2__branch-line" />}
        {kind === 'merge' && (
          <>
            <svg width="42" height="28" viewBox="0 0 42 28" fill="none">
              <path d="M30 0 C30 16, 14 8, 14 26" stroke="#8250df55" strokeWidth="2" />
            </svg>
            <span className="trunk-page-v2__merge-dot" />
          </>
        )}
      </div>
      <div className="trunk-page-v2__branch-body">{children}</div>
    </div>
  );
}

function MergeRow({ task, completedAt }: { task: WorkUnitTaskDto; completedAt?: number }) {
  return (
    <div className="trunk-page-v2__merge-row">
      <span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="6" cy="6" r="2.4" />
          <circle cx="18" cy="18" r="2.4" />
          <path d="M6 8.5V12a6 6 0 0 0 6 6h3.4" />
        </svg>
      </span>
      <strong>Merged into trunk</strong>
      <small>
        {task.prNumber === null ? 'squash · CI green' : `PR #${task.prNumber} · squash · CI green`}
      </small>
      {completedAt !== undefined && Number.isFinite(completedAt) && (
        <time><EventTimestamp iso={new Date(completedAt).toISOString()} /></time>
      )}
    </div>
  );
}

function CommitRow({ commit }: { commit: ThreadCommitDto }) {
  return (
    <div className="trunk-page-v2__commit-row">
      Committed as <code>{commit.shortSha}</code> — <code>{commit.subject}</code>
    </div>
  );
}

function taskWorkLabel(task: WorkUnitTaskDto, summary: TrunkSummary | undefined): string {
  const startedAt = Date.parse(task.createdAt);
  const elapsed = summary === undefined || !Number.isFinite(startedAt)
    ? 0
    : Math.max(0, summary.ts - startedAt);
  return `Worked for ${formatDuration(elapsed / 1000)}`;
}

function TaskArtifactTrace({ artifacts }: { artifacts: TrunkTaskArtifacts }) {
  const groups: ToolGroup[] = [];
  if (artifacts.files.length > 0) {
    groups.push({
      kind: 'Edit',
      rows: artifacts.files.slice(0, 4).map(file => ({ label: file.filename })),
    });
  }
  if (artifacts.commits.length > 0) {
    groups.push({
      kind: 'Commit',
      rows: artifacts.commits.slice(0, 2).map(commit => ({
        label: `${commit.shortSha} — ${commit.subject}`,
      })),
    });
  }
  return <ActivityStrip groups={groups} filesChanged={artifacts.files.length} />;
}

/**
 * The trunk conversation feed on the timeline spine (M10). Reuses the brain
 * feed's rounds / work folds / tool activity strips, and adds the trunk
 * milestone family: task cuts as `TaskCutNode` peaks, plus an outline strip
 * that lists every cut and jumps to it. The trunk has no stages — its big
 * nodes are its outputs. Architecture/risk stay prose in the rounds until a
 * later milestone emits them structured (DISCOVERY-FINDINGS deferral).
 */
export function TrunkFeed({
  messages, tasks, density, onOpenTask, trailer, mergeReadyIds, onAnswerQuestion, onDecidePermission,
  artifactsByTaskId, tracesByRequestMessageId,
}: {
  messages: ThreadMessageDto[];
  tasks: WorkUnitTaskDto[];
  density: Density;
  onOpenTask: (taskId: string) => void;
  trailer?: ReactNode;
  /** Task ids with an open merge gate — drives the inline card's "Ready to
   *  merge" badge so it matches the Tasks-tab card. */
  mergeReadyIds?: ReadonlySet<string>;
  /** Sends an AskUserQuestion answer as the next user turn — makes the
   *  latest unanswered question card interactive. */
  onAnswerQuestion?: (text: string) => void;
  /** Answers a pending `permission_request` — makes the trunk's approval
   *  prompt (previously answerable only by waiting out the backend's
   *  timeout) an actual clickable card. */
  onDecidePermission?: PermissionDecideHandler;
  artifactsByTaskId?: ReadonlyMap<string, TrunkTaskArtifacts>;
  /** Provider trace remains a separate projection, joined only for display
   * by the stable typed request-message id. */
  tracesByRequestMessageId?: ReadonlyMap<string, TrunkTraceEventDto[]>;
}) {
  const full = density === 'full';
  const items = buildTrunkTimeline(messages, tasks);

  // The last AskUserQuestion with no user turn after it is still waiting
  // on the user — that one renders interactive; answered ones stay static.
  let pendingAskId: string | null = null;
  for (const m of messages) {
    if (m.type === 'tool_call' && parseToolCall(m.contentJson).name === 'AskUserQuestion') {
      pendingAskId = m.id;
    }
    else if (m.role === 'user' && m.type === 'text') {
      pendingAskId = null;
    }
  }
  const ask: AskHandling = { pendingId: pendingAskId, onAnswer: onAnswerQuestion };

  // Number autonomous rounds (R1, R2…) continuously across the whole feed,
  // including rounds folded inside a completed-task block.
  let autonomous = 0;
  const renderRound = (round: TrunkRound): ReactNode => {
    const tag = round.userTurn === null ? `R${(autonomous += 1)}` : undefined;
    const work = trunkWork(round);
    const trace = round.userTurn === null
      ? []
      : tracesByRequestMessageId?.get(round.userTurn.id) ?? [];
    const headline = trunkHeadline(round);
    // A question — or a permission prompt — awaiting the user must be
    // visible without un-folding.
    const holdsPendingAsk = ask.pendingId !== null && work.some(m => m.id === ask.pendingId);
    const holdsPendingPermission = work.some(m => m.type === 'permission_request');
    const failures = work.filter(m => m.type === 'error').length
      + trace.filter(traceFailure).length;
    return (
      <Round key={round.id} tag={tag}>
        {round.userTurn !== null && (
          <UserTurn
            quiet
            text={extractText(round.userTurn.contentJson)}
            timestamp={<EventTimestamp iso={round.userTurn.ts} />}
            threadId={round.userTurn.threadId}
            images={extractImages(round.userTurn.contentJson)}
            managedSkills={extractManagedSkills(round.userTurn.contentJson)}
            messageSeq={round.userTurn.seq}
          />
        )}
        {(work.length > 0 || trace.length > 0) && (
          <WorkFold
            label={workedFor(work, headline, trace)}
            failed={failures}
            forceOpen={full || holdsPendingAsk || holdsPendingPermission || failures > 0}
          >
            {renderTrace(trace, full)}
            {renderWork(work, full, ask, onDecidePermission)}
          </WorkFold>
        )}
        {headline !== null && (
          <Headline bare who="Agent" body={extractText(headline.contentJson)} />
        )}
      </Round>
    );
  };
  const renderCut = (t: WorkUnitTaskDto): ReactNode => {
    const card = toTaskCard(t, mergeReadyIds?.has(t.id) ?? false);
    return (
      <TaskCutNode
        key={t.id}
        id={`cut-${t.id}`}
        title={card.title}
        status={card.status}
        statusText={card.statusText}
        branch={card.branch}
        createdLabel={card.createdLabel}
        prNumber={card.prNumber}
        mergeReady={card.mergeReady}
        pr={card.pr}
        onOpen={() => onOpenTask(t.id)}
      />
    );
  };

  // Cut order is the only reliable boundary when tasks run concurrently.
  // Locked frame 1b keeps completed history compact above this feed, keeps
  // older still-running cuts as quiet fold rows, and leaves the newest cut
  // expanded on its branch rail. Inside that live detail only the ordinary
  // `Worked for …` trace rows start collapsed.
  const summaryByTaskId = new Map<string, TrunkSummary>();
  for (const item of items) {
    if (item.kind === 'summary' && item.summary.taskId !== null) {
      summaryByTaskId.set(item.summary.taskId, item.summary);
    }
  }
  const cutItems = items.filter(item => item.kind === 'cut');
  // Only the newest task still in flight stays expanded on the branch rail. A
  // finished task folds into the compact top history instead — so once a task
  // completes, its whole cut collapses out of the feed. When every task is
  // finished, nothing stays expanded here.
  const latestTaskId = [...cutItems].reverse()
    .find(item => !TERMINAL_TASK_STATUSES.has(item.cut.task.status))?.cut.task.id;

  const nodes: ReactNode[] = [];
  let segment: ReactNode[] = [];
  for (const item of items) {
    if (item.kind === 'summary') continue;
    if (item.kind === 'round') {
      segment.push(renderRound(item.round));
      continue;
    }
    const { task } = item.cut;
    const cutCard = renderCut(task);
    const s = summaryByTaskId.get(task.id);
    const status = cardStatus(task.status);

    if (task.id === latestTaskId) {
      nodes.push(...segment);
      segment = [];
      nodes.push(
        <BranchRailRow key={`cut-${task.id}`} kind="cut">{cutCard}</BranchRailRow>,
      );
      const artifacts = artifactsByTaskId?.get(task.id);
      const hasArtifactDetails = artifacts !== undefined
        && (artifacts.files.length > 0 || artifacts.commits.length > 0);
      if (hasArtifactDetails) {
        nodes.push(
          <BranchRailRow key={`work-${task.id}`} kind="detail">
            <WorkFold label={taskWorkLabel(task, s)}>
              <TaskArtifactTrace artifacts={artifacts} />
            </WorkFold>
          </BranchRailRow>,
        );
      }
      if (s !== undefined && s.text.trim().length > 0) {
        nodes.push(
          <BranchRailRow key={`summary-${task.id}`} kind="detail">
            <Headline bare who="Agent" body={s.text} />
          </BranchRailRow>,
        );
      }
      if (artifacts !== undefined && artifacts.files.length > 0) {
        nodes.push(
          <BranchRailRow key={`files-${task.id}`} kind="detail">
            <TaskChangedFilesCard
              files={artifacts.files}
              commitCount={artifacts.commits.length}
              verb="Edited"
              onUndo={artifacts.onUndo}
              onReview={artifacts.onReview}
            />
          </BranchRailRow>,
        );
      }
      const commit = artifacts?.commits[0];
      if (commit !== undefined) {
        nodes.push(
          <BranchRailRow key={`commit-${task.id}`} kind="detail">
            <CommitRow commit={commit} />
          </BranchRailRow>,
        );
      }
      if (task.status === 'COMPLETED' && task.prState?.toUpperCase() === 'MERGED') {
        nodes.push(
          <BranchRailRow key={`merge-${task.id}`} kind="merge">
            <MergeRow task={task} completedAt={s?.ts} />
          </BranchRailRow>,
        );
      }
      continue;
    }

    // Finished tasks are already rendered as the compact merged-task rows
    // immediately below the trunk head by TrunkPage — drop their feed block.
    if (s !== undefined || TERMINAL_TASK_STATUSES.has(task.status)) {
      segment = [];
      continue;
    }

    nodes.push(
      <TaskFold
        key={`fold-${task.id}`}
        title={taskLabel(task)}
        tone={s !== undefined ? 'done' : 'running'}
        status={status}
        statusLabel={task.status === 'COMPLETED' ? 'merged' : FOLD_STATUS_LABEL[status]}
        forceOpen={full}
      >
        {segment}
        {cutCard}
        {s !== undefined && s.text.trim().length > 0 && <Headline bare who="Agent" body={s.text} />}
      </TaskFold>,
    );
    segment = [];
  }
  // The trailing segment is live conversation that hasn't been cut into a
  // task yet — mark the seam so it doesn't read as nested under the last
  // folded task sitting right above it.
  if (nodes.length > 0 && segment.length > 0) nodes.push(<SpineBreak key="trailing-break" />);
  nodes.push(...segment);

  return (
    <>
      <Spine variant="trunk">{nodes}</Spine>
      {trailer}
    </>
  );
}

function extractManagedSkills(contentJson: string): string[] {
  try {
    const parsed = JSON.parse(contentJson) as { managedSkills?: unknown };
    return Array.isArray(parsed.managedSkills)
      ? parsed.managedSkills.filter((s): s is string => typeof s === 'string' && s.length > 0)
      : [];
  }
  catch {
    return [];
  }
}
