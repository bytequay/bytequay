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
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent } from 'react';
import {
  Bot,
  ChevronDown,
  ChevronRight,
  CircleCheck,
  CircleHelp,
  CircleMinus,
  CircleX,
  Clock,
  LoaderCircle,
  Play,
  Square,
  UserRound,
  X,
} from 'lucide-react';
import type { CodeHostRef, LocalRunDto, LocalRunMethodDto, LocalRunOutputDto, ReviewCodeFilesDto } from './contracts';
import {
  failedRows,
  formatDuration,
  methodRows,
  runIsStale,
  runLabel,
  runMeta,
  runSummary,
  scopeText,
  shownResults,
  statusLabel,
  statusTone,
} from './runModel';
import { shortSha } from './codeModel';
import { usePopoverDismiss } from '../../usePopoverDismiss';
import TerminalOutput from './TerminalOutput';

type SubTab = 'tests' | 'details';
type OutputState = { text: string; offset: number; complete: boolean; snapshot: boolean };

const MIN_HEIGHT = 96;
const MAX_HEIGHT = 560;
const MIN_TREE_WIDTH = 160;
const HISTORY_WIDTH = 380;
const MIN_LOG_WIDTH = 220;

function StatusIcon({ run }: { run: LocalRunDto }) {
  const tone = statusTone(run.status);
  const label = statusLabel(run.status);
  if (tone === 'running') return <LoaderCircle className="ct-spin ct-status is-running" size={12} strokeWidth={2} aria-label={label} />;
  if (tone === 'pass') return <CircleCheck className="ct-status is-pass" size={12} strokeWidth={2} aria-label={label} />;
  if (tone === 'fail') return <CircleX className="ct-status is-fail" size={12} strokeWidth={2} aria-label={label} />;
  return <CircleHelp className="ct-status is-neutral" size={12} strokeWidth={2} aria-label={label} />;
}

function RowIcon({ row }: { row: LocalRunMethodDto }) {
  if (row.status === 'PASSED') return <CircleCheck className="ct-status is-pass" size={12} strokeWidth={2} aria-label="Passed" />;
  if (row.status === 'SKIPPED') return <CircleMinus className="ct-status is-neutral" size={12} strokeWidth={2} aria-label="Skipped" />;
  return <CircleX className="ct-status is-fail" size={12} strokeWidth={2} aria-label={row.status === 'ERROR' ? 'Error' : 'Failed'} />;
}

/** Every run that is not a tab, newest first; picking one opens it as a tab. */
function RunHistory({ runs, files, align, onPick, onClose }: {
  runs: readonly LocalRunDto[];
  files: ReviewCodeFilesDto | null;
  align: 'left' | 'right';
  onPick: (runId: string) => void;
  onClose: () => void;
}) {
  const host = usePopoverDismiss(onClose, true);
  return (
    <div className={`ct-popover ct-history is-${align}`} ref={host} role="dialog" aria-label="Run history" style={{ width: HISTORY_WIDTH }}>
      <div className="ct-history__head">Run history · {runs.length}</div>
      <ul className="ct-history__list">
        {runs.map(item => (
          <li key={item.id}>
            <button type="button" className="ct-history__row" title={item.command} onClick={() => onPick(item.id)}>
              <StatusIcon run={item} />
              <span className="ct-history__label">{runLabel(item)}</span>
              {runIsStale(item, files) && <span className="ct-tab__dot" title="Code changed since this run — kept as evidence" />}
              <span className="ct-history__meta">
                {item.initiator === 'USER' ? 'You' : 'Review agent'} · {statusLabel(item.status)} · {new Date(item.startedAt).toLocaleTimeString()}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function RunPanel({
  runs,
  files,
  showRunHint = false,
  hostKind,
  onStop,
  readOutput,
  selectedRunId,
  onSelectRun,
  embedded = false,
  onAskAgent,
}: {
  runs: readonly LocalRunDto[];
  files: ReviewCodeFilesDto | null;
  showRunHint?: boolean;
  /** Whose working copy this is; a development task's agent runs are not mirrored here. */
  hostKind: CodeHostRef['kind'];
  embedded?: boolean;
  onAskAgent?: (run: LocalRunDto, text: string) => void;
  onStop: (run: LocalRunDto) => void;
  readOutput: (runId: string, after: number) => Promise<LocalRunOutputDto>;
  selectedRunId: string | null;
  onSelectRun: (runId: string | null) => void;
}) {
  const [open, setOpen] = useState(true);
  const [selection, setSelection] = useState('');
  const [failureDetail, setFailureDetail] = useState<LocalRunMethodDto | null>(null);
  const [height, setHeight] = useState(236);
  // Null keeps the stylesheet's share until the divider is first moved.
  const [treeWidth, setTreeWidth] = useState<number | null>(null);
  const results = useRef<HTMLDivElement>(null);
  const [subTab, setSubTab] = useState<SubTab>('tests');
  // The runs shown as tabs, in tab order; every other run is in history.
  const [tabIds, setTabIds] = useState<readonly string[]>(() => selectedRunId === null ? [] : [selectedRunId]);
  // Which edge of its button the history list lines up with, so it opens toward the free side of the window.
  const [historyOpen, setHistoryOpen] = useState<'left' | 'right' | null>(null);
  const [failedOnly, setFailedOnly] = useState(false);
  const [outputs, setOutputs] = useState<ReadonlyMap<string, OutputState>>(new Map());
  const [now, setNow] = useState(() => Date.now());
  const outputsRef = useRef(outputs);
  outputsRef.current = outputs;
  const log = useRef<HTMLDivElement>(null);
  // The log keeps the newest line in view until the reader scrolls up, and follows again once they return to the bottom.
  const logFollows = useRef<{ runId: string | null; follows: boolean }>({ runId: null, follows: true });

  // A run selected from outside the tab strip, a new run or the first load, replaces the tabs; picking from history adds a tab first.
  if (selectedRunId !== null && !tabIds.includes(selectedRunId)) setTabIds([selectedRunId]);
  const run = runs.find(item => item.id === selectedRunId) ?? null;
  const running = runs.some(item => item.status === 'RUNNING');

  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [running]);

  // Output streams while the run is live and is read once more when it settles, so the tail is never lost.
  useEffect(() => {
    if (run === null || !open) return;
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const runId = run.id;
    const finished = run.status !== 'RUNNING';
    const poll = async () => {
      const current = outputsRef.current.get(runId);
      if (current?.complete) return;
      try {
        // Final storage compacts the stream's omitted middle, so its offsets start over.
        const snapshot = finished && !current?.snapshot;
        const chunk = await readOutput(runId, snapshot ? 0 : current?.offset ?? 0);
        if (!live) return;
        setOutputs(map => {
          const previous = map.get(runId);
          const gap = chunk.skippedBytes > 0 ? `\n… ${chunk.skippedBytes.toLocaleString()} characters of output omitted …\n` : '';
          return new Map(map).set(runId, {
            text: `${snapshot ? '' : previous?.text ?? ''}${gap}${chunk.text}`,
            offset: chunk.nextOffset,
            complete: finished && chunk.complete,
            snapshot: finished,
          });
        });
        if (!finished || !chunk.complete) timer = setTimeout(() => { void poll(); }, 1000);
      }
      catch {
        if (live && !finished) timer = setTimeout(() => { void poll(); }, 2000);
      }
    };
    void poll();
    return () => {
      live = false;
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [open, readOutput, run?.id, run?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (run === null) return;
    setSubTab('tests'); setSelection(''); setFailureDetail(null);
  }, [run?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const startResize = (event: ReactMouseEvent) => {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = open ? height : MIN_HEIGHT;
    setOpen(true);
    const move = (next: MouseEvent) => setHeight(Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, startHeight + startY - next.clientY)));
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };

  const clampTreeWidth = (width: number) => {
    const total = results.current?.getBoundingClientRect().width ?? 0;
    return Math.round(Math.max(MIN_TREE_WIDTH, Math.min(total - MIN_LOG_WIDTH, width)));
  };
  const currentTreeWidth = () => results.current?.querySelector<HTMLElement>('.ct-run__tree')?.getBoundingClientRect().width ?? MIN_TREE_WIDTH;
  const startSplit = (event: ReactMouseEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = currentTreeWidth();
    const move = (next: MouseEvent) => setTreeWidth(clampTreeWidth(startWidth + next.clientX - startX));
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };
  const nudgeSplit = (event: ReactKeyboardEvent) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    setTreeWidth(clampTreeWidth(currentTreeWidth() + (event.key === 'ArrowLeft' ? -24 : 24)));
  };

  const shownTabs = tabIds.flatMap(id => runs.filter(item => item.id === id));
  const history = runs.filter(item => !tabIds.includes(item.id));
  const closeTab = (id: string) => {
    const remaining = tabIds.filter(tabId => tabId !== id);
    setTabIds(remaining);
    if (id === selectedRunId) onSelectRun(remaining[Math.max(0, tabIds.indexOf(id) - 1)] ?? null);
  };
  const openFromHistory = (id: string) => {
    setTabIds(current => [...current, id]);
    onSelectRun(id);
    setHistoryOpen(null);
    setOpen(true);
  };
  const rows = run === null ? [] : methodRows(run);
  const tree = run === null ? [] : shownResults(run, failedOnly);
  const firstFailed = run === null ? undefined : failedRows(run)[0];
  const failure = failureDetail ?? firstFailed;
  const output = run === null ? null : outputs.get(run.id) ?? null;

  useLayoutEffect(() => {
    const element = log.current;
    if (element === null || run === null) return;
    if (logFollows.current.runId !== run.id) logFollows.current = { runId: run.id, follows: true };
    if (logFollows.current.follows) element.scrollTop = element.scrollHeight;
  }, [run?.id, output?.text, subTab, open]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <section className={`ct-run${open ? ' is-open' : ''}${embedded ? ' is-embedded' : ''}`} aria-label="Runs">
      {!embedded && <div className="ct-run__resize" role="separator" aria-orientation="horizontal" aria-label="Resize the run panel"
        title="Drag to resize the run panel" onMouseDown={startResize} />}
      <header className="ct-run__header">
        {!embedded && <button type="button" className="ct-run__toggle" aria-expanded={open} title="Show or hide runs" aria-label="Show or hide runs"
          onClick={() => setOpen(value => !value)}>
          {open ? <ChevronDown size={13} strokeWidth={1.8} aria-hidden="true" /> : <ChevronRight size={13} strokeWidth={1.8} aria-hidden="true" />}
        </button>}
        {!embedded && <span className="ct-run__mode"><Play size={12} strokeWidth={1.8} aria-hidden="true" /> Run</span>}
        <div className="ct-run__tabs" role="tablist" aria-label="Runs">
          {shownTabs.map(item => (
            <div key={item.id} className={`ct-run__tab${item.id === selectedRunId ? ' is-selected' : ''}`}>
              <button type="button" role="tab" aria-selected={item.id === selectedRunId} className="ct-run__tab-open"
                title={`${item.initiator === 'USER' ? 'You' : 'Review agent'} · ${statusLabel(item.status)} · ${new Date(item.startedAt).toLocaleTimeString()} · scope: ${scopeText(item)}\n${item.command}`}
                onClick={() => {
                  onSelectRun(item.id);
                  setOpen(true);
                }}>
                <StatusIcon run={item} />
                <span className="ct-run__tab-label">{runLabel(item)}</span>
                {item.initiator === 'USER'
                  ? <UserRound className="ct-run__initiator" size={11} strokeWidth={1.8} aria-label="You started this run" />
                  : <Bot className="ct-run__initiator" size={11} strokeWidth={1.8} aria-label="The review agent started this run" />}
                {runIsStale(item, files) && <span className="ct-tab__dot" title="Code changed since this run — kept as evidence" />}
              </button>
              <button type="button" className="ct-run__tab-close" aria-label="Close run tab" title="Close this tab — the run moves to history"
                onClick={() => closeTab(item.id)}><X size={10} strokeWidth={2} aria-hidden="true" /></button>
            </div>
          ))}
        </div>
        {history.length > 0 && (
          <div className="ct-run__history-host">
            <button type="button" className={`ct-run__history${historyOpen !== null ? ' is-open' : ''}`} aria-expanded={historyOpen !== null} aria-haspopup="dialog"
              title="Runs not shown as tabs" onClick={event => {
                const button = event.currentTarget.getBoundingClientRect();
                setHistoryOpen(value => value !== null ? null : button.left + HISTORY_WIDTH > window.innerWidth - 8 ? 'right' : 'left');
              }}>
              <Clock size={12} strokeWidth={1.8} aria-hidden="true" /> history <span className="ct-run__history-count">{history.length}</span>
            </button>
            {historyOpen !== null && (
              <RunHistory runs={history} files={files} align={historyOpen} onPick={openFromHistory} onClose={() => setHistoryOpen(null)} />
            )}
          </div>
        )}
        <span className="ct-run__count">{runs.length === 0 ? 'nothing has run yet' : `${runs.length} ${runs.length === 1 ? 'run' : 'runs'} · newest first`}</span>
      </header>
      {open && (
        <div className="ct-run__body" style={{ height: embedded ? undefined : height }}>
          {run === null ? (
            <div className="ct-run__empty">
              <div>{runs.length === 0 ? 'No runs yet' : 'Pick a run tab to see its result'}</div>
              <p>
                {hostKind === 'review' ? 'Runs you start and runs the review agent starts share this history.' : 'Runs you start here share this history.'}
                {' '}Each keeps its initiator, scope, the source version that ran, status, timing, output and exit status.
              </p>
              {showRunHint && <p>Use the play button beside a test in the editor to run or debug it.</p>}
            </div>
          ) : (
            <>
              <div className="ct-run__toolbar">
                <button type="button" className="ct-run__action" disabled={run.status !== 'RUNNING' && !run.treeUnverified} aria-label="Stop" title="Stop"
                  onClick={() => onStop(run)}><Square size={12} strokeWidth={2} aria-hidden="true" /></button>
                <button type="button" className="ct-run__action is-failed-only" aria-pressed={failedOnly}
                  aria-label="Show only failed tests" title={failedOnly ? 'Showing only failed tests — click to show all' : 'Show only failed tests'}
                  onClick={() => setFailedOnly(value => !value)}><CircleX size={13} strokeWidth={1.8} aria-hidden="true" /></button>
                <span className="ct-run__divider" aria-hidden="true" />
                <div className="ct-run__subtabs" role="tablist" aria-label="Run result">
                  {(['tests', 'details'] as const).map(tab => (
                    <button key={tab} type="button" role="tab" aria-selected={subTab === tab} onClick={() => setSubTab(tab)}>
                      {tab === 'tests' ? 'Tests' : 'Details'}
                    </button>
                  ))}
                </div>
                {onAskAgent && <button type="button" className="ct-text-button" disabled={!selection.trim()} onClick={() => onAskAgent(run, selection)}>Ask agent about selection</button>}
                <span className="ct-toolbar__spacer" />
                <span className={`ct-run__summary is-${statusTone(run.status)}`}><StatusIcon run={run} /> {runSummary(run, now)}</span>
                <span className="ct-run__meta">{runMeta(run, now)}</span>
              </div>
              {run.treeUnverified && (
                <div className="ct-run__warning" role="alert">The run&apos;s processes could not be verified stopped. It keeps the working copy until Stop succeeds.</div>
              )}
              {subTab === 'tests' && (
                <div className="ct-run__results" ref={results}>
                  <div className="ct-run__tree" role="tree" aria-label="Test results" style={treeWidth === null ? undefined : { width: treeWidth }}>
                    {rows.length === 0 ? (
                      <div className="ct-run__empty">
                        <div>{run.status === 'RUNNING' ? 'Test rows appear when the runner finishes'
                          : run.scope === 'COMMAND' ? 'Command result — not a parsed test run'
                            : run.status === 'STOPPED' || run.status === 'INTERRUPTED' || run.status === 'TIMED_OUT' ? 'Stopped before the runner reported'
                              : 'No test rows'}</div>
                      </div>
                    ) : tree.length === 0 ? (
                      <div className="ct-run__empty">
                        <div>No failed tests</div>
                        <button type="button" className="ct-text-button" onClick={() => setFailedOnly(false)}>Show all tests</button>
                      </div>
                    ) : tree.map(result => (
                      <div key={result.className} role="group">
                        <div className="ct-run__class">
                          <ChevronDown size={12} strokeWidth={1.8} aria-hidden="true" />
                          <span className="ct-run__class-name" title={result.className}>{result.className.slice(result.className.lastIndexOf('.') + 1)}</span>
                          <span className="ct-run__duration">{formatDuration(result.durationMs)}</span>
                        </div>
                        {result.methods.map((method, index) => (
                          <div key={`${method.name}:${index}`} role="treeitem" className="ct-run__row">
                            <span className="ct-run__row-label">
                              <RowIcon row={method} />
                              <button type="button" className="ct-run__row-name ct-text-button" onClick={() => setFailureDetail(method)}>{method.name}{run.scope === 'CLASS' || run.scope === 'METHOD' ? '()' : ''}</button>
                            </span>
                            <span className="ct-run__duration">{formatDuration(method.durationMs)}</span>
                          </div>
                        ))}
                      </div>
                    ))}
                  </div>
                  <div className="ct-run__split" role="separator" aria-orientation="vertical" aria-label="Resize the test list" tabIndex={0}
                    title="Drag to resize the test list" onMouseDown={startSplit} onKeyDown={nudgeSplit} onDoubleClick={() => setTreeWidth(null)} />
                  <div className="ct-run__log" aria-label="Run log" ref={log} onScroll={event => {
                    const element = event.currentTarget;
                    logFollows.current.follows = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
                  }}>
                    {failure && (failure.message || failure.trace) && <div className="ct-run__failure" aria-label="Test failure details">
                      <strong>{failure.name}</strong><pre>{[failure.message, failure.trace].filter(Boolean).join('\n')}</pre>
                      {onAskAgent && <button type="button" className="ct-text-button" onClick={() => onAskAgent(run, [failure.message, failure.trace].filter(Boolean).join('\n').slice(0, 30_000))}>Ask agent about failure</button>}
                    </div>}
                    {output === null ? <div className="ct-run__note" role="status">Reading output…</div> : <TerminalOutput key={run.id} text={output.text} onSelect={setSelection} />}
                    <div className="ct-run__note">
                      {run.status === 'RUNNING' ? 'Streaming — test rows appear when the runner finishes.'
                        : run.outputTruncated
                          ? run.omittedBytes === null
                            ? run.initiator === 'USER' ? 'Output truncated; how much was omitted is unknown.' : 'Output truncated by the agent; the end is not available.'
                            : `Output truncated — ${run.omittedBytes.toLocaleString()} characters omitted.`
                          : `Exit status ${run.exitCode ?? '—'}`}
                    </div>
                  </div>
                </div>
              )}
              {subTab === 'details' && (
                <dl className="ct-run__details">
                  <dt>Initiator</dt><dd>{run.initiator === 'USER' ? 'You' : 'Review agent'}</dd>
                  <dt>Scope</dt><dd>{scopeText(run)}</dd>
                  <dt>Selector</dt><dd>{run.command}</dd>
                  <dt>Runner</dt><dd>{run.scope === 'COMMAND' ? 'none — plain command' : run.scope === 'TESTS' ? 'IDE test controller' : run.scope === 'DEBUG' ? 'IDE debugger' : run.scope === 'RUN' ? 'IDE program or task' : 'JUnit via Maven Surefire'}</dd>
                  <dt>Working copy</dt><dd>{hostKind === 'review' ? 'Agent review working copy' : 'Development task working copy'}{run.sourceHeadSha === null ? '' : ` · ${shortSha(run.sourceHeadSha)}`}</dd>
                  <dt>Source version</dt><dd>{run.sourceHeadSha === null ? 'unknown' : shortSha(run.sourceHeadSha)}
                    {run.localPaths.length === 0 ? ' · no local files' : ` + ${run.localPaths.length} local ${run.localPaths.length === 1 ? 'file' : 'files'}`}</dd>
                  <dt>Local files</dt><dd>{run.localPaths.length === 0 ? '—' : run.localPaths.join(', ')}</dd>
                  <dt>Started · duration</dt><dd>{new Date(run.startedAt).toLocaleString()} · {run.endedAt === null ? 'running' : formatDuration(Date.parse(run.endedAt) - Date.parse(run.startedAt))}</dd>
                  <dt>Exit status</dt><dd>{run.exitCode ?? '—'}</dd>
                  <dt>Where it ran</dt><dd>Locally, in this working copy — separate from GitHub CI</dd>
                </dl>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}
