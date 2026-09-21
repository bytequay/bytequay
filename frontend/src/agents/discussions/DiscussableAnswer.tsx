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


import React, { useCallback, useEffect, useRef, useState } from 'react';

import { DiscussionFooter } from './DiscussionFooter';
import { renderMarkdownBlocks } from '../markdown';
import type { TaskDiscussionDto } from './contracts';

/** Below this a selection is a stray click rather than a passage; the service refuses it too. */
const MINIMUM_QUOTE = 3;

/** How far above its passage the pill sits, when there is room for it there. */
const PILL_HEIGHT = 34;

export type SelectedPassage = { turnId: string; quote: string; occurrence: number };

/**
 * One agent answer, with its discussions hung under the blocks they quote.
 *
 * <p>A footer sits under the block containing its passage rather than at the end of the answer, so
 * three quotes in three paragraphs read as three marks in three places. The quote is stored as the
 * reader saw it, so a block is found by plain substring search over its text; the occurrence index
 * picks which appearance when the same text repeats. A passage that spans two blocks belongs to no
 * single one, so its row falls to the end of the answer and says so.
 */
export function DiscussableAnswer({ turnId, text, discussions, onSelect, onOpen }: {
  turnId: string | null;
  text: string;
  discussions: TaskDiscussionDto[];
  onSelect?: (passage: SelectedPassage) => void;
  onOpen?: (discussionId: string) => void;
}): React.ReactElement {
  const answer = useRef<HTMLDivElement>(null);
  const [pill, setPill] = useState<{ top: number; left: number; passage: SelectedPassage } | null>(null);
  const blocks = renderMarkdownBlocks(text);

  const placed = new Map<number, TaskDiscussionDto[]>();
  const unanchored: TaskDiscussionDto[] = [];
  for (const discussion of discussions) {
    const index = blockOf(blocks.map(block => block.text), discussion.quote, discussion.occurrence);
    if (index === null) unanchored.push(discussion);
    else placed.set(index, [...placed.get(index) ?? [], discussion]);
  }

  const capture = useCallback(() => {
    if (turnId === null || onSelect === undefined) return;
    const selection = window.getSelection();
    const container = answer.current;
    if (selection === null || container === null || selection.rangeCount === 0 || selection.isCollapsed) {
      setPill(null);
      return;
    }
    const range = selection.getRangeAt(0);
    // A selection leaving this answer anchors to no turn, so it is not offered at all.
    if (!container.contains(range.startContainer) || !container.contains(range.endContainer)) {
      setPill(null);
      return;
    }
    const quote = selection.toString().replace(/\s+/g, ' ').trim();
    if (quote.length < MINIMUM_QUOTE) {
      setPill(null);
      return;
    }
    const before = new Range();
    before.setStart(container, 0);
    before.setEnd(range.startContainer, range.startOffset);
    const occurrence = occurrencesIn(before.toString(), quote) + 1;
    const bounds = range.getBoundingClientRect();
    const frame = container.getBoundingClientRect();
    const above = bounds.top - frame.top - PILL_HEIGHT;
    setPill({
      top: above < 0 ? bounds.bottom - frame.top + 6 : above,
      left: Math.max(0, bounds.left - frame.left),
      passage: { turnId, quote, occurrence },
    });
  }, [onSelect, turnId]);

  useEffect(() => {
    const dismiss = (event: KeyboardEvent): void => { if (event.key === 'Escape') setPill(null); };
    // The pointer is released wherever the drag ended. Selecting backwards ends at the passage's
    // start, which is commonly outside this answer, so an element-scoped listener never sees it.
    // What decides the offer is where the selection is, which capture already checks.
    const released = (): void => capture();
    document.addEventListener('keydown', dismiss);
    document.addEventListener('mouseup', released);
    return () => {
      document.removeEventListener('keydown', dismiss);
      document.removeEventListener('mouseup', released);
    };
  }, [capture]);

  return (
    <div className="agt-answer">
      <div className="agt-md" ref={answer}>
        {blocks.map((block, index) => (
          <React.Fragment key={index}>
            {block.node}
            <DiscussionFooter discussions={placed.get(index) ?? []} siblings={discussions} onOpen={onOpen} />
          </React.Fragment>
        ))}
      </div>
      <DiscussionFooter discussions={unanchored} siblings={discussions} unanchored onOpen={onOpen} />
      {pill !== null && (
        <button
          className="agt-disc-pill"
          type="button"
          style={{ top: pill.top, left: pill.left }}
          onMouseDown={event => event.preventDefault()}
          onClick={() => { onSelect?.(pill.passage); setPill(null); }}
        >Ask about this</button>
      )}
    </div>
  );
}

/** The block holding the wanted appearance of a quote, or null when no single block holds it. */
export function blockOf(blocks: string[], quote: string, occurrence: number): number | null {
  const wanted = quote.replace(/\s+/g, ' ').trim();
  if (wanted === '') return null;
  let seen = 0;
  for (let index = 0; index < blocks.length; index++) {
    const found = occurrencesIn(blocks[index], wanted);
    if (seen + found >= occurrence) return index;
    seen += found;
  }
  // Fewer appearances than the index names: the answer or the renderer moved on. Anchor to the
  // first block that holds the text at all rather than dropping the thread.
  for (let index = 0; index < blocks.length; index++) {
    if (occurrencesIn(blocks[index], wanted) > 0) return index;
  }
  return null;
}

function occurrencesIn(haystack: string, needle: string): number {
  const text = haystack.replace(/\s+/g, ' ');
  let count = 0;
  let at = text.indexOf(needle);
  while (at !== -1) {
    count++;
    at = text.indexOf(needle, at + needle.length);
  }
  return count;
}
