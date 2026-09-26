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


import React, { useCallback, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react';

import { DiscussionFooter } from './DiscussionFooter';
import { remoteErrorMessage } from '../../pulls/PullOverview';
import { renderMarkdownBlocks } from '../markdown';
import { ANSWERING_ELSEWHERE, type TaskDiscussionDto } from './contracts';

/** Below this a selection is a stray click rather than a passage; the service refuses it too. */
const MINIMUM_QUOTE = 3;
/** The affordance sits this far below the last selected line. */
const BELOW = 8;
/** How far from the bottom right corner the browser's own resize grip takes a press. */
const RESIZE_GRIP = 18;

export type SelectedPassage = { turnId: string; quote: string; occurrence: number };

/** Why a selection cannot be asked about, when it cannot. */
type Refusal = 'overruns' | 'streaming' | 'completed';

type Offer = { left: number; top: number; above: number } & (
  | { kind: 'ask'; passage: SelectedPassage }
  | { kind: 'inert'; reason: Refusal }
  | { kind: 'compose'; passage: SelectedPassage });

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
export function DiscussableAnswer({ turnId, text, repository, discussions, completed = false, streaming = false, busy = false, focused, onSelect, onOpen, onHover }: {
  turnId: string | null;
  text: string;
  /** `owner/repo` a bare `#1234` in the answer refers to. */
  repository?: string | null;
  discussions: TaskDiscussionDto[];
  /** The task released its files, so no new discussion can be anchored here. */
  completed?: boolean;
  /** This answer is still being written, so it has nothing stable to anchor to. */
  streaming?: boolean;
  /** Another discussion on the task is being answered; the backend takes one question at a time. */
  busy?: boolean;
  /** The discussion whose passage the reader is pointing at, from the transcript or the panel. */
  focused?: string | null;
  /** Settles once the question is recorded; a rejection keeps the composer and its draft open. */
  onSelect?: (passage: SelectedPassage, question: string) => Promise<unknown>;
  onOpen?: (discussionId: string) => void;
  onHover?: (discussionId: string | null) => void;
}): React.ReactElement {
  const answer = useRef<HTMLDivElement>(null);
  // The offer is positioned against the shell, so its coordinates must be measured there too; the
  // rendered blocks sit inset from it, and measuring those puts every offer out by that inset.
  const shell = useRef<HTMLDivElement>(null);
  const anchor = useRef<HTMLDivElement>(null);
  const [offer, setOffer] = useState<Offer | null>(null);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  // A composer the reader moved or resized keeps where they put it until it closes.
  const [moved, setMoved] = useState<{ left: number; top: number } | null>(null);
  const [sized, setSized] = useState<CSSProperties>({});
  const blocks = renderMarkdownBlocks(text, repository);

  const placed = new Map<number, { discussion: TaskDiscussionDto; nth: number }[]>();
  const adrift: TaskDiscussionDto[] = [];
  for (const discussion of discussions) {
    const at = placeOf(blocks.map(block => block.text), discussion.quote, discussion.occurrence);
    if (at === null) adrift.push(discussion);
    else placed.set(at.index, [...placed.get(at.index) ?? [], { discussion, nth: at.nth }]);
  }

  const capture = useCallback(() => {
    if (offer?.kind === 'compose') return;
    setOffer(null);
    const selection = window.getSelection();
    const container = answer.current;
    if (selection === null || container === null || selection.rangeCount === 0 || selection.isCollapsed) return;
    const range = selection.getRangeAt(0).cloneRange();
    if (!range.intersectsNode(container)) return;

    // Chromium can place the endpoint after the answer (or before the next node's text)
    // when selecting its last sentence. Only actual text outside the answer is an overrun.
    let overruns = false;
    if (!container.contains(range.startContainer)) {
      const outside = range.cloneRange();
      outside.setEnd(container, 0);
      overruns = outside.toString().trim() !== '';
      range.setStart(container, 0);
    }
    if (!container.contains(range.endContainer)) {
      const outside = range.cloneRange();
      outside.setStart(container, container.childNodes.length);
      overruns ||= outside.toString().trim() !== '';
      range.setEnd(container, container.childNodes.length);
    }
    if (range.toString().trim() === '') return;

    const rects = [...range.getClientRects()];
    if (rects.length === 0 || shell.current === null) return;
    const frame = shell.current.getBoundingClientRect();
    const last = rects[rects.length - 1];
    const first = rects[0];
    const at = {
      left: last.left + last.width / 2 - frame.left,
      top: last.bottom - frame.top + BELOW,
      above: first.top - frame.top - BELOW,
    };

    // A discussion belongs to exactly one answer, so a range that leaves it has no anchor.
    if (overruns) {
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
  }, [completed, offer?.kind, onSelect, streaming, turnId]);

  useLayoutEffect(() => {
    const popup = anchor.current;
    const container = shell.current;
    if (offer === null || popup === null || container === null) return;
    const position = (): void => {
      const frame = container.getBoundingClientRect();
      const viewport = container.closest('.agt-transcript')?.getBoundingClientRect();
      const box = popup.getBoundingClientRect();
      const left = Math.max(0, -frame.left);
      const right = Math.min(frame.width, window.innerWidth - frame.left);
      const top = Math.max(0, viewport?.top ?? 0) - frame.top + BELOW;
      const bottom = Math.min(window.innerHeight, viewport?.bottom ?? window.innerHeight) - frame.top - BELOW;
      const wantedTop = moved?.top ?? (offer.top + box.height <= bottom ? offer.top : offer.above - box.height);
      // Clamp the whole box, including the wider composer, inside the visible reading pane.
      popup.style.left = `${Math.max(left, Math.min(moved?.left ?? offer.left - box.width / 2, right - box.width))}px`;
      popup.style.top = `${Math.max(top, Math.min(wantedTop, bottom - box.height))}px`;
    };
    position();
    const observer = new ResizeObserver(position);
    observer.observe(container);
    observer.observe(popup);
    window.addEventListener('resize', position);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', position);
    };
  }, [offer, moved]);

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
    const released = (event: MouseEvent): void => {
      // A live range ending after this answer also encloses the newly rendered popup.
      // Its own buttons must act on the captured passage without selecting their labels.
      if (event.target instanceof Node && anchor.current?.contains(event.target)) return;
      capture();
    };
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
    setRefusal(null);
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

  const ask = async (passage: SelectedPassage): Promise<void> => {
    const question = draft.trim();
    if (question === '' || busy || sending || onSelect === undefined) return;
    setSending(true);
    setRefusal(null);
    try {
      await onSelect(passage, question);
      setDraft('');
      place(null);
    }
    catch (failure) {
      setRefusal(remoteErrorMessage(failure));
    }
    finally {
      setSending(false);
    }
  };

  return (
    <div className="agt-answer agt-disc" ref={shell}>
      <div className="agt-md" ref={answer}>
        {blocks.map((block, index) => {
          const here = placed.get(index) ?? [];
          // Each thread tints its own passage, so a block holding two is marked twice over.
          const marked = here.reduce((node, { discussion, nth }) => markPassage(node, discussion.quote, nth, body => (
            <button
              type="button"
              className={`agt-disc-mark${focused === discussion.id ? ' is-anchored' : ''}`}
              data-discussion={discussion.id}
              title="Show this discussion"
              onClick={() => onOpen?.(discussion.id)}
              onMouseEnter={() => onHover?.(discussion.id)}
              onMouseLeave={() => onHover?.(null)}
            >{body}</button>
          )), block.node);
          return (
            <React.Fragment key={index}>
              {marked}
              <DiscussionFooter discussions={here.map(item => item.discussion)} siblings={discussions} focused={focused} onOpen={onOpen} onHover={onHover} />
            </React.Fragment>
          );
        })}
      </div>
      <DiscussionFooter discussions={adrift} siblings={discussions} focused={focused} unanchored onOpen={onOpen} onHover={onHover} />
      {offer !== null && (
        <div
          className="agt-disc-anchor"
          ref={anchor}
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
                <button className="agt-disc-inert__fix" type="button" onMouseDown={event => event.preventDefault()}
                  onClick={() => trim(answer.current, () => capture())}>
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
                    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void ask(offer.passage);
                  }}
                />
                <div className="agt-disc-pop__actions">
                  <span className="agt-disc-pop__refusal" role="alert">{refusal}</span>
                  <button className="agt-disc-pop__cancel" type="button" onClick={() => { setDraft(''); place(null); }}>Cancel</button>
                  <button className="agt-disc-pop__ask" type="button" disabled={draft.trim() === '' || busy || sending}
                    title={busy ? ANSWERING_ELSEWHERE : undefined} onClick={() => { void ask(offer.passage); }}>Ask</button>
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Brings the passage a discussion is about into the transcript, at the top of it.
 *
 * <p>The mark rather than its turn: a turn runs for several screens, so scrolling to the turn
 * routinely leaves the passage the thread is about off screen. The answer stands in when the
 * passage has drifted out of it, and the turn when the answer has not rendered at all.
 */
export function revealPassage(target: { turnId: string; discussionId?: string }): void {
  const mark = target.discussionId === undefined ? null
    : document.querySelector<HTMLElement>(`.agt-disc-mark[data-discussion="${CSS.escape(target.discussionId)}"]`);
  const turn = document.querySelector<HTMLElement>(`.agt-turn[data-history-anchor="${CSS.escape(target.turnId)}"]`);
  (mark ?? turn?.querySelector<HTMLElement>('.agt-answer') ?? turn)?.scrollIntoView({ block: 'start' });
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

const FENCE = /^(`{3,}|~{3,})/;
const LEADING_MARKERS = /^(?:(?:#{1,6}|[>*+-]|\d+[.)])\s+)+/;
const LINK = /\[([^\]]+)\]\([^)]*\)/g;
const INLINE_MARKS = /[`*_~]/g;

/**
 * The renderer's twin of the backend's DiscussionQuotes.normalize: markdown source and the text a
 * reader selects reduce to the same string. A block is matched by its source, so a quote crossing
 * inline code or a link finds nothing until both sides lose their marks.
 */
function normalize(text: string): string {
  return text.split(/\r\n|\r|\n/)
    .map(line => line.trim())
    .filter(line => !FENCE.test(line))
    .map(line => line.replace(LEADING_MARKERS, '').replace(LINK, '$1').replace(INLINE_MARKS, ''))
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * The block holding the wanted appearance of a quote, and which appearance inside that block it is.
 * The occurrence is counted over the whole answer, but a mark is placed within one block.
 */
export function placeOf(blocks: string[], quote: string, occurrence: number): { index: number; nth: number } | null {
  const index = blockOf(blocks, quote, occurrence);
  if (index === null) return null;
  let before = 0;
  for (let earlier = 0; earlier < index; earlier++) before += occurrencesIn(blocks[earlier], quote);
  return { index, nth: Math.max(1, occurrence - before) };
}

/** The plain text a rendered node reads as, which is what the reader dragged over. */
function nodeText(node: React.ReactNode): string {
  if (typeof node === 'string') return node;
  if (typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(nodeText).join('');
  if (React.isValidElement(node)) return nodeText((node.props as { children?: React.ReactNode }).children);
  return '';
}

/**
 * Where an appearance of a quote sits in rendered text, as offsets into that text. The reader's
 * selection collapsed its whitespace, so the search does too and then maps back.
 */
function spanOf(text: string, quote: string, nth: number): [number, number] | null {
  const wanted = quote.replace(/\s+/g, ' ').trim();
  if (wanted === '') return null;
  const origin: number[] = [];
  let flat = '';
  for (let index = 0; index < text.length; index++) {
    if (/\s/.test(text[index])) {
      if (flat === '' || flat.endsWith(' ')) continue;
      flat += ' ';
    }
    else flat += text[index];
    origin.push(index);
  }
  let at = flat.indexOf(wanted);
  for (let seen = 1; at !== -1 && seen < nth; seen++) at = flat.indexOf(wanted, at + wanted.length);
  if (at === -1) return null;
  return [origin[at], origin[at + wanted.length - 1] + 1];
}

/** Rebuilds a rendered block with the text between two offsets wrapped, splitting inline marks. */
function wrapSpan(node: React.ReactNode, start: number, end: number, at: number, wrap: (body: React.ReactNode) => React.ReactNode): [React.ReactNode, number] {
  if (typeof node === 'string' || typeof node === 'number') {
    const text = String(node);
    const stop = at + text.length;
    if (stop <= start || at >= end) return [node, stop];
    const from = Math.max(0, start - at);
    const to = Math.min(text.length, end - at);
    return [<React.Fragment key={`mark-${at}`}>{text.slice(0, from)}{wrap(text.slice(from, to))}{text.slice(to)}</React.Fragment>, stop];
  }
  if (Array.isArray(node)) {
    const out: React.ReactNode[] = [];
    let cursor = at;
    for (const child of node) {
      const [next, moved] = wrapSpan(child, start, end, cursor, wrap);
      out.push(next);
      cursor = moved;
    }
    return [out, cursor];
  }
  if (React.isValidElement(node)) {
    const children = (node.props as { children?: React.ReactNode }).children;
    const stop = at + nodeText(node).length;
    // A void element such as a task-list checkbox carries no text and cannot take children.
    if (children === undefined || stop <= start || at >= end) return [node, stop];
    return [React.cloneElement(node, {}, wrapSpan(children, start, end, at, wrap)[0]), stop];
  }
  return [node, at];
}

/** One rendered block with an appearance of a quote wrapped, or the block untouched if it is gone. */
export function markPassage(node: React.ReactNode, quote: string, nth: number, wrap: (body: React.ReactNode) => React.ReactNode): React.ReactNode {
  const span = spanOf(nodeText(node), quote, nth);
  return span === null ? node : wrapSpan(node, span[0], span[1], 0, wrap)[0];
}

/** The block holding the wanted appearance of a quote, or null when no single block holds it. */
export function blockOf(blocks: string[], quote: string, occurrence: number): number | null {
  const wanted = normalize(quote);
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
  const text = normalize(haystack);
  const wanted = normalize(needle);
  if (wanted === '') return 0;
  let count = 0;
  let at = text.indexOf(wanted);
  while (at !== -1) {
    count++;
    at = text.indexOf(wanted, at + wanted.length);
  }
  return count;
}
