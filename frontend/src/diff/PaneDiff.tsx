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
import { useEffect, useRef, useState } from 'react';
import { truncatePathMiddle } from './DiffFileTreePane';
import { DiffInlineComments, diffInlineCommentFromLocalPr, rangeLabel } from './DiffInlineComments';
import type { DiffFileDto } from '../types';
import type { LocalPRComment } from '../types/localPr';

type Side = 'LEFT' | 'RIGHT';
type Row = { kind: 'add' | 'del' | 'ctx' | 'hunk'; ln: number | null; mark: string; content: string };
type ComposerSlot = { file: string; side: Side; line: number; startLine?: number; startSide?: Side } | null;

/** Parse a unified-diff patch into compact display rows, tracking new-side
 *  line numbers (old-side for deletions). File headers are dropped. */
function parsePatch(patch: string): Row[] {
  const rows: Row[] = [];
  let newLn = 0;
  let oldLn = 0;
  // A patch is newline-terminated; drop the trailing empty split so it doesn't
  // render a phantom blank row (or a spurious comment anchor).
  for (const raw of patch.replace(/\n$/, '').split('\n')) {
    if (raw.startsWith('@@')) {
      const m = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(raw);
      if (m) { oldLn = Number(m[1]); newLn = Number(m[2]); }
      rows.push({ kind: 'hunk', ln: null, mark: '', content: raw });
      continue;
    }
    if (raw.startsWith('+++') || raw.startsWith('---') || raw.startsWith('diff ')
        || raw.startsWith('index ') || raw.startsWith('\\')) {
      continue;
    }
    if (raw.startsWith('+')) {
      rows.push({ kind: 'add', ln: newLn, mark: '+', content: raw.slice(1) });
      newLn += 1;
    }
    else if (raw.startsWith('-')) {
      rows.push({ kind: 'del', ln: oldLn, mark: '−', content: raw.slice(1) });
      oldLn += 1;
    }
    else {
      rows.push({ kind: 'ctx', ln: newLn, mark: '', content: raw.startsWith(' ') ? raw.slice(1) : raw });
      newLn += 1;
      oldLn += 1;
    }
  }
  return rows;
}

/** Key a comment to a diff line — filename + side + line number, so a
 *  deletion's old-side number never collides with an addition's new-side
 *  number on the same hunk. */
function lineKey(filename: string, side: Side, ln: number): string {
  return `${filename}:${side}:${ln}`;
}

/**
 * A compact multi-file diff sized for the stage right pane — each file as a
 * header (path + ± counts) over line-numbered rows. Lighter than the full
 * {@code ContinuousDiff} (no fold state, syntax highlighting, or scroll-sync).
 *
 * Inline commenting is a first-class prop (design #50): with
 * `allowLocalComments`, each line offers a comment anchor that opens a local
 * comment composer, and existing `file-line` comments render inline with an
 * origin badge. The origin the anchor writes is always `local` — real GitHub
 * comments arrive through their own path once the PR is pushed.
 */
export function PaneDiff({
  files, comments = [], allowLocalComments = false, onAddComment, onReplyComment, onResolveComment, onDismissComment,
}: {
  files: DiffFileDto[];
  comments?: LocalPRComment[];
  allowLocalComments?: boolean;
  onAddComment?: (
    filePath: string, side: Side, line: number,
    startLine: number | undefined, startSide: Side | undefined, body: string,
  ) => void;
  onReplyComment?: (
    parentCommentId: string, filePath: string, side: Side, line: number,
    startLine: number | undefined, startSide: Side | undefined, body: string,
  ) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
}) {
  // Which (file, side, line) has its composer expanded. Existing threads
  // always show; the composer only appears once a line's anchor is clicked.
  const [composer, setComposer] = useState<ComposerSlot>(null);

  const byLine = new Map<string, LocalPRComment[]>();
  for (const c of comments) {
    if (c.scope !== 'file-line' || c.filePath === null || c.lineNumber === null) continue;
    const key = lineKey(c.filePath, c.side, c.lineNumber);
    (byLine.get(key) ?? byLine.set(key, []).get(key)!).push(c);
  }

  const closeComposer = () => setComposer(null);

  // Plain click → single-line composer. Shift-click on a second row of the
  // same file+side while a composer is open → extends the range.
  const openComposer = (file: string, side: Side, line: number, shiftKey: boolean) => {
    setComposer(prev => {
      if (shiftKey && prev !== null && prev.file === file && prev.side === side) {
        const start = Math.min(prev.line, line);
        const end = Math.max(prev.line, line);
        return start === end
          ? { file, side, line: end }
          : { file, side, line: end, startLine: start, startSide: side };
      }
      return { file, side, line };
    });
  };

  // Drag-select: pointerdown starts the range, pointerenter on later rows of
  // the same file+side extends it, a window-level pointerup commits it.
  const [dragRange, setDragRange] = useState<{ file: string; side: Side; start: number; end: number } | null>(null);
  const dragRangeRef = useRef<typeof dragRange>(null);
  const suppressNextClickRef = useRef(false);

  const onRowPointerDown = (file: string, side: Side, line: number) => {
    const range = { file, side, start: line, end: line };
    dragRangeRef.current = range;
    setDragRange(range);
  };
  const onRowPointerEnter = (file: string, side: Side, line: number) => {
    const cur = dragRangeRef.current;
    if (!cur || cur.file !== file || cur.side !== side || cur.end === line) return;
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
      setComposer({ file: drag.file, side: drag.side, line: end, startLine: start, startSide: drag.side });
    };
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, []);

  const isInRange = (file: string, side: Side, line: number): boolean => {
    if (dragRange && dragRange.file === file && dragRange.side === side) {
      const lo = Math.min(dragRange.start, dragRange.end);
      const hi = Math.max(dragRange.start, dragRange.end);
      return line >= lo && line <= hi;
    }
    if (composer === null || composer.file !== file || composer.side !== side) return false;
    if (composer.startLine == null) return composer.line === line;
    return line >= composer.startLine && line <= composer.line;
  };

  return (
    <>
      {files.map(file => {
        const slash = file.filename.lastIndexOf('/');
        const dir = slash >= 0 ? `${truncatePathMiddle(file.filename.slice(0, slash))}/` : '';
        const name = slash >= 0 ? file.filename.slice(slash + 1) : file.filename;
        return (
          <div className="diff" key={file.filename}>
            <div className="diff-file-head">
              <span className="ic" aria-hidden>▾</span>
              <span className="path"><span className="path-dim">{dir}</span><span className="path-bold">{name}</span></span>
              <span className="count">
                {file.additions > 0 && <span className="add">+{file.additions}</span>}
                {file.additions > 0 && file.deletions > 0 ? ' ' : ''}
                {file.deletions > 0 && <span className="del">−{file.deletions}</span>}
              </span>
            </div>
            {file.patch !== null && file.patch.length > 0 && (
              <div className="diff-lines">
                {parsePatch(file.patch).map((r, i) => {
                  const side: Side | null = r.kind === 'hunk' || r.ln === null ? null : r.kind === 'del' ? 'LEFT' : 'RIGHT';
                  const key = side !== null ? lineKey(file.filename, side, r.ln!) : null;
                  const lineComments = key !== null ? byLine.get(key) ?? [] : [];
                  const composerHere = side !== null
                    && composer !== null && composer.file === file.filename && composer.side === side && composer.line === r.ln;
                  const commentable = allowLocalComments && side !== null;
                  const hasThread = lineComments.length > 0 || composerHere;
                  const inRange = side !== null && isInRange(file.filename, side, r.ln!);
                  return (
                    <div key={i}>
                      <div
                        className={`diff-line${r.kind === 'del' ? ' del' : r.kind === 'add' ? ' add' : r.kind === 'hunk' ? ' hunk' : ''}${lineComments.length > 0 ? ' has-comment' : ''}${inRange ? ' diff-row--in-range' : ''}`}
                        onPointerDown={commentable ? () => onRowPointerDown(file.filename, side!, r.ln!) : undefined}
                        onPointerEnter={commentable ? () => onRowPointerEnter(file.filename, side!, r.ln!) : undefined}
                      >
                        <span className="ln">{r.ln ?? ''}</span>
                        <span className="mark">{r.mark}</span>
                        <span className="content">{r.content.length > 0 ? r.content : ' '}</span>
                        {commentable && (
                          <span
                            className="comment-anchor"
                            role="button"
                            aria-label={`Comment on line ${r.ln}`}
                            onClick={(e) => {
                              if (suppressNextClickRef.current) { suppressNextClickRef.current = false; return; }
                              if (composerHere && !e.shiftKey) { closeComposer(); return; }
                              openComposer(file.filename, side!, r.ln!, e.shiftKey);
                            }}
                          >{lineComments.length > 0 ? '⚠' : '+'}</span>
                        )}
                      </div>
                      {hasThread && key !== null && side !== null && r.ln !== null && (
                        <DiffInlineComments
                          comments={lineComments.map(diffInlineCommentFromLocalPr)}
                          allowLocalComments={allowLocalComments}
                          onAdd={onAddComment !== undefined && composerHere
                            ? body => {
                              onAddComment(file.filename, composer!.side, composer!.line, composer!.startLine, composer!.startSide, body);
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
                          onCancel={composerHere ? closeComposer : undefined}
                          composingOn={composerHere
                            ? rangeLabel(composer!.side, composer!.line, composer!.startLine, composer!.startSide)
                            : undefined}
                        />
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </>
  );
}
