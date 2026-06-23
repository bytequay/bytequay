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
import { useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type {
  CiFixHistoryEntry, RealtimeCi, StageConversationRow, StageDetailData,
} from '../../types/brainView';
import type { StageDto, StageType } from '../../types/brainView';
import PromptContextInspector from '../../inspector/PromptContextInspector';
import { ConversationScrubber } from './ConversationScrubber';
import { relativeShort } from './format';
import { CommitsCard, ContextWindowCard } from './RightRail';
import { useStageDetailData } from './useStageDetailData';

type Props = {
  taskId: string;
  stageId: string;
  /** The task's thread, for the shared prompt-context inspector. */
  threadId?: string;
  onBack: () => void;
  onOpenStage: (stageId: string) => void;
  /** Open the standalone code (commit/diff/files) page for this task. */
  onOpenCode?: () => void;
};

/** Compact GitHub mark for the repo chip. */
function GithubMark() {
  return (
    <svg viewBox="0 0 16 16" width={13} height={13} role="img" aria-label="GitHub repo" fill="currentColor">
      <path fillRule="evenodd" clipRule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.66 7.66 0 014 0c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}

/** PascalCase label for a stage type (mirrors StageType.displayName()). */
function stageLabel(type: StageType): string {
  switch (type) {
    case 'PLAN_STAGE': return 'PlanStage';
    case 'DEVELOPMENT_STAGE': return 'DevelopmentStage';
    case 'CI_FIXING_STAGE': return 'CiFixingStage';
    case 'REVIEW_MONITOR_STAGE': return 'ReviewMonitorStage';
    case 'CLEANUP_STAGE': return 'CleanupStage';
    case 'REVIEW_STAGE': return 'ReviewStage';
    default: return type;
  }
}

/** A small glyph logo per stage type for the navigator chips. */
function stageIcon(type: StageType): string {
  switch (type) {
    case 'PLAN_STAGE': return '◆';
    case 'DEVELOPMENT_STAGE': return '⌗';
    case 'CI_FIXING_STAGE': return '⚙';
    case 'REVIEW_MONITOR_STAGE': return '⊚';
    case 'REVIEW_STAGE': return '⊜';
    case 'CLEANUP_STAGE': return '✦';
    default: return '▸';
  }
}

/** Left-border accent per stage type, per the mockup's colour encoding. */
function stageAccent(type: StageType): string {
  switch (type) {
    case 'CI_FIXING_STAGE': return '#d97706';      // amber
    case 'REVIEW_MONITOR_STAGE': return '#7c3aed';  // purple
    case 'DEVELOPMENT_STAGE': return '#2563eb';     // blue
    case 'CLEANUP_STAGE': return '#475569';         // slate
    default: return '#475569';
  }
}

/**
 * The stage drill-in page reached from a brain-view stage chip or a
 * brain-agent drill-in chip. Renders everything the existing data
 * supports: iteration bands with a chronological tool-call / stage-event /
 * summary / user-message log, the derivable metrics subset, realtime CI,
 * the CI-fix history, and the steering composer.
 */

/** Steer the stage's dev agent: a textarea + send button. Disabled (with a
 *  hint) once the stage is closed or paused. ⌘/Ctrl+↵ sends. */
function StageComposer(
  { disabled, onSubmit }: { disabled: boolean; onSubmit: (text: string) => Promise<void> },
) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = () => {
    const trimmed = text.trim();
    if (trimmed === '' || busy || disabled) return;
    setBusy(true);
    setError(null);
    onSubmit(trimmed)
      .then(() => setText(''))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Failed to send'))
      .finally(() => setBusy(false));
  };
  return (
    <div className="composer" aria-label="Stage composer">
      <textarea
        className="t"
        rows={4}
        value={text}
        disabled={disabled || busy}
        placeholder={disabled ? 'Steering unavailable on a closed stage' : 'Steer this stage… (⌘↵ to send)'}
        aria-label="Steering message"
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
        }}
      />
      <button
        type="button"
        className="send-icon"
        disabled={disabled || busy || text.trim() === ''}
        aria-label="Send steering message"
        onClick={submit}
      >↑</button>
      {error !== null && <div className="composer-error" role="alert">{error}</div>}
    </div>
  );
}

export default function TaskStageDetailView({ taskId, stageId, threadId, onBack, onOpenStage, onOpenCode }: Props) {
  const { data, refresh } = useStageDetailData(stageId);
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const bandRefs = useRef<Map<number, HTMLDivElement | null>>(new Map());
  const scrollRef = useRef<HTMLElement | null>(null);
  const jumpToRow = (rowId: string) => {
    scrollRef.current?.querySelector<HTMLElement>(`[data-row-id="${rowId}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  if (data === null) {
    return (
      <div className="stage-detail" style={{ padding: 24 }}>
        <button type="button" onClick={onBack} aria-label="Back to brain view">← Brain</button>
        <p>Loading stage…</p>
      </div>
    );
  }

  const { task, stage, allStages, subStages, iterations, conversation, realtimeCi, ciFixHistory, context } = data;
  const accent = stageAccent(stage.type);
  const nowMs = Date.now();

  const jumpToIteration = (n: number) => {
    bandRefs.current.get(n)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  // A development stage drives in fiery red; when it's actively executing
  // (a running iteration or an ACTIVE state) the background pulses and 🔥
  // marks what's running.
  const isDev = stage.type === 'DEVELOPMENT_STAGE';
  const isExecuting = stage.state === 'ACTIVE' || iterations.some(it => it.endedAt === null);

  return (
    <div className={`stage-detail${isDev ? ' stage-detail--dev' : ''}`
      + `${isDev && isExecuting ? ' stage-detail--executing' : ''}`}>
      {/* ── Breadcrumb top bar ─────────────────────────────────────── */}
      <header className="sd-breadcrumb">
        <button type="button" onClick={onBack} aria-label="Back to brain view">← Brain</button>
        <span className="sd-task-chip">● TASK {task.taskNumber}</span>
        <span className="sd-stage-chip" style={{ borderLeft: `3px solid ${accent}`, paddingLeft: 6 }}>
          {stageLabel(stage.type)}
        </span>
        <span className="sd-repo-chip" title={task.repoFullName}>
          <GithubMark />
          <span className="sd-branch">⎇ {task.branch}</span>
        </span>
        {task.prNumber !== null && (
          <a
            className="sd-pr-chip"
            href={`https://github.com/${task.repoFullName}/pull/${task.prNumber}`}
            target="_blank"
            rel="noreferrer"
          >
            PR #{task.prNumber}{task.prDraft ? ' (draft)' : ''}
          </a>
        )}
        <span className="sd-agent-pill">{task.agentRuntime}{task.agentModel ? ` · ${task.agentModel}` : ''}</span>
        <span className={`sd-iter-status sd-iter-status--${stage.state.toLowerCase()}`}>
          {isExecuting && <span aria-hidden>🔥 </span>}
          {stage.currentIterationNumber !== null
            ? `ITERATION #${stage.currentIterationNumber} · ${stage.state}`
            : `${stage.iterationCount} ITERATION${stage.iterationCount === 1 ? '' : 'S'} · ${stage.state}`}
        </span>
      </header>

      {/* ── Iteration nav strip ────────────────────────────────────── */}
      <nav className="iter-strip" aria-label="Iterations">
        <span className="iter-strip-lbl">Iterations</span>
        {iterations.map(it => (
          <button
            key={it.id}
            type="button"
            className={`iter-pill${it.endedAt === null ? ' iter-pill--running' : ' iter-pill--done'}`}
            onClick={() => jumpToIteration(it.iterationNumber)}
            aria-label={`Jump to iteration ${it.iterationNumber}`}
          >
            #{it.iterationNumber} {it.endedAt === null ? '🔥' : '✓'}
          </button>
        ))}
        {iterations.length === 0 && <span className="iter-empty">No iterations yet.</span>}
        <span className="iter-strip-totals">
          {stage.metrics.wallTimeSec !== undefined && <><b>{fmtDuration(stage.metrics.wallTimeSec)}</b> wall</>}
          {stage.metrics.toolCallsCount !== undefined && <> · <b>{stage.metrics.toolCallsCount}</b> tool calls</>}
          {stage.metrics.turnsCount !== undefined && <> · <b>{stage.metrics.turnsCount}</b> turns</>}
          {stage.metrics.costCents !== undefined && <> · <b>{fmtCost(stage.metrics.costCents)}</b></>}
        </span>
      </nav>

      <div className="stage-body">
        <LeftRail
          allStages={allStages}
          subStages={subStages}
          currentStageId={stage.id}
          realtimeCi={realtimeCi}
          ciFixHistory={ciFixHistory}
          onOpenStage={onOpenStage}
        />

        <div className="center-col">
          <ConversationScrubber
            position="right"
            dashes={conversation
              .filter(r => r.kind === 'user')
              .map(r => ({ id: r.id, label: (r.text ?? '').slice(0, 60), active: false }))}
            onJumpTo={jumpToRow}
          />
          <main className="log-col" aria-label="Stage log" ref={scrollRef}>
            <div className="conv-card">
              {conversation.map(row => (
                <ConversationRowView
                  key={row.id}
                  row={row}
                  accent={accent}
                  nowMs={nowMs}
                  registerRef={row.kind === 'iteration_marker' && row.iterationNumber !== null
                    ? (el) => bandRefs.current.set(row.iterationNumber as number, el)
                    : undefined}
                />
              ))}
              {conversation.length === 0 && (
                <p className="log-empty">No activity in this stage yet.</p>
              )}
            </div>
          </main>
          <StageComposer
            disabled={stage.state === 'CLOSED' || stage.state === 'PAUSED'}
            onSubmit={async (text) => {
              const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
              if (bridge?.steerStage === undefined) return;
              await bridge.steerStage(stage.id, text);
              refresh();
            }}
          />
        </div>

        <RightRail
          stage={stage}
          context={context}
          nowMs={nowMs}
          onViewDiff={onOpenCode}
          onViewContext={() => setInspectorOpen(true)}
        />
      </div>
      {inspectorOpen && threadId !== undefined && (
        <PromptContextInspector
          scope="TASK"
          threadId={threadId}
          taskId={taskId}
          onClose={() => setInspectorOpen(false)}
        />
      )}
    </div>
  );
}

function LeftRail({
  allStages, subStages, currentStageId, realtimeCi, ciFixHistory, onOpenStage,
}: {
  allStages: StageDto[];
  subStages: StageDto[];
  currentStageId: string;
  realtimeCi: RealtimeCi | null;
  ciFixHistory: CiFixHistoryEntry[];
  onOpenStage: (stageId: string) => void;
}) {
  return (
    <aside className="left-rail" aria-label="Stage navigator">
      <section className="lr-section">
        <h3>Stages <span className="lr-count">{allStages.length}</span></h3>
        {allStages.map(s => (
          <button
            key={s.id}
            type="button"
            className={`stage-nav-chip${s.id === currentStageId ? ' current' : ''}`
              + `${s.state === 'CLOSED' ? ' closed' : ''}`}
            aria-current={s.id === currentStageId ? 'true' : undefined}
            onClick={() => onOpenStage(s.id)}
          >
            <span className="stage-nav-chip__logo" aria-hidden style={{ color: stageAccent(s.type) }}>
              {stageIcon(s.type)}
            </span>
            <span className="stage-nav-chip__name">{stageLabel(s.type)}</span>
            <span className="stage-nav-chip__state">{s.state}</span>
          </button>
        ))}
      </section>
      {subStages.length > 0 && (
        <section className="lr-section">
          <h3>Sub-stages <span className="lr-count">{subStages.length} panels</span></h3>
          {subStages.map(s => (
            <button key={s.id} type="button" className="stage-nav-chip" onClick={() => onOpenStage(s.id)}>
              <span className="stage-nav-chip__logo" aria-hidden style={{ color: stageAccent(s.type) }}>
                {stageIcon(s.type)}
              </span>
              <span className="stage-nav-chip__name">{stageLabel(s.type)}</span>
            </button>
          ))}
        </section>
      )}

      <section className="realtime-ci" aria-label="Realtime CI">
        <h3>Realtime CI{realtimeCi !== null && <span className="lr-badge">polling</span>}</h3>
        {realtimeCi === null
          ? <p className="muted">No linked PR.</p>
          : (
            <>
              <div className={`ci-status ci-${realtimeCi.status}`}>{realtimeCi.status.toUpperCase()}</div>
              {realtimeCi.checks.map(c => (
                <div key={c.name} className={`ci-check ci-${c.status}`}>
                  {c.status === 'ok' ? '✓' : c.status === 'fail' ? '✗' : '◷'} {c.name}
                </div>
              ))}
            </>
          )}
      </section>

      {/* CI fix history is a CI-fixing-stage concept — only show it when the
          stage actually accumulated fixes (never on the dev/plan stage). */}
      {ciFixHistory.length > 0 && (
        <section className="ci-fix-history" aria-label="CI fix history">
          <h3>CI fix history<span className="lr-count">{ciFixHistory.length} fixes</span></h3>
          {ciFixHistory.map(f => (
            <div key={f.iterationNumber} className="ci-fix-card">
              <span className="ci-fix-iter">#{f.iterationNumber}</span>{' '}
              {/* Enriched red-CI iters lead with the failing check (hover for
                  the full error); older iters fall back to the ended reason. */}
              {f.failedCheck !== undefined
                ? <span className="ci-fix-check" title={f.errorMessage ?? f.failedCheck}>{f.failedCheck}</span>
                : <span className="ci-fix-reason">{f.endedReason ?? 'in progress'}</span>}
              {f.actionsRunUrl !== undefined && (
                <a className="ci-fix-link" href={f.actionsRunUrl} aria-label="Open the Actions run">↗</a>
              )}
              {f.summaryText !== null && <div className="ci-fix-summary">{f.summaryText}</div>}
            </div>
          ))}
        </section>
      )}
    </aside>
  );
}

/**
 * One row of the stage transcript. The conversation is the base timeline;
 * an {@code iteration_marker} renders as a band header (layered in for the
 * looping stages), agent/user turns as bubbles, and tool calls as compact
 * tagged rows.
 */
function ConversationRowView({
  row, accent, nowMs, registerRef,
}: {
  row: StageConversationRow;
  accent: string;
  nowMs: number;
  registerRef?: (el: HTMLDivElement | null) => void;
}) {
  if (row.kind === 'iteration_marker') {
    return (
      <div className="sd-iter-marker" id={row.id} data-row-id={row.id} ref={registerRef}
        style={{ borderLeftColor: accent }}>
        <span className="sd-iter-marker__n">#{row.iterationNumber}</span>
        {row.text !== null && row.text !== '' && (
          <span className="sd-iter-marker__trigger">{row.text}</span>
        )}
      </div>
    );
  }
  if (row.kind === 'tool_call') {
    // Wire fields can be absent (undefined), not just null — guard with a
    // type check so a missing field never reaches `.split()` / rendering.
    const detail = typeof row.toolDetail === 'string' && row.toolDetail !== '' ? row.toolDetail : null;
    const result = typeof row.toolResult === 'string' && row.toolResult !== '' ? row.toolResult : null;
    const diff = typeof row.toolDiff === 'string' && row.toolDiff !== '' ? row.toolDiff : null;
    const klass = `tool-card tool-${(row.toolTag ?? '').toLowerCase()}`
      + (row.toolError ? ' tool-card--error' : '');
    return (
      <details className={klass} id={row.id} data-row-id={row.id} open={row.toolError === true}>
        <summary className="tool-card__head">
          <span className="tool-tag">{row.toolTag ?? 'tool'}</span>
          <span className="tool-label">{row.toolLabel ?? ''}</span>
          {detail !== null && <code className="tool-detail">{detail}</code>}
          {row.toolError && <span className="tool-card__badge">error</span>}
          <span className="tool-time">{relativeShort(row.ts, nowMs)}</span>
        </summary>
        <div className="tool-card__body">
          {detail !== null && (
            <div className="tool-log">
              <div className="tool-log__label">{diff !== null ? 'File' : 'Command'}</div>
              <pre className="tool-log__pre">{detail}</pre>
            </div>
          )}
          {diff !== null && (
            <div className="tool-log">
              <div className="tool-log__label">Diff</div>
              <pre className="tool-log__pre tool-diff">
                {diff.split('\n').map((line, i) => (
                  <span
                    key={i}
                    className={'tool-diff__line'
                      + (line.startsWith('+') ? ' diff-add' : line.startsWith('-') ? ' diff-del' : '')}
                  >{line}{'\n'}</span>
                ))}
              </pre>
            </div>
          )}
          <div className="tool-log">
            <div className="tool-log__label">Result</div>
            <pre className={`tool-log__pre${row.toolError ? ' tool-log__pre--error' : ''}`}>
              {result !== null ? result : '(no output captured)'}
            </pre>
          </div>
        </div>
      </details>
    );
  }
  if (row.kind === 'user') {
    // Soft-mint right-aligned bubble, matching the brain feed's you-msg
    // (no YOU label/avatar).
    return (
      <div className="ev you-msg" id={row.id} data-row-id={row.id}>
        <div className="body">
          <div className="tx">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{row.text ?? ''}</ReactMarkdown>
          </div>
        </div>
      </div>
    );
  }
  // Agent turn: persona icon + label + text, matching the brain feed's .ev row.
  return (
    <div className="ev" id={row.id} data-row-id={row.id}>
      <span className="ic agent" aria-hidden>⊹</span>
      <div className="body">
        <div className="who-row">
          <span className="who agent">AGENT</span>
          <span className="ts">{relativeShort(row.ts, nowMs)}</span>
        </div>
        <div className="tx">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{row.text ?? ''}</ReactMarkdown>
        </div>
      </div>
    </div>
  );
}


/** A labelled metrics group; renders nothing when it has no present rows. */
function MetricGroup({ title, rows }: { title: string; rows: Array<[string, string]> }) {
  if (rows.length === 0) return null;
  return (
    <div className="metric-group">
      <div className="metric-group-h">{title}</div>
      {rows.map(([label, value]) => (
        <div key={label} className="metric-row">
          <span className="metric-label">{label}</span>
          <span className="metric-value">{value}</span>
        </div>
      ))}
    </div>
  );
}

/** Compact token count (122400 → "122.4k"). */
/** Human duration: "3h 56m" / "8m 12s" / "15s". */
function fmtDuration(sec: number): string {
  if (sec >= 3600) return `${Math.floor(sec / 3600)}h ${Math.round((sec % 3600) / 60)}m`;
  if (sec >= 60) return `${Math.floor(sec / 60)}m ${sec % 60}s`;
  return `${sec}s`;
}

/** Cents → dollars: 31 → "$0.31". */
function fmtCost(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

function RightRail({ stage, context, nowMs, onViewDiff, onViewContext }: {
  stage: StageDetailData['stage'];
  context: StageDetailData['context'];
  nowMs: number;
  onViewDiff?: () => void;
  onViewContext: () => void;
}) {
  const m = stage.metrics;
  const budget = stage.config.autoPushBudget;
  const rows = (...entries: Array<[string, number | undefined, string?]>): Array<[string, string]> =>
    entries.filter(([, v]) => v !== undefined).map(([l, v, s = '']) => [l, `${v}${s}`]);

  const opsRows: Array<[string, string]> = [];
  if (m.operationsCount !== undefined) {
    const total = Object.values(m.operationsCount).reduce((a, b) => a + b, 0);
    opsRows.push(['Operations', String(total)]);
    for (const [kind, n] of Object.entries(m.operationsCount)) opsRows.push([`· ${kind}`, String(n)]);
  }
  if (m.loopIterations !== undefined) opsRows.push(['Loop iterations', String(m.loopIterations)]);

  return (
    <aside className="right-rail" aria-label="Stage details">
      <section className="stage-identity-card">
        <h3>{stageLabel(stage.type)}</h3>
        <div className={`si-state si-state--${stage.state.toLowerCase()}`}>{stage.state}</div>
        <div className="si-row"><span>Opened</span><span className="si-mono">{stage.openedAt}</span></div>
        {stage.closedAt !== null && (
          <div className="si-row"><span>Closed</span><span className="si-mono">{stage.closedAt}</span></div>
        )}
        {stage.currentIterationNumber !== null && (
          <div className="si-row">
            <span>Iteration</span><span className="si-mono">{stage.currentIterationNumber} / loop</span>
          </div>
        )}
        <div className="si-row">
          <span>internal_review</span>
          <span className="si-mono">{stage.config.internalReviewEnabled ? 'ON' : 'OFF'}</span>
        </div>
        {m.terminalState !== undefined && (
          <div className="si-row"><span>Terminal state</span><span className="si-mono">{m.terminalState}</span></div>
        )}
      </section>

      <section className="metrics-card" aria-label="Stage metrics">
        <h3>Metrics <span className="metrics-sub">this stage</span></h3>
        <MetricGroup title="Time" rows={[
          ...(m.wallTimeSec !== undefined ? [['Wall time', fmtDuration(m.wallTimeSec)] as [string, string]] : []),
          ...(m.activeTimeSec !== undefined ? [['Active time', fmtDuration(m.activeTimeSec)] as [string, string]] : []),
          ...(m.waitingUserTimeSec !== undefined ? [['Waiting on you', fmtDuration(m.waitingUserTimeSec)] as [string, string]] : []),
        ]} />
        <MetricGroup title="Operations" rows={opsRows} />
        <MetricGroup title="Agent work" rows={[
          ...rows(
            ['Tool calls', m.toolCallsCount],
            ['Turns', m.turnsCount],
            ['Messages', m.messagesCount],
            ['Tokens', m.tokensCount],
          ),
          ...(m.costCents !== undefined ? [['Cost', fmtCost(m.costCents)] as [string, string]] : []),
        ]} />
        <MetricGroup title="Health" rows={[
          ...rows(['Interventions', m.interventionsCount], ['Backflows', m.backflowsCount]),
          ['Panels', String(m.panelInvocationsCount)],
        ]} />
      </section>

      {budget !== undefined && budget !== null && (
        <section className="budget-card" aria-label="Auto-push budget">
          <h3>Auto-push budget</h3>
          <div className="budget-pips" aria-hidden>
            {Array.from({ length: budget.limit }, (_, i) => (
              <span key={i} className={i < budget.used ? 'pip pip--used' : 'pip'} />
            ))}
          </div>
          <div className="budget-label">
            {budget.used} / {budget.limit}
            {budget.used >= budget.limit && <span className="budget-exhausted"> · EXHAUSTED</span>}
          </div>
        </section>
      )}

      {/* Reuse the brain window's commits + context-window cards so every
          task window (trunk / brain / stage) shares one design. */}
      <CommitsCard commits={[]} nowMs={nowMs} onViewDiff={onViewDiff ?? (() => {})} />
      <ContextWindowCard ctx={context} onViewContext={onViewContext} />
    </aside>
  );
}
