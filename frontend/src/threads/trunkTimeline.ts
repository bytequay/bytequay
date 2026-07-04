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
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';
import { isShellTool, shellCommand } from './toolDisplay';

/** Best-effort plain text out of a message's JSON envelope. Thinking rows
 *  carry a `summary`; text rows carry `text`/`content`. */
export function extractText(contentJson: string): string {
  try {
    const v: unknown = JSON.parse(contentJson);
    if (typeof v === 'string') return v;
    if (v !== null && typeof v === 'object') {
      const o = v as Record<string, unknown>;
      if (typeof o.text === 'string') return o.text;
      if (typeof o.content === 'string') return o.content;
      if (typeof o.summary === 'string') return o.summary;
    }
  }
  catch { /* non-JSON envelope */ }
  return '';
}

/** Read a `permission_request` message's envelope: `{callId, toolName,
 *  summary}` — see `AbstractCliThreadAgent`'s `StreamEvent.PermissionRequested`
 *  mapping on the backend. */
export function parsePermissionRequest(
  contentJson: string): { callId: string; toolName: string; summary: string } {
  try {
    const o = JSON.parse(contentJson) as Record<string, unknown>;
    return {
      callId: typeof o.callId === 'string' ? o.callId : '',
      toolName: typeof o.toolName === 'string' ? o.toolName : 'tool',
      summary: typeof o.summary === 'string' ? o.summary : '',
    };
  }
  catch {
    return { callId: '', toolName: 'tool', summary: '' };
  }
}

/** callIds already resolved (decided or auto-allowed) — a `permission_request`
 *  with no entry here is still pending and renders as a clickable card. */
function decidedCallIds(messages: ThreadMessageDto[]): Set<string> {
  const ids = new Set<string>();
  for (const m of messages) {
    if (m.type !== 'permission_decision' && m.type !== 'permission_auto_allowed') continue;
    try {
      const o = JSON.parse(m.contentJson) as Record<string, unknown>;
      if (typeof o.callId === 'string') ids.add(o.callId);
    }
    catch { /* skip */ }
  }
  return ids;
}

/** Read a tool-call message into a tool name + a one-line summary. */
export function parseToolCall(contentJson: string): { name: string; summary: string } {
  try {
    const c = JSON.parse(contentJson) as { toolName?: unknown; input?: unknown };
    const name = typeof c.toolName === 'string' && c.toolName.length > 0 ? c.toolName : 'Tool';
    let summary = '';
    if (isShellTool(name)) {
      summary = shellCommand(c.input);
    }
    else if (c.input !== null && typeof c.input === 'object') {
      const o = c.input as Record<string, unknown>;
      for (const k of ['description', 'prompt', 'pattern', 'query', 'path', 'file_path', 'url', 'command']) {
        const v = o[k];
        if (typeof v === 'string' && v.length > 0) { summary = v; break; }
      }
    }
    summary = summary.replace(/\s+/g, ' ').trim();
    return { name, summary: summary.length > 160 ? `${summary.slice(0, 160)}…` : summary };
  }
  catch {
    return { name: 'Tool', summary: '' };
  }
}

/** A trunk round: a user turn (or autonomous) + the agent's rows. */
export type TrunkRound = {
  id: string;
  userTurn: ThreadMessageDto | null;
  rows: ThreadMessageDto[];
  ts: number;
};

/** A task-cut milestone — a task seeded from this thread. */
export type TrunkCut = { id: string; task: WorkUnitTaskDto; ts: number };

/** A task-completion marker written to the trunk when a task finishes — the
 *  delimiter that closes a task's foldable block in the feed. */
export type TrunkSummary = { id: string; taskId: string | null; taskSeq: number | null; text: string; ts: number };

/** The trunk timeline: rounds + task-cut milestones + completion summaries
 *  merged in time order. */
export type TrunkItem =
  | { kind: 'round'; round: TrunkRound }
  | { kind: 'cut'; cut: TrunkCut }
  | { kind: 'summary'; summary: TrunkSummary };

/** The message type the backend writes to the trunk on task completion. */
export const TASK_SUMMARY_TYPE = 'task_summary';

const PLANNING_TYPES = new Set(['text', 'thinking', 'tool_call', 'permission_request']);

/** Read a task-completion marker's envelope: {text, taskId, taskSeq}. */
function parseSummary(m: ThreadMessageDto): TrunkSummary {
  let taskId: string | null = null;
  let taskSeq: number | null = null;
  try {
    const o = JSON.parse(m.contentJson) as Record<string, unknown>;
    if (typeof o.taskId === 'string') taskId = o.taskId;
    if (typeof o.taskSeq === 'number') taskSeq = o.taskSeq;
  }
  catch { /* fall back to text-only */ }
  return { id: m.id, taskId, taskSeq, text: extractText(m.contentJson), ts: Date.parse(m.ts) };
}

/**
 * Pure transform of the trunk's raw planning messages + its cut tasks into
 * the timeline-spine model: rounds (user turn + agent rows) interleaved with
 * task-cut milestones at their creation time. The trunk has no stages — its
 * structure is the set of *outputs* it produces (task cuts), so the cuts are
 * the spine's big nodes. Architecture/risk are prose in the rounds until a
 * later milestone emits them structured (see DISCOVERY-FINDINGS deferral).
 */
export function buildTrunkTimeline(messages: ThreadMessageDto[], tasks: WorkUnitTaskDto[]): TrunkItem[] {
  const decided = decidedCallIds(messages);
  const trunk = messages.filter(m => m.taskId === null
    && (PLANNING_TYPES.has(m.type) || m.type === TASK_SUMMARY_TYPE)
    // An already-decided prompt is history, not a live card — drop it rather
    // than re-render a stale "approval needed" for something already resolved.
    && !(m.type === 'permission_request' && decided.has(parsePermissionRequest(m.contentJson).callId)));
  const rounds: TrunkRound[] = [];
  const summaries: TrunkSummary[] = [];
  for (const m of trunk) {
    if (m.type === TASK_SUMMARY_TYPE) {
      // A completion marker isn't conversation — it closes a task's block.
      summaries.push(parseSummary(m));
      continue;
    }
    const isUser = m.role === 'user' && m.type === 'text';
    if (isUser) {
      rounds.push({ id: m.id, userTurn: m, rows: [], ts: Date.parse(m.ts) });
      continue;
    }
    const last = rounds[rounds.length - 1];
    if (last === undefined) {
      rounds.push({ id: m.id, userTurn: null, rows: [m], ts: Date.parse(m.ts) });
    }
    else {
      last.rows.push(m);
    }
  }

  const items: TrunkItem[] = rounds.map(round => ({ kind: 'round' as const, round }));
  for (const task of tasks) {
    items.push({ kind: 'cut', cut: { id: task.id, task, ts: Date.parse(task.createdAt) } });
  }
  for (const summary of summaries) {
    items.push({ kind: 'summary', summary });
  }
  // Stable sort by timestamp keeps rounds and cuts in chronological order; a
  // cut lands right after the round that produced it.
  items.sort((a, b) => tsOf(a) - tsOf(b));
  return items;
}

function tsOf(item: TrunkItem): number {
  const t = item.kind === 'round' ? item.round.ts
    : item.kind === 'cut' ? item.cut.ts
    : item.summary.ts;
  return Number.isFinite(t) ? t : 0;
}

/** The round's headline row (its last assistant text), or null. */
export function trunkHeadline(round: TrunkRound): ThreadMessageDto | null {
  // Only the round's LAST row counts — a `text` row with something after it
  // (e.g. a still-pending permission_request the agent raised after replying)
  // isn't a conclusion yet, and `trunkWork`'s slice-before-the-headline logic
  // would otherwise silently drop everything after it from rendering.
  const last = round.rows[round.rows.length - 1];
  return last !== undefined && last.type === 'text' ? last : null;
}

/** The round's work rows (everything before the headline). */
export function trunkWork(round: TrunkRound): ThreadMessageDto[] {
  const headline = trunkHeadline(round);
  if (headline === null) return round.rows;
  const idx = round.rows.lastIndexOf(headline);
  return round.rows.slice(0, idx);
}
