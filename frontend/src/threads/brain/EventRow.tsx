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
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { BrainFeedRow, BrainFeedRowType, StageType } from '../../types/brainView';
import { StageTag } from './StageTag';
import { relativeShort } from './format';

type Props = {
  row: BrainFeedRow;
  /** Resolved display label for `row.stageId`, or null when the row
   *  carries no stage (user / brain messages). */
  stageLabel: string | null;
  nowMs: number;
  /** True for the row representing a stage iteration that's running now
   *  — adds the pulsing icon + LIVE pill. */
  live: boolean;
  /** True briefly after a scrubber jump targets this row. */
  pulsing: boolean;
  onOpenStage: (stageId: string) => void;
};

type IconSpec = { cls: string; glyph: string };

const ICONS: Record<BrainFeedRowType, IconSpec> = {
  STAGE_OPENED: { cls: 'stage-open', glyph: '◼' },
  STAGE_CLOSED: { cls: 'stage-close', glyph: '✓' },
  PANEL_REVIEW_COMPLETED: { cls: 'review', glyph: '⚖' },
  PUSHED_PR_CREATED: { cls: 'push', glyph: '⏏' },
  ITERATION_SUMMARY: { cls: 'iter', glyph: '⊕' },
  USER_MESSAGE: { cls: 'user', glyph: 'YOU' },
  BRAIN_AGENT_RESPONSE: { cls: 'brain', glyph: '⊕' },
  NEEDS_ATTENTION: { cls: 'notify', glyph: '⚠' },
  NOTIFY_READY_FOR_MERGE: { cls: 'notify', glyph: '📣' },
  PLAN_RECORDED: { cls: 'plan-rec', glyph: '📋' },
  PLAN_APPROVED: { cls: 'plan-approved', glyph: '✓' },
  PLAN_FOLLOWUP_NOTED: { cls: 'followup', glyph: '⚠' },
};

function shortStageName(type: StageType | null): string {
  switch (type) {
    case 'DEVELOPMENT_STAGE': return 'DEVELOPMENT';
    case 'CI_FIXING_STAGE': return 'CI FIXING';
    case 'REVIEW_MONITOR_STAGE': return 'REVIEW MONITOR';
    case 'CLEANUP_STAGE': return 'CLEANUP';
    case 'REVIEW_STAGE': return 'REVIEW';
    default: return 'STAGE';
  }
}

function eventTitle(row: BrainFeedRow, live: boolean): string {
  switch (row.type) {
    case 'STAGE_OPENED': return 'STAGE OPENED';
    case 'STAGE_CLOSED': return 'STAGE CLOSED';
    case 'PANEL_REVIEW_COMPLETED': return 'PANEL REVIEW COMPLETED';
    case 'PUSHED_PR_CREATED': return 'PUSHED · PR CREATED';
    case 'ITERATION_SUMMARY':
      return `${shortStageName(row.stageType)} · ${live ? 'RUNNING' : 'ITERATION SUMMARY'}`;
    case 'USER_MESSAGE': return 'YOU';
    case 'BRAIN_AGENT_RESPONSE': return 'BRAIN';
    case 'NEEDS_ATTENTION': return 'NEEDS ATTENTION';
    case 'NOTIFY_READY_FOR_MERGE': return 'READY FOR MERGE';
    case 'PLAN_RECORDED': return live ? 'RECORDING PLAN' : 'PLAN RECORDED';
    case 'PLAN_APPROVED': return 'PLAN APPROVED';
    case 'PLAN_FOLLOWUP_NOTED': return 'FOLLOW-UP NOTE';
  }
}

function whoClass(type: BrainFeedRowType): string {
  if (type === 'USER_MESSAGE') return 'user';
  if (type === 'BRAIN_AGENT_RESPONSE') return 'brain';
  return 'system';
}

/**
 * One brain-feed row. A single component with per-type variants —
 * system events (left-aligned cards with a persona icon), the user's
 * own message (right-aligned green bubble, avatar on the right), and the
 * brain agent's reply.
 */
export function EventRow({ row, stageLabel, nowMs, live, pulsing, onOpenStage }: Props) {
  const icon = ICONS[row.type];
  const isUser = row.type === 'USER_MESSAGE';
  const rowClass = `ev${isUser ? ' you-msg' : ''}${pulsing ? ' jump-pulse' : ''}`;

  return (
    <div className={rowClass} id={row.id} data-row-id={row.id}>
      <span className={`ic ${icon.cls}${live ? ' live' : ''}`} aria-hidden>
        {icon.glyph}
      </span>
      <div className="body">
        <div className="who-row">
          <span className={`who ${whoClass(row.type)}`}>{eventTitle(row, live)}</span>
          {row.stageId !== null && stageLabel !== null && (
            <StageTag
              label={stageLabel}
              stageType={row.stageType}
              onOpen={() => onOpenStage(row.stageId as string)}
            />
          )}
          {live && (
            <span className="live-pill"><span className="d" />LIVE</span>
          )}
          <span className="ts">{relativeShort(row.ts, nowMs)}</span>
        </div>
        <div className="tx">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{row.body}</ReactMarkdown>
        </div>
        {row.referencedStageId !== null && (
          <button
            type="button"
            className="drill"
            onClick={() => onOpenStage(row.referencedStageId as string)}
          >
            🔍 Open stage
          </button>
        )}
      </div>
    </div>
  );
}
