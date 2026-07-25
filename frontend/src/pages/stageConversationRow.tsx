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
import { EventRow, EventTimestamp, PullRequestCreatedEvent, ToolBlock, UserMsg, WorkFold } from '../ui/conv';
import {
  ChevronRightIcon, ClockIcon, McpCubeIcon, PenIcon, PlanIcon, SearchIcon, TerminalRunIcon,
} from '../ui/TaskBrainDesignIcons';
import { formatDuration } from '../threads/brain/format';
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
 *  (which read as an empty line). When the tool name merely repeats the
 *  coarse tag pill it's dropped so a Read reads "Read /path", not the
 *  doubled "Read Read /path"; "Run Bash …" keeps both since they differ. */
function toolDesc(label: string | null, detail: string | null, tag?: string): ReactNode {
  const name = nonBlank(label);
  const arg = nonBlank(detail);
  if (name === null && arg === null) return 'Tool call';
  if (arg === null) return name;
  if (name === null || name === tag) return <span className="tool-arg">{arg}</span>;
  return <>{name} <span className="tool-arg">{arg}</span></>;
}

const PLAN_KICKOFF_PREFIX = 'The plan for this task has been approved — implement it now.';
const CI_FIX_CONTEXT_PREFIX = '## Context from prior stages\nYou are a fresh agent for the CI-fixing stage —';
const CI_FIX_PROMPT_PREFIX = 'CI is failing on the shipped PR ';
const LOCAL_COMMENTS_PREFIX = 'New comments arrived on your local PR "';

function kickoffIntent(text: string): string {
  const start = text.indexOf('\nIntent:');
  const end = text.indexOf('\nSteps:', start + 1);
  if (start === -1 || end === -1) return 'Development instructions sent automatically';
  return text.slice(start + '\nIntent:'.length, end).trim()
    || 'Development instructions sent automatically';
}

function ciFixPreview(text: string): string {
  const lines = text.split('\n');
  const prLine = lines.find(line => line.startsWith(CI_FIX_PROMPT_PREFIX));
  const pr = prLine?.slice(CI_FIX_PROMPT_PREFIX.length).replace(/\.$/, '');
  const checksStart = lines.indexOf('Failing checks:');
  const checkLines = checksStart === -1 ? [] : lines.slice(checksStart + 1);
  const checksEnd = checkLines.findIndex(line => line.trim().length === 0);
  const checks = (checksEnd === -1 ? checkLines : checkLines.slice(0, checksEnd))
    .map(line => line.replace(/^\s*-\s*/, ''));
  return [pr, ...checks].filter(Boolean).join(' · ')
    || 'Failing checks and remediation instructions sent automatically';
}

type RuntimeKickoff = {
  title: string;
  preview: string;
  bodyLabel: string;
  icon: ReactNode;
};

/** Development and CI-fix kickoff prompts are stored as user-role model
 *  inputs, but they are runtime-generated history rather than messages the
 *  person typed. Their fixed prefixes are the wire contracts emitted by the
 *  backend; older stored turns have no separate origin field. */
function runtimeKickoff(r: StageConversationRow): RuntimeKickoff | null {
  if (r.kind !== 'user' || r.text === null) return null;
  if (r.text.startsWith(PLAN_KICKOFF_PREFIX)) {
    return {
      title: 'Approved plan',
      preview: kickoffIntent(r.text),
      bodyLabel: 'Development kickoff prompt',
      icon: <PlanIcon size={14} />,
    };
  }
  if (r.text.startsWith(CI_FIX_CONTEXT_PREFIX) || r.text.startsWith(CI_FIX_PROMPT_PREFIX)) {
    return {
      title: 'CI fix instructions',
      preview: ciFixPreview(r.text),
      bodyLabel: 'CI-fix kickoff prompt',
      icon: <TerminalRunIcon size={14} />,
    };
  }
  if (r.text.startsWith(LOCAL_COMMENTS_PREFIX)) {
    const firstLine = r.text.split('\n', 1)[0];
    return {
      title: 'Address local review comments',
      preview: firstLine.replace(/\s+Unlike remote review comments.*$/, ''),
      bodyLabel: 'Local-comment addressing prompt',
      icon: <PenIcon />,
    };
  }
  return null;
}

function RuntimeKickoffCard({ row, kickoff }: {
  row: StageConversationRow;
  kickoff: RuntimeKickoff;
}) {
  const text = row.text ?? '';
  return (
    <details className="runtime-kickoff-card" data-seq={row.messageSeq ?? undefined}>
      <summary className="runtime-kickoff-card__summary">
        <span className="runtime-kickoff-card__icon" aria-hidden>{kickoff.icon}</span>
        <span className="runtime-kickoff-card__copy">
          <span className="runtime-kickoff-card__title">{kickoff.title}</span>
          <span className="runtime-kickoff-card__preview">{kickoff.preview}</span>
        </span>
        <span className="runtime-kickoff-card__badge">Runtime</span>
        <span className="runtime-kickoff-card__time"><EventTimestamp iso={row.ts} /></span>
        <span className="runtime-kickoff-card__chevron" aria-hidden>
          <ChevronRightIcon size={13} strokeWidth={2} />
        </span>
      </summary>
      <div className="runtime-kickoff-card__body">
        <div className="runtime-kickoff-card__body-label">{kickoff.bodyLabel}</div>
        <div className="runtime-kickoff-card__prompt">{text}</div>
        {row.managedSkills.length > 0 && (
          <div className="runtime-kickoff-card__skills">
            Managed skills: {row.managedSkills.join(', ')}
          </div>
        )}
      </div>
    </details>
  );
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
  const kickoff = runtimeKickoff(r);
  if (kickoff !== null) {
    return <RuntimeKickoffCard key={r.id} row={r} kickoff={kickoff} />;
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
          markdown={r.text ?? undefined}
        />
      );
    case 'tool_call':
      // No "Agent" who-row — tool calls render as bare blocks so a run of
      // them doesn't repeat the redundant agent header on every line. Tag
      // falls back to "Tool" so the block never collapses to a blank line.
      {
        const tag = nonBlank(r.toolTag) ?? 'Tool';
        // Read/Write args are file paths — head-truncate so the filename tail
        // stays visible when the worktree prefix overflows the row.
        const pathArg = tag === 'Read' || tag === 'Write';
        return (
          <ToolBlock
            key={r.id}
            tag={tag}
            icon={verbIcon(tag)}
            desc={toolDesc(r.toolLabel, r.toolDetail, tag)}
            descTail={pathArg}
          >
            {r.toolResult ?? r.toolDiff ?? undefined}
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
