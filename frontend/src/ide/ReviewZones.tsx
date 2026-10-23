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
import { useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ReactNode } from 'react';
// eslint-disable-next-line import/no-unresolved -- Native mouse targets from the pinned editor runtime.
import { MouseTargetType, type IOverlayWidget } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
import type { CommentGesture, LineCard } from '../pulls/code-tests/useCodeComments';
import type { IdeRuntime } from './runtime';

type Zone = { key: string; id: string; node: HTMLDivElement; line: number; resize: ResizeObserver; height: number; widget: IOverlayWidget; layout: (top?: number) => void };

/** React retains the actual cards; Monaco owns only their placement and measured height between code lines. */
export default function ReviewZones({ runtime, uri, cards, renderCard, onComment, commentRange }: {
  runtime: IdeRuntime;
  uri: string;
  cards: LineCard[];
  renderCard?: (key: string) => ReactNode;
  onComment?: (gesture: CommentGesture) => void;
  commentRange?: { start: number; end: number } | null;
}) {
  const zones = useRef(new Map<string, Zone>());
  const [placed, setPlaced] = useState<Zone[]>([]);
  const active = runtime.editors.getActiveCodeEditor();
  const editor = active?.getModel()?.uri.toString() === uri ? active : runtime.editors.listCodeEditors().find(item => item.getModel()?.uri.toString() === uri);
  const comment = useRef(onComment); comment.current = onComment;
  const canComment = onComment !== undefined;

  useLayoutEffect(() => {
    if (!editor || !canComment) return;
    const element = editor.getContainerDomNode();
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'pull-diff-code__add-comment ide-add-comment'; button.textContent = '+';
    button.style.display = 'none';
    let hoveredLine: number | null = null;
    let position = { top: 0, left: 0 };
    const widget: IOverlayWidget = { getId: () => 'bytequay-add-comment', getDomNode: () => button, getPosition: () => ({ preference: position }) };
    editor.addOverlayWidget(widget);
    const hide = () => { hoveredLine = null; button.style.display = 'none'; };
    let press: { x: number; y: number; dragging: boolean } | null = null;
    const hover = (event: MouseEvent) => {
      if (press || !(event.target instanceof Node) || !element.contains(event.target)) { hide(); return; }
      if (button.contains(event.target as Node)) return;
      const target = editor.getTargetAtClientPoint(event.clientX, event.clientY);
      if (!target?.position || ![MouseTargetType.GUTTER_LINE_NUMBERS, MouseTargetType.CONTENT_TEXT, MouseTargetType.CONTENT_EMPTY].includes(target.type)
        || target.element?.closest('.ide-blame-line')) { hide(); return; }
      const line = editor.getScrolledVisiblePosition(target.position);
      if (!line) { hide(); return; }
      hoveredLine = target.position.lineNumber;
      button.title = `Add a comment on line ${hoveredLine}`; button.setAttribute('aria-label', button.title);
      const layout = editor.getLayoutInfo();
      position = { top: line.top + (line.height - 24) / 2, left: layout.lineNumbersLeft + layout.lineNumbersWidth - 24 };
      button.style.display = 'grid'; editor.layoutOverlayWidget(widget);
    };
    const down = (event: MouseEvent) => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey) return;
      const target = editor.getTargetAtClientPoint(event.clientX, event.clientY);
      const line = button.contains(event.target as Node) ? hoveredLine
        : target?.type === MouseTargetType.GUTTER_LINE_NUMBERS && !target.element?.closest('.ide-blame-line') ? target.position?.lineNumber : null;
      if (line == null) return;
      // Own line-number gestures before Monaco selects text; blame, folding and breakpoint controls keep theirs.
      event.preventDefault(); event.stopPropagation();
      press = { x: event.clientX, y: event.clientY, dragging: false };
      hide();
      comment.current?.({ type: 'start', line, extend: event.shiftKey });
    };
    const move = (event: MouseEvent) => {
      // Removing an earlier composer moves the lines underneath a stationary pointer.
      if (!press || (!press.dragging && Math.hypot(event.clientX - press.x, event.clientY - press.y) <= 3)) return;
      press.dragging = true;
      const target = editor.getTargetAtClientPoint(event.clientX, event.clientY);
      if (target?.position) comment.current?.({ type: 'drag', line: target.position.lineNumber });
    };
    const finish = () => { hide(); if (press) { press = null; comment.current?.({ type: 'finish' }); } };
    const cancel = (event: KeyboardEvent) => {
      if (!press || event.key !== 'Escape') return;
      event.preventDefault(); event.stopPropagation(); press = null; comment.current?.({ type: 'cancel' });
    };
    button.onclick = event => {
      if (event.detail !== 0 || hoveredLine === null) return;
      comment.current?.({ type: 'start', line: hoveredLine, extend: event.shiftKey }); comment.current?.({ type: 'finish' });
    };
    const scrolled = editor.onDidScrollChange(hide);
    const resized = editor.onDidLayoutChange(hide);
    const changed = editor.onDidChangeModelContent(hide);
    const zonesChanged = editor.onDidChangeViewZones(hide);
    element.classList.add('ide-commentable');
    element.addEventListener('mousedown', down, true);
    // Capture moves before native overlays stop propagation, including moves outside the editor.
    window.addEventListener('mousemove', hover, true);
    element.addEventListener('mouseleave', hide);
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', finish);
    window.addEventListener('blur', finish);
    window.addEventListener('keydown', cancel, true);
    return () => {
      finish(); element.classList.remove('ide-commentable');
      scrolled.dispose(); resized.dispose(); changed.dispose(); zonesChanged.dispose(); editor.removeOverlayWidget(widget);
      element.removeEventListener('mousedown', down, true);
      window.removeEventListener('mousemove', hover, true);
      element.removeEventListener('mouseleave', hide);
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', finish);
      window.removeEventListener('blur', finish);
      window.removeEventListener('keydown', cancel, true);
    };
  }, [editor, uri, canComment]);

  useLayoutEffect(() => {
    if (!editor || !commentRange) return;
    const highlight = editor.createDecorationsCollection([{
      range: { startLineNumber: commentRange.start, endLineNumber: commentRange.end, startColumn: 1, endColumn: 1 },
      options: { isWholeLine: true, className: 'ide-comment-selection' },
    }]);
    return () => highlight.clear();
  }, [editor, uri, commentRange?.start, commentRange?.end]); // eslint-disable-line react-hooks/exhaustive-deps

  useLayoutEffect(() => {
    if (!editor) return;
    const attached = zones.current;
    // Monaco drops view zones with a detached model but keeps and re-attaches overlay widgets, and skips
    // changeViewZones while no model is set, so cards are removed outside it and rebuilt for the next model.
    const detach = () => {
      editor.changeViewZones(access => { for (const zone of attached.values()) access.removeZone(zone.id); });
      for (const zone of attached.values()) { zone.resize.disconnect(); editor.removeOverlayWidget(zone.widget); }
      attached.clear();
    };
    const layout = editor.onDidLayoutChange(() => { for (const zone of attached.values()) zone.layout(); });
    const model = editor.onDidChangeModel(() => { detach(); setPlaced([]); });
    return () => { layout.dispose(); model.dispose(); detach(); };
  }, [editor, uri]);

  useLayoutEffect(() => {
    if (!editor) return;
    let changed = false;
    const wanted = new Set(cards.map(card => card.key));
    editor.changeViewZones(access => {
      for (const [key, zone] of zones.current) {
        if (wanted.has(key)) continue;
        zone.resize.disconnect(); access.removeZone(zone.id); editor.removeOverlayWidget(zone.widget); zones.current.delete(key); changed = true;
      }
      for (const card of cards) {
        const existing = zones.current.get(card.key);
        if (existing && existing.line === card.line) continue;
        if (existing) { existing.resize.disconnect(); access.removeZone(existing.id); editor.removeOverlayWidget(existing.widget); }
        const node = document.createElement('div');
        node.className = 'ide-review-zone';
        // Typing in a private card must not dispatch the editor's global shortcuts.
        node.onkeydown = event => { if (event.target instanceof Element && event.target.closest('input, textarea, [contenteditable]')) event.stopPropagation(); };
        const content = existing?.node ?? document.createElement('div');
        content.className = 'ct-inline-card'; node.appendChild(content);
        // Monaco hides view zones from assistive technology. An empty zone reserves space while a public
        // overlay widget holds the interactive React card in the accessibility tree.
        let position = { top: -1e6, left: editor.getLayoutInfo().contentLeft };
        const widget: IOverlayWidget = { getId: () => `bytequay-review:${card.key}`, getDomNode: () => node, getPosition: () => ({ preference: position }) };
        editor.addOverlayWidget(widget);
        const layout = (top = position.top) => {
          const info = editor.getLayoutInfo();
          node.style.width = `${info.contentWidth}px`;
          node.style.visibility = top < -node.offsetHeight || top >= info.height ? 'hidden' : 'visible';
          if (position.top === top && position.left === info.contentLeft) return;
          // Let Monaco position the overlay; its renderer can clear a manually assigned top during layout.
          position = { top, left: info.contentLeft }; editor.layoutOverlayWidget(widget);
        };
        layout();
        const spec = {
          afterLineNumber: Math.min(card.line, editor.getModel()?.getLineCount() ?? card.line), heightInPx: existing?.height ?? 80,
          domNode: document.createElement('div'), suppressMouseDown: false,
          onDomNodeTop: layout,
        };
        const id = access.addZone(spec);
        const zone: Zone = { key: card.key, id, node: content, widget, layout, line: card.line, height: spec.heightInPx, resize: new ResizeObserver(() => {
          const height = Math.ceil(content.getBoundingClientRect().height) + 12;
          if (height === zone.height || height < 12) return;
          zone.height = height; spec.heightInPx = height;
          editor.changeViewZones(update => update.layoutZone(id));
        }) };
        zone.resize.observe(content);
        zones.current.set(card.key, zone); changed = true;
      }
    });
    if (changed || placed.length !== zones.current.size) setPlaced([...zones.current.values()]);
  }, [editor, cards, placed.length]);

  return <>{placed.map(zone => createPortal(renderCard?.(zone.key), zone.node, zone.key))}</>;
}
