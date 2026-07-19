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
import { Av, RepoAv, RobotIcon } from './atoms';
import type { PullChip, PullRow } from './model';
import type { Bucket } from './workspaceModel';

/**
 * The workspace PR board (three fixed columns + cards), ported verbatim from
 * the Workspace PRs prototype's board markup and boardCols() shapes. Cards
 * keep the same shape whether or not the detail pane is open — the auto-fit
 * grid just reflows to fewer columns.
 */

const COLUMNS: { bucket: Bucket; name: string; dot: string; emptyText: string }[] = [
  { bucket: 'attention', name: 'NEEDS ATTENTION', dot: '#d4a72c', emptyText: 'Nothing here' },
  { bucket: 'progress', name: 'IN PROGRESS', dot: '#0969da', emptyText: 'Nothing in progress' },
  { bucket: 'cleared', name: 'CLEARED TODAY', dot: '#1f883d', emptyText: 'Cleared PRs collapse here and reset at midnight' },
];

/** ≤2 label chips plus a "+N" overflow chip, per the prototype's boardCols(). */
function cardChips(row: PullRow): PullChip[] {
  const chips = row.chips.slice(0, 2);
  if (row.chips.length > 2) chips.push({ t: `+${row.chips.length - 2}`, bg: '#eceef0', fg: '#59636e' });
  return chips;
}

function BoardCard({ row, onPick }: { row: PullRow; onPick: () => void }) {
  return (
    <div
      className="pl-hov-card"
      onClick={onPick}
      style={{ border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', padding: '11px 13px', cursor: 'pointer', boxShadow: '0 1px 2px rgba(0,0,0,0.03)' }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, minWidth: 0 }}>
        <span style={{ fontFamily: "'SF Mono',ui-monospace,Menlo,monospace", fontSize: 11.5, color: '#8b949e', flexShrink: 0 }}>#{row.num}</span>
        <RepoAv repo={row.repo} size={16} />
        <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '0.06em', color: '#59636e', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {(row.repo.split('/')[1] ?? row.repo).toUpperCase()}
        </span>
        <span style={{ flex: 1 }} />
        {row.hasAgent && (
          <span title="Agent review assigned" style={{ color: '#8b5cf6', display: 'inline-flex', flexShrink: 0 }}><RobotIcon size={13} /></span>
        )}
      </div>
      <div style={{ fontSize: 13, fontWeight: 500, color: '#17191c', lineHeight: 1.4, marginTop: 7 }}>{row.title}</div>
      {row.chips.length > 0 && (
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', marginTop: 8 }}>
          {cardChips(row).map(l => (
            <span key={l.t} style={{ fontSize: 11, fontWeight: 500, padding: '2px 8px', borderRadius: 999, background: l.bg, color: l.fg, whiteSpace: 'nowrap' }}>{l.t}</span>
          ))}
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 10, borderTop: '1px dashed #e7e9ec', paddingTop: 8, fontSize: 11.5, color: '#59636e' }}>
        <Av login={row.author} size={16} />
        <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>@{row.author}</span>
        {row.status === 'running' && <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#2da44e', flexShrink: 0 }} />}
        {row.status === 'failed' && <span style={{ color: '#cf222e', fontWeight: 700 }}>✕</span>}
        {row.status === 'passed' && <span style={{ color: '#1a7f37', fontWeight: 700 }}>✓</span>}
        <span style={{ letterSpacing: '0.05em', fontSize: 10.5, fontWeight: 600 }}>BUILD</span>
        <span style={{ marginLeft: 'auto', whiteSpace: 'nowrap', color: '#8b949e' }}>{row.time}</span>
      </div>
    </div>
  );
}

export default function PullBoard({ columns, onPick }: {
  columns: Record<Bucket, PullRow[]>;
  onPick: (row: PullRow) => void;
}) {
  return (
    <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '16px 18px 30px' }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))', gap: 18, alignItems: 'start' }}>
        {COLUMNS.map(col => {
          const rows = columns[col.bucket];
          return (
            <div key={col.bucket} style={{ minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 2px 10px' }}>
                <span style={{ width: 9, height: 9, borderRadius: '50%', background: col.dot }} />
                <span style={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.08em', color: '#454c54', whiteSpace: 'nowrap' }}>{col.name}</span>
                <span style={{ fontSize: 12, color: '#8b949e' }}>{rows.length}</span>
              </div>
              {rows.length > 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {rows.map(row => <BoardCard key={row.id} row={row} onPick={() => onPick(row)} />)}
                </div>
              ) : (
                <div style={{ border: '1.5px dashed #d5dbe1', borderRadius: 10, padding: '26px 14px', textAlign: 'center', fontSize: 12.5, color: '#8b949e' }}>{col.emptyText}</div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
