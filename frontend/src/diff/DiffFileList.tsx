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
import { useMemo, type ReactNode } from 'react';
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
 *  variants:
 *  - Top-of-file gap (no hunk above): single button — only "up"
 *    direction makes sense (loading lines toward the bottom of the gap,
 *    just above this hunk header).
 *  - Bottom-of-file gap (no hunk below): single button — only "down"
 *    makes sense (loading lines after the last hunk's content).
 *  - Middle gap: up, all, and down buttons in one full-width row.
 */
function ExpandControls({
  gap,
  loaded,
  onClick,
  upBusy,
  downBusy,
  allBusy,
}: {
  gap: Gap;
  loaded: LoadedGap;
  onClick: (gap: Gap, dir: 'up' | 'down' | 'all') => void;
  upBusy: boolean;
  downBusy: boolean;
  allBusy: boolean;
}) {
  const showUp = canExpandUp(gap, loaded);
  const showDown = canExpandDown(gap, loaded);
  const remaining = hiddenLineCount(gap, loaded);
  const label = remaining === null
    ? 'More unmodified lines'
    : `${remaining} unmodified line${remaining === 1 ? '' : 's'}`;
  // Single-button style for top/bottom gaps: only one direction is
  // meaningful, so we render one big affordance instead of the split.
  if (gap.isTop || gap.isBottom) {
    const dir: 'up' | 'down' = gap.isTop ? 'up' : 'down';
    const busy = dir === 'up' ? upBusy : downBusy;
    const enabled = dir === 'up' ? showUp : showDown;
    return (
      <button
        type="button"
        className="diff-expand-row diff-expand-row--single"
        onClick={() => onClick(gap, dir)}
        disabled={!enabled || busy}
        title={label}
        aria-label={label}
      >
        <span className="diff-expand-row__icon">{dir === 'up' ? <ArrowUpIcon /> : <ArrowDownIcon />}</span>
        <span className="diff-expand-row__label">{label}</span>
      </button>
    );
  }
  // Middle gap: three affordances in one row — reveal from the top,
  // reveal the whole remaining hidden block, or reveal from the bottom.
  return (
    <div className="diff-expand-row diff-expand-row--middle">
      <button
        type="button"
        className="diff-expand-btn diff-expand-btn--up"
        onClick={() => onClick(gap, 'up')}
        disabled={!showUp || upBusy}
        title={`Expand ${EXPAND_INCREMENT} lines above`}
        aria-label={`Expand ${EXPAND_INCREMENT} lines above`}
      >
        <ArrowUpIcon />
      </button>
      <button
        type="button"
        className="diff-expand-row__label-btn"
        onClick={() => onClick(gap, 'all')}
        disabled={remaining === 0 || upBusy || downBusy || allBusy}
        title={label}
        aria-label={label}
      >
        {label}
      </button>
      <button
        type="button"
        className="diff-expand-btn diff-expand-btn--down"
        onClick={() => onClick(gap, 'down')}
        disabled={!showDown || downBusy}
        title={`Expand ${EXPAND_INCREMENT} lines below`}
        aria-label={`Expand ${EXPAND_INCREMENT} lines below`}
      >
        <ArrowDownIcon />
      </button>
    </div>
  );
}

function hiddenLineCount(gap: Gap, loaded: LoadedGap): number | null {
  if (gap.newEnd === null) return null;
  let hidden = 0;
  for (let n = gap.newStart; n <= gap.newEnd; n++) {
    if (!loaded.has(n)) hidden++;
  }
  return hidden;
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
  onExpandClick?: (gap: Gap, direction: 'up' | 'down' | 'all') => void;
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
              const allBusy = loadingState.has(`${hi}:all`);
              if (showExpand) {
                return (
                  <div key={ri} className="diff-row diff-row--hunk-header">
                    <span className="diff-row__expand-cell">
                      <ExpandControls
                        gap={gapAbove!}
                        loaded={loadedAbove}
                        onClick={onExpandClick!}
                        upBusy={upBusy}
                        downBusy={downBusy}
                        allBusy={allBusy}
                      />
                    </span>
                  </div>
                );
              }
              return (
                <div key={ri} className="diff-row diff-row--hunk-header">
                  <span className="diff-row__gutter" />
                  <span className="diff-row__gutter" />
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
        const allBusy = loadingState.has(`${hunks.length}:all`);
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
                    allBusy={allBusy}
                  />
                </span>
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
