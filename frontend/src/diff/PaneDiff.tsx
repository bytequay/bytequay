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
import { truncatePathMiddle } from './DiffFileTreePane';
import type { DiffFileDto } from '../types';

type Row = { kind: 'add' | 'del' | 'ctx' | 'hunk'; ln: number | null; mark: string; content: string };

/** Parse a unified-diff patch into compact display rows, tracking new-side
 *  line numbers (old-side for deletions). File headers are dropped. */
function parsePatch(patch: string): Row[] {
  const rows: Row[] = [];
  let newLn = 0;
  let oldLn = 0;
  for (const raw of patch.split('\n')) {
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

/**
 * A compact, read-only multi-file diff sized for the stage right pane —
 * each file as a header (path + ± counts) over line-numbered rows. Lighter
 * than the full {@code ContinuousDiff} (no fold state, syntax highlighting,
 * or scroll-sync), matching the in-pane Changes diff in the design.
 */
export function PaneDiff({ files }: { files: DiffFileDto[] }) {
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
                {parsePatch(file.patch).map((r, i) => (
                  <div key={i} className={`diff-line${r.kind === 'del' ? ' del' : r.kind === 'add' ? ' add' : r.kind === 'hunk' ? ' hunk' : ''}`}>
                    <span className="ln">{r.ln ?? ''}</span>
                    <span className="mark">{r.mark}</span>
                    <span className="content">{r.content.length > 0 ? r.content : ' '}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </>
  );
}
