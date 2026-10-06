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
import type { IOverlayWidget } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
import type { LineCard } from '../pulls/code-tests/CodeView';
import type { IdeRuntime } from './runtime';

type Zone = { key: string; id: string; node: HTMLDivElement; container: HTMLDivElement; line: number; resize: ResizeObserver; height: number; widget: IOverlayWidget };

/** React retains the actual cards; Monaco owns only their placement and measured height between code lines. */
export default function ReviewZones({ runtime, uri, cards, renderCard }: {
  runtime: IdeRuntime;
  uri: string;
  cards: LineCard[];
  renderCard?: (key: string) => ReactNode;
}) {
  const zones = useRef(new Map<string, Zone>());
  const [placed, setPlaced] = useState<Zone[]>([]);
  const active = runtime.editors.getActiveCodeEditor();
  const editor = active?.getModel()?.uri.toString() === uri ? active : runtime.editors.listCodeEditors().find(item => item.getModel()?.uri.toString() === uri);

  useLayoutEffect(() => {
    if (!editor) return;
    const attached = zones.current;
    const layout = editor.onDidLayoutChange(info => {
      for (const zone of attached.values()) {
        zone.container.style.left = `${info.contentLeft}px`; zone.container.style.width = `${info.contentWidth}px`;
      }
    });
    return () => {
      layout.dispose();
      editor.changeViewZones(access => {
        for (const zone of attached.values()) { zone.resize.disconnect(); access.removeZone(zone.id); editor.removeOverlayWidget(zone.widget); }
        attached.clear();
      });
    };
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
        const node = existing?.container ?? document.createElement('div');
        node.className = 'ide-review-zone';
        const content = node.firstElementChild as HTMLDivElement | null ?? document.createElement('div');
        if (!content.parentElement) node.appendChild(content);
        // Monaco hides view zones from assistive technology. An empty zone reserves space while a public
        // overlay widget holds the interactive React card in the accessibility tree.
        const widget: IOverlayWidget = { getId: () => `bytequay-review:${card.key}`, getDomNode: () => node, getPosition: () => null };
        editor.addOverlayWidget(widget);
        const spec = {
          afterLineNumber: Math.min(card.line, editor.getModel()?.getLineCount() ?? card.line), heightInPx: existing?.height ?? 80,
          domNode: document.createElement('div'), suppressMouseDown: false,
          onDomNodeTop: (top: number) => {
            const layout = editor.getLayoutInfo();
            node.style.position = 'absolute'; node.style.top = `${top}px`; node.style.left = `${layout.contentLeft}px`; node.style.width = `${layout.contentWidth}px`;
          },
        };
        const id = access.addZone(spec);
        const zone: Zone = { key: card.key, id, node: content, container: node, widget, line: card.line, height: spec.heightInPx, resize: new ResizeObserver(() => {
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
