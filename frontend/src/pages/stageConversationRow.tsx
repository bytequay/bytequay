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
import type { StageConversationRow } from '../types/brainView';
import {
  EventRow, EventTimestamp, PullRequestCreatedEvent, RuntimeKickoffCard, ToolBlock, UserMsg, WorkFold,
  runtimeKickoff,
} from '../ui/conv';
import {
  ClockIcon, McpCubeIcon, PenIcon, SearchIcon, TerminalRunIcon,
} from '../ui/TaskBrainDesignIcons';
import { formatDuration, shortPaths } from '../threads/brain/format';
import { PermissionCard, type PermissionDecideHandler } from '../threads/PermissionCard';

/** A blank-safe trim: empty/whitespace strings count as absent. */
function nonBlank(s: string | null): string | null {
  return s !== null && s.trim().length > 0 ? s.trim() : null;
}

/** The design's verb icon per tool tag (Run → terminal prompt, Read →
 *  magnifier, MCP → package, Write → pen); everything else runs as Run. */
function verbIcon(tag: string): ReactNode {
  switch (tag) {
    case 'Read': return <SearchIcon />;
    case 'MCP': return <McpCubeIcon />;
    case 'Write': return <PenIcon />;
    default: return <TerminalRunIcon />;
  }
}

/** The tool-block description: the tool name plus its command / target
 *  (the Bash command, the edited file, the search pattern…) so the row
 *  shows what actually ran, not just "Bash" — and never renders blank
 *  (which read as an empty line). Absolute paths shorten to their last two
 *  segments; the unfolded body keeps the full text. When the tool name
 *  merely repeats the coarse tag pill it's dropped so a Read reads
 *  "Read …/Foo.java", not the doubled "Read Read …"; "Run Bash …" keeps
 *  both since they differ. */
function toolDesc(label: string | null, detail: string | null, tag?: string): ReactNode {
  const name = nonBlank(label);
  const raw = nonBlank(detail);
  const arg = raw === null ? null : shortPaths(raw);
  if (name === null && arg === null) return 'Tool call';
  if (arg === null) return name;
  if (name === null || name === tag) return <span className="tool-arg">{arg}</span>;
  return <>{name} <span className="tool-arg">{arg}</span></>;
}

/** The unfolded body: the full, unshortened command or path first, then the
 *  tool's own output — so a row whose head was truncated is still readable
 *  in full on click. */
function toolBody(detail: string | null, result: string | null): string | undefined {
  const parts = [nonBlank(detail), nonBlank(result)].filter(part => part !== null);
  return parts.length === 0 ? undefined : parts.join('\n\n');
}

/**
 * Renders one stage-transcript row into a V3 conversation element. Shared
 * by the stage detail page and other read-only conversation surfaces so
 * both show an identical transcript. {@code onDecide} wires the Allow / Deny
 * buttons on a pending {@code permission} row; surfaces that pass none (the
 * read-only code-diff column) render it as a static "awaiting approval" note.
 * {@code threadId} resolves a `user` row's attached-screenshot thumbnails.
 */
export function stageRow(
  r: StageConversationRow, onDecide?: PermissionDecideHandler, threadId?: string): ReactNode {
  const kickoff = r.kind === 'user' ? runtimeKickoff(r.text) : null;
  if (kickoff !== null) {
    return (
      <RuntimeKickoffCard
        key={r.id}
        text={r.text ?? ''}
        kickoff={kickoff}
        timestamp={<EventTimestamp iso={r.ts} />}
        messageSeq={r.messageSeq}
        managedSkills={r.managedSkills}
      />
    );
  }
  switch (r.kind) {
    case 'pull_request_progress':
    case 'pull_request_created':
      return (
        <PullRequestCreatedEvent
          key={r.id}
          pullRequest={r.pullRequest}
          timestamp={<EventTimestamp iso={r.ts} />}
        />
      );
    case 'user':
      return (
        <UserMsg
          key={r.id}
          text={r.text ?? ''}
          timestamp={<EventTimestamp iso={r.ts} />}
          threadId={threadId}
          images={r.images}
          managedSkills={r.managedSkills}
          messageSeq={r.messageSeq}
        />
      );
    case 'agent':
      return (
        <EventRow
          key={r.id}
          kind="agent"
          who="Agent"
          timestamp={<EventTimestamp iso={r.ts} />}
          markdown={r.text ?? ''}
        />
      );
    case 'iteration_marker':
      return (
        <EventRow
          key={r.id}
          kind="system"
          who={`Iteration ${r.iterationNumber ?? ''}`}
          timestamp={<EventTimestamp iso={r.ts} />}
          markdown={r.text === 'user_steering' ? 'Steered by you' : r.text ?? undefined}
        />
      );
    case 'tool_call':
      // No "Agent" who-row — tool calls render as bare blocks so a run of
      // them doesn't repeat the redundant agent header on every line. Tag
      // falls back to "Tool" so the block never collapses to a blank line.
      {
        const tag = nonBlank(r.toolTag) ?? 'Tool';
        return (
          <ToolBlock
            key={r.id}
            tag={tag}
            icon={verbIcon(tag)}
            desc={toolDesc(r.toolLabel, r.toolDetail, tag)}
          >
            {toolBody(r.toolDetail, r.toolResult ?? r.toolDiff)}
          </ToolBlock>
        );
      }
    case 'permission':
      if (onDecide && r.callId) {
        return (
          <PermissionCard
            key={r.id}
            permission={{ callId: r.callId, toolName: nonBlank(r.toolLabel) ?? 'tool', summary: r.text ?? '' }}
            onDecide={onDecide}
          />
        );
      }
      // Read-only surface (the code-diff conversation column) can't act on a
      // prompt — show a static note rather than an inert card.
      return (
        <EventRow
          key={r.id}
          kind="system"
          who="Permission"
          timestamp={<EventTimestamp iso={r.ts} />}
          markdown={`Awaiting approval: \`${nonBlank(r.toolLabel) ?? 'tool'}\``}
        />
      );
    default:
      return null;
  }
}

/** The fold header: "Worked for 3m 12s · 5 steps" (Task Conversation
 *  design), falling back to "Agent worked" when the run spans under a
 *  second or timestamps are missing. */
function foldSummary(run: StageConversationRow[]): { label: string; meta: string } {
  const meta = `· ${run.length} ${run.length === 1 ? 'step' : 'steps'}`;
  const first = run[0]?.ts;
  const last = run[run.length - 1]?.ts;
  if (first === undefined || last === undefined) return { label: 'Agent worked', meta };
  const elapsedSec = (new Date(last).getTime() - new Date(first).getTime()) / 1000;
  return elapsedSec >= 1 ? { label: `Worked for ${formatDuration(elapsedSec)}`, meta } : { label: 'Agent worked', meta };
}

/**
 * The stage transcript with consecutive tool calls folded into collapsible
 * "Worked for … · N steps" groups (Task Conversation design). User, agent,
 * iteration and permission rows are group boundaries and render inline;
 * the trailing group of a still-streaming stage stays open so live tool
 * activity remains visible.
 */
export function stageFeed(
  rows: StageConversationRow[], onDecide?: PermissionDecideHandler, threadId?: string,
  live = false, defaultOpen = false): ReactNode[] {
  const out: ReactNode[] = [];
  let run: StageConversationRow[] = [];
  const flush = (isTail: boolean) => {
    if (run.length === 0) return;
    const { label, meta } = foldSummary(run);
    out.push(
      <WorkFold
        key={`fold-${run[0].id}`}
        label={label}
        meta={<>{meta} · <EventTimestamp iso={run[0].ts} /></>}
        icon={<ClockIcon size={14} strokeWidth={1.8} />}
        forceOpen={live && isTail}
        defaultOpen={defaultOpen}
      >
        {run.map(r => stageRow(r, onDecide, threadId))}
      </WorkFold>,
    );
    run = [];
  };
  for (const r of rows) {
    if (r.kind === 'tool_call') {
      run.push(r);
      continue;
    }
    flush(false);
    out.push(stageRow(r, onDecide, threadId));
  }
  flush(true);
  return out;
}
