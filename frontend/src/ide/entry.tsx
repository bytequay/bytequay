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
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import LogoLoading from '../LogoLoading';
import { attachPart, Parts } from '@codingame/monaco-vscode-views-service-override';
import type { TextEditor } from 'vscode';
import type { Bridge } from '../types';
import { useCodeComments, type CodeCommentsOptions, type CommentContext } from '../pulls/code-tests/useCodeComments';
import type { ReviewCodeFileDto, ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import type { IdeConnection, IdeContext, HistoryComparison } from './contracts';
import { installSemanticQueries } from './semanticQueries';
import { startRuntime, type IdeRuntime } from './runtime';
import { ReviewDraftStorage } from '../pulls/ReviewDrafts';
import { restoreSourceDrafts, trackSourceDrafts, restoreNavigation, trackNavigation, discardRecoveredDraft, type SourceDraft } from './drafts';
import TestRuns, { testRunPanel } from './TestRuns';
import TerminalView from '../pulls/code-tests/TerminalView';
import FileExplorer from './FileExplorer';
import { useResizablePane } from '../useResizablePane';
import { PanelLeft, ArrowLeft, ArrowRight, ChevronDown, ChevronUp, MessageSquare, Play, Bug, TerminalSquare, GitCommitHorizontal } from 'lucide-react';
import ReviewZones from './ReviewZones';
import LineChanges from './LineChanges';
import EditorNavigation from './EditorNavigation';
import Usages from './Usages';
import Search from './Search';
import { installGitHistory } from './gitHistory';
import { codeHostKey } from '../pulls/code-tests/contracts';
import { pollVisible } from './pollVisible';
import '../css/base.css';
import '../css/markdown-editor.css';
import '../css/pulls.css';
import '../css/agent-review.css';
import '../css/code-tests.css';
import './workbench.css';

const request = window.ideWorkbench.request;
// Shared review components see only these scoped capabilities, never the full desktop preload.
const privateBridge: Pick<Bridge, 'getGitHubUserProfile' | 'fetchGitHubCodeSnippet' | 'openInAppBrowser' | 'getAgentReview' | 'getAgentTask' | 'askAgentReview' | 'resolveAgentReviewFinding' | 'reopenAgentReviewFinding' | 'betterWords' | 'postPrReviewComment' | 'replyToReviewComment' | 'setReviewThreadResolved' | 'editPrComment' | 'setPrReaction'> = {
  getGitHubUserProfile: (...args) => request('getGitHubUserProfile', ...args) as ReturnType<Bridge['getGitHubUserProfile']>,
  fetchGitHubCodeSnippet: (...args) => request('fetchGitHubCodeSnippet', ...args) as ReturnType<Bridge['fetchGitHubCodeSnippet']>,
  openInAppBrowser: (...args) => request('openInAppBrowser', ...args) as ReturnType<Bridge['openInAppBrowser']>,
  postPrReviewComment: (...args) => request('github.post', ...args) as ReturnType<Bridge['postPrReviewComment']>,
  replyToReviewComment: (...args) => request('github.reply', ...args) as ReturnType<Bridge['replyToReviewComment']>,
  setReviewThreadResolved: (...args) => request('github.resolve', ...args) as ReturnType<Bridge['setReviewThreadResolved']>,
  editPrComment: (...args) => request('github.edit', ...args) as ReturnType<Bridge['editPrComment']>,
  setPrReaction: (...args) => request('github.react', ...args) as ReturnType<Bridge['setPrReaction']>,
  getAgentReview: (...args) => request('getAgentReview', ...args) as ReturnType<Bridge['getAgentReview']>,
  getAgentTask: (...args) => request('getAgentTask', ...args) as ReturnType<Bridge['getAgentTask']>,
  askAgentReview: (...args) => request('askAgentReview', ...args) as ReturnType<Bridge['askAgentReview']>,
  resolveAgentReviewFinding: (...args) => request('resolveAgentReviewFinding', ...args) as ReturnType<Bridge['resolveAgentReviewFinding']>,
  reopenAgentReviewFinding: (...args) => request('reopenAgentReviewFinding', ...args) as ReturnType<Bridge['reopenAgentReviewFinding']>,
  betterWords: (...args) => request('betterWords', ...args) as ReturnType<Bridge['betterWords']>,
};
Object.assign(window, { bridge: privateBridge });

function Part({ part }: { part: Parts }) {
  const host = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (!host.current) return;
    const attached = attachPart(part, host.current);
    return () => attached.dispose();
  }, [part]);
  return <div ref={host} className="ide-native-part" />;
}

function reviewOptions(context: IdeContext): CodeCommentsOptions | undefined {
  const source = context.comments;
  if (!source) return undefined;
  return {
    ...source,
    onPendingChange: pending => { void request('comments.pending', pending); },
    onDetailChange: detail => { void request('comments.detail', detail); },
    onAskAgent: source.askAgent ? async comment => { await request('comments.ask', comment); } : undefined,
    onOpenFindingInReview: source.openFinding ? finding => { void request('comments.finding', finding); } : undefined,
    threads: { ...source.threads, onRefresh: async () => { await request('comments.refresh'); }, onDetailUpdate: () => { void request('comments.refresh'); } },
    localReview: {
      ...source.localReview,
      onReply: async (...args) => { await request('comments.reply', ...args); },
      onResolve: async (...args) => { await request('comments.resolve', ...args); },
      onRetry: async (...args) => { await request('comments.retry', ...args); },
    },
  };
}

function Workbench({ runtime, context, connection }: { runtime: IdeRuntime; context: IdeContext; connection: IdeConnection }) {
  const [active, setActive] = useState<TextEditor | undefined>(runtime.api.window.activeTextEditor);
  const [generation, setGeneration] = useState(0);
  const [files, setFiles] = useState<ReviewCodeFilesDto | null>(null);
  const [file, setFile] = useState<(ReviewCodeFileDto & { revision: string; sourceFingerprint: string | null }) | null>(null);
  const [filesReload, setFilesReload] = useState(0);
  const [filesVisible, setFilesVisible] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [execution, setExecution] = useState(runtime.executions.run);
  const [executionError, setExecutionError] = useState(runtime.executions.error);
  const [bottom, setBottom] = useState<'run' | 'debug' | 'terminal' | 'history'>('run');
  const [panelCollapsed, setPanelCollapsed] = useState(true);
  const column = useResizablePane(285, 180, 650);
  const panel = useResizablePane(270, 100, 900, 'start', 'y');
  const [history, setHistory] = useState<HistoryComparison | null>(null);
  const [gitHistory, setGitHistory] = useState<ReturnType<typeof installGitHistory> | null>(null);
  const [recovered, setRecovered] = useState<SourceDraft[]>([]);
  const [theme, setTheme] = useState(localStorage.getItem('bytequay.ide.theme') ?? 'ByteQuay IDEA Light');
  const command = (name: string) => { void runtime.api.commands.executeCommand(name).then(undefined, cause => setError(String(cause))); };
  const report = () => {
    const privateDrafts = [...document.querySelectorAll('textarea')].filter(node => !node.classList.contains('inputarea') && node.value.trim()).length;
    window.ideWorkbench.report({ dirty: runtime.api.workspace.textDocuments.filter(document => document.isDirty).length + privateDrafts,
      busy: runtime.executions.run !== null || runtime.terminals.instances.some(terminal => terminal.hasChildProcesses) || runtime.api.debug.activeDebugSession !== undefined || runtime.tests.results.some(result => result.completedAt === undefined) });
  };

  useEffect(() => {
    let previous = runtime.executions.run?.id;
    const listener = runtime.executions.onChange(state => {
      setExecution(state.run); setExecutionError(state.error);
      const panel = state.run && testRunPanel(state.run);
      if (panel && state.run?.id !== previous) { setBottom(panel); setPanelCollapsed(false); }
      previous = state.run?.id;
    });
    return () => listener.dispose();
  }, [runtime]);

  useEffect(() => {
    const history = installGitHistory(runtime, context.host);
    setGitHistory(history);
    return () => history.dispose();
  }, [runtime, codeHostKey(context.host)]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (files?.revision) gitHistory?.updateRevision(files.revision); }, [gitHistory, files?.revision]);

  useEffect(() => {
    const drafts = trackSourceDrafts(runtime, setError);
    let navigation: ReturnType<typeof trackNavigation> | undefined;
    let live = true;
    void restoreSourceDrafts(runtime, draft => setRecovered(current => [...current, draft]))
      .then(() => restoreNavigation(runtime)).catch(cause => setError(String(cause)))
      .finally(() => { if (live) navigation = trackNavigation(runtime, setError); });
    return () => { live = false; drafts.dispose(); navigation?.dispose(); };
  }, [runtime]);

  useEffect(() => {
    const editor = runtime.api.window.onDidChangeActiveTextEditor(value => { setActive(value); setGeneration(value => value + 1); });
    const changed = runtime.api.workspace.onDidChangeTextDocument(() => { setGeneration(value => value + 1); report(); });
    const saved = runtime.api.workspace.onDidSaveTextDocument(() => { setGeneration(value => value + 1); report(); });
    const tests = runtime.tests.onResultsChanged(report);
    const debug = runtime.debug.onDidChangeState(report);
    document.addEventListener('input', report);
    const timer = setInterval(report, 5_000);
    const showPanel = (id: string | undefined) => {
      // Native test and debugger views must not replace the selected result or expose launch configuration.
      if (id?.includes('debug') || id === 'workbench.panel.repl' || id?.toLowerCase().includes('test')) {
        const run = runtime.executions.run;
        setBottom((run && testRunPanel(run)) || (id?.toLowerCase().includes('test') ? 'run' : 'debug'));
      }
      else if (id?.includes('scm')) setBottom('history');
      else return;
      setPanelCollapsed(false);
    };
    const panel = runtime.panels.onDidPaneCompositeOpen(event => { if (event.viewContainerLocation === 1) showPanel(event.composite.getId()); });
    return () => { editor.dispose(); changed.dispose(); saved.dispose(); tests.dispose(); debug.dispose(); panel.dispose(); clearInterval(timer); document.removeEventListener('input', report); };
  }, [runtime]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let live = true;
    const stop = pollVisible(async () => {
      try {
        const value = await request('listCodeFiles', context.host);
        if (live) { setFiles(value as ReviewCodeFilesDto); setFilesError(null); }
      } catch (cause) { if (live) setFilesError(`Could not refresh working copy: ${String(cause)}`); }
    }, 5_000);
    return () => { live = false; stop(); };
  }, [context.host.kind, context.host.kind === 'task' ? context.host.taskId : context.host.reviewId, filesReload]); // eslint-disable-line react-hooks/exhaustive-deps

  const selected = active?.document.uri.scheme === 'vscode-remote' && active.document.uri.path.startsWith(connection.root + '/')
    ? active.document.uri.path.slice(connection.root.length + 1) : null;
  useEffect(() => {
    let live = true;
    setFile(null);
    setFileError(null);
    if (selected && files) void request('readCodeFile', context.host, selected, files.revision).then(value => {
      if (live) setFile({ ...value as ReviewCodeFileDto, revision: files.revision, sourceFingerprint: files.sourceFingerprint });
    }, cause => { if (live) setFileError(`Could not read file details: ${String(cause)}`); });
    return () => { live = false; };
  }, [selected, files?.sourceFingerprint, files?.revision]); // eslint-disable-line react-hooks/exhaustive-deps
  const fileContext: CommentContext | null = useMemo(() => {
    if (!selected || !active || file?.path !== selected || !files || (!active.document.isDirty && active.document.getText() !== file.content)) return null;
    if (file.revision !== files.revision || file.sourceFingerprint !== files.sourceFingerprint) return null;
    return { path: selected, text: active.document.getText(), prPatch: file.prPatch, draft: active.document.isDirty,
      uncommitted: files.files.find(entry => entry.path === selected)?.uncommitted ?? true,
      sourceHead: file.revision, observedHead: context.review ? context.review.observedHeadSha : files.revision,
      askBlocked: context.comments?.localReview.disabledReason ?? null };
  }, [selected, active, file, files, context.review, context.comments?.localReview.disabledReason, generation]); // eslint-disable-line react-hooks/exhaustive-deps -- Native documents are mutable; generation signals their edits.

  const comments = useCodeComments(reviewOptions(context), fileContext, context.review === null ? undefined : {
    review: context.review, onReviewChange: review => { void request('review.changed', review); },
  }, {
    busy: context.findingBusy ?? false, error: context.findingError ?? null, clearError: () => {},
    actions: {
      onAsk: async (finding, message) => { await request('finding.ask', finding.id, message); },
      onResolve: async (finding, reason, remember) => { await request('finding.resolve', finding.id, reason, remember); },
      onReopen: async finding => { await request('finding.reopen', finding.id); },
      onAddToReview: async finding => { await request('finding.add', finding.id); },
      onPostNow: async (finding, body) => { await request('finding.post', finding.id, body); },
      onEditDraft: async (finding, body) => { await request('finding.editDraft', finding.id, body); },
      onRemoveFromReview: async finding => { await request('finding.remove', finding.id); },
      onRequestFix: context.review?.sourceTaskId ? async (finding, instruction) => { await request('finding.fix', finding.id, instruction); } : undefined,
      onNavigate: finding => { void request('comments.finding', finding.id); },
    },
  });
  const commentSelection = () => {
    if (!active || !comments.onComment) return;
    comments.onComment({ type: 'start', line: active.selection.start.line + 1, extend: false });
    comments.onComment({ type: 'drag', line: active.selection.end.line + 1 });
    comments.onComment({ type: 'finish' });
  };
  const selectBottom = async (value: typeof bottom) => {
    if (bottom === value && !panelCollapsed) {
      setPanelCollapsed(true);
      return;
    }
    setBottom(value);
    setPanelCollapsed(false);
    if (value === 'history') {
      try { setHistory(null); await gitHistory?.focus(); }
      catch (cause) { setError(String(cause)); }
    }
  };
  const chooseCommit = async (commit: string, parent?: string) => {
    try { if (gitHistory) setHistory(await gitHistory.readCommit(commit, parent)); }
    catch (cause) { setError(String(cause)); }
  };

  return <main className={`ide-workbench ${['ByteQuay Darcula', 'GitHub Dark'].includes(theme) ? 'is-dark' : ''}`} data-theme={theme}>
    <header className="ide-toolbar">
      <button aria-label="Back" onClick={() => command('workbench.action.navigateBack')} title="Back"><ArrowLeft size={15} /></button>
      <button aria-label="Forward" onClick={() => command('workbench.action.navigateForward')} title="Forward"><ArrowRight size={15} /></button>
      {!filesVisible && <button aria-label="Show files" title="Show files" onClick={() => setFilesVisible(true)}><PanelLeft size={15} /></button>}
      <Search editors={runtime.editors} workspace={runtime.workspace} files={files} />
      <button disabled={!comments.onComment} onClick={commentSelection}><MessageSquare size={14} /> Comment</button>
      <EditorNavigation runtime={runtime} active={active} context={context} file={file} revision={files?.revision}
        onHistory={commit => { setBottom('history'); setPanelCollapsed(false); void chooseCommit(commit); }} />
      <select aria-label="IDE theme" value={theme} onChange={event => {
        const value = event.target.value;
        localStorage.setItem('bytequay.ide.theme', value); setTheme(value);
        document.documentElement.style.colorScheme = ['ByteQuay Darcula', 'GitHub Dark'].includes(value) ? 'dark' : 'light';
        void runtime.api.workspace.getConfiguration().update('workbench.colorTheme', value, runtime.api.ConfigurationTarget.Global);
      }}><option>ByteQuay IDEA Light</option><option>ByteQuay Darcula</option><option>GitHub Dark</option></select>
    </header>
    {executionError && <div className="ide-notice" role="alert">{executionError}</div>}
    {execution?.treeUnverified && <div className="ide-notice" role="status">Execution finished; verifying process cleanup.
      <button onClick={() => { void runtime.executions.stop(true).catch(cause => setError(String(cause))); }}>Stop code tools to finish cleanup</button>
    </div>}
    {(error || filesError || fileError) && <div className="ide-notice" role="alert">{error ?? filesError ?? fileError}<button onClick={() => { setError(null); setFilesError(null); setFileError(null); }}>Dismiss</button></div>}
    {recovered.map(draft => <div className="ide-notice" role="alert" key={`${draft.path}:${draft.text}`}>
      <span>A saved draft for {draft.path} differs from the current file. Your draft has been kept.</span>
      <button onClick={() => { void runtime.api.workspace.openTextDocument({ content: draft.text }).then(document => runtime.api.window.showTextDocument(document, { preview: false })); }}>Open recovered draft</button>
      <button onClick={() => { discardRecoveredDraft(draft); setRecovered(current => current.filter(item => item !== draft)); }}>Discard recovered draft</button>
    </div>)}
    <div className="ide-code-row">
      {filesVisible && <div className="ide-files-pane" style={{ width: column.width }}><FileExplorer files={files} selected={selected} runtime={runtime} onRefresh={() => setFilesReload(value => value + 1)} onError={setError} onHide={() => setFilesVisible(false)} /></div>}
      {filesVisible && <div className={`ct-resize${column.resizing ? ' is-resizing' : ''}`} role="separator" tabIndex={0} aria-orientation="vertical"
        aria-label="Resize the Files column" aria-valuenow={column.width} aria-valuemin={180} aria-valuemax={650}
        onMouseDown={event => { event.currentTarget.focus(); column.startResize(event); }} onKeyDown={event => {
          if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
          event.preventDefault(); column.setWidth(width => Math.max(180, Math.min(650, width + (event.key === 'ArrowLeft' ? -20 : 20))));
        }} />}
      <section className="ide-editor" aria-label="Source code"><Part part={Parts.EDITOR_PART} />{comments.unplaced}</section>
    </div>
    {active && <ReviewZones runtime={runtime} uri={active.document.uri.toString()} cards={comments.cards} renderCard={comments.renderCard}
      onComment={comments.onComment} commentRange={comments.commentRange} />}
    {active && <LineChanges runtime={runtime} uri={active.document.uri.toString()} file={file?.path === selected ? file : null} />}
    <Usages editors={runtime.editors} workspace={runtime.workspace} />
    {!panelCollapsed && <div className={`ide-panel-resize${panel.resizing ? ' is-resizing' : ''}`} role="separator" tabIndex={0} aria-orientation="horizontal"
      aria-label="Resize the tools panel" aria-valuenow={panel.width} aria-valuemin={100} aria-valuemax={900}
      onMouseDown={event => { event.currentTarget.focus(); panel.startResize(event); }} onKeyDown={event => {
        if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
        event.preventDefault(); panel.setWidth(height => Math.max(100, Math.min(900, height + (event.key === 'ArrowUp' ? 20 : -20))));
      }} />}
    <footer className={`ide-bottom${panelCollapsed ? ' is-collapsed' : ''}`} style={{ height: panelCollapsed ? 'auto' : panel.width }}>
      <nav className="ide-toolbar" aria-label="Code tools">
        <button aria-pressed={!panelCollapsed && bottom === 'run'} onClick={() => { void selectBottom('run'); }}><Play size={13} /> Run</button>
        <button aria-pressed={!panelCollapsed && bottom === 'debug'} onClick={() => { void selectBottom('debug'); }}><Bug size={14} /> Debug</button>
        <button aria-pressed={!panelCollapsed && bottom === 'terminal'} onClick={() => { void selectBottom('terminal'); }}><TerminalSquare size={14} /> Terminal</button>
        <button aria-pressed={!panelCollapsed && bottom === 'history'} onClick={() => { void selectBottom('history'); }}><GitCommitHorizontal size={14} /> Git history</button>
        <span className="ide-spacer" />
        <button aria-label={panelCollapsed ? 'Expand tools panel' : 'Collapse tools panel'} title={panelCollapsed ? 'Expand tools panel' : 'Collapse tools panel'}
          aria-expanded={!panelCollapsed} onClick={() => setPanelCollapsed(value => !value)}>{panelCollapsed ? <ChevronUp size={14} /> : <ChevronDown size={14} />}</button>
      </nav>
      <div className="ide-tools" style={{ display: bottom === 'history' && !history ? undefined : 'none' }}><Part part={Parts.PANEL_PART} /></div>
      <TestRuns key={codeHostKey(context.host)} context={context} files={files} runtime={runtime} mode={bottom === 'run' || bottom === 'debug' ? bottom : null} />
      {bottom === 'terminal' && <TerminalView host={context.host} bridge={window.ideWorkbench.terminal} />}
      {bottom === 'history' && <nav className="ide-toolbar" aria-label="History scope">
        <button onClick={() => { setHistory(null); void gitHistory?.focus().catch(cause => setError(String(cause))); }}>Repository history</button>
        <button disabled={!selected} onClick={() => { setHistory(null); void gitHistory?.focus(selected ?? undefined).catch(cause => setError(String(cause))); }}>Current file history</button>
      </nav>}
      {bottom === 'history' && history && <section className="ide-history" aria-label="Commit details">
        <div><strong>{history.commit.message.split('\n')[0]}</strong><p>{history.commit.author} · {new Date(history.commit.authoredAt).toLocaleString()}</p><code>{history.commit.sha}</code>
          <p>{history.commit.message.split('\n').slice(1).join('\n')}</p>
          {history.commit.parents.length > 1 && <label>Compare parent <select aria-label="Compare parent" value={history.parent ?? ''}
            onChange={event => { void chooseCommit(history.commit.sha, event.target.value); }}>{history.commit.parents.map(parent => <option key={parent} value={parent}>{parent.slice(0, 12)}</option>)}</select></label>}
          {history.truncated && <p role="status">The changed-file list is truncated.</p>}
        </div><div>{history.files.map(file => <button key={file.path} onClick={() => { void gitHistory?.openDiff(history, file).catch(cause => setError(String(cause))); }}>
          {file.status} · {file.originalPath ? `${file.originalPath} → ` : ''}{file.path}
        </button>)}</div>
      </section>}

    </footer>
  </main>;
}

function DraftWorkbench(props: Parameters<typeof Workbench>[0]) {
  const [error, setError] = useState<string | null>(null);
  const draftStorage = useMemo(() => ({
    read: (key: string): unknown => { const value = localStorage.getItem(`bytequay.ide.reviewDraft:${key}`); return value === null ? undefined : JSON.parse(value); },
    write: (key: string, value: unknown) => {
      try { if (value === undefined) localStorage.removeItem(`bytequay.ide.reviewDraft:${key}`); else localStorage.setItem(`bytequay.ide.reviewDraft:${key}`, JSON.stringify(value)); }
      catch { setError('Review draft storage is full. Keep this editor open until your reply is saved.'); }
    },
  }), []);
  return <ReviewDraftStorage.Provider value={draftStorage}>
    {error && <div className="ide-notice" role="alert">{error}</div>}
    <Workbench {...props} />
  </ReviewDraftStorage.Provider>;
}

function App() {
  const [context, setContext] = useState<IdeContext | null>(null);
  const [ready, setReady] = useState<{ runtime: IdeRuntime; connection: IdeConnection } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const stop = window.ideWorkbench.onContext(setContext);
    void window.ideWorkbench.bootstrap().then(async bootstrap => {
      if (bootstrap.context) setContext(bootstrap.context);
      const runtime = await startRuntime(bootstrap.connection, document.body);
      installSemanticQueries(runtime.workspace);
      setReady({ runtime, connection: bootstrap.connection });
    }).catch(cause => setError(String(cause)));
    return stop;
  }, []);
  if (error) return <div className="ide-loading" role="alert">{error}</div>;
  if (!ready || !context) return <div className="ct-tools-loading" role="status">
    <span aria-hidden="true"><LogoLoading size={56} /></span>
    <span>Loading code tools…</span>
  </div>;
  return <DraftWorkbench {...ready} context={context} />;
}

createRoot(document.getElementById('root')).render(<App />);
