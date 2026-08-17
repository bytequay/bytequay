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
import { Fragment, useMemo, useRef, useState } from 'react';
import { parseUnifiedDiff } from '../diffParse';
import type { DiffFileDto } from '../types';

export default function PullChanges({ files }: { files: DiffFileDto[] }) {
  const [treeOpen, setTreeOpen] = useState(true);
  const [selected, setSelected] = useState(files[0]?.filename ?? null);
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const scrollRef = useRef<HTMLDivElement>(null);
  const totals = useMemo(() => files.reduce((sum, file) => ({
    additions: sum.additions + file.additions,
    deletions: sum.deletions + file.deletions,
  }), { additions: 0, deletions: 0 }), [files]);

  const pick = (filename: string) => {
    setSelected(filename);
    setClosed(previous => {
      if (!previous.has(filename)) return previous;
      const next = new Set(previous);
      next.delete(filename);
      return next;
    });
    const container = scrollRef.current;
    const card = container?.querySelector(`[data-file-card="${CSS.escape(filename)}"]`);
    if (!container || !card) return;
    const top = card.getBoundingClientRect().top - container.getBoundingClientRect().top
      + container.scrollTop - 12;
    container.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
  };

  const toggle = (filename: string) => setClosed(previous => {
    const next = new Set(previous);
    if (next.has(filename)) next.delete(filename);
    else next.add(filename);
    return next;
  });

  if (files.length === 0) return <div className="hp-empty">No changed files reported.</div>;

  return (
    <div className="pull-changes">
      <div className="pull-changes__toolbar">
        <button
          type="button"
          className={treeOpen ? 'is-active' : ''}
          onClick={() => setTreeOpen(value => !value)}
          aria-label="Toggle changed files"
          title="Toggle changed files"
        >
          <PanelIcon />
        </button>
        <strong>{files.length} changed {files.length === 1 ? 'file' : 'files'}</strong>
        <span className="pull-changes__totals">
          <span>+{totals.additions}</span>
          <span>−{totals.deletions}</span>
        </span>
      </div>
      <div className="pull-changes__panes">
        {treeOpen && (
          <nav className="pull-file-tree" aria-label="Changed files">
            {files.map(file => {
              const segments = file.filename.split('/');
              const name = segments.pop() ?? file.filename;
              return (
                <button
                  type="button"
                  key={file.filename}
                  className={selected === file.filename ? 'is-active' : ''}
                  onClick={() => pick(file.filename)}
                  title={file.filename}
                >
                  <FileIcon />
                  <span><strong>{name}</strong>{segments.length > 0 && <small>{segments.join('/')}</small>}</span>
                </button>
              );
            })}
          </nav>
        )}
        <div className="pull-changes__scroll" ref={scrollRef}>
          {files.map(file => (
            <DiffFileCard
              key={file.filename}
              file={file}
              open={!closed.has(file.filename)}
              onToggle={() => toggle(file.filename)}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function DiffFileCard({ file, open, onToggle }: {
  file: DiffFileDto;
  open: boolean;
  onToggle: () => void;
}) {
  const hunks = useMemo(() => parseUnifiedDiff(file.patch), [file.patch]);
  const blocks = changeBlocks(file.additions, file.deletions);

  return (
    <article className="pull-diff-file" data-file-card={file.filename}>
      <button type="button" className="pull-diff-file__header" onClick={onToggle} aria-expanded={open}>
        <span className={`pull-diff-file__chevron${open ? ' is-open' : ''}`} aria-hidden="true">›</span>
        <span className="pull-diff-file__path">{file.filename}</span>
        <span className="pull-diff-file__status">{file.status}</span>
        <span className="pull-diff-file__stats">
          <span>+{file.additions}</span><span>−{file.deletions}</span>
        </span>
        <span className="pull-diff-file__blocks" aria-hidden="true">
          {blocks.map((kind, index) => <i className={`is-${kind}`} key={index} />)}
        </span>
      </button>
      {open && file.patch === null && (
        <div className="pull-diff-file__empty">Binary file or diff unavailable.</div>
      )}
      {open && file.patch !== null && hunks.length === 0 && (
        <div className="pull-diff-file__empty">No textual diff was returned.</div>
      )}
      {open && hunks.length > 0 && (
        <div className="pull-diff-file__code-scroll">
          <table className="pull-diff-code"><tbody>
            {hunks.map((hunk, hunkIndex) => {
              const previousEnd = hunkIndex === 0
                ? 1
                : hunks[hunkIndex - 1].newStart + hunks[hunkIndex - 1].newCount;
              const hiddenLines = Math.max(0, hunk.newStart - previousEnd);
              return (
                <Fragment key={`${hunk.header}:${hunkIndex}`}>
                  {hiddenLines > 0 && (
                    <tr className="is-gap"><td colSpan={3}>↕ {hiddenLines} unmodified lines</td></tr>
                  )}
                  <tr className="is-hunk"><td colSpan={3}>{hunk.header}</td></tr>
                  {hunk.rows.map((row, rowIndex) => (
                    <tr className={`is-${row.kind}`} key={`${row.oldLine}:${row.newLine}:${rowIndex}`}>
                      <td className="is-line">{row.oldLine}</td>
                      <td className="is-line">{row.newLine}</td>
                      <td className="is-source">
                        <span>{row.kind === 'add' ? '+' : row.kind === 'del' ? '−' : ' '}</span>{row.content || ' '}
                      </td>
                    </tr>
                  ))}
                </Fragment>
              );
            })}
          </tbody></table>
        </div>
      )}
    </article>
  );
}

function changeBlocks(additions: number, deletions: number): Array<'add' | 'del' | 'empty'> {
  const total = additions + deletions;
  if (total === 0) return Array.from({ length: 5 }, () => 'empty');
  const added = Math.round((additions / total) * 5);
  return Array.from({ length: 5 }, (_, index) => index < added ? 'add' : 'del');
}

function PanelIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M9 4v16" />
    </svg>
  );
}

function FileIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M14 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z" />
      <path d="M14 2v5h5" />
    </svg>
  );
}
