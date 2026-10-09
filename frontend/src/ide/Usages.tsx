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
import type { ITextModel } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/model';
import { ReferencesModel, type OneReference } from '@codingame/monaco-vscode-api/vscode/vs/editor/contrib/gotoSymbol/browser/referencesModel';
import { fileName, isTestFile } from '../pulls/code-tests/codeModel';

type Request = { id: number; editor: ICodeEditor; model: ITextModel; position: Position; name: string; x: number; y: number };
type Usage = { reference: OneReference; code?: string; start?: number; end?: number; unavailable?: boolean };

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
  const [message, setMessage] = useState<string | null>(null);
  const [selected, setSelected] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const close = () => { closeRef.current(); if (request.editor.getDomNode()?.isConnected) request.editor.focus(); };
  useEffect(() => {
    popup.current?.focus();
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
    let references: ReferencesModel | undefined;
    setRows([]); setLoading(true); setWaiting(false); setMessage(null); setSelected(0);
    void (async () => {
      const [features, models] = await Promise.all([getService(ILanguageFeaturesService), getService(ITextModelService)]);
      if (source.token.isCancellationRequested) return;
      while (!features.referenceProvider.has(request.model)) {
        if (!['java', 'go', 'rust'].includes(request.model.getLanguageId())) throw new Error('Reference search is not available for this file.');
        setWaiting(true);
        await new Promise<void>(resolve => {
          const listeners = new DisposableStore();
          const done = () => { listeners.dispose(); resolve(); };
          listeners.add(features.referenceProvider.onDidChange(() => { if (features.referenceProvider.has(request.model)) done(); }));
          listeners.add(source.token.onCancellationRequested(done));
        });
        if (source.token.isCancellationRequested) return;
        setWaiting(false);
      }
      const providers = features.referenceProvider.ordered(request.model);
      const links = await Promise.all(providers.map(provider => provider.provideReferences(request.model, request.position, { includeDeclaration: false }, source.token)));
      if (source.token.isCancellationRequested) return;
      references = new ReferencesModel(links.flatMap(items => items ?? []), request.name);
      setRows(references.references.map(reference => ({ reference })));
      setLoading(false);
      // Read each source once and release it immediately; even a large result list does not hold open every file.
      for (const group of references.groups) {
        if (source.token.isCancellationRequested) return;
        const snippets = new Map<string, Omit<Usage, 'reference'>>();
        try {
          const file = await models.createModelReference(group.uri);
          try {
            for (const reference of group.children) {
              const line = file.object.textEditorModel.getLineContent(reference.range.startLineNumber);
              const indent = line.length - line.trimStart().length;
              snippets.set(reference.id, { code: line.trim(), start: Math.max(0, reference.range.startColumn - 1 - indent),
                end: reference.range.endLineNumber === reference.range.startLineNumber ? reference.range.endColumn - 1 - indent : line.trim().length });
            }
          } finally { file.dispose(); }
        } catch { group.children.forEach(reference => snippets.set(reference.id, { unavailable: true })); }
        if (source.token.isCancellationRequested) return;
        setRows(current => current.map(row => snippets.has(row.reference.id) ? { ...row, ...snippets.get(row.reference.id) } : row));
      }
    })().catch(error => { if (!source.token.isCancellationRequested) { setMessage(String(error instanceof Error ? error.message : error)); setLoading(false); } });
    return () => { source.dispose(true); references?.dispose(); };
  }, [request, refresh]);
  useEffect(() => { list.current?.focus(); }, [rows.length]);
  useEffect(() => { document.getElementById(`ide-usage-${selected}`)?.scrollIntoView({ block: 'nearest' }); }, [selected]);
  const open = async (row: Usage) => {
    try {
      const editors = await getService(IEditorService);
      await editors.openEditor({ resource: row.reference.uri, options: { selection: row.reference.range, pinned: true } });
      closeRef.current();
    } catch (error) { setMessage(String(error)); }
  };
  const top = Math.max(12, Math.min(request.y + 18, window.innerHeight - 440));
  return <section ref={popup} className="ide-usages" role="dialog" aria-label={`Usages of ${request.name}`} tabIndex={-1}
    style={{ left: Math.max(12, Math.min(request.x, window.innerWidth - 1052)), top, maxHeight: window.innerHeight - top - 12 }}
    onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); } }}>
    <header><strong>Usages of {request.name}</strong><span role="status">{waiting ? 'Preparing reference search…' : loading ? 'Finding usages…' : message ? 'Search unavailable' : `${rows.length} usages`}</span>
      <button aria-label="Refresh usages" title="Refresh usages" onClick={() => setRefresh(value => value + 1)}><RotateCw size={14} /></button>
      <button aria-label="Close usages" title="Close (Escape)" onClick={close}><X size={15} /></button></header>
    {message && <p role="alert">{message}</p>}
    {waiting && <p>Waiting for the language service to finish preparing reference search. Results will appear automatically.</p>}
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
