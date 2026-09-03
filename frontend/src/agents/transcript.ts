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
import type { AgentEventEnvelope } from './events';

/**
 * The transcript as the screen draws it: turns, each with the user's message, the agent's
 * intermediate blocks, and its answer. Everything here is derived from events on every render;
 * nothing is persisted, so a folded summary can never disagree with the events it came from.
 */

export type ActivityState = 'STARTED' | 'PROGRESS' | 'COMPLETED' | 'FAILED';

export type CommandAction = { kind: 'READ' | 'LIST_FILES' | 'SEARCH' | 'OTHER'; path: string | null; query: string | null };

export type CommandMember = {
  kind: 'command';
  id: string;
  command: string;
  state: ActivityState;
  output: string | null;
  exitCode: number | null;
  truncated: boolean;
  cwd: string | null;
  durationMs: number | null;
  actions: CommandAction[];
};

export type FileMember = {
  kind: 'file';
  id: string;
  path: string;
  change: string;
  diff: string | null;
  added: number;
  removed: number;
};

export type ToolMember = {
  kind: 'tool';
  id: string;
  name: string;
  state: ActivityState;
  summary: string;
  durationMs: number | null;
};

export type ActivityMember = CommandMember | FileMember | ToolMember;

export type TextBlock = {
  kind: 'text';
  id: string;
  role: 'commentary' | 'reasoning' | 'planText';
  text: string;
  /** False while the text is still streaming; a completed item replaces every delta before it. */
  complete: boolean;
};

export type PlanStep = { text: string; state: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' };

export type PlanBlock = { kind: 'plan'; id: string; steps: PlanStep[] };

export type ActivityBlock = {
  kind: 'activity';
  id: string;
  members: ActivityMember[];
  startedAt: string;
  /** When the last member reached a terminal state; null while any member is still running. */
  endedAt: string | null;
};

export type ApprovalBlock = {
  kind: 'approval';
  id: string;
  approvalId: string;
  title: string;
  detail: string;
  approvalKind: string;
  scope: string[];
  agent: string;
  decisions: string[];
  actionable: boolean;
  outcome: string | null;
  at: string;
};

export type ErrorBlock = { kind: 'error'; id: string; code: string; message: string; retryable: boolean };

export type NoteBlock = { kind: 'note'; id: string; text: string };

export type TurnBlock = TextBlock | PlanBlock | ActivityBlock | ApprovalBlock | ErrorBlock | NoteBlock;

export type UserBlock = {
  id: string;
  text: string;
  attachments: Array<{ id: string; name: string; mediaType: string }>;
  delivery: string;
  at: string;
};

export type UsageFacts = {
  inputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
  cost: number | null;
  costProvenance: string;
};

export type TranscriptTurn = {
  turnId: string | null;
  ordinal: number;
  user: UserBlock | null;
  blocks: TurnBlock[];
  answer: { id: string; text: string; complete: boolean } | null;
  startedAt: string | null;
  endedAt: string | null;
  outcome: 'completed' | 'interrupted' | null;
  usage: UsageFacts | null;
};

type Message = AgentEventEnvelope & { type: 'assistant_message' };

function itemKey(event: AgentEventEnvelope): string {
  return event.source.providerItemId ?? event.eventId;
}

/** A delta carries no provider event id; only an item's completion does. */
function completesItem(event: AgentEventEnvelope): boolean {
  return event.source.providerEventId !== null;
}

function diffCounts(diff: string | null): { added: number; removed: number } {
  if (diff === null) return { added: 0, removed: 0 };
  let added = 0;
  let removed = 0;
  for (const line of diff.split('\n')) {
    if (line.startsWith('+') && !line.startsWith('+++')) added++;
    else if (line.startsWith('-') && !line.startsWith('---')) removed++;
  }
  return { added, removed };
}

function agentName(source: AgentEventEnvelope['source']): string {
  return source.logicalAgentPath ?? (source.parentProviderThreadId === null ? 'root agent' : 'subagent');
}

function isTerminal(member: ActivityMember): boolean {
  return member.kind === 'file' || member.state === 'COMPLETED' || member.state === 'FAILED';
}

export function activityRunning(block: ActivityBlock): boolean {
  return block.members.some((member) => !isTerminal(member));
}

class TurnBuilder {
  readonly turn: TranscriptTurn;
  private readonly members = new Map<string, { block: ActivityBlock; member: ActivityMember }>();
  private readonly texts = new Map<string, TextBlock>();
  private readonly approvals = new Map<string, ApprovalBlock>();
  private plan: PlanBlock | null = null;

  constructor(turnId: string | null, ordinal: number) {
    this.turn = {
      turnId,
      ordinal,
      user: null,
      blocks: [],
      answer: null,
      startedAt: null,
      endedAt: null,
      outcome: null,
      usage: null,
    };
  }

  apply(event: AgentEventEnvelope): void {
    switch (event.type) {
      case 'user_message':
        this.turn.user = {
          id: event.eventId,
          text: event.payload.text,
          attachments: event.payload.attachments.map((attachment) => ({ ...attachment })),
          delivery: event.payload.deliveryKind,
          at: event.occurredAt,
        };
        break;
      case 'assistant_message':
        this.message(event);
        break;
      case 'plan_updated':
        // The latest plan replaces the steps; the block keeps its place and identity.
        if (this.plan === null) {
          this.plan = { kind: 'plan', id: event.eventId, steps: [] };
          this.turn.blocks.push(this.plan);
        }
        this.plan.steps = event.payload.items.map((item) => ({ text: item.text, state: item.state }));
        break;
      case 'command':
        this.activity(event, itemKey(event), (existing) => {
          const previous = existing?.kind === 'command' ? existing : null;
          const payload = event.payload;
          const started = payload.state === 'STARTED';
          const output = payload.state === 'PROGRESS'
            ? (previous?.output ?? '') + (payload.output ?? '')
            : payload.output ?? (started ? null : previous?.output ?? null);
          return {
            kind: 'command',
            id: previous?.id ?? event.eventId,
            command: payload.argv.length > 0 ? payload.argv.join(' ') : previous?.command ?? '',
            state: payload.state,
            output,
            exitCode: payload.exitCode ?? previous?.exitCode ?? null,
            truncated: payload.truncated || (payload.state === 'PROGRESS' && (previous?.truncated ?? false)),
            cwd: payload.cwd ?? previous?.cwd ?? null,
            durationMs: payload.durationMs ?? previous?.durationMs ?? null,
            actions: payload.actions.length > 0 ? payload.actions.map((action) => ({ ...action })) : previous?.actions ?? [],
          };
        });
        break;
      case 'file_change':
        this.activity(event, itemKey(event), (existing) => ({
          kind: 'file',
          id: existing?.id ?? event.eventId,
          path: event.payload.relativePath,
          change: event.payload.change,
          diff: event.payload.diff,
          ...diffCounts(event.payload.diff),
        }));
        break;
      case 'tool':
        this.activity(event, itemKey(event), (existing) => ({
          kind: 'tool',
          id: existing?.id ?? event.eventId,
          name: event.payload.name,
          state: event.payload.state,
          summary: event.payload.summary,
          durationMs: event.payload.durationMs ?? (existing?.kind === 'tool' ? existing.durationMs : null),
        }));
        break;
      case 'approval_requested': {
        const block: ApprovalBlock = {
          kind: 'approval',
          id: event.eventId,
          approvalId: event.payload.approvalId,
          title: event.payload.title,
          detail: event.payload.detail,
          approvalKind: event.payload.approvalKind,
          scope: [...event.payload.scope],
          agent: agentName(event.source),
          decisions: [...event.payload.availableDecisions],
          actionable: event.payload.actionable,
          outcome: null,
          at: event.occurredAt,
        };
        this.approvals.set(block.approvalId, block);
        this.turn.blocks.push(block);
        break;
      }
      case 'approval_resolved': {
        const block = this.approvals.get(event.payload.approvalId);
        if (block !== undefined) {
          block.outcome = event.payload.outcome;
          block.actionable = false;
          block.at = event.occurredAt;
        }
        break;
      }
      case 'usage':
        this.turn.usage = {
          inputTokens: event.payload.inputTokens,
          cachedInputTokens: event.payload.cachedInputTokens,
          outputTokens: event.payload.outputTokens,
          cost: event.payload.cost,
          costProvenance: event.payload.costProvenance,
        };
        break;
      case 'lifecycle':
        this.lifecycle(event);
        break;
      case 'error':
        this.turn.blocks.push({
          kind: 'error',
          id: event.eventId,
          code: event.payload.code,
          message: event.payload.message,
          retryable: event.payload.retryable,
        });
        break;
      default:
        break;
    }
  }

  private message(event: Message): void {
    const key = itemKey(event);
    const complete = completesItem(event);
    if (event.payload.messageKind === 'ANSWER' && event.payload.phase === 'FINAL') {
      // The authoritative answer replaces whatever streamed toward it under the same item.
      const streamed = this.texts.get(key);
      if (streamed !== undefined) {
        this.turn.blocks = this.turn.blocks.filter((block) => block !== streamed);
        this.texts.delete(key);
      }
      this.turn.answer = { id: streamed?.id ?? event.eventId, text: event.payload.text, complete: true };
      return;
    }
    const role = event.payload.messageKind === 'REASONING_SUMMARY'
      ? 'reasoning'
      : event.payload.messageKind === 'PLAN_TEXT' ? 'planText' : 'commentary';
    const existing = this.texts.get(key);
    if (existing === undefined) {
      const block: TextBlock = { kind: 'text', id: event.eventId, role, text: event.payload.text, complete };
      this.texts.set(key, block);
      this.turn.blocks.push(block);
      return;
    }
    if (complete) {
      existing.text = event.payload.text;
      existing.complete = true;
    }
    else if (!existing.complete) {
      existing.text += event.payload.text;
    }
  }

  private activity(
    event: AgentEventEnvelope,
    key: string,
    build: (existing: ActivityMember | null) => ActivityMember,
  ): void {
    const known = this.members.get(key);
    if (known !== undefined) {
      const replacement = build(known.member);
      known.block.members = known.block.members.map((member) => (member === known.member ? replacement : member));
      known.member = replacement;
      this.settle(known.block, event.occurredAt);
      return;
    }
    const last = this.turn.blocks[this.turn.blocks.length - 1];
    // Activity that follows activity joins its group; anything else in between starts a new one.
    const block: ActivityBlock = last !== undefined && last.kind === 'activity'
      ? last
      : { kind: 'activity', id: event.eventId, members: [], startedAt: event.occurredAt, endedAt: null };
    if (block !== last) this.turn.blocks.push(block);
    const member = build(null);
    block.members = [...block.members, member];
    this.members.set(key, { block, member });
    this.settle(block, event.occurredAt);
  }

  private settle(block: ActivityBlock, at: string): void {
    block.endedAt = activityRunning(block) ? null : at;
  }

  private lifecycle(event: AgentEventEnvelope & { type: 'lifecycle' }): void {
    switch (event.payload.phase) {
      case 'TURN_STARTED':
        this.turn.startedAt = event.occurredAt;
        break;
      case 'TURN_COMPLETED':
        this.turn.endedAt = event.occurredAt;
        this.turn.outcome = 'completed';
        break;
      case 'INTERRUPTED':
        this.turn.endedAt = event.occurredAt;
        this.turn.outcome = 'interrupted';
        this.turn.blocks.push({ kind: 'error', id: event.eventId, code: 'INTERRUPTED', message: event.payload.message, retryable: false });
        break;
      case 'RETRYING':
      case 'RATE_LIMITED':
        this.turn.blocks.push({ kind: 'note', id: event.eventId, text: event.payload.message });
        break;
      default:
        break;
    }
  }
}

/** Groups the applied events into turns, in the order the turns first appeared. */
export function projectTranscript(events: AgentEventEnvelope[]): TranscriptTurn[] {
  const builders = new Map<string | null, TurnBuilder>();
  const order: TurnBuilder[] = [];
  for (const event of events) {
    if (event.turnId === null && event.type === 'lifecycle') {
      // Task-level lifecycle belongs to the task's own summary, not to a turn.
      continue;
    }
    let builder = builders.get(event.turnId);
    if (builder === undefined) {
      builder = new TurnBuilder(event.turnId, order.length + 1);
      builders.set(event.turnId, builder);
      order.push(builder);
    }
    builder.apply(event);
  }
  return order.map((builder) => builder.turn);
}

function count(amount: number, singular: string, plural = `${singular}s`): string {
  return `${amount} ${amount === 1 ? singular : plural}`;
}

function joinClauses(parts: string[]): string {
  if (parts.length <= 1) return parts.join('');
  if (parts.length === 2) return `${parts[0]} and ${parts[1]}`;
  return `${parts.slice(0, -1).join(', ')}, and ${parts[parts.length - 1]}`;
}

type ActivityCounts = { reads: number; searches: number; listings: number; commands: number; edits: number; web: number; tools: number };

function activityCounts(members: ActivityMember[]): ActivityCounts {
  const readPaths = new Set<string>();
  const editPaths = new Set<string>();
  const counts: ActivityCounts = { reads: 0, searches: 0, listings: 0, commands: 0, edits: 0, web: 0, tools: 0 };
  for (const member of members) {
    if (member.kind === 'file') {
      editPaths.add(member.path);
    }
    else if (member.kind === 'tool') {
      if (member.name === 'Web search') counts.web++;
      else counts.tools++;
    }
    else {
      let parsed = false;
      for (const action of member.actions) {
        if (action.kind === 'READ') { readPaths.add(action.path ?? member.command); parsed = true; }
        else if (action.kind === 'SEARCH') { counts.searches++; parsed = true; }
        else if (action.kind === 'LIST_FILES') { counts.listings++; parsed = true; }
      }
      if (!parsed) counts.commands++;
    }
  }
  counts.reads = readPaths.size;
  counts.edits = editPaths.size;
  return counts;
}

/**
 * The folded sentence for a finished group, from typed facts only: "Read 3 files, ran 2 commands,
 * and edited 2 files". It is recomputed on every render and never stored.
 */
export function activitySummary(members: ActivityMember[]): string {
  const counts = activityCounts(members);
  const parts: string[] = [];
  if (counts.reads > 0) parts.push(`read ${count(counts.reads, 'file')}`);
  if (counts.listings > 0) parts.push(`listed ${count(counts.listings, 'directory', 'directories')}`);
  if (counts.searches > 0) parts.push(`ran ${count(counts.searches, 'search', 'searches')}`);
  if (counts.commands > 0) parts.push(`ran ${count(counts.commands, 'command')}`);
  if (counts.edits > 0) parts.push(`edited ${count(counts.edits, 'file')}`);
  if (counts.web > 0) parts.push(`searched the web ${count(counts.web, 'time')}`);
  if (counts.tools > 0) parts.push(`used ${count(counts.tools, 'tool')}`);
  if (parts.length === 0) return 'Did nothing visible';
  const sentence = joinClauses(parts);
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}

/** What a running group is doing, for its header: "reading files and running commands". */
export function activityPhrase(members: ActivityMember[]): string {
  const counts = activityCounts(members);
  const parts: string[] = [];
  if (counts.reads > 0 || counts.listings > 0) parts.push('reading files');
  if (counts.searches > 0) parts.push('searching');
  if (counts.commands > 0) parts.push('running commands');
  if (counts.edits > 0) parts.push('editing files');
  if (counts.web > 0) parts.push('searching the web');
  if (counts.tools > 0) parts.push('using tools');
  return joinClauses(parts.slice(0, 2)) || 'working';
}

/** "38s", "4m 51s", "1h 02m": the compact form the design uses for elapsed time and durations. */
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) return `${hours}h ${String(minutes).padStart(2, '0')}m`;
  if (minutes > 0) return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  return `${seconds}s`;
}

/** Milliseconds a turn has been running, frozen at its end once it has one. */
export function turnElapsedMs(turn: TranscriptTurn, now: number): number | null {
  if (turn.startedAt === null) return null;
  const end = turn.endedAt === null ? now : Date.parse(turn.endedAt);
  return Math.max(0, end - Date.parse(turn.startedAt));
}

export function activityElapsedMs(block: ActivityBlock, now: number): number {
  const end = block.endedAt === null ? now : Date.parse(block.endedAt);
  return Math.max(0, end - Date.parse(block.startedAt));
}

/** "Turn 1 · Working for 38s", "Turn 1 · Worked for 4m 51s", "Turn 2 · Interrupted after 12s". */
export function turnLabel(turn: TranscriptTurn, now: number, waitingOnUser: boolean): string {
  const elapsed = turnElapsedMs(turn, now);
  const duration = elapsed === null ? null : formatDuration(elapsed);
  if (turn.endedAt === null) {
    if (waitingOnUser) return `Turn ${turn.ordinal} · Waiting on you`;
    return duration === null ? `Turn ${turn.ordinal}` : `Turn ${turn.ordinal} · Working for ${duration}`;
  }
  if (turn.outcome === 'interrupted') {
    return duration === null ? `Turn ${turn.ordinal} · Interrupted` : `Turn ${turn.ordinal} · Interrupted after ${duration}`;
  }
  return duration === null ? `Turn ${turn.ordinal} · Done` : `Turn ${turn.ordinal} · Worked for ${duration}`;
}

/** Token counts and the cost, which is named as unavailable rather than left blank. */
export function usageText(usage: UsageFacts): string {
  const cost = usage.cost === null ? 'cost unavailable' : `$${usage.cost.toFixed(4)}`;
  return `in ${usage.inputTokens} · cached ${usage.cachedInputTokens} · out ${usage.outputTokens} · ${cost}`;
}

export function planCounts(steps: PlanStep[]): { completed: number; inProgress: number; pending: number } {
  return {
    completed: steps.filter((step) => step.state === 'COMPLETED').length,
    inProgress: steps.filter((step) => step.state === 'IN_PROGRESS').length,
    pending: steps.filter((step) => step.state === 'PENDING').length,
  };
}

/** "Step 2 of 3" while a step runs, "Completed" once none is left. */
export function planChip(steps: PlanStep[]): string {
  const counts = planCounts(steps);
  if (steps.length > 0 && counts.completed === steps.length) return 'Completed';
  const current = steps.findIndex((step) => step.state !== 'COMPLETED');
  return current < 0 ? 'Completed' : `Step ${current + 1} of ${steps.length}`;
}

export function planSummary(steps: PlanStep[]): string {
  const counts = planCounts(steps);
  return [
    counts.completed > 0 ? `${counts.completed} completed` : null,
    counts.inProgress > 0 ? `${counts.inProgress} in progress` : null,
    counts.pending > 0 ? `${counts.pending} pending` : null,
  ].filter((part) => part !== null).join(' · ');
}

/** The current plan is the newest turn's; an older turn's plan is history. */
export function currentPlan(turns: TranscriptTurn[]): PlanBlock | null {
  for (let index = turns.length - 1; index >= 0; index--) {
    const plan = turns[index].blocks.find((block): block is PlanBlock => block.kind === 'plan');
    if (plan !== undefined) return plan;
  }
  return null;
}

/** Whether an activity group is shown open: always while running, otherwise as the user last chose. */
export function activityExpanded(block: ActivityBlock, choice: boolean | undefined): boolean {
  return activityRunning(block) ? true : choice ?? false;
}
