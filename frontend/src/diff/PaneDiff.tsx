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
import { useState } from 'react';
import { truncatePathMiddle } from './DiffFileTreePane';
import { DiffInlineComments } from './DiffInlineComments';
import type { DiffFileDto } from '../types';
import type { LocalPRComment } from '../types/localPr';

type Row = { kind: 'add' | 'del' | 'ctx' | 'hunk'; ln: number | null; mark: string; content: string };

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

/** Key a comment to a diff line — one filename + new-side line number. */
function lineKey(filename: string, ln: number): string {
  return `${filename}:${ln}`;
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
  files, comments = [], allowLocalComments = false, onAddComment, onResolveComment, onDismissComment,
}: {
  files: DiffFileDto[];
  comments?: LocalPRComment[];
  allowLocalComments?: boolean;
  onAddComment?: (filePath: string, lineNumber: number, body: string) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
}) {
  // Which (file:line) has its composer expanded. Existing threads always show;
  // the composer only appears when the user clicks a line's anchor.
  const [openLine, setOpenLine] = useState<string | null>(null);

  const byLine = new Map<string, LocalPRComment[]>();
  for (const c of comments) {
    if (c.scope !== 'file-line' || c.filePath === null || c.lineNumber === null) continue;
    const key = lineKey(c.filePath, c.lineNumber);
    (byLine.get(key) ?? byLine.set(key, []).get(key)!).push(c);
  }

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
                  // Comments anchor to new-side lines only (added / context) —
                  // a deletion's old-side number would otherwise collide with an
                  // addition's new-side number on the same hunk.
                  const newSide = (r.kind === 'add' || r.kind === 'ctx') && r.ln !== null;
                  const key = newSide ? lineKey(file.filename, r.ln!) : null;
                  const lineComments = key !== null ? byLine.get(key) ?? [] : [];
                  const commentable = allowLocalComments && newSide;
                  const hasThread = lineComments.length > 0 || (key !== null && openLine === key);
                  return (
                    <div key={i}>
                      <div className={`diff-line${r.kind === 'del' ? ' del' : r.kind === 'add' ? ' add' : r.kind === 'hunk' ? ' hunk' : ''}${lineComments.length > 0 ? ' has-comment' : ''}`}>
                        <span className="ln">{r.ln ?? ''}</span>
                        <span className="mark">{r.mark}</span>
                        <span className="content">{r.content.length > 0 ? r.content : ' '}</span>
                        {commentable && (
                          <span
                            className="comment-anchor"
                            role="button"
                            aria-label={`Comment on line ${r.ln}`}
                            onClick={() => setOpenLine(prev => (prev === key ? null : key))}
                          >{lineComments.length > 0 ? '⚠' : '+'}</span>
                        )}
                      </div>
                      {hasThread && key !== null && r.ln !== null && (
                        <DiffInlineComments
                          comments={lineComments}
                          allowLocalComments={allowLocalComments}
                          onAdd={onAddComment !== undefined
                            ? body => { onAddComment(file.filename, r.ln!, body); setOpenLine(null); }
                            : undefined}
                          onResolve={onResolveComment}
                          onDismiss={onDismissComment}
                          onCancel={openLine === key ? () => setOpenLine(null) : undefined}
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
