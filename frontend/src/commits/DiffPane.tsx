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
import { Fragment, useMemo } from 'react';
import { parseUnifiedDiff } from '../diffParse';
import type { GitDiffFileDto } from '../types';

const STATUS_CLASS: Record<string, string> = { A: 'is-add', D: 'is-del', R: 'is-mod', M: 'is-mod' };

export function FileList({ files, selected, onSelect }: {
  files: GitDiffFileDto[];
  selected: number;
  onSelect: (index: number) => void;
}) {
  return (
    <>
      {files.map((entry, index) => (
        <button
          type="button"
          key={entry.path}
          className={`cmt-file-row${index === selected ? ' is-active' : ''}`}
          title={entry.path}
          onClick={() => onSelect(index)}
        >
          <span className={`cmt-file-status ${STATUS_CLASS[entry.status] ?? 'is-mod'}`}>
            {entry.status}
          </span>
          <span className="cmt-file-path">{entry.path}</span>
          <span className="cmt-file-stats">
            <span className="is-add">+{entry.additions}</span>{' '}
            <span className="is-del">−{entry.deletions}</span>
          </span>
        </button>
      ))}
    </>
  );
}

export function DiffView({ file }: { file: GitDiffFileDto | null }) {
  const hunks = useMemo(() => parseUnifiedDiff(file?.patch), [file?.patch]);
  if (!file) return <div className="cmt-diff-empty">No changes to show.</div>;
  return (
    <div className="cmt-diff-view">
      <div className="cmt-diff-head">
        {file.path.split('/').pop()}
        <span className="is-add">+{file.additions}</span>
        <span className="is-del">−{file.deletions}</span>
      </div>
      {(file.patch === null || file.patch === undefined) && (
        <div className="cmt-diff-empty">Binary file or diff unavailable.</div>
      )}
      {hunks.map((hunk, hunkIndex) => (
        <Fragment key={`${hunk.header}:${hunkIndex}`}>
          <div className="cmt-diff-line is-hunk">
            <span className="cmt-diff-num" /><span className="cmt-diff-num" />
            <span className="cmt-diff-text">{hunk.header}</span>
          </div>
          {hunk.rows.map((row, rowIndex) => (
            <div className={`cmt-diff-line is-${row.kind}`} key={`${row.oldLine}:${row.newLine}:${rowIndex}`}>
              <span className="cmt-diff-num">{row.oldLine}</span>
              <span className="cmt-diff-num">{row.newLine}</span>
              <span className="cmt-diff-text">{row.content || ' '}</span>
            </div>
          ))}
        </Fragment>
      ))}
    </div>
  );
}

/** GitHub-Desktop-style file list + single-file unified diff. */
export default function DiffPane({ files, selected, onSelect }: {
  files: GitDiffFileDto[];
  selected: number;
  onSelect: (index: number) => void;
}) {
  if (files.length === 0) {
    return <div className="cmt-diff-empty">No changes to show.</div>;
  }
  const file = files[Math.min(selected, files.length - 1)] ?? null;
  return (
    <div className="cmt-diff-pane">
      <div className="cmt-file-list">
        <FileList files={files} selected={selected} onSelect={onSelect} />
      </div>
      <DiffView file={file} />
    </div>
  );
}
