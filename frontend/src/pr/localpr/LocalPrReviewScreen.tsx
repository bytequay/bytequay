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
import { useMemo, useRef, useState } from 'react';
import ResizeHandle from '../../ResizeHandle';
import { ContinuousDiff, FileDiffBody } from '../../diff/DiffFileList';
import type { AnchorSide, RowDecoration } from '../../diff/DiffFileList';
import { DiffFileTreePane } from '../../diff/DiffFileTreePane';
import type { FilesPaneMode } from '../../diff/DiffFileTreePane';
import { DiffInlineComments } from '../../diff/DiffInlineComments';
import { statusBadge } from '../../diffStatusBadge';
import { treeOrderedFiles } from '../../fileTree';
import type { DiffFileDto } from '../../types';
import type { LocalPRComment } from '../../types/localPr';

const MODE_KEY = 'bq.localPrReview.filesMode';

function lineKey(filename: string, ln: number): string {
  return `${filename}:${ln}`;
}

/**
 * One file's diff body for the local review page. It renders through the
 * SAME {@link FileDiffBody} the remote PR diff uses (identical rows, syntax
 * highlighting, gutters) and injects the local {@code file-line} comments via
 * FileDiffBody's {@code renderAfterRow} / {@code rowDecoration} hooks — the
 * remote review page wires GitHub review threads through the very same hooks.
 */
function LocalFileDiff({ file, comments, allowLocalComments, onAddComment, onResolveComment, onDismissComment }: {
  file: DiffFileDto;
  comments: LocalPRComment[];
  allowLocalComments: boolean;
  onAddComment?: (filePath: string, lineNumber: number, body: string) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
}) {
  // Which new-side line has its composer open. Existing threads always show;
  // the composer only appears when the user clicks a line to add a comment.
  const [openLine, setOpenLine] = useState<string | null>(null);

  const byLine = useMemo(() => {
    const m = new Map<string, LocalPRComment[]>();
    for (const c of comments) {
      if (c.scope !== 'file-line' || c.filePath !== file.filename || c.lineNumber === null) continue;
      const k = lineKey(file.filename, c.lineNumber);
      (m.get(k) ?? m.set(k, []).get(k)!).push(c);
    }
    return m;
  }, [comments, file.filename]);

  // Comments anchor to new-side lines only (additions + context) — the same
  // rule the remote review threads follow.
  const renderAfterRow = (side: AnchorSide, line: number) => {
    if (side !== 'RIGHT') return null;
    const key = lineKey(file.filename, line);
    const lineComments = byLine.get(key) ?? [];
    if (lineComments.length === 0 && openLine !== key) return null;
    return (
      <DiffInlineComments
        comments={lineComments}
        allowLocalComments={allowLocalComments}
        onAdd={onAddComment !== undefined
          ? body => { onAddComment(file.filename, line, body); setOpenLine(null); }
          : undefined}
        onResolve={onResolveComment}
        onDismiss={onDismissComment}
      />
    );
  };

  const rowDecoration = (side: AnchorSide, line: number): RowDecoration | null => {
    if (side !== 'RIGHT' || !allowLocalComments) return null;
    const key = lineKey(file.filename, line);
    const hasComment = (byLine.get(key)?.length ?? 0) > 0;
    return {
      className: hasComment ? ' has-comment' : '',
      addCommentAffordance: true,
      role: 'button',
      title: 'Comment on this line',
      onClick: () => setOpenLine(prev => (prev === key ? null : key)),
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
  onAddComment?: (filePath: string, lineNumber: number, body: string) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
  onBack: () => void;
  error?: string | null;
}) {
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [mode, setMode] = useState<FilesPaneMode>(() => {
    try { return localStorage.getItem(MODE_KEY) === 'flat' ? 'flat' : 'tree'; }
    catch { return 'tree'; }
  });
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(new Set());
  const [filesWidth, setFilesWidth] = useState(260);
  const bodyRef = useRef<HTMLDivElement>(null);

  // Tree-ordered so the continuous diff scrolls in the same order the tree
  // lists — matching the remote viewer.
  const orderedFiles = useMemo(
    () => (files ? treeOrderedFiles(files, f => f.filename) : []), [files]);

  const switchMode = (next: FilesPaneMode) => {
    setMode(next);
    try { localStorage.setItem(MODE_KEY, next); }
    catch { /* storage unavailable */ }
  };
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
            <div className="diff-viewer__mode-toggle" role="tablist" aria-label="File list layout">
              <button
                type="button"
                role="tab"
                className={`diff-viewer__mode-btn${mode === 'tree' ? ' diff-viewer__mode-btn--active' : ''}`}
                onClick={() => switchMode('tree')}
                aria-selected={mode === 'tree'}
                title="Tree — group by directory"
              >Tree</button>
              <button
                type="button"
                role="tab"
                className={`diff-viewer__mode-btn${mode === 'flat' ? ' diff-viewer__mode-btn--active' : ''}`}
                onClick={() => switchMode('flat')}
                aria-selected={mode === 'flat'}
                title="Flat — one row per file"
              >Flat</button>
            </div>
          </div>
          <DiffFileTreePane
            files={files}
            error={error}
            mode={mode}
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
