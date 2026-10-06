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
import { attachPart, Parts } from '@codingame/monaco-vscode-views-service-override';
import type { TextEditor } from 'vscode';
import type { Bridge } from '../types';
import { useCodeComments, type CodeCommentsOptions, type CommentContext } from '../pulls/code-tests/useCodeComments';
import type { ReviewCodeFileDto, ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import { latestRun } from '../pulls/agent-review/model';
import type { IdeConnection, IdeContext, HistoryComparison } from './contracts';
import { startRuntime, chooseKeymap, type IdeRuntime } from './runtime';
import { ReviewDraftStorage } from '../pulls/ReviewDrafts';
import { restoreSourceDrafts, trackSourceDrafts, restoreNavigation, trackNavigation, discardRecoveredDraft, type SourceDraft } from './drafts';
import FileExplorer from './FileExplorer';
import { useResizablePane } from '../useResizablePane';
import { PanelLeft, ArrowLeft, ArrowRight, Search, ListTree, GitBranch, MessageSquare, Play, Bug, TerminalSquare, GitCommitHorizontal, Square } from 'lucide-react';
import ReviewZones from './ReviewZones';
import EditorNavigation from './EditorNavigation';
import { installGitHistory } from './gitHistory';
import { codeHostKey } from '../pulls/code-tests/contracts';
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
  const [file, setFile] = useState<ReviewCodeFileDto | null>(null);
  const [filesReload, setFilesReload] = useState(0);
  const [sidebar, setSidebar] = useState<'files' | 'search' | null>('files');
  const [error, setError] = useState<string | null>(null);
  const [bottom, setBottom] = useState<'tests' | 'debug' | 'terminal' | 'history'>('tests');
  const column = useResizablePane(285, 180, 650);
  const panel = useResizablePane(270, 100, 900, 'start', 'y');
  const [history, setHistory] = useState<HistoryComparison | null>(null);
  const [gitHistory, setGitHistory] = useState<ReturnType<typeof installGitHistory> | null>(null);
  const [recovered, setRecovered] = useState<SourceDraft[]>([]);
  const [keymap, setKeymap] = useState(localStorage.getItem('bytequay.ide.keymap') ?? 'IDEA');
  const [theme, setTheme] = useState(localStorage.getItem('bytequay.ide.theme') ?? 'ByteQuay IDEA Light');
  const command = (name: string) => { void runtime.api.commands.executeCommand(name).then(undefined, cause => setError(String(cause))); };
  const report = () => {
    const privateDrafts = [...document.querySelectorAll('textarea')].filter(node => !node.classList.contains('inputarea') && node.value.trim()).length;
    window.ideWorkbench.report({ dirty: runtime.api.workspace.textDocuments.filter(document => document.isDirty).length + privateDrafts,
      busy: runtime.terminals.instances.some(terminal => terminal.hasChildProcesses) || runtime.api.debug.activeDebugSession !== undefined || runtime.tests.results.some(result => result.completedAt === undefined) });
  };

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
      if (id === 'terminal') setBottom('terminal');
      else if (id?.includes('debug') || id === 'workbench.panel.repl') setBottom('debug');
      else if (id?.toLowerCase().includes('test')) setBottom('tests');
      else if (id?.includes('scm')) setBottom('history');
    };
    showPanel(runtime.panels.getActivePaneComposite(1)?.getId());
    const panel = runtime.panels.onDidPaneCompositeOpen(event => { if (event.viewContainerLocation === 1) showPanel(event.composite.getId()); });
    return () => { editor.dispose(); changed.dispose(); saved.dispose(); tests.dispose(); debug.dispose(); panel.dispose(); clearInterval(timer); document.removeEventListener('input', report); };
  }, [runtime]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let live = true;
    const load = () => { void request('listCodeFiles', context.host).then(value => { if (live) setFiles(value as ReviewCodeFilesDto); }, cause => { if (live) setError(String(cause)); }); };
    load();
    const timer = setInterval(() => { if (document.visibilityState === 'visible') load(); }, 5_000);
    return () => { live = false; clearInterval(timer); };
  }, [context.host.kind, context.host.kind === 'task' ? context.host.taskId : context.host.reviewId, filesReload]); // eslint-disable-line react-hooks/exhaustive-deps

  const selected = active?.document.uri.scheme === 'vscode-remote' && active.document.uri.path.startsWith(connection.root + '/')
    ? active.document.uri.path.slice(connection.root.length + 1) : null;
  useEffect(() => {
    let live = true;
    setFile(null);
    if (selected) void request('readCodeFile', context.host, selected).then(value => { if (live) setFile(value as ReviewCodeFileDto); }, cause => { if (live) setError(String(cause)); });
    return () => { live = false; };
  }, [selected, files?.sourceFingerprint, files?.revision]); // eslint-disable-line react-hooks/exhaustive-deps
  const fileContext: CommentContext | null = useMemo(() => {
    if (!selected || !active || file?.path !== selected || !files) return null;
    const reviewedHead = context.review ? latestRun(context.review)?.headSha ?? null : files.revision;
    return { path: selected, text: active.document.getText(), prPatch: file.prPatch, draft: active.document.isDirty,
      uncommitted: files.files.find(entry => entry.path === selected)?.uncommitted ?? true,
      atReviewedRevision: files.revision === reviewedHead, reviewedHead, observedHead: context.review?.observedHeadSha ?? reviewedHead,
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
    setBottom(value);
    if (value === 'tests') command('workbench.view.testing.focus');
    if (value === 'debug') command('workbench.view.debug');
    if (value === 'terminal') command('workbench.action.terminal.focus');
    if (value === 'history') {
      try { setHistory(null); await gitHistory?.focus(); }
      catch (cause) { setError(String(cause)); }
    }
  };
  const chooseCommit = async (commit: string, parent?: string) => {
    try { if (gitHistory) setHistory(await gitHistory.readCommit(commit, parent)); }
    catch (cause) { setError(String(cause)); }
  };

  return <main className={`ide-workbench ${theme.includes('Darcula') ? 'is-dark' : ''}`}>
    <header className="ide-toolbar">
      <button aria-label="Back" onClick={() => command('workbench.action.navigateBack')} title="Back"><ArrowLeft size={15} /></button>
      <button aria-label="Forward" onClick={() => command('workbench.action.navigateForward')} title="Forward"><ArrowRight size={15} /></button>
      {sidebar !== 'files' && <button aria-label="Show files" title="Show files" onClick={() => setSidebar('files')}><PanelLeft size={15} /></button>}
      <button className="ide-search" onClick={() => command('workbench.action.quickOpen')}>Go to file <kbd>{keymap === 'IDEA' ? '⌘⇧O' : '⌘P'}</kbd></button>
      <button onClick={() => command('workbench.action.showAllSymbols')}><ListTree size={14} /> Symbols</button>
      <button onClick={() => { setSidebar('search'); command('workbench.action.findInFiles'); }}><Search size={14} /> Search</button>
      <button onClick={() => command('editor.showCallHierarchy')}><GitBranch size={14} /> Callers</button>
      <button onClick={() => command('editor.action.referenceSearch.trigger')}>Usages</button>
      <button disabled={!comments.onComment} onClick={commentSelection}><MessageSquare size={14} /> Comment</button>
      <EditorNavigation runtime={runtime} active={active} context={context} file={file} revision={files?.revision}
        onHistory={commit => { setBottom('history'); void chooseCommit(commit); }} />
      <select aria-label="Editor keymap" value={keymap} onChange={event => { const value = event.target.value; void chooseKeymap(value).then(() => setKeymap(value), cause => setError(String(cause))); }}><option>IDEA</option><option>VS Code</option></select>
      <select aria-label="IDE theme" value={theme} onChange={event => {
        const value = event.target.value;
        localStorage.setItem('bytequay.ide.theme', value); setTheme(value);
        void runtime.api.workspace.getConfiguration().update('workbench.colorTheme', value, runtime.api.ConfigurationTarget.Global);
      }}><option>ByteQuay IDEA Light</option><option>ByteQuay Darcula</option></select>
    </header>
    {error && <div className="ide-notice" role="alert">{error}<button onClick={() => setError(null)}>Dismiss</button></div>}
    {recovered.map(draft => <div className="ide-notice" role="alert" key={`${draft.path}:${draft.text}`}>
      <span>A saved draft for {draft.path} differs from the current file. Your draft has been kept.</span>
      <button onClick={() => { void runtime.api.workspace.openTextDocument({ content: draft.text }).then(document => runtime.api.window.showTextDocument(document, { preview: false })); }}>Open recovered draft</button>
      <button onClick={() => { discardRecoveredDraft(draft); setRecovered(current => current.filter(item => item !== draft)); }}>Discard recovered draft</button>
    </div>)}
    <div className="ide-code-row">
      {sidebar === 'files' && <div className="ide-files-pane" style={{ width: column.width }}><FileExplorer files={files} selected={selected} runtime={runtime} onRefresh={() => setFilesReload(value => value + 1)} onError={setError} onHide={() => setSidebar(null)} /></div>}
      {sidebar === 'search' && <aside className="ide-files-pane" style={{ width: column.width }} aria-label="Code search"><Part part={Parts.SIDEBAR_PART} /></aside>}
      {sidebar && <div className={`ct-resize${column.resizing ? ' is-resizing' : ''}`} role="separator" tabIndex={0} aria-orientation="vertical"
        aria-label="Resize the Files column" aria-valuenow={column.width} aria-valuemin={180} aria-valuemax={650}
        onMouseDown={event => { event.currentTarget.focus(); column.startResize(event); }} onKeyDown={event => {
          if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
          event.preventDefault(); column.setWidth(width => Math.max(180, Math.min(650, width + (event.key === 'ArrowLeft' ? -20 : 20))));
        }} />}
      <section className="ide-editor" aria-label="Source code"><Part part={Parts.EDITOR_PART} />{comments.unplaced}</section>
    </div>
    {active && <ReviewZones runtime={runtime} uri={active.document.uri.toString()} cards={comments.cards} renderCard={comments.renderCard} />}
    <div className={`ide-panel-resize${panel.resizing ? ' is-resizing' : ''}`} role="separator" tabIndex={0} aria-orientation="horizontal"
      aria-label="Resize the tools panel" aria-valuenow={panel.width} aria-valuemin={100} aria-valuemax={900}
      onMouseDown={event => { event.currentTarget.focus(); panel.startResize(event); }} onKeyDown={event => {
        if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
        event.preventDefault(); panel.setWidth(height => Math.max(100, Math.min(900, height + (event.key === 'ArrowUp' ? 20 : -20))));
      }} />
    <footer className="ide-bottom" style={{ height: panel.width }}>
      <nav className="ide-toolbar" aria-label="Code tools">
        <button aria-pressed={bottom === 'tests'} onClick={() => { void selectBottom('tests'); }}><Play size={13} /> Tests</button>
        <button aria-pressed={bottom === 'debug'} onClick={() => { void selectBottom('debug'); }}><Bug size={14} /> Debug</button>
        <button aria-pressed={bottom === 'terminal'} onClick={() => { void selectBottom('terminal'); }}><TerminalSquare size={14} /> Terminal</button>
        <button aria-pressed={bottom === 'history'} onClick={() => { void selectBottom('history'); }}><GitCommitHorizontal size={14} /> Git history</button>
        <span className="ide-spacer" />
        <button onClick={() => command('testing.runCurrentFile')}>Run file tests</button>
        <button onClick={() => { void selectBottom('debug'); command('testing.debugCurrentFile'); }}>Debug file tests</button>
        <button onClick={() => { void selectBottom('debug'); command('workbench.action.debug.start'); }}>Debug configuration</button>
        <button onClick={() => command('workbench.action.debug.stop')}><Square size={12} /> Stop debug</button>
      </nav>
      <div className="ide-tools" style={{ display: bottom === 'history' && history ? 'none' : undefined }}><Part part={Parts.PANEL_PART} /></div>
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
      setReady({ runtime, connection: bootstrap.connection });
    }).catch(cause => setError(String(cause)));
    return stop;
  }, []);
  if (error) return <div className="ide-loading" role="alert">{error}</div>;
  if (!ready || !context) return <div className="ide-loading" role="status">Loading code tools…</div>;
  return <DraftWorkbench {...ready} context={context} />;
}

createRoot(document.getElementById('root')).render(<App />);
