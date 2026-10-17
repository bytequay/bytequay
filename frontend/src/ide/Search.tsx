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
/* eslint-disable import/no-unresolved -- Override native find commands while reusing their editor and search services. */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Search as SearchIcon, X } from 'lucide-react';
import { getService, IEditorService, type ICodeEditorService } from '@codingame/monaco-vscode-api';
import { CancellationTokenSource } from '@codingame/monaco-vscode-api/vscode/vs/base/common/cancellation';
import { isMacintosh } from '@codingame/monaco-vscode-api/vscode/vs/base/common/platform';
import { matchesFuzzy } from '@codingame/monaco-vscode-api/vscode/vs/base/common/filters';
import type { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import { CommandsRegistry } from '@codingame/monaco-vscode-api/vscode/vs/platform/commands/common/commands';
import { SymbolKind } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/languages';
import type { ICodeEditor } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
import type { ITextModel } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/model';
import { fileName, isTestFile } from '../pulls/code-tests/codeModel';
import type { ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import { searchCode, searchModule, relativeSearchPath, type SearchTab, type SearchRow, type SearchResults } from './codeSearch';

type Request = { id: number; scope: 'project' | 'file'; tab: SearchTab; query: string; editor: ICodeEditor | null; model: ITextModel | null };
const tabs: SearchTab[] = ['All', 'Files', 'Text'];

export default function Search({ editors, workspace, files }: { editors: ICodeEditorService; workspace: URI; files: ReviewCodeFilesDto | null }) {
  const [request, setRequest] = useState<Request | null>(null);
  const sequence = useRef(0);
  const lastQuery = useRef('');
  const open = (scope: Request['scope'], tab: SearchTab = 'All') => {
    const editor = editors.getFocusedCodeEditor() ?? editors.getActiveCodeEditor();
    const model = editor?.getModel() ?? null;
    const selection = editor?.getSelection();
    const selected = model && selection ? model.getValueInRange(selection) : '';
    const query = request ? lastQuery.current : selected && selected.length < 200 && !selected.includes('\n') ? selected : lastQuery.current;
    lastQuery.current = query;
    setRequest({ id: ++sequence.current, scope, tab: scope === 'file' ? 'Text' : tab, query, editor, model });
  };
  const openRef = useRef(open); openRef.current = open;
  useEffect(() => {
    let shiftDown = false;
    let lastShift = 0;
    const reset = () => { shiftDown = false; lastShift = 0; };
    const down = (event: KeyboardEvent) => {
      if (event.isComposing || event.repeat) { reset(); return; }
      if (event.key === 'Shift' && !event.altKey && !event.ctrlKey && !event.metaKey) { shiftDown = true; return; }
      reset();
      if ((isMacintosh ? event.metaKey : event.ctrlKey) && !event.altKey && event.key.toLowerCase() === 'f') {
        event.preventDefault(); event.stopImmediatePropagation(); openRef.current(event.shiftKey ? 'project' : 'file');
      }
    };
    const up = (event: KeyboardEvent) => {
      if (event.key !== 'Shift' || !shiftDown || event.altKey || event.ctrlKey || event.metaKey) { reset(); return; }
      shiftDown = false;
      if (lastShift && performance.now() - lastShift < 400) { event.preventDefault(); event.stopImmediatePropagation(); reset(); openRef.current('project'); }
      else lastShift = performance.now();
    };
    window.addEventListener('keydown', down, true); window.addEventListener('keyup', up, true); window.addEventListener('blur', reset);
    const commands = [
      CommandsRegistry.registerCommand('actions.find', () => openRef.current('file')),
      CommandsRegistry.registerCommand('workbench.action.findInFiles', () => openRef.current('project')),
      CommandsRegistry.registerCommand('workbench.view.search', () => openRef.current('project')),
      CommandsRegistry.registerCommand('workbench.action.quickTextSearch', () => openRef.current('project', 'Text')),
    ];
    return () => {
      commands.forEach(command => command.dispose()); window.removeEventListener('keydown', down, true);
      window.removeEventListener('keyup', up, true); window.removeEventListener('blur', reset);
    };
  }, []);
  return <>
    <button className="ide-search" title="Search everywhere (double Shift or ⌘⇧F)" onClick={() => open('project')}><SearchIcon size={14} /> Search <kbd>⇧⇧</kbd></button>
    {request && <SearchResultsPopup key={request.id} request={request} workspace={workspace} files={files}
      onQuery={query => { lastQuery.current = query; }} onClose={() => setRequest(null)} />}
  </>;
}

function SearchResultsPopup({ request, workspace, files, onQuery, onClose }: {
  request: Request; workspace: URI; files: ReviewCodeFilesDto | null; onQuery: (query: string) => void; onClose: () => void;
}) {
  const [scope, setScope] = useState(request.scope);
  const [tab, setTab] = useState(request.tab);
  const [query, setQuery] = useState(request.query);
  const [results, setResults] = useState<SearchResults>({ rows: [], messages: [] });
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const [selected, setSelected] = useState<string | undefined>();
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const popup = useRef<HTMLElement>(null);
  const navigation = useRef<CancellationTokenSource | undefined>(undefined);
  const closeRef = useRef(onClose); closeRef.current = onClose;
  const close = () => { closeRef.current(); if (request.editor?.getDomNode()?.isConnected) request.editor.focus(); };
  const model = request.model && !request.model.isDisposed() ? request.model : undefined;
  const manifests = useMemo(() => [...new Set((files?.files ?? []).filter(file => /(^|\/)(pom\.xml|Cargo\.toml|go\.mod|package\.json)$/.test(file.path))
    .map(file => file.path.slice(0, Math.max(0, file.path.lastIndexOf('/')))))].filter(Boolean).sort((a, b) => b.length - a.length), [files]);
  const index = Math.max(0, results.rows.findIndex(row => row.id === selected));
  const row = results.rows[index];
  useEffect(() => {
    input.current?.focus(); input.current?.select();
    const changed = request.model?.onDidChangeContent(() => setRevision(value => value + 1));
    const disposed = request.model?.onWillDispose(() => setRevision(value => value + 1));
    return () => { changed?.dispose(); disposed?.dispose(); navigation.current?.dispose(true); };
  }, [request]);
  useEffect(() => {
    const source = new CancellationTokenSource();
    navigation.current?.dispose(true); setOpening(false);
    setResults({ rows: [], messages: [] }); setSelected(undefined); setError('');
    if (!query || scope === 'file' && !model) { setLoading(false); return () => source.dispose(true); }
    setLoading(true);
    const timer = setTimeout(() => {
      void searchCode(workspace, query, scope === 'file' ? 'Text' : tab, scope === 'file' ? model : undefined, source.token, setResults)
        .catch(cause => { if (!source.token.isCancellationRequested) setError(String(cause)); })
        .finally(() => { if (!source.token.isCancellationRequested) setLoading(false); });
    }, 150);
    return () => { clearTimeout(timer); source.dispose(true); };
  }, [query, scope, tab, model, workspace, revision]);
  useEffect(() => { popup.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }); }, [row?.id]);
  const open = async (row: SearchRow) => {
    if (opening) return;
    setOpening(true); setError('');
    navigation.current?.dispose(true);
    const source = new CancellationTokenSource(); navigation.current = source;
    try {
      const location = row.resolve ? await row.resolve(source.token) : { uri: row.uri, range: row.range };
      if (source.token.isCancellationRequested) return;
      const editors = await getService(IEditorService);
      if (source.token.isCancellationRequested) return;
      await editors.openEditor({ resource: location.uri, options: { selection: location.range, pinned: true } });
      if (!source.token.isCancellationRequested) closeRef.current();
    } catch (cause) { if (!source.token.isCancellationRequested) setError(String(cause)); }
    finally { if (!source.token.isCancellationRequested) setOpening(false); }
  };
  return <div className="ide-search-shade" onMouseDown={event => { if (event.target === event.currentTarget) close(); }}>
    <section ref={popup} className="ide-search-popup" role="dialog" aria-modal="true" aria-label={scope === 'file' ? 'Search current file' : 'Search everywhere'}
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
        if (event.target === input.current && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
          event.preventDefault(); setSelected(results.rows[Math.max(0, Math.min(results.rows.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)))]?.id);
        }
        if (event.key === 'Enter' && event.target === input.current && row) { event.preventDefault(); void open(row); }
        if (event.key === 'Tab') {
          const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not([disabled]):not([tabindex="-1"]), input, select')];
          if (event.shiftKey && document.activeElement === controls[0]) { event.preventDefault(); controls.at(-1)?.focus(); }
          else if (!event.shiftKey && document.activeElement === controls.at(-1)) { event.preventDefault(); controls[0]?.focus(); }
        }
      }}>
      <header>
        <nav aria-label="Search categories">{tabs.map(value => <button key={value} disabled={scope === 'file' && value !== 'Text'} aria-pressed={tab === value}
          onClick={() => { setTab(value); input.current?.focus(); }}>{value}</button>)}</nav>
        <select aria-label="Search scope" value={scope} onChange={event => {
          const scope = event.target.value as Request['scope']; setScope(scope); setTab(scope === 'file' ? 'Text' : 'All'); input.current?.focus();
        }}><option value="project">Project</option><option value="file" disabled={!model}>Current file</option></select>
        <button aria-label="Close search" title="Close (Escape)" onClick={close}><X size={16} /></button>
      </header>
      <label className="ide-search-input"><SearchIcon size={19} aria-hidden="true" /><input ref={input} role="combobox" aria-label="Search query"
        aria-expanded={true} aria-controls="ide-search-results" aria-activedescendant={row ? `ide-search-row-${index}` : undefined} aria-autocomplete="list"
        placeholder={scope === 'file' ? `Find in ${model ? fileName(model.uri.path) : 'current file'}` : 'Search classes, fields, files, and text'} value={query}
        onChange={event => { setQuery(event.target.value); onQuery(event.target.value); }} /></label>
      {error && <p role="alert">{error}</p>}
      {results.messages.map(message => <p key={message} role="status">{message}</p>)}
      {scope === 'file' && !model && <p>Open a source file to search within it.</p>}
      {!loading && query && !results.rows.length && !error && !results.messages.length && (scope === 'project' || model) && <p>No matches found.</p>}
      <div id="ide-search-results" className="ide-search-results" role="listbox" aria-label="Search results" aria-busy={loading || opening}>
        {results.rows.map((row, at) => {
          const path = relativeSearchPath(workspace, row.uri) ?? row.uri.path;
          const test = isTestFile(path);
          const letter = row.kind === 'text' ? 'T' : row.kind === 'file' ? 'F' : row.kind === 'field' ? 'f'
            : row.symbolKind === SymbolKind.Interface ? 'I' : row.symbolKind === SymbolKind.Enum ? 'E' : row.symbolKind === SymbolKind.Struct ? 'R' : row.kind === 'class' ? 'C' : 'S';
          return <button key={row.id} id={`ide-search-row-${at}`} className={`ide-search-row${test ? ' is-test' : ''}`} role="option"
            aria-selected={index === at} tabIndex={-1} title={`${path}:${row.range.startLineNumber}`} data-kind={row.kind}
            onMouseDown={event => { event.preventDefault(); setSelected(row.id); }} onClick={() => { void open(row); }}>
            <span className={`ide-search-kind is-${letter}`} aria-label={test ? `Test ${row.kind}` : row.kind}>{letter}</span>
            <span className="ide-search-name"><span className="ide-search-title">{row.kind === 'text' ? <code><Match text={row.name} query={query} literal /></code> : <Match text={row.name} query={query} />}</span>
              <small>{row.detail}</small></span>
            <span className="ide-search-module">{searchModule(path, manifests, fileName(workspace.path))}</span>
          </button>;
        })}
      </div>
      <footer><span>{row ? `${relativeSearchPath(workspace, row.uri) ?? row.uri.path}:${row.range.startLineNumber}` : 'Project files · respects search exclusions'}</span><span>↑↓ Select · Enter Open · Esc Close</span></footer>
    </section>
  </div>;
}

function Match({ text, query, literal = false }: { text: string; query: string; literal?: boolean }) {
  const offset = text.toLowerCase().indexOf(query.toLowerCase());
  const matches = literal ? offset < 0 ? [] : [{ start: offset, end: offset + query.length }] : matchesFuzzy(query, text, true) ?? [];
  let start = 0;
  return <>{matches.map((match, index) => {
    const before = text.slice(start, match.start); start = match.end;
    return <span key={index}>{before}<mark>{text.slice(match.start, match.end)}</mark></span>;
  })}{text.slice(start)}</>;
}
