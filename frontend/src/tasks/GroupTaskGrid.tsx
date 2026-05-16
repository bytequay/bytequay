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
import { useEffect, useMemo, useState } from 'react';
import type { TaskDto, TaskGroupDto, TaskMessageDto, TaskStatusDto } from '../types';
import GroupMenu from './GroupMenu';

type Props = {
  tasks: TaskDto[];
  groups: TaskGroupDto[];
  /** Click a tile → open the full detail page. The tile preview is
   *  read-only; per-tile send boxes are deferred until we have a good
   *  story for managing N independent conversations on one screen. */
  onOpen: (taskId: string) => void;
  /** Reassign a task to another group (or null to unpin). The page
   *  parent persists the change and refreshes. */
  onMoveGroup: (taskId: string, groupId: string | null) => void | Promise<void>;
};

const TILE_PREVIEW_LIMIT = 8;
const POLL_MS = 4000;

/**
 * Two-column grid of large task tiles, one per group member.
 * Each tile mirrors the structured-detail layout in miniature:
 * status header, scrollable recent-activity bullets, footer with
 * runtime/cost. Click anywhere to jump to the full detail page.
 *
 * <p>Faithfully follows {@code docs/mockups/design/tasks/tasks-group.png}
 * minus the per-tile send box, which would require running N
 * conversations in parallel and is out of scope for this slice.
 */
export default function GroupTaskGrid({ tasks, groups, onOpen, onMoveGroup }: Props) {
  const [previews, setPreviews] = useState<Record<string, TaskMessageDto[]>>({});

  // Fan-out: pull recent messages for each tile in parallel. The
  // backend is local so the latency cost is small even for the
  // largest groups users are likely to assemble.
  useEffect(() => {
    let cancelled = false;
    const ids = tasks.map(t => t.id);
    async function refresh() {
      const results = await Promise.all(ids.map(async id => {
        try {
          const ms = await window.bridge.getTaskMessages(id);
          return [id, ms] as const;
        }
        catch {
          return [id, [] as TaskMessageDto[]] as const;
        }
      }));
      if (cancelled) return;
      const next: Record<string, TaskMessageDto[]> = {};
      for (const [id, ms] of results) next[id] = ms;
      setPreviews(next);
    }
    void refresh();
    const handle = window.setInterval(() => { void refresh(); }, POLL_MS);
    return () => { cancelled = true; window.clearInterval(handle); };
  }, [tasks]);

  if (tasks.length === 0) {
    return (
      <div style={emptyStyle}>
        <div style={emptyTitleStyle}>No tasks in this group yet</div>
        <div style={mutedStyle}>
          Use <strong>+ Add task</strong> above to start one — it'll be
          pinned to this group automatically.
        </div>
      </div>
    );
  }

  return (
    <div style={gridStyle}>
      {tasks.map(t => (
        <TaskTile
          key={t.id}
          task={t}
          groups={groups}
          messages={previews[t.id] ?? []}
          onOpen={() => onOpen(t.id)}
          onMoveGroup={onMoveGroup}
        />
      ))}
    </div>
  );
}

function TaskTile({ task, groups, messages, onOpen, onMoveGroup }: {
  task: TaskDto;
  groups: TaskGroupDto[];
  messages: TaskMessageDto[];
  onOpen: () => void;
  onMoveGroup: (taskId: string, groupId: string | null) => void | Promise<void>;
}) {
  const recent = useMemo(
    () => messages.slice(-TILE_PREVIEW_LIMIT).filter(visibleInTile),
    [messages]);
  return (
    <article
      style={tileStyle}
      onClick={onOpen}
      role="button"
      tabIndex={0}
      onKeyDown={e => { if (e.key === 'Enter') onOpen(); }}
    >
      <header style={tileHeaderStyle}>
        <div style={tileTitleWrapStyle}>
          <span style={{ ...tileStripeStyle, background: stripeColor(task.status) }} />
          <div style={tileTitleStyle}>{task.title}</div>
        </div>
        <div style={tileHeaderRightStyle}>
          <StatusBadge status={task.status} />
          <GroupMenu task={task} groups={groups} onChange={onMoveGroup} />
        </div>
      </header>
      <div style={tileBodyStyle}>
        {recent.length === 0 && (
          <div style={emptyPreviewStyle}>
            Waiting for the first turn…
          </div>
        )}
        {recent.map(m => (
          <MessageLine key={m.id} message={m} />
        ))}
      </div>
      <footer style={tileFooterStyle}>
        <span style={footerMetaStyle}>{task.model}</span>
        <span style={footerSepStyle}>·</span>
        <span style={footerMetaStyle}>{formatAge(task.updatedAt)}</span>
        <div style={{ flex: 1 }} />
        <span style={footerMetricStyle}>{formatCost(task.costUsdMilli)}</span>
        <span style={footerSepStyle}>·</span>
        <span style={footerMetricStyle}>
          {formatTokens(task.tokensIn + task.tokensOut)} tok
        </span>
      </footer>
    </article>
  );
}

function MessageLine({ message }: { message: TaskMessageDto }) {
  const parsed = useMemo(() => safeParseContent(message), [message]);
  if (message.type === 'tool_call') {
    const tool = String(parsed?.toolName ?? 'tool');
    const summary = summariseToolInput(tool, parsed?.input);
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: toolColor(tool) }}>{tool}</span>
        <span style={lineBodyStyle}>{summary}</span>
      </div>
    );
  }
  if (message.type === 'tool_result') {
    const ok = parsed?.isError !== true;
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: ok ? '#047857' : '#b91c1c' }}>
          {ok ? '✓' : '✗'}
        </span>
        <span style={lineBodyStyle}>{summariseResult(parsed)}</span>
      </div>
    );
  }
  if (message.type === 'text' && message.role === 'assistant') {
    const text = String(parsed?.text ?? '');
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: '#6b7280' }}>›</span>
        <span style={{ ...lineBodyStyle, color: '#1f2937' }}>{truncate(text, 160)}</span>
      </div>
    );
  }
  if (message.type === 'text' && message.role === 'user') {
    const text = String(parsed?.text ?? '');
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: '#7c3aed' }}>you</span>
        <span style={{ ...lineBodyStyle, color: '#374151' }}>{truncate(text, 160)}</span>
      </div>
    );
  }
  if (message.type === 'permission_request') {
    const tool = String(parsed?.toolName ?? 'tool');
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: '#d97706' }}>?</span>
        <span style={lineBodyStyle}>
          awaiting approval for <strong>{tool}</strong>
        </span>
      </div>
    );
  }
  return null;
}

function StatusBadge({ status }: { status: TaskStatusDto }) {
  const palette: Record<TaskStatusDto, { fg: string; bg: string; label: string }> = {
    RUNNING:   { fg: '#fff', bg: '#10b981', label: 'RUN' },
    AWAITING:  { fg: '#fff', bg: '#d97706', label: 'WAIT' },
    PENDING:   { fg: '#374151', bg: '#e5e7eb', label: 'QUEUED' },
    IDLE:      { fg: '#374151', bg: '#e5e7eb', label: 'IDLE' },
    COMPLETED: { fg: '#fff', bg: '#047857', label: 'DONE' },
    ERRORED:   { fg: '#fff', bg: '#dc2626', label: 'ERR' },
  };
  const p = palette[status];
  return (
    <span style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 6,
      padding: '2px 10px',
      borderRadius: 999,
      background: p.bg,
      color: p.fg,
      fontSize: 11,
      fontWeight: 700,
      letterSpacing: 0.4,
    }}>
      {status === 'RUNNING' && <span style={pulseDotStyle} />}
      {p.label}
    </span>
  );
}

// ────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────

/** Keep the tile preview to entries that carry signal — drop the
 *  bookkeeping events that bloat the message log but don't help a
 *  reader skim what's happening. */
function visibleInTile(m: TaskMessageDto): boolean {
  return m.type === 'tool_call'
      || m.type === 'tool_result'
      || m.type === 'text'
      || m.type === 'permission_request';
}

function safeParseContent(m: TaskMessageDto): Record<string, unknown> | null {
  try {
    return JSON.parse(m.contentJson) as Record<string, unknown>;
  }
  catch {
    return null;
  }
}

function summariseToolInput(tool: string, input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const obj = input as Record<string, unknown>;
  // Best-effort prettifier covering the most common Claude Code tools.
  if (typeof obj.file_path === 'string') return obj.file_path;
  if (typeof obj.path === 'string') return obj.path;
  if (typeof obj.pattern === 'string') return obj.pattern;
  if (typeof obj.command === 'string') return truncate(obj.command, 120);
  if (typeof obj.description === 'string') return truncate(obj.description, 120);
  return '';
}

function summariseResult(parsed: Record<string, unknown> | null): string {
  if (!parsed) return '';
  if (typeof parsed.summary === 'string') return truncate(parsed.summary, 140);
  if (typeof parsed.output === 'string') return truncate(parsed.output, 140);
  return '';
}

function toolColor(tool: string): string {
  switch (tool.toLowerCase()) {
    case 'read':  return '#2563eb';
    case 'write': return '#7c3aed';
    case 'edit':  return '#9333ea';
    case 'bash':  return '#0f766e';
    case 'grep':  return '#0891b2';
    case 'glob':  return '#0891b2';
    default:      return '#374151';
  }
}

function stripeColor(s: TaskStatusDto): string {
  switch (s) {
    case 'RUNNING':   return '#10b981';
    case 'AWAITING':  return '#d97706';
    case 'IDLE':      return '#9ca3af';
    case 'PENDING':   return '#9ca3af';
    case 'COMPLETED': return '#047857';
    case 'ERRORED':   return '#dc2626';
  }
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

function formatAge(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

function formatCost(milli: number): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

function formatTokens(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1000_000).toFixed(1)}M`;
}

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

const gridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fill, minmax(420px, 1fr))',
  gap: 16,
};

const tileStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: '#fff',
  border: '1px solid #e5e7eb',
  borderRadius: 10,
  overflow: 'hidden',
  cursor: 'pointer',
  transition: 'border-color 0.12s ease, box-shadow 0.12s ease',
  minHeight: 280,
};
const tileHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 12,
  padding: '12px 14px',
  borderBottom: '1px solid #f1f5f9',
};
const tileHeaderRightStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  flexShrink: 0,
};
const tileTitleWrapStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  minWidth: 0,
  flex: 1,
};
const tileStripeStyle: React.CSSProperties = {
  width: 3,
  alignSelf: 'stretch',
  borderRadius: 2,
  flexShrink: 0,
};
const tileTitleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  color: '#0f172a',
  lineHeight: 1.35,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  display: '-webkit-box',
  WebkitLineClamp: 2,
  WebkitBoxOrient: 'vertical',
};
const tileBodyStyle: React.CSSProperties = {
  flex: 1,
  padding: '10px 14px',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  overflowY: 'auto',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  background: '#fafafa',
};
const emptyPreviewStyle: React.CSSProperties = {
  fontFamily: 'inherit',
  fontSize: 12,
  color: '#9ca3af',
  fontStyle: 'italic',
  padding: '20px 0',
  textAlign: 'center',
};
const lineStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'baseline',
  lineHeight: 1.4,
};
const lineGlyphStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  textTransform: 'uppercase',
  flexShrink: 0,
  minWidth: 36,
};
const lineBodyStyle: React.CSSProperties = {
  color: '#4b5563',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flex: 1,
  minWidth: 0,
};
const tileFooterStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '8px 14px',
  borderTop: '1px solid #f1f5f9',
  background: '#fff',
  fontSize: 11,
};
const footerMetaStyle: React.CSSProperties = { color: '#6b7280' };
const footerSepStyle: React.CSSProperties = { color: '#d1d5db' };
const footerMetricStyle: React.CSSProperties = {
  color: '#374151',
  fontWeight: 600,
  fontVariantNumeric: 'tabular-nums',
};
const pulseDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  borderRadius: '50%',
  background: '#fff',
};
const emptyStyle: React.CSSProperties = {
  padding: '40px 24px',
  textAlign: 'center',
  border: '1px dashed #d1d5db',
  borderRadius: 8,
};
const emptyTitleStyle: React.CSSProperties = { fontSize: 16, fontWeight: 600, marginBottom: 4 };
const mutedStyle: React.CSSProperties = { color: '#6b7280', fontSize: 13 };
