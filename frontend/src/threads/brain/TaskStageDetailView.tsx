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
import type {
  CiFixHistoryEntry, IterationDetail, RealtimeCi, StageDetailData, StageLogRow,
} from '../../types/brainView';
import type { StageDto, StageType } from '../../types/brainView';
import { useStageDetailData } from './useStageDetailData';

type Props = {
  taskId: string;
  stageId: string;
  onBack: () => void;
  onOpenStage: (stageId: string) => void;
};

/** PascalCase label for a stage type (mirrors StageType.displayName()). */
function stageLabel(type: StageType): string {
  switch (type) {
    case 'DEVELOPMENT_STAGE': return 'DevelopmentStage';
    case 'CI_FIXING_STAGE': return 'CiFixingStage';
    case 'REVIEW_MONITOR_STAGE': return 'ReviewMonitorStage';
    case 'CLEANUP_STAGE': return 'CleanupStage';
    case 'REVIEW_STAGE': return 'ReviewStage';
    default: return type;
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
        rows={2}
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

export default function TaskStageDetailView({ taskId, stageId, onBack, onOpenStage }: Props) {
  const { data, refresh } = useStageDetailData(stageId);
  const bandRefs = useRef<Map<number, HTMLDivElement | null>>(new Map());

  if (data === null) {
    return (
      <div className="stage-detail" style={{ padding: 24 }}>
        <button type="button" onClick={onBack} aria-label="Back to brain view">← Brain</button>
        <p>Loading stage…</p>
      </div>
    );
  }

  const { task, stage, allStages, subStages, iterations, realtimeCi, ciFixHistory, context } = data;
  const accent = stageAccent(stage.type);

  const jumpToIteration = (n: number) => {
    bandRefs.current.get(n)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div className="stage-detail">
      {/* ── Breadcrumb top bar ─────────────────────────────────────── */}
      <header className="sd-breadcrumb">
        <button type="button" onClick={onBack} aria-label="Back to brain view">← Brain</button>
        <span className="sd-task-chip">● TASK {task.taskNumber}</span>
        <span className="sd-stage-chip" style={{ borderLeft: `3px solid ${accent}`, paddingLeft: 6 }}>
          {stageLabel(stage.type)}
        </span>
        <span className="sd-repo-chip">{task.repoFullName}</span>
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
            #{it.iterationNumber} {it.endedAt === null ? '◼' : '✓'}
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

        <main className="log-col" aria-label="Stage log">
          {iterations.map(it => (
            <IterationBand
              key={it.id}
              iteration={it}
              accent={accent}
              registerRef={(el) => bandRefs.current.set(it.iterationNumber, el)}
            />
          ))}
          {iterations.length === 0 && (
            <p className="log-empty">This stage has no iterations to show yet.</p>
          )}
          <StageComposer
            disabled={stage.state === 'CLOSED' || stage.state === 'PAUSED'}
            onSubmit={async (text) => {
              const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
              if (bridge?.steerStage === undefined) return;
              await bridge.steerStage(stage.id, text);
              refresh();
            }}
          />
        </main>

        <RightRail stage={stage} context={context} />
      </div>
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
            className={s.id === currentStageId ? 'stage-nav-chip current' : 'stage-nav-chip'}
            aria-current={s.id === currentStageId ? 'true' : undefined}
            onClick={() => onOpenStage(s.id)}
          >
            {stageLabel(s.type)} · {s.state}
          </button>
        ))}
      </section>
      {subStages.length > 0 && (
        <section className="lr-section">
          <h3>Sub-stages <span className="lr-count">{subStages.length} panels</span></h3>
          {subStages.map(s => (
            <button key={s.id} type="button" className="stage-nav-chip" onClick={() => onOpenStage(s.id)}>
              {stageLabel(s.type)}
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

      <section className="ci-fix-history" aria-label="CI fix history">
        <h3>CI fix history{ciFixHistory.length > 0 && <span className="lr-count">{ciFixHistory.length} fixes</span>}</h3>
        {ciFixHistory.length === 0
          ? <p className="muted">—</p>
          : ciFixHistory.map(f => (
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
    </aside>
  );
}

function IterationBand({
  iteration, accent, registerRef,
}: {
  iteration: IterationDetail;
  accent: string;
  registerRef: (el: HTMLDivElement | null) => void;
}) {
  return (
    <div className="iter-band" ref={registerRef} style={{ borderLeft: `3px solid ${accent}`, marginBottom: 16 }}>
      <div className="iter-band-header" style={{ background: `${accent}14`, padding: '4px 8px' }}>
        <strong>#{iteration.iterationNumber}</strong> · {iteration.trigger}
        {iteration.endedReason !== null && <span className="iter-reason"> · {iteration.endedReason}</span>}
      </div>
      <div className="iter-log">
        {iteration.log.map(row => <LogRow key={row.id} row={row} />)}
      </div>
    </div>
  );
}

/** Left-border accent per operation kind (color-codes the card). */
const OPERATION_COLORS: Record<string, string> = {
  code: '#d97706',     // amber
  validate: '#2563eb', // blue
  push: '#16a34a',     // green
  publish: '#7c3aed',  // purple
};

function LogRow({ row }: { row: StageLogRow }) {
  if (row.kind === 'operation' && row.operation) {
    const op = row.operation;
    return (
      <div
        className={`operation-card operation-${op.operation}`}
        style={{ borderLeft: `3px solid ${OPERATION_COLORS[op.operation] ?? '#6b7280'}` }}
      >
        <div className="operation-head">
          <span className="operation-kind">{op.operation}</span>
          <span className="operation-meta">
            {op.toolCallCount} {op.toolCallCount === 1 ? 'tool call' : 'tool calls'} · {op.durationSec}s
            {op.status === 'failed' ? ' · failed' : ''}
          </span>
        </div>
        <div className="operation-tools">
          {op.toolCalls.map(tc => <LogRow key={tc.id} row={tc} />)}
        </div>
      </div>
    );
  }
  if (row.kind === 'tool_call' && row.toolCall) {
    return (
      <div className={`tool-row tool-${row.toolCall.tag.toLowerCase()}`}>
        <span className="tool-tag">{row.toolCall.tag}</span>
        <span className="tool-label">{row.toolCall.label}</span>
        {row.toolCall.detail !== null && <span className="tool-detail"> {row.toolCall.detail}</span>}
      </div>
    );
  }
  if (row.kind === 'stage_event' && row.stageEvent) {
    return (
      <div className="stage-event-row" style={{ borderStyle: 'dashed' }}>
        <span className="event-badge">{row.stageEvent.eventType}</span> {row.stageEvent.message}
      </div>
    );
  }
  if (row.kind === 'iteration_summary' && row.iterationSummary) {
    return (
      <div className="iter-sum">
        <span className="iter-sum-ic" aria-hidden>⊕</span>
        <div className="iter-sum-body">
          <div className="iter-sum-hd">Iteration summary</div>
          <div className="iter-sum-text">{row.iterationSummary.text}</div>
          <div className="iter-sum-meta">
            summarize_iteration() · {row.iterationSummary.recordedBy ?? 'recorded'}
          </div>
        </div>
      </div>
    );
  }
  if (row.kind === 'user_message' && row.userMessage) {
    return (
      <div className="you-bubble" style={{ textAlign: 'right' }}>
        <span className="you-avatar" aria-hidden>YOU</span> {row.userMessage.text}
      </div>
    );
  }
  return null;
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
function tokensShort(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}

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

function RightRail({ stage, context }: { stage: StageDetailData['stage']; context: StageDetailData['context'] }) {
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

  const pct = context.tokensLimit > 0
    ? Math.round((context.tokensUsed / context.tokensLimit) * 100) : 0;

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

      <section className="context-card" aria-label="Context window">
        <h3>Context <span className="metrics-sub">dev agent</span></h3>
        <div className={`context-pct context-${context.safeBand}`}>{pct}% · {context.safeBand}</div>
        <div className="context-bar" aria-hidden>
          <span className={`context-bar-fill context-${context.safeBand}`} style={{ width: `${pct}%` }} />
        </div>
        <div className="context-nums">
          {tokensShort(context.tokensUsed)} / {tokensShort(context.tokensLimit)} tokens
        </div>
      </section>
    </aside>
  );
}
