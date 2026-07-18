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
import { Fragment, useMemo, useState } from 'react';
import { parseUnifiedDiff } from '../diffParse';
import type { LoadedGap } from '../diffExpand';
import { rangeLabel } from '../diff/DiffInlineComments';
import { useDiffRangeComposer } from '../diff/useDiffRangeComposer';
import type { MarkdownRepoContext } from '../markdown';
import type { DiffFileDto, ReviewThreadDto } from '../types';
import { diffRowsFor, fullGapRange } from './changesModel';
import { Av, sqsFor } from './atoms';
import { InlineComposerRow, InlineThreadRow } from './PullDiffCommentRows';

/** Everything the thread rows need to hit the same GitHub-first bridge calls
 *  InlineReviewThread makes. Null while the PR has no remote identity. */
export type ThreadRowContext = {
  repo: string;
  prNumber: number;
  /** Legacy PR primary key the resolve path prefers (best effort — the
   *  backend falls back to the thread's own comment id when stale). */
  prId: number;
  prAuthor: string | null;
  onChanged: () => void;
};

const MONO = "'SF Mono',ui-monospace,Menlo,monospace";

/**
 * One file card of the Changes diff, transcribed from the DC prototype
 * (Pull Requests.dc.html): header row (chevron / mono path / +N −N / ratio
 * squares), the `table.code` rows (expand bars, hunk headers, code rows), and
 * the prototype's "Load diff" placeholder when collapsed or patch-less.
 * Parsing and expand math come from diffParse / diffExpand via changesModel.
 */
export default function PullDiffCard({
  file, open, onToggle, threads, threadCtx, allowComments, onAddComment, fetchBlob, login, repoCtx,
}: {
  file: DiffFileDto;
  open: boolean;
  onToggle: () => void;
  /** Live GitHub review threads anchored in this file (outdated ones excluded). */
  threads: ReviewThreadDto[];
  threadCtx: ThreadRowContext | null;
  allowComments: boolean;
  onAddComment: ((filePath: string, side: 'LEFT' | 'RIGHT', line: number, startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string) => Promise<void>) | null;
  fetchBlob: ((path: string) => Promise<{ lines: string[] }>) | null;
  login: string;
  repoCtx: MarkdownRepoContext;
}) {
  const hunks = useMemo(() => parseUnifiedDiff(file.patch), [file.patch]);
  const [expanded, setExpanded] = useState<ReadonlyMap<number, LoadedGap>>(new Map());
  const rows = useMemo(() => diffRowsFor(hunks, expanded), [hunks, expanded]);
  const { composer, closeComposer, handleRowClick, onRowPointerDown, onRowPointerEnter } = useDiffRangeComposer();

  const threadsByLine = useMemo(() => {
    const m = new Map<string, ReviewThreadDto[]>();
    for (const t of threads) {
      if (t.line === null || t.outdated) continue;
      const key = `${t.side === 'LEFT' ? 'LEFT' : 'RIGHT'}:${t.line}`;
      const list = m.get(key);
      if (list !== undefined) list.push(t);
      else m.set(key, [t]);
    }
    return m;
  }, [threads]);

  const expandGap = (gapIndex: number) => {
    if (fetchBlob === null) return;
    const range = fullGapRange(hunks, gapIndex);
    if (range === null) return;
    void fetchBlob(file.filename)
      .then(blob => setExpanded(prev => {
        const out = new Map(prev);
        const gap = new Map(prev.get(gapIndex) ?? []);
        for (let n = range.from; n <= range.to; n++) {
          if (n - 1 < blob.lines.length) gap.set(n, blob.lines[n - 1]);
        }
        out.set(gapIndex, gap);
        return out;
      }))
      .catch(() => { /* leave the bar in place; a retry can re-click */ });
  };

  const hasPatch = file.patch !== null;
  const showRows = hasPatch && open;
  return (
    <div data-file-card={file.filename} style={{ border: '1px solid #d5dbe1', borderRadius: 10, overflow: 'hidden', background: '#fff' }}>
      <div onClick={hasPatch ? onToggle : undefined} style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '9px 14px', background: '#f6f8fa', borderBottom: '1px solid #e7e9ec', cursor: 'pointer' }}>
        <span style={{ display: 'inline-flex', color: '#8b949e', transform: showRows ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.15s' }}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /></svg>
        </span>
        <span style={{ fontFamily: MONO, fontSize: 12.5, fontWeight: 600, color: '#17191c', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>{file.filename}</span>
        <span style={{ marginLeft: 'auto', fontFamily: MONO, fontSize: 11.5, flexShrink: 0 }}>
          <span style={{ color: '#1a7f37', fontWeight: 700 }}>{file.additions > 0 ? `+${file.additions}` : ''}</span>
          {' '}
          <span style={{ color: '#cf222e', fontWeight: 700 }}>{file.deletions > 0 ? `−${file.deletions}` : ''}</span>
        </span>
        <span style={{ display: 'inline-flex', gap: 2, flexShrink: 0 }}>
          {sqsFor(file.additions, file.deletions).map((c, i) => (
            <span key={i} style={{ width: 8, height: 8, borderRadius: 2, background: c }} />
          ))}
        </span>
      </div>
      {showRows ? (
        <table className="pl-code"><tbody>
          {rows.map((r, i) => {
            if (r.kind === 'exp') {
              return (
                <tr key={i}>
                  <td colSpan={3} onClick={() => expandGap(r.gapIndex)} title={fetchBlob === null ? 'Expand needs a synced head commit' : undefined} style={{ background: '#ddf4ff', color: '#0550ae', fontSize: 11, padding: '4px 14px', cursor: 'pointer' }}>↕ {r.text}</td>
                </tr>
              );
            }
            if (r.kind === 'hunk') {
              return (
                <tr key={i}>
                  <td colSpan={3} style={{ background: '#f1f8ff', color: '#57606a', fontSize: 11.5, padding: '4px 14px' }}>{r.text}</td>
                </tr>
              );
            }
            const lineThreads = threadCtx !== null ? threadsByLine.get(`${r.side}:${r.line}`) ?? [] : [];
            const participants = lineThreads.flatMap(t => t.messages.map(m => m.author).filter((a): a is string => a !== null));
            const composerHere = composer !== null && composer.side === r.side && composer.line === r.line;
            return (
              <Fragment key={i}>
                <tr
                  className={r.cls}
                  data-pl-anchor={`${file.filename}:${r.side}:${r.line}`}
                  title={allowComments ? 'Comment on this line — shift-click or drag to select a range' : undefined}
                  onClick={allowComments ? e => handleRowClick({ side: r.side, line: r.line }, e.shiftKey, { toggleActive: true }) : undefined}
                  onPointerDown={allowComments ? () => onRowPointerDown({ side: r.side, line: r.line }) : undefined}
                  onPointerEnter={allowComments ? () => onRowPointerEnter({ side: r.side, line: r.line }) : undefined}
                >
                  <td className="ln">{r.oldLn}</td>
                  <td className="ln">{r.newLn}</td>
                  <td className="src">
                    {lineThreads.length > 0 && (
                      <span style={{ float: 'right', display: 'inline-flex', alignItems: 'center', gap: 4, fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif", fontSize: 11, color: '#57606a' }}>
                        <span style={{ display: 'inline-flex' }}>
                          {[...new Set(participants)].slice(0, 2).map((a, j) => (
                            <span key={a} style={{ display: 'inline-flex', borderRadius: '50%', border: '1.5px solid #fff', marginLeft: j > 0 ? -5 : 0 }}><Av login={a} size={16} /></span>
                          ))}
                        </span>
                        {participants.length}
                      </span>
                    )}
                    {r.sign}{r.text}
                  </td>
                </tr>
                {threadCtx !== null && lineThreads.map(t => (
                  <InlineThreadRow
                    key={t.rootGithubId}
                    thread={t}
                    prAuthor={threadCtx.prAuthor}
                    repo={threadCtx.repo}
                    prId={threadCtx.prId}
                    prNumber={threadCtx.prNumber}
                    repoCtx={repoCtx}
                    onChanged={threadCtx.onChanged}
                  />
                ))}
                {composerHere && onAddComment !== null && (
                  <InlineComposerRow
                    label={rangeLabel(composer.side, composer.line, composer.startLine, composer.startSide)}
                    login={login}
                    repoCtx={repoCtx}
                    onSubmit={async body => {
                      await onAddComment(file.filename, composer.side, composer.line, composer.startLine, composer.startSide, body);
                      closeComposer();
                    }}
                    onCancel={closeComposer}
                  />
                )}
              </Fragment>
            );
          })}
        </tbody></table>
      ) : (
        <div style={{ padding: '26px 16px', textAlign: 'center', background: '#fff' }}>
          <span
            onClick={hasPatch ? onToggle : undefined}
            title={hasPatch ? undefined : 'Not wired yet'}
            style={{ fontSize: 12.5, color: '#0969da', cursor: 'pointer' }}
          >
            Load diff
          </span>
          <span style={{ display: 'block', fontSize: 11.5, color: '#8b949e', marginTop: 3 }}>Large diffs are not rendered by default.</span>
        </div>
      )}
    </div>
  );
}
