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

export type HistoryScrollAnchor = {
  id: string;
  top: number;
  text: string;
  offset: number;
  textTop: number;
};

/** Captures visible passages, since prepending deltas can grow a message without moving its top. */
export function captureHistoryAnchors(container: HTMLElement): HistoryScrollAnchor[] {
  const viewport = container.getBoundingClientRect();
  return [...container.querySelectorAll<HTMLElement>('[data-history-anchor]')].flatMap((element) => {
    const bounds = element.getBoundingClientRect();
    if (bounds.bottom <= viewport.top + 20 || bounds.top >= viewport.bottom || element.querySelector('[data-history-anchor]') !== null) return [];
    return [capturePassage(container, element)];
  });
}

function capturePassage(container: HTMLElement, element: HTMLElement): HistoryScrollAnchor {
  const viewport = container.getBoundingClientRect();
  const bounds = element.getBoundingClientRect();
  const anchor: HistoryScrollAnchor = { id: element.dataset.historyAnchor ?? '', top: bounds.top, text: '', offset: 0, textTop: 0 };
  const range = document.caretRangeFromPoint(Math.max(viewport.left + 12, bounds.left + 12), Math.max(viewport.top + 24, bounds.top + 2));
  if (range === null || !element.contains(range.startContainer) || range.startContainer.nodeType !== Node.TEXT_NODE) return anchor;
  const prefix = document.createRange();
  prefix.selectNodeContents(element);
  prefix.setEnd(range.startContainer, range.startOffset);
  const offset = prefix.toString().length;
  const text = element.textContent ?? '';
  const start = Math.max(0, offset - 100);
  anchor.text = text.slice(start, offset + 160);
  anchor.offset = offset - start;
  range.setEnd(range.startContainer, Math.min(range.startOffset + 1, range.startContainer.textContent?.length ?? 0));
  anchor.textTop = range.getBoundingClientRect().top;
  return anchor;
}

/** Finds the same visible text after both a prepend and eviction at the opposite window edge. */
export function restoreHistoryAnchor(container: HTMLElement, anchors: HistoryScrollAnchor[]): void {
  const elements = new Map([...container.querySelectorAll<HTMLElement>('[data-history-anchor]')]
    .map((element) => [element.dataset.historyAnchor, element]));
  // A page edge can replace the first visible activity group; use the next surviving passage.
  const anchor = anchors.find((candidate) => elements.has(candidate.id));
  if (anchor === undefined) return;
  const element = elements.get(anchor.id);
  if (element === undefined) return;
  const offset = anchor.text === '' ? -1 : (element.textContent ?? '').indexOf(anchor.text);
  if (offset >= 0) {
    let remaining = offset + anchor.offset;
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
      const length = node.textContent?.length ?? 0;
      if (remaining >= length) { remaining -= length; continue; }
      const range = document.createRange();
      range.setStart(node, remaining);
      range.setEnd(node, Math.min(remaining + 1, length));
      container.scrollTop += range.getBoundingClientRect().top - anchor.textTop;
      return;
    }
  }
  container.scrollTop += element.getBoundingClientRect().top - anchor.top;
}
