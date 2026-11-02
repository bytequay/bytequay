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
/* eslint-disable import/no-unresolved -- Integrate the pinned workbench's embedded diff and command menus. */
import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { editor as monaco } from 'monaco-editor';
import { IInstantiationService, StandaloneServices } from '@codingame/monaco-vscode-api';
import { EditorOption } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/config/editorOptions';
import { EmbeddedDiffEditorWidget } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/widget/diffEditor/embeddedDiffEditorWidget';
import { MenuId, MenuRegistry } from '@codingame/monaco-vscode-api/vscode/vs/platform/actions/common/actions';
import type { ICodeEditor } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
import { X } from 'lucide-react';
import type { ReviewCodeFileDto } from '../pulls/code-tests/contracts';
import { lineChangeMarkers } from '../pulls/code-tests/lineChangeMarkers';
import type { IdeRuntime } from './runtime';
import ReviewZones from './ReviewZones';

type Marker = ReturnType<typeof lineChangeMarkers>[number];
type Preview = { marker: Marker; index: number; line: number; file: ReviewCodeFileDto; draft: boolean };

function ChangePreview({ editor, preview, onClose }: { editor: ICodeEditor; preview: Preview; onClose: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const { change } = preview.marker;
  // Terminating each fragment preserves the difference between no lines and one empty line.
  const before = change.originalLines.map(line => line.replace(/\r$/, '') + '\n').join('');
  const after = change.kind === 'DELETED' ? '' : (preview.file.content ?? '').replace(/^\uFEFF/, '').split(/\r\n|\r|\n/).slice(change.line - 1, change.line - 1 + change.count).join('\n') + '\n';
  const font = editor.getOption(EditorOption.fontInfo);
  const height = Math.min(320, Math.max(80, (change.originalLines.length + (change.kind === 'DELETED' ? 0 : change.count) + 1) * font.lineHeight));
  useLayoutEffect(() => {
    if (!host.current) return;
    const language = editor.getModel()?.getLanguageId();
    const original = monaco.createModel(before, language);
    const modified = monaco.createModel(after, language);
    // Simple widgets keep preview focus out of the workbench's active-file and draft tracking.
    const diff = StandaloneServices.get(IInstantiationService).createInstance(EmbeddedDiffEditorWidget, host.current, {
      automaticLayout: true, readOnly: true, domReadOnly: true, originalEditable: false,
      renderSideBySide: false, renderOverviewRuler: false, renderMarginRevertIcon: false, renderGutterMenu: false,
      ignoreTrimWhitespace: false, diffAlgorithm: 'advanced', diffCodeLens: false,
      fontFamily: font.fontFamily, fontSize: font.fontSize, lineHeight: font.lineHeight,
      minimap: { enabled: false }, lineNumbers: 'off', glyphMargin: false, folding: false,
      scrollBeyondLastLine: false, contextmenu: false, stickyScroll: { enabled: false }, links: false,
      ariaLabel: 'Saved change compared with base',
    }, { originalEditor: { isSimpleWidget: true }, modifiedEditor: { isSimpleWidget: true } }, editor);
    diff.setModel({ original, modified });
    return () => { diff.dispose(); original.dispose(); modified.dispose(); };
  }, [editor, before, after, font.fontFamily, font.fontSize, font.lineHeight]);
  useLayoutEffect(() => {
    if (!host.current) return;
    let frame = 0;
    const resize = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      // Wait for the shared zone to reserve its height before revealing a preview below the viewport.
      frame = requestAnimationFrame(() => {
        const bounds = host.current?.parentElement?.getBoundingClientRect();
        const viewport = editor.getContainerDomNode().getBoundingClientRect();
        if (!bounds) return;
        const overflow = Math.min(bounds.bottom - viewport.bottom + 8, bounds.top - viewport.top);
        if (overflow > 0) editor.setScrollTop(editor.getScrollTop() + overflow);
      });
    });
    resize.observe(host.current);
    return () => { resize.disconnect(); cancelAnimationFrame(frame); };
  }, [editor, preview]);
  return <section className="ide-change-preview" role="region" aria-label="Saved change">
    <header><strong>{preview.marker.title}</strong><button autoFocus aria-label="Close change preview" title="Close (Escape)" onClick={onClose}><X size={14} /></button></header>
    <p>Base → Saved{preview.draft ? ' · Unsaved edits are not included.' : ''}</p>
    <div ref={host} style={{ height }} />
  </section>;
}

export default function LineChanges({ runtime, uri, file }: { runtime: IdeRuntime; uri: string; file: ReviewCodeFileDto | null }) {
  const active = runtime.editors.getActiveCodeEditor();
  const editor = active?.getModel()?.uri.toString() === uri ? active : runtime.editors.listCodeEditors().find(editor => editor.getModel()?.uri.toString() === uri);
  const [preview, setPreview] = useState<Preview | null>(null);
  const shown = preview?.file === file ? preview : null;
  const cards = useMemo(() => shown ? [{ key: 'line-change-preview', line: shown.line }] : [], [shown]);
  const close = () => { setPreview(null); editor?.focus(); };

  useLayoutEffect(() => {
    const model = editor?.getModel();
    setPreview(null);
    if (!editor || !model || !file || file.content === null) return;
    let decorations: string[] = [];
    let markers: Marker[] = [];
    const saved = file.content.replace(/^\uFEFF/, '');
    const update = () => {
      setPreview(null);
      // Monaco carries the saved ranges through unsaved edits, so only the saved contents place them again.
      // shortcut: lines first changed in the unsaved draft get no marker until saved; live diff needs the full base text.
      if (model.getValue() !== saved) return;
      markers = lineChangeMarkers(file.lineChanges, model.getLineCount());
      decorations = model.deltaDecorations(decorations, markers.map((marker, index) => ({
        range: { startLineNumber: marker.start, endLineNumber: marker.end, startColumn: 1, endColumn: 1 },
        options: { description: 'ByteQuay line change', isWholeLine: true,
          linesDecorationsClassName: `${marker.className} ide-line-change-${index}`, linesDecorationsTooltip: `${marker.title} · Click to show change` },
      })));
    };
    const show = (index: number, atLine?: number) => {
      const range = model.getDecorationRange(decorations[index]);
      if (!range || editor.getModel() !== model) return;
      const marker = markers[index];
      const line = marker.change.kind === 'DELETED'
        ? marker.change.line === 0 ? range.startLineNumber - 1 : range.endLineNumber
        : Math.min(range.endLineNumber, Math.max(range.startLineNumber, atLine ?? range.startLineNumber));
      setPreview(current => current?.index === index ? null : { marker, index, line, file, draft: model.getValue() !== saved });
    };
    const element = editor.getContainerDomNode();
    const clicked = (event: MouseEvent) => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey || !(event.target instanceof Element)) return;
      const target = event.target.closest('.ct-line-change');
      if (!target) return;
      const index = markers.findIndex((_, index) => target.classList.contains(`ide-line-change-${index}`));
      if (index < 0) return;
      event.preventDefault(); event.stopPropagation(); show(index, editor.getTargetAtClientPoint(event.clientX, event.clientY)?.position?.lineNumber);
    };
    const action = monaco.addEditorAction({
      id: 'bytequay.showLineChange', label: 'Show Saved Change at Cursor', contextMenuGroupId: 'navigation',
      run: current => {
        if (current !== editor) return;
        const line = current.getPosition()?.lineNumber;
        const index = decorations.findIndex(id => { const range = model.getDecorationRange(id); return range && line !== undefined && line >= range.startLineNumber && line <= range.endLineNumber; });
        if (index >= 0) show(index, line);
      },
    });
    const palette = MenuRegistry.appendMenuItem(MenuId.CommandPalette, { command: { id: 'bytequay.showLineChange', title: 'Show Saved Change at Cursor' } });
    element.addEventListener('mousedown', clicked, true);
    update();
    const changed = model.onDidChangeContent(update);
    const switched = editor.onDidChangeModel(() => setPreview(null));
    return () => {
      changed.dispose(); switched.dispose(); action.dispose(); palette.dispose(); element.removeEventListener('mousedown', clicked, true);
      if (!model.isDisposed()) model.deltaDecorations(decorations, []);
    };
  }, [editor, uri, file]);

  useLayoutEffect(() => {
    if (!shown || !editor) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || !(event.target instanceof Node) || !editor.getContainerDomNode().contains(event.target)) return;
      event.preventDefault(); event.stopPropagation(); setPreview(null); editor.focus();
    };
    window.addEventListener('keydown', escape, true);
    return () => window.removeEventListener('keydown', escape, true);
  }, [shown, editor]);

  return <ReviewZones runtime={runtime} uri={uri} cards={cards}
    renderCard={() => editor && shown ? <ChangePreview editor={editor} preview={shown} onClose={close} /> : null} />;
}
