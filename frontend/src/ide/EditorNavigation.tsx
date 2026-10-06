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
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { editor as monaco } from 'monaco-editor';
import type { TextEditor } from 'vscode';
import type { ICodeEditor } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
// eslint-disable-next-line import/no-unresolved -- The pinned package exports this path; Vite and tsc resolve it.
import { MouseTargetType } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
// eslint-disable-next-line import/no-unresolved -- The pinned package exports this path; Vite and tsc resolve it.
import { EditorOption } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/config/editorOptions';
import { AlignRight, GitCommitHorizontal, X } from 'lucide-react';
import { codeHostKey, type ReviewCodeFileDto } from '../pulls/code-tests/contracts';
import type { BlameSpan, CodeBlame, IdeContext } from './contracts';
import type { IdeRuntime } from './runtime';
import { blameAt, blameDate, blameLine, overviewLine, uncommitted } from './navigationModel';

type Preview = { line: number; top: number; left: number; width: number; keyboard: boolean };
function previewPosition(editor: ICodeEditor, line: number, y: number, keyboard: boolean): Preview {
  const rect = editor.getDomNode()?.getBoundingClientRect();
  const width = Math.max(200, Math.min(900, (rect?.width ?? 700) - 28));
  return { line, width, left: Math.max(4, (rect?.right ?? window.innerWidth) - width - 24), top: Math.max(4, Math.min(y + 14, window.innerHeight - 270)), keyboard };
}

function CodePreview({ editor, preview, onChange, onClose }: { editor: ICodeEditor; preview: Preview; onChange: (line: number) => void; onClose: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const code = useRef<monaco.IStandaloneCodeEditor | undefined>(undefined);
  const lineCount = editor.getModel()?.getLineCount() ?? 1;
  const jump = () => { editor.setPosition({ lineNumber: preview.line, column: 1 }); editor.revealLineInCenter(preview.line); onClose(); editor.focus(); };
  useLayoutEffect(() => {
    if (!host.current) return;
    const font = editor.getOption(EditorOption.fontInfo);
    const instance = monaco.create(host.current, {
      model: editor.getModel(), readOnly: true, domReadOnly: true, automaticLayout: true,
      fontFamily: font.fontFamily, fontSize: font.fontSize, lineHeight: font.lineHeight,
      minimap: { enabled: false }, lineNumbers: 'on', glyphMargin: false, folding: false,
      scrollBeyondLastLine: false, contextmenu: false, overviewRulerLanes: 0, renderLineHighlight: 'none',
      scrollbar: { vertical: 'hidden', horizontal: 'hidden', handleMouseWheel: false },
      stickyScroll: { enabled: false }, links: false, hover: { enabled: 'off' },
    });
    code.current = instance;
    return () => { code.current = undefined; instance.dispose(); };
  }, [editor]);
  useLayoutEffect(() => { code.current?.revealLineNearTop(preview.line); }, [preview.line]);
  return <section className={`ide-code-preview${preview.keyboard ? '' : ' is-hover'}`} role={preview.keyboard ? 'dialog' : undefined}
    aria-label="Code preview" style={{ top: preview.top, left: preview.left, width: preview.width }} onKeyDown={event => {
      event.stopPropagation();
      if (event.key === 'Escape') { event.preventDefault(); onClose(); editor.focus(); }
      if (event.key === 'Enter') { event.preventDefault(); jump(); }
    }}>
    <header><span>Code preview</span>{preview.keyboard ? <>
      <label>Line <input autoFocus aria-label="Preview line" type="number" min={1} max={lineCount} defaultValue={preview.line}
        onChange={event => onChange(Math.max(1, Math.min(lineCount, Number(event.target.value) || 1)))} /></label>
      <button onClick={jump}>Go to line</button><button aria-label="Close code preview" onClick={() => { onClose(); editor.focus(); }}><X size={14} /></button>
    </> : <span>Line {preview.line} · click the strip to jump</span>}</header>
    <div ref={host} className="ide-preview-code" inert />
    {preview.keyboard && <pre className="ide-preview-accessible" aria-label="Previewed source">{Array.from({ length: Math.min(10, lineCount - preview.line + 1) }, (_, offset) =>
      `${preview.line + offset}: ${editor.getModel()?.getLineContent(preview.line + offset) ?? ''}`).join('\n').slice(0, 10_000)}</pre>}
  </section>;
}

export default function EditorNavigation({ runtime, active, context, file, revision, onHistory }: {
  runtime: IdeRuntime; active: TextEditor | undefined; context: IdeContext; file: ReviewCodeFileDto | null; revision: string | undefined;
  onHistory: (commit: string, path: string) => void;
}) {
  const [enabled, setEnabled] = useState(false);
  const [blame, setBlame] = useState<CodeBlame | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [detail, setDetail] = useState<BlameSpan | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const previewRef = useRef(preview); previewRef.current = preview;
  const native = runtime.editors.getActiveCodeEditor();
  const uri = active?.document.uri.toString();
  const editor = native?.getModel()?.uri.toString() === uri ? native : runtime.editors.listCodeEditors().find(item => item.getModel()?.uri.toString() === uri);
  const matches = !!active && !active.document.isDirty && file?.content?.replace(/^\uFEFF/, '') === active.document.getText();

  // Context is cloned over IPC on each shell update; compare its host identity to keep blame installed.
  useEffect(() => {
    let live = true;
    setBlame(null); setDetail(null); setStatus(null);
    if (!enabled || !file) return;
    if (!matches) { setStatus('Save or reload this file to view blame.'); return; }
    setStatus('Reading blame…');
    void window.ideWorkbench.request('readCodeBlame', context.host, file.path).then(value => {
      if (!live) return;
      const result = value as CodeBlame;
      if (result.contentHash !== file.contentHash || result.revision !== revision) { setStatus('The file changed. Waiting for its saved contents…'); return; }
      setBlame(result); setStatus(result.unavailableReason ?? (result.truncated ? 'Blame is partial for this file.' : null));
    }, cause => { if (live) setStatus(String(cause)); });
    return () => { live = false; };
  }, [enabled, matches, file?.path, file?.contentHash, revision, codeHostKey(context.host)]); // eslint-disable-line react-hooks/exhaustive-deps

  useLayoutEffect(() => {
    if (!editor || !enabled || !matches || !blame?.spans.length) return;
    const { lineNumbers, lineNumbersMinChars } = editor.getRawOptions();
    editor.updateOptions({ lineNumbers: line => blameLine(blame.spans, line), lineNumbersMinChars: 29 });
    const clicked = editor.onMouseDown(event => {
      if (event.target.type !== MouseTargetType.GUTTER_LINE_NUMBERS || !event.target.position) return;
      if (!(event.target.element instanceof Element) || !event.target.element.closest('.ide-blame-line')) return;
      setDetail(blameAt(blame.spans, event.target.position.lineNumber) ?? null);
    });
    return () => { clicked.dispose(); editor.updateOptions({ lineNumbers: lineNumbers ?? 'on', lineNumbersMinChars: lineNumbersMinChars ?? 5 }); };
  }, [editor, enabled, matches, blame]);

  useEffect(() => {
    setPreview(null);
    if (!editor) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const clear = () => { clearTimeout(timer); if (!previewRef.current?.keyboard) setPreview(null); };
    const move = editor.onMouseMove(event => {
      if (previewRef.current?.keyboard) return;
      const rect = editor.getDomNode()?.getBoundingClientRect();
      const model = editor.getModel();
      if (event.event.browserEvent.buttons !== 0) return;
      if (!rect || !model
        || ![MouseTargetType.OVERVIEW_RULER, MouseTargetType.SCROLLBAR].includes(event.target.type)
        || event.event.posx < rect.right - 20 || event.event.posy >= rect.bottom - 14) { clear(); return; }
      const line = overviewLine((event.event.posy - rect.top) / editor.getLayoutInfo().height, editor.getScrollHeight(), model.getLineCount(), line => editor.getTopForLineNumber(line));
      clearTimeout(timer);
      const show = () => setPreview(previewPosition(editor, line, event.event.posy, false));
      if (previewRef.current) show(); else timer = setTimeout(show, 180);
    });
    // Monaco handles scrollbar presses before onMouseDown. Observe the native gesture without taking its drag handling.
    let press: { x: number; y: number; line: number } | null = null;
    const down = (event: PointerEvent) => {
      const current = previewRef.current;
      const rect = editor.getDomNode()?.getBoundingClientRect();
      if (event.button !== 0 || !current || current.keyboard || !rect
        || event.clientX < rect.right - 20 || event.clientX > rect.right || event.clientY < rect.top || event.clientY >= rect.bottom - 14) return;
      press = { x: event.clientX, y: event.clientY, line: current.line };
      clearTimeout(timer); setPreview(null);
    };
    const up = (event: PointerEvent) => {
      const current = press;
      press = null;
      if (!current || event.button !== 0 || Math.hypot(event.clientX - current.x, event.clientY - current.y) > 4) return;
      editor.setPosition({ lineNumber: current.line, column: 1 }); editor.revealLineInCenter(current.line); editor.focus(); clear();
    };
    const cancel = () => { press = null; clear(); };
    window.addEventListener('pointerdown', down, true);
    window.addEventListener('pointerup', up, true);
    window.addEventListener('pointercancel', cancel, true);
    const leave = editor.onMouseLeave(clear);
    return () => {
      clearTimeout(timer); move.dispose(); leave.dispose();
      window.removeEventListener('pointerdown', down, true);
      window.removeEventListener('pointerup', up, true);
      window.removeEventListener('pointercancel', cancel, true);
    };
  }, [editor]);

  return <>
    <button aria-pressed={enabled} disabled={!editor || !file} onClick={() => { setEnabled(value => !value); setDetail(null); }} title="Show Git author and date beside each saved line"><GitCommitHorizontal size={14} /> Blame</button>
    {enabled && <button disabled={!blame?.spans.length || !matches} onClick={() => setDetail(blameAt(blame?.spans ?? [], (active?.selection.active.line ?? 0) + 1) ?? null)}>Line history</button>}
    <button disabled={!editor} onClick={() => { if (editor) setPreview(previewPosition(editor, editor.getPosition()?.lineNumber ?? 1, editor.getDomNode()?.getBoundingClientRect().top ?? 50, true)); }}><AlignRight size={14} /> Code preview</button>
    {enabled && status && <span className="ide-blame-status" role="status">{status}</span>}
    {editor && preview && <CodePreview editor={editor} preview={preview} onChange={line => setPreview(current => current && { ...current, line })} onClose={() => setPreview(null)} />}
    {enabled && matches && detail && <section className="ide-blame-detail" role="dialog" aria-label="Line history" onKeyDown={event => { if (event.key === 'Escape') { setDetail(null); editor?.focus(); } }}>
      <header><strong>{uncommitted(detail) ? 'Uncommitted changes' : detail.author}</strong><button autoFocus aria-label="Close line history" onClick={() => setDetail(null)}><X size={14} /></button></header>
      {!uncommitted(detail) && <><p>{blameDate(detail)} · <code>{detail.commit.slice(0, 12)}</code></p><p>{detail.summary}</p><p>{detail.originalPath}:{detail.originalLine}</p>
        <button onClick={() => { onHistory(detail.commit, detail.originalPath); setDetail(null); }}>Show commit in Git history</button></>}
      {uncommitted(detail) && <p>These saved lines differ from HEAD and do not have a commit yet.</p>}
    </section>}
  </>;
}
