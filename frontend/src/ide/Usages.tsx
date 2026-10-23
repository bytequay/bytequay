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
/* eslint-disable import/no-unresolved -- Use the same reference providers and models as the native editor. */
import { useEffect, useRef, useState } from 'react';
import { FileCode, FlaskConical, RotateCw, X } from 'lucide-react';
import { getService, IEditorService, ILanguageFeaturesService, ITextModelService, type ICodeEditorService } from '@codingame/monaco-vscode-api';
import { CancellationTokenSource } from '@codingame/monaco-vscode-api/vscode/vs/base/common/cancellation';
import { DisposableStore } from '@codingame/monaco-vscode-api/vscode/vs/base/common/lifecycle';
import { isMacintosh } from '@codingame/monaco-vscode-api/vscode/vs/base/common/platform';
import type { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import { MouseTargetType, type ICodeEditor } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
import type { Position } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/core/position';
import { Range, type IRange } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/core/range';
import type { Definition, Location, LocationLink, ProviderResult } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/languages';
import type { ITextModel } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/model';
import { ReferencesModel, type OneReference } from '@codingame/monaco-vscode-api/vscode/vs/editor/contrib/gotoSymbol/browser/referencesModel';
import { fileName, isTestFile } from '../pulls/code-tests/codeModel';
import { serviceLanguages } from './languages';
import { progressOf, useBusyStatus, waitForLanguageService } from './statusEntries';

type Request = { id: number; editor: ICodeEditor; model: ITextModel; position: Position; name: string; x: number; y: number };
type Usage = { reference: OneReference; code?: string; start?: number; end?: number; unavailable?: boolean };
type Declaration = { uri: string; range: IRange };

// A fast search ends in a jump or a list; drawing the popup sooner would flash it before a jump.
const REVEAL_DELAY = 400;
const SLOW_SEARCH = 2000;
const DECLARATION_WAIT = 1000;

function snippet(model: ITextModel, reference: OneReference): Omit<Usage, 'reference'> {
  const line = model.getLineContent(reference.range.startLineNumber);
  const indent = line.length - line.trimStart().length;
  return { code: line.trim(), start: Math.max(0, reference.range.startColumn - 1 - indent),
    end: reference.range.endLineNumber === reference.range.startLineNumber ? reference.range.endColumn - 1 - indent : line.trim().length };
}

export default function Usages({ editors, workspace }: { editors: ICodeEditorService; workspace: URI }) {
  const [request, setRequest] = useState<Request | null>(null);
  const sequence = useRef(0);
  useEffect(() => {
    const listeners = new Map<ICodeEditor, () => void>();
    const attach = (editor: ICodeEditor) => {
      // The outer container survives model changes; getDomNode() is replaced with each file's view.
      const element = editor.getContainerDomNode();
      let press: { x: number; y: number; model: ITextModel } | undefined;
      const down = (event: MouseEvent) => {
        press = undefined;
        if (event.button !== 0 || !(isMacintosh ? event.metaKey : event.ctrlKey) || event.altKey || event.shiftKey) return;
        const target = editor.getTargetAtClientPoint(event.clientX, event.clientY);
        const model = editor.getModel();
        if (target?.type !== MouseTargetType.CONTENT_TEXT || !target.position || !model?.getWordAtPosition(target.position)) return;
        // Capture before Monaco's definition gesture receives the same click.
        event.preventDefault(); event.stopPropagation();
        press = { x: event.clientX, y: event.clientY, model };
      };
      const up = (event: MouseEvent) => {
        const start = press; press = undefined;
        if (!start) return;
        event.preventDefault(); event.stopPropagation();
        if (event.button !== 0 || Math.hypot(event.clientX - start.x, event.clientY - start.y) > 4 || editor.getModel() !== start.model) return;
        const target = editor.getTargetAtClientPoint(event.clientX, event.clientY);
        const word = target?.position && start.model.getWordAtPosition(target.position);
        if (target?.type !== MouseTargetType.CONTENT_TEXT || !target.position || !word) return;
        editor.setPosition(target.position);
        editor.trigger('bytequay', 'editor.action.hideHover', {});
        setRequest({ id: ++sequence.current, editor, model: start.model, position: target.position, name: word.word, x: event.clientX, y: event.clientY });
      };
      element.addEventListener('mousedown', down, true); element.addEventListener('mouseup', up, true);
      listeners.set(editor, () => { element.removeEventListener('mousedown', down, true); element.removeEventListener('mouseup', up, true); });
    };
    editors.listCodeEditors().forEach(attach);
    const added = editors.onCodeEditorAdd(attach);
    const removed = editors.onCodeEditorRemove(editor => { listeners.get(editor)?.(); listeners.delete(editor); });
    return () => { added.dispose(); removed.dispose(); listeners.forEach(dispose => dispose()); };
  }, [editors]);
  return request && <UsageResults key={request.id} request={request} workspace={workspace} onClose={() => setRequest(current => current === request ? null : current)} />;
}

function UsageResults({ request, workspace, onClose }: { request: Request; workspace: URI; onClose: () => void }) {
  const popup = useRef<HTMLElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose); closeRef.current = onClose;
  const [rows, setRows] = useState<Usage[]>([]);
  const [loading, setLoading] = useState(true);
  const [waiting, setWaiting] = useState(false);
  // Until the definition answers, a click may be on a call, which opens its declaration, rather than a usages search.
  const [deciding, setDeciding] = useState(true);
  const [shown, setShown] = useState(false);
  const [slow, setSlow] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [selected, setSelected] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const status = useBusyStatus();
  const stalled = waiting || (loading && slow);
  useEffect(() => stalled ? waitForLanguageService() : undefined, [stalled]);
  const close = () => { closeRef.current(); if (request.editor.getDomNode()?.isConnected) request.editor.focus(); };
  useEffect(() => {
    const outside = (event: PointerEvent) => { if (event.target instanceof Node && !popup.current?.contains(event.target)) closeRef.current(); };
    const dismiss = () => closeRef.current();
    document.addEventListener('pointerdown', outside, true); window.addEventListener('resize', dismiss);
    const changes = new DisposableStore();
    changes.add(request.editor.onDidChangeModel(dismiss)); changes.add(request.model.onDidChangeContent(dismiss));
    changes.add(request.editor.onDidDispose(dismiss));
    return () => { changes.dispose(); document.removeEventListener('pointerdown', outside, true); window.removeEventListener('resize', dismiss); };
  }, [request]);
  useEffect(() => {
    const source = new CancellationTokenSource();
    const results: ReferencesModel[] = [];
    const here = request.model.uri.toString();
    let complete = false;
    setRows([]); setLoading(true); setWaiting(false); setDeciding(true); setSlow(false); setMessage(null); setSelected(0);
    const reveal = setTimeout(() => setShown(true), REVEAL_DELAY);
    const slowSearch = setTimeout(() => setSlow(true), SLOW_SEARCH);
    void (async () => {
      const [features, models, editors] = await Promise.all([getService(ILanguageFeaturesService), getService(ITextModelService), getService(IEditorService)]);
      if (source.token.isCancellationRequested) return;
      while (!features.referenceProvider.has(request.model)) {
        if (!serviceLanguages.includes(request.model.getLanguageId())) throw new Error('Reference search is not available for this file.');
        setWaiting(true); setShown(true);
        await new Promise<void>(resolve => {
          const listeners = new DisposableStore();
          const done = () => { listeners.dispose(); resolve(); };
          listeners.add(features.referenceProvider.onDidChange(() => { if (features.referenceProvider.has(request.model)) done(); }));
          listeners.add(source.token.onCancellationRequested(done));
        });
        if (source.token.isCancellationRequested) return;
        setWaiting(false);
      }
      // A declaration is an occurrence but not a usage, and a language service may return the symbol's own
      // and every overriding one whatever the search asks for.
      const locations = async (lookups: () => ProviderResult<Definition | LocationLink[]>[]): Promise<Declaration[]> => {
        try {
          return (await Promise.all(lookups())).flatMap((links): (Location | LocationLink)[] => Array.isArray(links) ? links : links ? [links] : [])
            .map(link => ({ uri: link.uri.toString(), range: ('targetSelectionRange' in link ? link.targetSelectionRange : undefined) ?? link.range }));
        } catch { return []; }
      };
      const definitions = locations(() => features.definitionProvider.ordered(request.model).map(provider => provider.provideDefinition(request.model, request.position, source.token)));
      const overrides = locations(() => features.implementationProvider.ordered(request.model).map(provider => provider.provideImplementation(request.model, request.position, source.token)));
      const declared = (known: Declaration[], uri: URI, range: IRange) => known.some(item => item.uri === uri.toString() && Range.areIntersectingOrTouching(item.range, range));
      // A use goes to its declaration, as Go to Definition does. Only a declaration, such as an interface method or its
      // implementation, lists its usages.
      const elsewhere = (found: Declaration[]) => found.length > 0 && !found.some(item => item.uri === here && Range.containsPosition(item.range, request.position));
      void definitions.then(found => {
        if (source.token.isCancellationRequested) return;
        if (!elsewhere(found)) { setDeciding(false); return; }
        request.editor.focus();
        request.editor.trigger('bytequay', 'editor.action.revealDefinition', {});
        closeRef.current();
      });
      // The open file answers long before the project search does; the editor's own word-match fallback would list every same-named method.
      const occurrences = features.documentHighlightProvider.ordered(request.model).filter(provider => (provider as { selector?: { language?: string } }).selector?.language !== '*');
      void Promise.all([Promise.all(occurrences.map(provider => provider.provideDocumentHighlights(request.model, request.position, source.token))), definitions]).then(([highlights, known]) => {
        if (complete || source.token.isCancellationRequested || elsewhere(known)) return;
        const local = (highlights.find(found => found?.length) ?? []).filter(found => !declared(known, request.model.uri, found.range));
        if (!local.length) return;
        const early = new ReferencesModel(local.map(found => ({ uri: request.model.uri, range: found.range })), request.name);
        results.push(early);
        setRows(early.references.map(reference => ({ reference, ...snippet(request.model, reference) })));
      }).catch(() => { /* The project search below still answers. */ });
      const providers = features.referenceProvider.ordered(request.model);
      const links = (await Promise.all(providers.map(provider => provider.provideReferences(request.model, request.position, { includeDeclaration: false }, source.token)))).flatMap(items => items ?? []);
      if (source.token.isCancellationRequested) return;
      complete = true;
      // A lookup that never answers must not hold back usages that already arrived.
      const capped = (lookup: Promise<Declaration[]>) => Promise.race([lookup, new Promise<Declaration[]>(resolve => setTimeout(() => resolve([]), DECLARATION_WAIT))]);
      const [found, overriding] = await Promise.all([capped(definitions), capped(overrides)]);
      if (source.token.isCancellationRequested || elsewhere(found)) return;
      const known = [...found, ...overriding];
      const references = new ReferencesModel(links.filter(link => !declared(known, link.uri, link.range)), request.name);
      results.push(references);
      const all = references.references;
      if (!all.length) {
        // A language service that has no outline for a file has not loaded it as project code, and finds nothing for any search in it.
        const outlines = features.documentSymbolProvider.ordered(request.model);
        const symbols = await Promise.all(outlines.map(provider => provider.provideDocumentSymbols(request.model, source.token)));
        if (source.token.isCancellationRequested) return;
        if (outlines.length && symbols.every(found => !found?.length)) throw new Error('The language service has not loaded this file as part of a project, so its usages cannot be searched.');
      }
      // A lone usage under the cursor would be a jump to where the user already is.
      if (all.length === 1 && !(all[0].uri.toString() === here && Range.containsPosition(all[0].range, request.position))) {
        await editors.openEditor({ resource: all[0].uri, options: { selection: all[0].range, pinned: true } });
        closeRef.current();
        return;
      }
      const local = all.filter(reference => reference.uri.toString() === here);
      setRows([...local.map(reference => ({ reference, ...snippet(request.model, reference) })), ...all.filter(reference => reference.uri.toString() !== here).map(reference => ({ reference }))]);
      setSelected(current => Math.max(0, Math.min(current, all.length - 1)));
      setLoading(false); setDeciding(false); setShown(true);
      // Read each source once and release it immediately; even a large result list does not hold open every file.
      for (const group of references.groups) {
        if (source.token.isCancellationRequested) return;
        if (group.uri.toString() === here) continue;
        const snippets = new Map<string, Omit<Usage, 'reference'>>();
        try {
          const file = await models.createModelReference(group.uri);
          try {
            for (const reference of group.children) snippets.set(reference.id, snippet(file.object.textEditorModel, reference));
          } finally { file.dispose(); }
        } catch { group.children.forEach(reference => snippets.set(reference.id, { unavailable: true })); }
        if (source.token.isCancellationRequested) return;
        setRows(current => current.map(row => snippets.has(row.reference.id) ? { ...row, ...snippets.get(row.reference.id) } : row));
      }
    })().catch(error => {
      complete = true;
      if (!source.token.isCancellationRequested) { setRows([]); setMessage(String(error instanceof Error ? error.message : error)); setLoading(false); setDeciding(false); setShown(true); }
    });
    return () => { clearTimeout(reveal); clearTimeout(slowSearch); source.dispose(true); results.forEach(result => result.dispose()); };
  }, [request, refresh]);
  useEffect(() => { if (shown) (list.current ?? popup.current)?.focus(); }, [shown, rows.length]);
  useEffect(() => { document.getElementById(`ide-usage-${selected}`)?.scrollIntoView({ block: 'nearest' }); }, [selected]);
  const open = async (row: Usage) => {
    try {
      const editors = await getService(IEditorService);
      await editors.openEditor({ resource: row.reference.uri, options: { selection: row.reference.range, pinned: true } });
      closeRef.current();
    } catch (error) { setMessage(String(error)); }
  };
  if (!shown) return null;
  const top = Math.max(12, Math.min(request.y + 18, window.innerHeight - 440));
  const title = deciding ? request.name : `Usages of ${request.name}`;
  return <section ref={popup} className="ide-usages" role="dialog" aria-label={title} tabIndex={-1}
    style={{ left: Math.max(12, Math.min(request.x, window.innerWidth - 1052)), top, maxHeight: window.innerHeight - top - 12 }}
    onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); } }}>
    <header><strong>{title}</strong><span role="status">{waiting ? 'Preparing reference search…' : deciding ? 'Finding declaration…' : loading ? rows.length ? 'Finding more…' : 'Finding usages…' : message ? 'Search unavailable' : `${rows.length} ${rows.length === 1 ? 'usage' : 'usages'}`}</span>
      <button aria-label="Refresh usages" title="Refresh usages" onClick={() => setRefresh(value => value + 1)}><RotateCw size={14} /></button>
      <button aria-label="Close usages" title="Close (Escape)" onClick={close}><X size={15} /></button></header>
    {message && <p role="alert">{message}</p>}
    {waiting && <p>Waiting for the language service to finish preparing reference search. A large project can take several minutes. Results will appear automatically.</p>}
    {deciding && !waiting && slow && <p>Waiting for the language service. A call opens its declaration, and a declaration lists its usages, once it answers.</p>}
    {loading && !deciding && !waiting && slow && <p>Still searching. The first search after a project opens waits for indexing, which can take a few minutes.</p>}
    {stalled && <>
      <progress max={100} value={progressOf(status)} aria-label="Language service progress" />
      {status && <p className="ide-usages__status">{status}</p>}
    </>}
    {!loading && !message && rows.length === 0 && <p>No usages found.</p>}
    {rows.length > 0 && <div ref={list} className="ide-usages__list" role="listbox" aria-label="Usages" tabIndex={0} aria-activedescendant={`ide-usage-${selected}`}
      onKeyDown={event => {
        const next = event.key === 'ArrowDown' ? Math.min(rows.length - 1, selected + 1) : event.key === 'ArrowUp' ? Math.max(0, selected - 1)
          : event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1 : undefined;
        if (next !== undefined) { event.preventDefault(); setSelected(next); }
        if (event.key === 'Enter') { event.preventDefault(); void open(rows[selected]); }
      }}>
      {rows.map((row, index) => {
        const uri = row.reference.uri;
        const path = uri.scheme === workspace.scheme && uri.authority === workspace.authority && uri.path.startsWith(workspace.path + '/')
          ? uri.path.slice(workspace.path.length + 1) : uri.path;
        const test = isTestFile(path);
        return <button key={row.reference.id} id={`ide-usage-${index}`} className={`ide-usages__row${test ? ' is-test' : ''}`} role="option" aria-selected={selected === index} tabIndex={-1}
          title={`${path}:${row.reference.range.startLineNumber}`} onMouseDown={() => setSelected(index)} onClick={() => { void open(row); }}>
          {test ? <FlaskConical size={15} aria-label="Test file" /> : <FileCode size={15} aria-hidden="true" />}
          <span className="ide-usages__path"><span>{path.slice(0, path.length - fileName(path).length)}</span><b>{fileName(path)}</b></span>
          <span className="ide-usages__line">{row.reference.range.startLineNumber}</span>
          <code>{row.code !== undefined ? <>{row.code.slice(0, row.start)}<strong>{row.code.slice(row.start, row.end)}</strong>{row.code.slice(row.end)}</>
            : <em>{row.unavailable ? 'Source unavailable' : 'Loading source…'}</em>}</code>
        </button>;
      })}
    </div>}
  </section>;
}
