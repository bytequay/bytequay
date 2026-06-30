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
import { useState } from 'react';
import type { ReactNode } from 'react';
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';
import {
  ActivityStrip, Headline, OutlineStrip, Round, Spine, TaskCutNode, UserTurn, WorkFold,
} from '../ui/conv';
import type { Density, OutlineChip, TaskStatus, ToolGroup } from '../ui/conv';
import { MarkdownProse } from './MarkdownProse';
import { EventTimestamp } from '../ui/conv';
import { taskLabel } from './taskLabel';
import {
  buildTrunkTimeline, extractText, parseToolCall, trunkHeadline, trunkWork,
} from './trunkTimeline';

/** Map a work-unit status to the task card's status pill. */
function cardStatus(status: string): TaskStatus {
  switch (status) {
    case 'COMPLETED': case 'IN_REVIEW': return 'shipped';
    case 'CANCELED': case 'ARCHIVED': return 'closed';
    case 'ERRORED': return 'errored';
    case 'PAUSED': return 'paused';
    case 'PENDING': return 'pending';
    default: return 'foreground';
  }
}

/** Batch a round's work rows into folded sub-messages + tool activity strips,
 *  preserving order (consecutive tool calls collapse into one strip). */
function renderWork(rows: ThreadMessageDto[], full: boolean): ReactNode[] {
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
    if (m.type === 'tool_call') { toolBatch.push(m); continue; }
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
export function TrunkFeed({ messages, tasks, density, onOpenTask, trailer }: {
  messages: ThreadMessageDto[];
  tasks: WorkUnitTaskDto[];
  density: Density;
  onOpenTask: (taskId: string) => void;
  trailer?: ReactNode;
}) {
  const full = density === 'full';
  const items = buildTrunkTimeline(messages, tasks);
  const [flashId, setFlashId] = useState<string | null>(null);

  const jump = (id: string) => {
    const el = typeof document !== 'undefined' ? document.getElementById(id) : null;
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setFlashId(id);
    window.setTimeout(() => setFlashId(cur => (cur === id ? null : cur)), 1200);
  };

  const chips: OutlineChip[] = items
    .filter((it): it is Extract<typeof it, { kind: 'cut' }> => it.kind === 'cut')
    .map(({ cut }) => ({
      id: `cut-${cut.task.id}`,
      icon: '◆',
      label: taskLabel(cut.task),
      tone: 'task' as const,
      status: cut.task.status === 'PENDING' ? 'Pending' : cardStatus(cut.task.status) === 'foreground' ? 'Foreground' : undefined,
      statusTone: cut.task.status === 'PENDING' ? ('pend' as const) : ('fg' as const),
      onJump: () => jump(`cut-${cut.task.id}`),
    }));

  let autonomous = 0;
  return (
    <>
      <OutlineStrip chips={chips} />
      <Spine>
        {items.map(item => {
          if (item.kind === 'cut') {
            const t = cut(item);
            return (
              <TaskCutNode
                key={t.id}
                id={`cut-${t.id}`}
                flash={flashId === `cut-${t.id}`}
                title={taskLabel(t)}
                status={cardStatus(t.status)}
                branch={t.branchName ?? undefined}
                onOpen={() => onOpenTask(t.id)}
              />
            );
          }
          const { round } = item;
          const tag = round.userTurn === null ? `R${(autonomous += 1)}` : undefined;
          const work = trunkWork(round);
          const headline = trunkHeadline(round);
          return (
            <Round key={round.id} tag={tag}>
              {round.userTurn !== null && (
                <UserTurn text={extractText(round.userTurn.contentJson)} timestamp={<EventTimestamp iso={round.userTurn.ts} />} />
              )}
              {work.length > 0 && (
                <WorkFold meta={`${work.length} ${work.length === 1 ? 'step' : 'steps'}`} forceOpen={full}>
                  {renderWork(work, full)}
                </WorkFold>
              )}
              {headline !== null && (
                <Headline who="Agent" body={extractText(headline.contentJson)} timestamp={<EventTimestamp iso={headline.ts} />} />
              )}
            </Round>
          );
        })}
      </Spine>
      {trailer}
    </>
  );
}

function cut(item: { kind: 'cut'; cut: { task: WorkUnitTaskDto } }): WorkUnitTaskDto {
  return item.cut.task;
}
