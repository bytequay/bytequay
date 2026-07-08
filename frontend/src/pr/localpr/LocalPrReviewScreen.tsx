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
import { useEffect, useMemo, useRef, useState } from 'react';
import ResizeHandle from '../../ResizeHandle';
import { ContinuousDiff, FileDiffBody } from '../../diff/DiffFileList';
import type { AnchorSide, RowDecoration } from '../../diff/DiffFileList';
import { DiffFileTreePane } from '../../diff/DiffFileTreePane';
import { DiffInlineComments, rangeLabel } from '../../diff/DiffInlineComments';
import { statusBadge } from '../../diffStatusBadge';
import { treeOrderedFiles } from '../../fileTree';
import type { DiffFileDto } from '../../types';
import type { LocalPRComment } from '../../types/localPr';

/** The (side, line) the inline composer is open on, or null when closed.
 *  `startLine`/`startSide` are set only for a multi-line range. */
type ComposerSlot = { side: AnchorSide; line: number; startLine?: number; startSide?: AnchorSide } | null;

function lineKey(filename: string, side: AnchorSide, ln: number): string {
  return `${filename}:${side}:${ln}`;
}

/**
 * One file's diff body for the local review page. It renders through the
 * SAME {@link FileDiffBody} the remote PR diff uses (identical rows, syntax
 * highlighting, gutters) and injects the local {@code file-line} comments via
 * FileDiffBody's {@code renderAfterRow} / {@code rowDecoration} hooks — the
 * remote review page wires GitHub review threads through the very same hooks.
 * Shift-click / drag-select builds a multi-line range, mirroring
 * DiffViewerScreen's composer (see TaskCodePage's embedded Changes tab for
 * the identical pattern applied to the task-in-progress diff).
 */
function LocalFileDiff({ file, comments, allowLocalComments, onAddComment, onResolveComment, onDismissComment }: {
  file: DiffFileDto;
  comments: LocalPRComment[];
  allowLocalComments: boolean;
  onAddComment?: (
    filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
}) {
  const [composer, setComposer] = useState<ComposerSlot>(null);

  const byLine = useMemo(() => {
    const m = new Map<string, LocalPRComment[]>();
    for (const c of comments) {
      if (c.scope !== 'file-line' || c.filePath !== file.filename || c.lineNumber === null) continue;
      const k = lineKey(file.filename, c.side, c.lineNumber);
      (m.get(k) ?? m.set(k, []).get(k)!).push(c);
    }
    return m;
  }, [comments, file.filename]);

  const closeComposer = () => setComposer(null);

  // Plain click → single-line composer. Shift-click on a second row of the
  // same side while a composer is open → extends the range.
  const openComposer = (side: AnchorSide, line: number, shiftKey: boolean) => {
    setComposer(prev => {
      if (shiftKey && prev !== null && prev.side === side) {
        const anchor = prev.line;
        const start = Math.min(anchor, line);
        const end = Math.max(anchor, line);
        return start === end ? { side, line: end } : { side, line: end, startLine: start, startSide: side };
      }
      return { side, line };
    });
  };

  // Drag-select: pointerdown starts the range, pointerenter on later rows
  // (same side only) extends it, a window-level pointerup commits it.
  const [dragRange, setDragRange] = useState<{ side: AnchorSide; start: number; end: number } | null>(null);
  const dragRangeRef = useRef<typeof dragRange>(null);
  const suppressNextClickRef = useRef(false);

  const onRowPointerDown = (side: AnchorSide, line: number) => {
    const range = { side, start: line, end: line };
    dragRangeRef.current = range;
    setDragRange(range);
  };
  const onRowPointerEnter = (side: AnchorSide, line: number) => {
    const cur = dragRangeRef.current;
    if (!cur || cur.side !== side || cur.end === line) return;
    const next = { ...cur, end: line };
    dragRangeRef.current = next;
    setDragRange(next);
  };
  useEffect(() => {
    const onUp = () => {
      const drag = dragRangeRef.current;
      if (!drag) return;
      dragRangeRef.current = null;
      setDragRange(null);
      const start = Math.min(drag.start, drag.end);
      const end = Math.max(drag.start, drag.end);
      if (end === start) return;
      suppressNextClickRef.current = true;
      setComposer({ side: drag.side, line: end, startLine: start, startSide: drag.side });
    };
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, []);

  const isInRange = (side: AnchorSide, line: number): boolean => {
    if (dragRange && dragRange.side === side) {
      const lo = Math.min(dragRange.start, dragRange.end);
      const hi = Math.max(dragRange.start, dragRange.end);
      return line >= lo && line <= hi;
    }
    if (composer === null || composer.side !== side) return false;
    if (composer.startLine == null) return composer.line === line;
    return line >= composer.startLine && line <= composer.line;
  };

  const renderAfterRow = (side: AnchorSide, line: number) => {
    const key = lineKey(file.filename, side, line);
    const lineComments = byLine.get(key) ?? [];
    const composerHere = composer !== null && composer.side === side && composer.line === line;
    if (lineComments.length === 0 && !composerHere) return null;
    return (
      <DiffInlineComments
        comments={lineComments}
        allowLocalComments={allowLocalComments}
        onAdd={onAddComment !== undefined && composerHere
          ? body => {
            onAddComment(file.filename, composer.side, composer.line, composer.startLine, composer.startSide, body);
            closeComposer();
          }
          : undefined}
        onResolve={onResolveComment}
        onDismiss={onDismissComment}
        onCancel={closeComposer}
        composingOn={composerHere
          ? rangeLabel(composer.side, composer.line, composer.startLine, composer.startSide)
          : undefined}
      />
    );
  };

  const rowDecoration = (side: AnchorSide, line: number): RowDecoration | null => {
    if (!allowLocalComments) return null;
    const hasComment = (byLine.get(lineKey(file.filename, side, line))?.length ?? 0) > 0;
    const composerHere = composer !== null && composer.side === side && composer.line === line;
    return {
      className: (hasComment ? ' has-comment' : '') + (isInRange(side, line) ? ' diff-row--in-range' : ''),
      addCommentAffordance: true,
      role: 'button',
      title: 'Comment on this line — shift-click or drag to select a range',
      onClick: (e) => {
        if (suppressNextClickRef.current) { suppressNextClickRef.current = false; return; }
        if (composerHere && !e.shiftKey) { closeComposer(); return; }
        openComposer(side, line, e.shiftKey);
      },
      onPointerDown: () => onRowPointerDown(side, line),
      onPointerEnter: () => onRowPointerEnter(side, line),
    };
  };

  return <FileDiffBody file={file} renderAfterRow={renderAfterRow} rowDecoration={rowDecoration} />;
}

/**
 * Full-page code-diff review for a LOCAL PR — the same two-column shape as the
 * remote {@code DiffViewerScreen} (changed-files tree on the left, continuous
 * diff on the right), reusing the exact same components and CSS. It carries no
 * remote-review machinery (AI sidebar, commits lane, GitHub publish); the only
 * write path is the local {@code file-line} comments.
 */
export function LocalPrReviewScreen({
  title, files, comments, allowLocalComments = false, onAddComment, onResolveComment, onDismissComment,
  onBack, error = null,
}: {
  title: string;
  /** Null = still loading. Empty array = nothing changed. */
  files: DiffFileDto[] | null;
  comments: LocalPRComment[];
  allowLocalComments?: boolean;
  onAddComment?: (
    filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
  onBack: () => void;
  error?: string | null;
}) {
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(new Set());
  const [filesWidth, setFilesWidth] = useState(260);
  const bodyRef = useRef<HTMLDivElement>(null);

  // Tree-ordered so the continuous diff scrolls in the same order the tree
  // lists — matching the remote viewer.
  const orderedFiles = useMemo(
    () => (files ? treeOrderedFiles(files, f => f.filename) : []), [files]);

  const toggleDir = (path: string) => setCollapsedDirs(prev => {
    const next = new Set(prev);
    if (next.has(path)) next.delete(path);
    else next.add(path);
    return next;
  });
  const onFilesResize = (clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    setFilesWidth(Math.max(180, Math.min(480, clientX - rect.left)));
  };

  return (
    <div className="diff-viewer">
      <div className="diff-viewer__toolbar">
        <button className="button button--secondary" onClick={onBack} type="button">← Back</button>
        <div className="diff-viewer__title">
          <span className="diff-viewer__pr-title">{title}</span>
        </div>
      </div>
      <div
        className="diff-viewer__body"
        ref={bodyRef}
        style={{ gridTemplateColumns: `${filesWidth}px 5px minmax(0, 1fr)` }}
      >
        <aside className="diff-viewer__files">
          <div className="diff-viewer__files-header">
            <span>Changed files</span>
            {files !== null && <span className="diff-viewer__files-count">{files.length}</span>}
          </div>
          <DiffFileTreePane
            files={files}
            error={error}
            mode="tree"
            pathOf={f => f.filename}
            statusBadgeOf={f => statusBadge(f.status)}
            selectedPath={selectedPath}
            onSelectPath={setSelectedPath}
            collapsedDirs={collapsedDirs}
            onToggleDir={toggleDir}
          />
        </aside>

        <ResizeHandle onResize={onFilesResize} ariaLabel="Resize changed-files panel" />

        <main className="diff-viewer__pane">
          {files !== null && files.length > 0 ? (
            <ContinuousDiff
              files={orderedFiles}
              selectedPath={selectedPath}
              onActiveFileChange={setSelectedPath}
              renderFileBody={file => (
                <LocalFileDiff
                  file={file}
                  comments={comments}
                  allowLocalComments={allowLocalComments}
                  onAddComment={onAddComment}
                  onResolveComment={onResolveComment}
                  onDismissComment={onDismissComment}
                />
              )}
            />
          ) : error !== null ? (
            <div className="diff-viewer__error">{error}</div>
          ) : files === null ? (
            <div className="diff-viewer__loading">Loading diff…</div>
          ) : (
            <div className="diff-viewer__empty">No files changed.</div>
          )}
        </main>
      </div>
    </div>
  );
}
