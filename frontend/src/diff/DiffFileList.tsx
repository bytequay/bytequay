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
import { memo, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { highlightToHtml, languageForPath } from '../highlight';
import type { DiffFileDto } from '../types';
import { parseUnifiedDiff, type DiffRowKind } from '../diffParse';
import {
  computeGap,
  canExpandUp,
  canExpandDown,
  isGapFullyLoaded,
  EXPAND_INCREMENT,
  type Gap,
  type LoadedGap,
} from '../diffExpand';

const ArrowUpIcon = () => (
  <svg className="diff-expand-btn__svg" viewBox="0 0 12 12" aria-hidden="true">
    <path d="M6 2 L10 7 L7.5 7 L7.5 10 L4.5 10 L4.5 7 L2 7 Z" />
  </svg>
);
const ArrowDownIcon = () => (
  <svg className="diff-expand-btn__svg" viewBox="0 0 12 12" aria-hidden="true">
    <path d="M6 10 L2 5 L4.5 5 L4.5 2 L7.5 2 L7.5 5 L10 5 Z" />
  </svg>
);

/** Expand-collapsed-code controls that sit in the gutter of a hunk
 *  header (or its own row for the after-last-hunk gap). The two button
 *  variants mirror docs/mockups/v2/codereview/expand.png:
 *
 *  - Top-of-file gap (no hunk above): single ↕ button — only "up"
 *    direction makes sense (loading lines toward the bottom of the gap,
 *    just above this hunk header).
 *  - Bottom-of-file gap (no hunk below): single ↕ button — only "down"
 *    makes sense (loading lines after the last hunk's content).
 *  - Middle gap: stacked control with an up chevron, a non-interactive
 *    "collapsed content" decoration in the middle, and a down chevron at
 *    the bottom. Either chevron loads the next 20 lines in that
 *    direction.
 */
function ExpandControls({
  gap,
  loaded,
  onClick,
  upBusy,
  downBusy,
}: {
  gap: Gap;
  loaded: LoadedGap;
  onClick: (gap: Gap, dir: 'up' | 'down') => void;
  upBusy: boolean;
  downBusy: boolean;
}) {
  const showUp = canExpandUp(gap, loaded);
  const showDown = canExpandDown(gap, loaded);
  // Single-button style for top/bottom gaps: only one direction is
  // meaningful, so we render one big affordance instead of the split.
  if (gap.isTop || gap.isBottom) {
    const dir: 'up' | 'down' = gap.isTop ? 'up' : 'down';
    const busy = dir === 'up' ? upBusy : downBusy;
    const enabled = dir === 'up' ? showUp : showDown;
    return (
      <button
        type="button"
        className="diff-expand-btn diff-expand-btn--single"
        onClick={() => onClick(gap, dir)}
        disabled={!enabled || busy}
        title={`Expand ${EXPAND_INCREMENT} more lines`}
        aria-label={`Expand ${EXPAND_INCREMENT} more lines`}
      >
        {dir === 'up' ? <ArrowUpIcon /> : <ArrowDownIcon />}
      </button>
    );
  }
  // Middle gap: github.com-style split. Dotted strip on top hints at
  // hidden lines; the two chevrons sit side-by-side below — up on the
  // left, down on the right. See docs/mockups/issue/code-diff/g-expand-button.png.
  return (
    <div className="diff-expand-split">
      <span className="diff-expand-split__divider" aria-hidden="true">
        <span /><span /><span /><span />
      </span>
      <div className="diff-expand-split__row">
        <button
          type="button"
          className="diff-expand-btn diff-expand-btn--up"
          onClick={() => onClick(gap, 'up')}
          disabled={!showUp || upBusy}
          title={`Expand ${EXPAND_INCREMENT} more lines up`}
          aria-label={`Expand ${EXPAND_INCREMENT} more lines up`}
        >
          <ArrowUpIcon />
        </button>
        <button
          type="button"
          className="diff-expand-btn diff-expand-btn--down"
          onClick={() => onClick(gap, 'down')}
          disabled={!showDown || downBusy}
          title={`Expand ${EXPAND_INCREMENT} more lines down`}
          aria-label={`Expand ${EXPAND_INCREMENT} more lines down`}
        >
          <ArrowDownIcon />
        </button>
      </div>
    </div>
  );
}

/** The (side, line) a diff row anchors to: deletions live on the LEFT
 *  (old) side, additions and context on the RIGHT (new) side. Hunk
 *  headers never reach the overlay callbacks. */
export type AnchorSide = 'LEFT' | 'RIGHT';

/** Interactive decoration the host attaches to a diff row. When
 *  `rowDecoration` is omitted entirely, rows render plain — no extra
 *  class, handlers, or `+` affordance (what the read-only task page
 *  wants). */
export type RowDecoration = {
  /** Extra classNames appended after the base `diff-row diff-row--{kind}`. */
  className?: string;
  /** Value for the row's `data-anchor` attribute. */
  dataAnchor?: string;
  onClick?: (e: React.MouseEvent) => void;
  onPointerDown?: (e: React.PointerEvent) => void;
  onPointerEnter?: () => void;
  role?: string;
  tabIndex?: number;
  title?: string;
  /** When true, render the `+` add-comment affordance in the new-side
   *  gutter (the click-to-comment hint). */
  addCommentAffordance?: boolean;
};

export type FileDiffBodyProps = {
  file: DiffFileDto;
  /** Expanded-gap state — Map<gapIndex, Map<newLine, content>>. Omit to
   *  render no expanded rows (and, with `onExpandClick` omitted, no
   *  expand controls). */
  expanded?: Map<number, LoadedGap>;
  /** Set of `${gapIndex}:${dir}` keys currently loading, driving the
   *  per-direction busy state on the expand controls. */
  expandLoading?: Set<string>;
  /** When provided, hunk-header gaps render ExpandControls wired to this
   *  callback. When omitted, gaps render plain gutters (no controls). */
  onExpandClick?: (gap: Gap, direction: 'up' | 'down') => void;
  /** Rendered immediately after each diff row (and each expanded row),
   *  keyed by the row's anchor. The host returns the overlays for that
   *  anchor (findings, threads, composer, …). */
  renderAfterRow?: (anchorSide: AnchorSide, anchorLine: number) => ReactNode;
  /** Returns the interactive attributes to attach to a diff row. Omit to
   *  render plain, non-interactive rows. */
  rowDecoration?: (anchorSide: AnchorSide, anchorLine: number, rowKind: DiffRowKind) => RowDecoration | null | undefined;
};

/** Renders the hunk/row/expand body of a SINGLE diff file. The overlay
 *  content (findings, threads, inline composer) and the interactive row
 *  attributes are injected by the host through `renderAfterRow` /
 *  `rowDecoration`, so this component carries no PR-specific state. */
export function FileDiffBody({ file, expanded, expandLoading, onExpandClick, renderAfterRow, rowDecoration }: FileDiffBodyProps) {
  const hunks = useMemo(() => parseUnifiedDiff(file.patch), [file.patch]);
  // Syntax-highlight every diff line against the file's language. Each line
  // is highlighted independently (a diff row is one line), so multi-line
  // constructs don't carry state across rows — fine for GitHub-style diffs.
  const lang = useMemo(() => languageForPath(file.filename), [file.filename]);
  const expandedState = expanded ?? EMPTY_EXPANDED;
  const loadingState = expandLoading ?? EMPTY_LOADING;

  if (file.patch === null || file.patch === undefined) {
    return (
      <div className="diff-file-empty">
        <span className="diff-file-empty__label">
          {file.status === 'renamed' ? 'File renamed without content changes.' : 'No diff available (binary file or large diff).'}
        </span>
      </div>
    );
  }
  if (hunks.length === 0) {
    return <div className="diff-file-empty">Empty diff.</div>;
  }
  return (
    <>
      {hunks.map((hunk, hi) => {
        const gapAbove = computeGap(hunks, hi);
        const loadedAbove = gapAbove ? (expandedState.get(hi) ?? new Map<number, string>()) : new Map<number, string>();
        // Expanded rows render in newLine-ascending order between the
        // previous hunk's last row and this hunk's header. They behave
        // as plain context rows for finding-anchoring purposes.
        const expandedRows = gapAbove
          ? [...loadedAbove.entries()].sort((a, b) => a[0] - b[0])
          : [];
        return (
          <div key={hi} className="diff-hunk">
            {expandedRows.map(([newLine, content]) => {
              const oldLine = newLine + gapAbove!.oldOffset;
              return (
                <div key={`exp-${newLine}`}>
                  <div className="diff-row diff-row--context diff-row--expanded">
                    <span className="diff-row__gutter">{oldLine}</span>
                    <span className="diff-row__gutter">{newLine}</span>
                    <span className="diff-row__content">
                      <span className="diff-row__sigil"> </span>
                      <span className="hljs" dangerouslySetInnerHTML={{ __html: highlightToHtml(content, lang) }} />
                    </span>
                  </div>
                  {renderAfterRow?.('RIGHT', newLine)}
                </div>
              );
            })}
            {hunk.rows.map((row, ri) => {
            if (row.kind === 'hunk-header') {
              const showExpand = onExpandClick != null && gapAbove != null && !isGapFullyLoaded(gapAbove, loadedAbove);
              const upBusy = loadingState.has(`${hi}:up`);
              const downBusy = loadingState.has(`${hi}:down`);
              return (
                <div key={ri} className="diff-row diff-row--hunk-header">
                  {showExpand ? (
                    <span className="diff-row__expand-cell">
                      <ExpandControls
                        gap={gapAbove!}
                        loaded={loadedAbove}
                        onClick={onExpandClick!}
                        upBusy={upBusy}
                        downBusy={downBusy}
                      />
                    </span>
                  ) : (
                    <>
                      <span className="diff-row__gutter" />
                      <span className="diff-row__gutter" />
                    </>
                  )}
                  <span className="diff-row__content">{hunk.header}</span>
                </div>
              );
            }
            // The line + side this row anchors to. Deletions exist only on
            // the LEFT side; additions and context default to RIGHT (the
            // new file). Hunk headers are filtered above.
            const anchorSide: AnchorSide = row.kind === 'del' ? 'LEFT' : 'RIGHT';
            const anchorLine = row.kind === 'del' ? row.oldLine : row.newLine;
            const deco = anchorLine != null ? rowDecoration?.(anchorSide, anchorLine, row.kind) : undefined;
            return (
              <div key={ri}>
                <div
                  className={
                    `diff-row diff-row--${row.kind}`
                    + (deco?.className ?? '')
                  }
                  // Stable anchor used by the AI sidebar's "jump to line"
                  // button: file.filename + the new-side line number, since
                  // AI findings always reference the new file.
                  data-anchor={deco?.dataAnchor}
                  onClick={deco?.onClick}
                  onPointerDown={deco?.onPointerDown}
                  onPointerEnter={deco?.onPointerEnter}
                  role={deco?.role}
                  tabIndex={deco?.tabIndex}
                  title={deco?.title}
                >
                  <span className="diff-row__gutter">{row.oldLine ?? ''}</span>
                  <span className="diff-row__gutter">
                    {row.newLine ?? ''}
                    {deco?.addCommentAffordance && <span className="diff-row__add-comment" aria-hidden="true">+</span>}
                  </span>
                  <span className="diff-row__content">
                    <span className="diff-row__sigil">
                      {row.kind === 'add' ? '+' : row.kind === 'del' ? '−' : ' '}
                    </span>
                    <span className="hljs" dangerouslySetInnerHTML={{ __html: highlightToHtml(row.content, lang) }} />
                  </span>
                </div>
                {anchorLine != null && renderAfterRow?.(anchorSide, anchorLine)}
              </div>
            );
          })}
        </div>
        );
      })}
      {/* After-last-hunk gap. Bottom expand controls live in their own
          row since there's no hunk header below to attach to. */}
      {(() => {
        const bottomGap = computeGap(hunks, hunks.length);
        if (!bottomGap) return null;
        const loadedBottom = expandedState.get(hunks.length) ?? new Map<number, string>();
        const expandedRows = [...loadedBottom.entries()].sort((a, b) => a[0] - b[0]);
        const downBusy = loadingState.has(`${hunks.length}:down`);
        const upBusy = loadingState.has(`${hunks.length}:up`);
        const showExpand = onExpandClick != null && (canExpandUp(bottomGap, loadedBottom) || canExpandDown(bottomGap, loadedBottom));
        return (
          <div className="diff-hunk">
            {expandedRows.map(([newLine, content]) => {
              const oldLine = newLine + bottomGap.oldOffset;
              return (
                <div key={`exp-bot-${newLine}`}>
                  <div className="diff-row diff-row--context diff-row--expanded">
                    <span className="diff-row__gutter">{oldLine}</span>
                    <span className="diff-row__gutter">{newLine}</span>
                    <span className="diff-row__content">
                      <span className="diff-row__sigil"> </span>
                      <span className="hljs" dangerouslySetInnerHTML={{ __html: highlightToHtml(content, lang) }} />
                    </span>
                  </div>
                  {renderAfterRow?.('RIGHT', newLine)}
                </div>
              );
            })}
            {showExpand && (
              <div className="diff-row diff-row--hunk-header">
                <span className="diff-row__expand-cell">
                  <ExpandControls
                    gap={bottomGap}
                    loaded={loadedBottom}
                    onClick={onExpandClick!}
                    upBusy={upBusy}
                    downBusy={downBusy}
                  />
                </span>
                <span className="diff-row__content" />
              </div>
            )}
          </div>
        );
      })()}
    </>
  );
}

// Stable empty defaults so `expanded`/`expandLoading` omission doesn't
// churn referential identity on each render.
const EMPTY_EXPANDED: Map<number, LoadedGap> = new Map();
const EMPTY_LOADING: Set<string> = new Set();

export type ContinuousDiffProps = {
  files: DiffFileDto[];
  selectedPath: string | null;
  onActiveFileChange: (path: string) => void;
  /** Renders the body (hunks/rows) for one non-collapsed file. The host
   *  supplies the per-file overlays and interactivity through whatever it
   *  passes to FileDiffBody. */
  renderFileBody: (file: DiffFileDto) => ReactNode;
};

/* The continuous (concatenated) multi-file diff: a single scroll
 * container that stacks every file's body under a sticky-flow header.
 * Owns per-file fold state and the scroll→active-file sync that keeps
 * the file-list rail in sync with whatever is roughly under the
 * scroll-area's top edge, and clicking a file in the rail scrolls
 * smoothly to its header.
 */
// Memoized: the concatenated diff (every file + syntax highlighting) is the
// heaviest subtree on the page. Without memo it re-renders on every resize
// mousemove tick and every commit-selection click — the source of the drag
// jank and slow selection. With stable props (see the call site) it only
// re-renders when the diff content actually changes.
export const ContinuousDiff = memo(function ContinuousDiff({
  files,
  selectedPath,
  onActiveFileChange,
  renderFileBody,
}: ContinuousDiffProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const sectionsRef = useRef<Map<string, HTMLElement>>(new Map());
  const lastSyncedFromClick = useRef<string | null>(null);
  // Window during which handleScroll suppresses its active-file sync.
  // Set when we kick off a programmatic scroll so intermediate files
  // passing under the top band don't bounce setSelectedPath through
  // every file en route — those re-renders interrupted the smooth
  // animation and the scroll would land short of the clicked file
  // (the "moves only ~8 files at a time" bug).
  const suppressActiveSyncUntil = useRef(0);
  // Per-file fold, GitHub-style: click the header chevron to hide a
  // file's hunks while keeping its header in the scroll flow. Filenames
  // in the set are collapsed; expanded is the default.
  const [collapsedFiles, setCollapsedFiles] = useState<Set<string>>(new Set());
  const toggleFileCollapsed = (filename: string) => {
    setCollapsedFiles((prev) => {
      const next = new Set(prev);
      if (next.has(filename)) {
        next.delete(filename);
      }
      else {
        next.add(filename);
      }
      return next;
    });
  };

  // Smoothly scroll to the section when the user picks a file in the rail.
  // We track `lastSyncedFromClick` so the scroll handler doesn't fight the
  // animation by re-setting the selection every frame.
  useEffect(() => {
    if (!selectedPath) return;
    if (lastSyncedFromClick.current === selectedPath) return;
    const el = sectionsRef.current.get(selectedPath);
    if (!el) return;
    lastSyncedFromClick.current = selectedPath;
    // Cover any reasonable smooth-scroll duration. 1500ms is generous
    // enough for cross-document jumps; once it elapses the active-
    // file detector resumes for genuine user scrolling.
    suppressActiveSyncUntil.current = Date.now() + 1500;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [selectedPath]);

  // Pick the file whose header is closest to the top of the scroll area.
  const handleScroll = () => {
    if (Date.now() < suppressActiveSyncUntil.current) return;
    const scroller = scrollRef.current;
    if (!scroller) return;
    const scrollerTop = scroller.getBoundingClientRect().top;
    let activePath: string | null = null;
    let bestOffset = Number.NEGATIVE_INFINITY;
    sectionsRef.current.forEach((el, path) => {
      const offset = el.getBoundingClientRect().top - scrollerTop;
      // Treat any header within 60px of the top as "the active one" — that
      // band lets the active selection flip slightly before the previous
      // file scrolls fully out of view, which feels more responsive.
      if (offset <= 60 && offset > bestOffset) {
        bestOffset = offset;
        activePath = path;
      }
    });
    if (activePath && activePath !== selectedPath) {
      lastSyncedFromClick.current = activePath;
      onActiveFileChange(activePath);
    }
  };

  return (
    <div
      ref={scrollRef}
      className="diff-viewer__pane-scroll diff-viewer__pane-scroll--continuous"
      onScroll={handleScroll}
    >
      {files.map((file) => (
        <section
          key={file.filename}
          ref={(el) => {
            if (el) sectionsRef.current.set(file.filename, el);
            else sectionsRef.current.delete(file.filename);
          }}
          className="diff-file-section"
          data-path={file.filename}
          // Anchor used by the AI sidebar's jump fallback when a
          // finding's line doesn't match any rendered diff row (e.g.
          // a multi-commit diff where the finding lives outside the
          // current hunks). Lets the fallback at least scroll the user
          // to the right file instead of the click looking inert.
          data-file-anchor={file.filename}
        >
          <header className="diff-file-section__header">
            <button
              type="button"
              className="diff-file-section__fold"
              aria-expanded={!collapsedFiles.has(file.filename)}
              onClick={() => toggleFileCollapsed(file.filename)}
              title={collapsedFiles.has(file.filename) ? 'Expand file' : 'Collapse file'}
            >
              {collapsedFiles.has(file.filename) ? '▸' : '▾'}
            </button>
            <span className="diff-viewer__pane-filename">{file.filename}</span>
            <span className={`diff-viewer__pane-status diff-viewer__pane-status--${file.status}`}>{file.status}</span>
            <span className="diff-file-section__stats">
              <span className="diff-file-row__add">+{file.additions}</span>
              <span className="diff-file-row__del">−{file.deletions}</span>
            </span>
          </header>
          {!collapsedFiles.has(file.filename) && renderFileBody(file)}
        </section>
      ))}
    </div>
  );
});
