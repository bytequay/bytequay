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
import { changesTreeRows } from './changesModel';

/**
 * The Changes tab's left file-tree pane, transcribed from the DC prototype's
 * treeRows() markup (Pull Requests.dc.html): dir rows with chevron + blue
 * folder, file rows with a doc glyph, single-child directory chains collapsed
 * into one "a/b/c" row. Clicking a file selects it and scrolls the diff.
 */
export default function PullFileTree({ paths, selected, width, onPick }: {
  paths: string[];
  selected: string | null;
  width: number;
  onPick: (path: string) => void;
}) {
  const rows = useMemo(() => changesTreeRows(paths), [paths]);
  return (
    <div style={{ width, flexShrink: 0, overflowY: 'auto', padding: '8px 6px 20px', background: '#fff' }}>
      {rows.map(n => (
        <div
          key={`${n.isDir ? 'd' : 'f'}:${n.path}`}
          className="pl-hov-tree"
          onClick={n.isDir ? undefined : () => onPick(n.path)}
          style={{
            display: 'flex', alignItems: 'center', gap: 5, padding: '3px 6px', paddingLeft: n.pad,
            borderRadius: 6, cursor: 'pointer',
            background: !n.isDir && n.path === selected ? '#e7e9ec' : 'transparent',
          }}
        >
          {n.isDir ? (
            <>
              <span style={{ display: 'inline-flex', color: '#8b949e', flexShrink: 0 }}>
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
              </span>
              <span style={{ display: 'inline-flex', color: '#54aeff', flexShrink: 0 }}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M4 5h5l2 2h9a1.5 1.5 0 0 1 1.5 1.5V18a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 18V6.5A1.5 1.5 0 0 1 4 5z" /></svg>
              </span>
            </>
          ) : (
            <>
              <span style={{ width: 11, flexShrink: 0 }} />
              <span style={{ display: 'inline-flex', color: '#8b949e', flexShrink: 0 }}>
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z" /><path d="M14 2v5h5" /></svg>
              </span>
            </>
          )}
          <span style={{ fontSize: 12, color: '#1f2328', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{n.name}</span>
        </div>
      ))}
    </div>
  );
}
