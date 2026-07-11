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
import { useMemo } from 'react';
import type { AnchorSide, RowDecoration } from '../../diff/DiffFileList';
import { DiffReviewShell } from '../../diff/DiffReviewShell';
import { ExpandableFileDiffBody } from '../../diff/ExpandableFileDiffBody';
import { DiffInlineComments, diffInlineCommentFromLocalPr, rangeLabel } from '../../diff/DiffInlineComments';
import { useDiffRangeComposer } from '../../diff/useDiffRangeComposer';
import type { DiffFileDto } from '../../types';
import type { LocalPRComment } from '../../types/localPr';

function lineKey(filename: string, side: AnchorSide, ln: number): string {
  return `${filename}:${side}:${ln}`;
}

/**
 * One file's diff body for the local review page. It renders through the
 * shared expandable diff body and injects local {@code file-line} comments
 * through the row overlay hooks. Shift-click / drag-select range handling
 * comes from the shared composer hook used by task and remote PR diffs too.
 */
function LocalFileDiff({
  file, comments, allowLocalComments, fetchFileBlob, onAddComment, onReplyComment, onResolveComment, onDismissComment,
}: {
  file: DiffFileDto;
  comments: LocalPRComment[];
  allowLocalComments: boolean;
  fetchFileBlob?: (path: string) => Promise<{ lines: string[] }>;
  onAddComment?: (
    filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onReplyComment?: (
    parentCommentId: string, filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
}) {
  const {
    composer,
    closeComposer,
    handleRowClick,
    onRowPointerDown,
    onRowPointerEnter,
    isInRange,
  } = useDiffRangeComposer();

  const byLine = useMemo(() => {
    const m = new Map<string, LocalPRComment[]>();
    for (const c of comments) {
      if (c.scope !== 'file-line' || c.filePath !== file.filename || c.lineNumber === null) continue;
      const k = lineKey(file.filename, c.side, c.lineNumber);
      (m.get(k) ?? m.set(k, []).get(k)!).push(c);
    }
    return m;
  }, [comments, file.filename]);

  const renderAfterRow = (side: AnchorSide, line: number) => {
    const key = lineKey(file.filename, side, line);
    const lineComments = byLine.get(key) ?? [];
    const composerHere = composer !== null && composer.side === side && composer.line === line;
    if (lineComments.length === 0 && !composerHere) return null;
    return (
      <DiffInlineComments
        comments={lineComments.map(diffInlineCommentFromLocalPr)}
        allowLocalComments={allowLocalComments}
        onAdd={onAddComment !== undefined && composerHere
          ? body => {
            onAddComment(file.filename, composer.side, composer.line, composer.startLine, composer.startSide, body);
            closeComposer();
          }
          : undefined}
        onReply={onReplyComment !== undefined
          ? (comment, body) => {
            if (comment.filePath === null || comment.lineNumber === null) return;
            onReplyComment(
              comment.id,
              comment.filePath,
              comment.side,
              comment.lineNumber,
              comment.startLine ?? undefined,
              comment.startSide ?? undefined,
              body);
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
    return {
      className: (hasComment ? ' has-comment' : '') + (isInRange({ side, line }) ? ' diff-row--in-range' : ''),
      addCommentAffordance: true,
      role: 'button',
      title: 'Comment on this line — shift-click or drag to select a range',
      onClick: (e) => {
        handleRowClick({ side, line }, e.shiftKey, { toggleActive: true });
      },
      onPointerDown: () => onRowPointerDown({ side, line }),
      onPointerEnter: () => onRowPointerEnter({ side, line }),
    };
  };

  return (
    <ExpandableFileDiffBody
      file={file}
      fetchFileBlob={fetchFileBlob}
      renderAfterRow={renderAfterRow}
      rowDecoration={rowDecoration}
    />
  );
}

/**
 * Full-page code-diff review for a LOCAL PR — the same two-column shape as the
 * remote PR diff (changed-files tree on the left, continuous diff on the
 * right), reusing the exact same components and CSS. It carries no
 * remote-review machinery (AI sidebar, commits lane, GitHub publish); the only
 * write path is the local {@code file-line} comments.
 */
export function LocalPrReviewScreen({
  title, files, comments, allowLocalComments = false, onAddComment, onReplyComment, onResolveComment, onDismissComment,
  onBack, error = null, fetchFileBlob,
}: {
  title: string;
  /** Null = still loading. Empty array = nothing changed. */
  files: DiffFileDto[] | null;
  comments: LocalPRComment[];
  allowLocalComments?: boolean;
  fetchFileBlob?: (path: string) => Promise<{ lines: string[] }>;
  onAddComment?: (
    filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onReplyComment?: (
    parentCommentId: string, filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
  onBack: () => void;
  error?: string | null;
}) {
  return (
    <DiffReviewShell
      title={title}
      files={files}
      error={error}
      onBack={onBack}
      renderFileBody={file => (
        <LocalFileDiff
          file={file}
          comments={comments}
          allowLocalComments={allowLocalComments}
          fetchFileBlob={fetchFileBlob}
          onAddComment={onAddComment}
          onReplyComment={onReplyComment}
          onResolveComment={onResolveComment}
          onDismissComment={onDismissComment}
        />
      )}
    />
  );
}
