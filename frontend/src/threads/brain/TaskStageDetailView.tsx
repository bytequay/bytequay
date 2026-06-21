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
      <header className="sd-breadcrumb" style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px', borderBottom: '1px solid #e2e8f0' }}>
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
        <span className="sd-iter-pill">
          {stage.currentIterationNumber !== null
            ? `iter #${stage.currentIterationNumber}`
            : `${stage.iterationCount} iterations`}
        </span>
      </header>

      {/* ── Iteration nav strip ────────────────────────────────────── */}
      <nav className="iter-strip" aria-label="Iterations" style={{ display: 'flex', gap: 6, padding: '6px 12px', borderBottom: '1px solid #e2e8f0' }}>
        {iterations.map(it => (
          <button
            key={it.id}
            type="button"
            className="iter-pill"
            onClick={() => jumpToIteration(it.iterationNumber)}
            aria-label={`Jump to iteration ${it.iterationNumber}`}
          >
            #{it.iterationNumber} {it.endedAt === null ? '◼' : '✓'}
          </button>
        ))}
        {iterations.length === 0 && <span className="iter-empty">No iterations yet.</span>}
      </nav>

      <div
        className="stage-body"
        style={{ display: 'grid', gridTemplateColumns: '252px minmax(0, 1fr) 308px', gap: 12 }}
      >
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
        <h3>Stages</h3>
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
          <h3>Sub-stages</h3>
          {subStages.map(s => (
            <button key={s.id} type="button" className="stage-nav-chip" onClick={() => onOpenStage(s.id)}>
              {stageLabel(s.type)}
            </button>
          ))}
        </section>
      )}

      <section className="realtime-ci" aria-label="Realtime CI">
        <h3>Realtime CI</h3>
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
        <h3>CI fix history</h3>
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

function LogRow({ row }: { row: StageLogRow }) {
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
      <div className="iter-sum" style={{ borderStyle: 'dashed' }}>
        <div className="iter-sum-text">{row.iterationSummary.text}</div>
        <div className="iter-sum-meta">
          summarize_iteration() · {row.iterationSummary.recordedBy ?? 'recorded'}
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

function RightRail({ stage, context }: { stage: StageDetailData['stage']; context: StageDetailData['context'] }) {
  const m = stage.metrics;
  const budget = stage.config.autoPushBudget;
  const metricRows: Array<[string, string]> = [];
  const push = (label: string, value: number | undefined, suffix = '') => {
    if (value !== undefined) metricRows.push([label, `${value}${suffix}`]);
  };
  push('Wall time', m.wallTimeSec, 's');
  push('Active time', m.activeTimeSec, 's');
  push('Waiting on you', m.waitingUserTimeSec, 's');
  push('Iterations', m.loopIterations);
  push('Tool calls', m.toolCallsCount);
  push('Turns', m.turnsCount);
  push('Messages', m.messagesCount);
  push('Interventions', m.interventionsCount);
  push('Backflows', m.backflowsCount);
  push('Tokens', m.tokensCount);
  push('Cost', m.costCents, '¢');
  metricRows.push(['Panels', String(m.panelInvocationsCount)]);
  if (m.operationsCount !== undefined) {
    const ops = Object.entries(m.operationsCount).map(([k, n]) => `${k} ${n}`).join(' · ');
    if (ops.length > 0) metricRows.push(['Operations', ops]);
  }

  return (
    <aside className="right-rail" aria-label="Stage details">
      <section className="stage-identity-card">
        <h3>{stageLabel(stage.type)}</h3>
        <div className="si-state">{stage.state}</div>
        <div className="si-times">
          opened {stage.openedAt}{stage.closedAt !== null ? ` · closed ${stage.closedAt}` : ''}
        </div>
        {m.terminalState !== undefined && <div className="si-terminal">{m.terminalState}</div>}
      </section>

      <section className="metrics-card" aria-label="Stage metrics">
        <h3>Metrics</h3>
        {metricRows.map(([label, value]) => (
          <div key={label} className="metric-row">
            <span className="metric-label">{label}</span>
            <span className="metric-value">{value}</span>
          </div>
        ))}
      </section>

      {budget !== undefined && (
        <section className="budget-card" aria-label="Auto-push budget">
          <h3>Auto-push budget</h3>
          <div className="budget-pips">{budget.used} / {budget.limit}</div>
        </section>
      )}

      <section className="context-card" aria-label="Context window">
        <h3>Context</h3>
        <div className={`context-band context-${context.safeBand}`}>
          {context.tokensUsed} / {context.tokensLimit} tokens
        </div>
      </section>
    </aside>
  );
}
