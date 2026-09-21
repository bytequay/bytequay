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


import React, { useCallback, useEffect, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react';

import { DiscussionFooter } from './DiscussionFooter';
import { renderMarkdownBlocks } from '../markdown';
import type { TaskDiscussionDto } from './contracts';

/** Below this a selection is a stray click rather than a passage; the service refuses it too. */
const MINIMUM_QUOTE = 3;
/** The affordance sits this far below the last selected line. */
const BELOW = 8;
/** Within this of the viewport's bottom it flips above the first selected line instead. */
const EDGE = 44;
/** How far from the bottom right corner the browser's own resize grip takes a press. */
const RESIZE_GRIP = 18;

export type SelectedPassage = { turnId: string; quote: string; occurrence: number };

/** Why a selection cannot be asked about, when it cannot. */
type Refusal = 'overruns' | 'streaming' | 'completed';

type Offer =
  | { kind: 'ask'; left: number; top: number; passage: SelectedPassage }
  | { kind: 'inert'; left: number; top: number; reason: Refusal }
  | { kind: 'compose'; left: number; top: number; passage: SelectedPassage };

const SPEECH = <path d="M2.5 3.6h11v7.2H7l-3.2 2.4v-2.4H2.5z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />;

function Glyph({ size, children }: { size: number; children: React.ReactNode }): React.ReactElement {
  return <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">{children}</svg>;
}

const REFUSALS: Record<Refusal, { text: string; fix?: string }> = {
  overruns: { text: 'Selection runs past the end of this answer', fix: 'Trim to this answer' },
  streaming: { text: 'You can ask once this answer finishes' },
  completed: { text: 'Task completed — no new discussions.' },
};

/**
 * One agent answer: its discussions, the offer to start another, and the passage each is about.
 *
 * <p>The first question is asked inline, at the selection, because it is a reflex written while the
 * eye is still on the sentence; every question after it belongs to the thread and is asked in the
 * panel. A footer hangs under the block holding its passage rather than at the end of the answer.
 */
export function DiscussableAnswer({ turnId, text, discussions, completed = false, streaming = false, focused, onSelect, onOpen, onHover }: {
  turnId: string | null;
  text: string;
  discussions: TaskDiscussionDto[];
  /** The task released its files, so no new discussion can be anchored here. */
  completed?: boolean;
  /** This answer is still being written, so it has nothing stable to anchor to. */
  streaming?: boolean;
  /** The discussion whose passage the reader is pointing at, from the transcript or the panel. */
  focused?: string | null;
  onSelect?: (passage: SelectedPassage, question: string) => void;
  onOpen?: (discussionId: string) => void;
  onHover?: (discussionId: string | null) => void;
}): React.ReactElement {
  const answer = useRef<HTMLDivElement>(null);
  // The offer is positioned against the shell, so its coordinates must be measured there too; the
  // rendered blocks sit inset from it, and measuring those puts every offer out by that inset.
  const shell = useRef<HTMLDivElement>(null);
  const [offer, setOffer] = useState<Offer | null>(null);
  const [draft, setDraft] = useState('');
  // A composer the reader moved or resized keeps where they put it until it closes.
  const [moved, setMoved] = useState<{ left: number; top: number } | null>(null);
  const [sized, setSized] = useState<CSSProperties>({});
  const blocks = renderMarkdownBlocks(text);

  const placed = new Map<number, TaskDiscussionDto[]>();
  const adrift: TaskDiscussionDto[] = [];
  for (const discussion of discussions) {
    const index = blockOf(blocks.map(block => block.text), discussion.quote, discussion.occurrence);
    if (index === null) adrift.push(discussion);
    else placed.set(index, [...placed.get(index) ?? [], discussion]);
  }

  const capture = useCallback(() => {
    setOffer(current => (current?.kind === 'compose' ? current : null));
    const selection = window.getSelection();
    const container = answer.current;
    if (selection === null || container === null || selection.rangeCount === 0 || selection.isCollapsed) return;
    const range = selection.getRangeAt(0);
    const startsHere = container.contains(range.startContainer);
    if (!startsHere && !container.contains(range.endContainer)) return;

    const rects = [...range.getClientRects()];
    if (rects.length === 0 || shell.current === null) return;
    const frame = shell.current.getBoundingClientRect();
    const last = rects[rects.length - 1];
    const first = rects[0];
    const flip = last.bottom + BELOW + 30 > window.innerHeight - EDGE;
    const anchor = flip ? first : last;
    const at = {
      left: Math.min(Math.max(0, anchor.left + anchor.width / 2 - frame.left), frame.width),
      top: flip ? first.top - frame.top - 30 - BELOW : last.bottom - frame.top + BELOW,
    };

    // A discussion belongs to exactly one answer, so a range that leaves it has no anchor.
    if (!container.contains(range.endContainer) || !startsHere) {
      setOffer({ kind: 'inert', ...at, reason: 'overruns' });
      return;
    }
    if (streaming || completed) {
      setOffer({ kind: 'inert', ...at, reason: streaming ? 'streaming' : 'completed' });
      return;
    }
    if (turnId === null || onSelect === undefined) return;
    const quote = selection.toString().replace(/\s+/g, ' ').trim();
    if (quote.length < MINIMUM_QUOTE) return;
    const before = new Range();
    before.setStart(container, 0);
    before.setEnd(range.startContainer, range.startOffset);
    setOffer({ kind: 'ask', ...at, passage: { turnId, quote, occurrence: occurrencesIn(before.toString(), quote) + 1 } });
  }, [completed, onSelect, streaming, turnId]);

  useEffect(() => {
    const dismiss = (event: KeyboardEvent): void => {
      // Alt+Enter is the keyboard route into the composer, printed once on the pill itself.
      if (event.key === 'Escape') place(null);
      else if (event.key === 'Enter' && event.altKey) {
        setOffer(current => (current?.kind === 'ask' ? { ...current, kind: 'compose' } : current));
      }
    };
    // The pointer is released wherever the drag ended. Selecting backwards ends at the passage's
    // start, which is commonly outside this answer, so an element-scoped listener never sees it.
    const released = (): void => capture();
    document.addEventListener('keydown', dismiss);
    document.addEventListener('mouseup', released);
    return () => {
      document.removeEventListener('keydown', dismiss);
      document.removeEventListener('mouseup', released);
    };
  }, [capture]);

  const place = (next: Offer | null): void => {
    setMoved(null);
    setSized({});
    setOffer(next);
  };

  /** Drags the composer by its quote band, the way the hover card drags by its head. */
  const startDrag = (event: ReactMouseEvent<HTMLElement>): void => {
    const box = event.currentTarget.closest('.agt-disc-pop')?.getBoundingClientRect();
    const frame = shell.current?.getBoundingClientRect();
    if (box === undefined || frame === undefined) return;
    const grabX = event.clientX - box.left;
    const grabY = event.clientY - box.top;
    const move = (next: globalThis.MouseEvent): void => setMoved({
      left: next.clientX - grabX - frame.left,
      top: next.clientY - grabY - frame.top,
    });
    const stop = (): void => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.userSelect = '';
    };
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };

  /** The browser's own grip writes width and height from here, so the placed height has to give way. */
  const startResize = (event: ReactMouseEvent<HTMLElement>): void => {
    const box = event.currentTarget.getBoundingClientRect();
    if (box.right - event.clientX > RESIZE_GRIP || box.bottom - event.clientY > RESIZE_GRIP) return;
    setSized({ width: box.width, height: box.height });
  };

  const ask = (passage: SelectedPassage): void => {
    const question = draft.trim();
    if (question === '') return;
    onSelect?.(passage, question);
    setDraft('');
    place(null);
  };

  return (
    <div className="agt-answer agt-disc" ref={shell}>
      <div className="agt-md" ref={answer}>
        {blocks.map((block, index) => (
          <React.Fragment key={index}>
            {block.node}
            <DiscussionFooter discussions={placed.get(index) ?? []} siblings={discussions} focused={focused} onOpen={onOpen} onHover={onHover} />
          </React.Fragment>
        ))}
      </div>
      <DiscussionFooter discussions={adrift} siblings={discussions} focused={focused} unanchored onOpen={onOpen} onHover={onHover} />
      {offer !== null && (
        <div
          className={`agt-disc-anchor${moved === null ? '' : ' is-moved'}`}
          style={{ left: moved?.left ?? offer.left, top: moved?.top ?? offer.top }}
        >
          {offer.kind === 'ask' && (
            <button className="agt-disc-pill" type="button" onMouseDown={event => event.preventDefault()}
              onClick={() => place({ ...offer, kind: 'compose' })}>
              <Glyph size={14}>{SPEECH}</Glyph>
              Ask about this
              <span className="agt-disc-pill__key">⌥↩</span>
            </button>
          )}
          {offer.kind === 'inert' && (
            <div className="agt-disc-inert">
              <span className="agt-disc-inert__glyph">
                <Glyph size={14}>
                  <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.4" />
                  <path d="M8 5v3.6M8 10.8v.4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                </Glyph>
              </span>
              <span>{REFUSALS[offer.reason].text}</span>
              {REFUSALS[offer.reason].fix !== undefined && (
                <button className="agt-disc-inert__fix" type="button" onClick={() => trim(answer.current, () => capture())}>
                  {REFUSALS[offer.reason].fix}
                </button>
              )}
            </div>
          )}
          {offer.kind === 'compose' && (
            <div className="agt-disc-pop" style={sized} onMouseDown={startResize}>
              <div className="agt-disc-pop__quote" onMouseDown={startDrag} title="Drag to move">
                <span className="agt-disc-pop__rail" />
                <span className="agt-disc-pop__text">{offer.passage.quote}</span>
              </div>
              <div className="agt-disc-pop__body">
                <textarea
                  rows={3}
                  autoFocus
                  aria-label="Ask about this passage"
                  placeholder="Ask about this passage…"
                  value={draft}
                  onChange={event => setDraft(event.target.value)}
                  onKeyDown={event => {
                    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) ask(offer.passage);
                  }}
                />
                <div className="agt-disc-pop__actions">
                  <span />
                  <button className="agt-disc-pop__cancel" type="button" onClick={() => { setDraft(''); place(null); }}>Cancel</button>
                  <button className="agt-disc-pop__ask" type="button" disabled={draft.trim() === ''} onClick={() => ask(offer.passage)}>Ask</button>
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Drops the part of the selection that left the answer, so the offer can be made for what is left. */
function trim(container: HTMLElement | null, again: () => void): void {
  const selection = window.getSelection();
  if (container === null || selection === null || selection.rangeCount === 0) return;
  const range = selection.getRangeAt(0).cloneRange();
  if (!container.contains(range.startContainer)) range.setStart(container, 0);
  if (!container.contains(range.endContainer)) range.setEnd(container, container.childNodes.length);
  selection.removeAllRanges();
  selection.addRange(range);
  again();
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
