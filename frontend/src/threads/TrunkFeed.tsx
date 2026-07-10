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
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';
import {
  ActivityStrip, Headline, Round, Spine, SpineBreak, TaskCutNode, TaskFold, UserTurn, WorkFold,
} from '../ui/conv';
import type { Density, TaskStatus, ToolGroup } from '../ui/conv';
import { AskQuestionCard } from './AskQuestionCard';
import { MarkdownProse } from './MarkdownProse';
import { EventTimestamp } from '../ui/conv';
import { cardStatus, toTaskCard } from './taskCardData';
import { taskLabel } from './taskLabel';
import {
  buildTrunkTimeline, extractImages, extractText, parsePermissionRequest, parseToolCall, trunkHeadline, trunkWork,
} from './trunkTimeline';
import type { TrunkRound, TrunkSummary } from './trunkTimeline';
import { PermissionCard } from './PermissionCard';
import type { PermissionDecideHandler } from './PermissionCard';

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
    out.push(
      <div className={`sp-submsg${m.type === 'thinking' ? ' sp-submsg--think' : ''}`} key={m.id}>
        <div className="sp-submsg__tx"><MarkdownProse text={text} /></div>
      </div>,
    );
  }
  flush();
  return out;
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
    const headline = trunkHeadline(round);
    // A question — or a permission prompt — awaiting the user must be
    // visible without un-folding.
    const holdsPendingAsk = ask.pendingId !== null && work.some(m => m.id === ask.pendingId);
    const holdsPendingPermission = work.some(m => m.type === 'permission_request');
    return (
      <Round key={round.id} tag={tag}>
        {round.userTurn !== null && (
          <UserTurn
            text={extractText(round.userTurn.contentJson)}
            timestamp={<EventTimestamp iso={round.userTurn.ts} />}
            threadId={round.userTurn.threadId}
            images={extractImages(round.userTurn.contentJson)}
            managedSkills={extractManagedSkills(round.userTurn.contentJson)}
          />
        )}
        {work.length > 0 && (
          <WorkFold
            meta={`${work.length} ${work.length === 1 ? 'step' : 'steps'}`}
            forceOpen={full || holdsPendingAsk || holdsPendingPermission}
          >
            {renderWork(work, full, ask, onDecidePermission)}
          </WorkFold>
        )}
        {headline !== null && (
          <Headline who="Agent" body={extractText(headline.contentJson)} timestamp={<EventTimestamp iso={headline.ts} />} />
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

  // Segment the trunk by CUT events, not by task completion: each segment is
  // the planning conversation that led up to a cut, plus the cut card
  // itself, and it folds the instant the cut happens — regardless of how
  // long the resulting task takes to finish, and with no "current task stays
  // open" exception: every cut folds immediately, including the most recent
  // one. That's what the trunk actually does: talk until the work is clear
  // enough to cut, cut it (the boundary), then start fresh on whatever comes
  // next. Folding on completion timing instead (the old approach) breaks
  // under concurrent tasks — a later task is routinely cut, and does its own
  // work, before an earlier one's completion summary finally lands, which
  // buried the later task's cut inside the earlier one's collapsed fold,
  // invisible until you happened to expand exactly that one. Cut order has
  // no such ambiguity: cuts are strictly ordered events regardless of how
  // long each resulting task takes afterward. Only the trailing segment
  // after the LAST cut — conversation that hasn't itself produced a cut yet
  // — stays unfolded.
  const summaryByTaskId = new Map<string, TrunkSummary>();
  for (const item of items) {
    if (item.kind === 'summary' && item.summary.taskId !== null) {
      summaryByTaskId.set(item.summary.taskId, item.summary);
    }
  }

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
    nodes.push(
      <TaskFold
        key={`fold-${task.id}`}
        title={taskLabel(task)}
        tone={s !== undefined ? 'done' : 'running'}
        summary={s?.text}
        statusLabel={FOLD_STATUS_LABEL[cardStatus(task.status)]}
        forceOpen={full}
      >
        {segment}
        {cutCard}
        {s !== undefined && s.text.trim().length > 0 && <Headline who="Agent" body={s.text} />}
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
      <Spine>{nodes}</Spine>
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
