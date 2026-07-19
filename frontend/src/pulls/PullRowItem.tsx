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
import {
  Av,
  CiFailIcon,
  CiPassIcon,
  CommentBubbleIcon,
  PrMergedIcon,
  PrOpenIcon,
  RepoAv,
  RobotIcon,
  shortCount,
} from './atoms';
import type { PullRow } from './model';

/**
 * One PR list row, ported verbatim from the prototypes' work-list markup.
 * `wide` (no detail pane) renders the 5-column grid; `narrow` (pane open)
 * renders the stacked layout with pill chips. The prototype's 'issue' row
 * variant is omitted — the dashboard feed is PRs-only.
 */

function KindIcon({ row, size }: { row: PullRow; size: number }) {
  if (row.kind === 'merged') {
    return <span style={{ color: '#8250df', flexShrink: 0, display: 'inline-flex' }}><PrMergedIcon size={size} /></span>;
  }
  return <span style={{ color: '#1a7f37', flexShrink: 0, display: 'inline-flex' }}><PrOpenIcon size={size} /></span>;
}

function WideRow({ row }: { row: PullRow }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 150px 140px 56px 34px', alignItems: 'center', gap: 10 }}>
      <div style={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <KindIcon row={row} size={16} />
          <span style={{ fontSize: 13, fontWeight: 500, color: '#17191c', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{row.title}</span>
          <span style={{ fontSize: 13.5, color: '#8b949e', flexShrink: 0 }}>#{row.num}</span>
        </div>
        {row.chips.length > 0 && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', paddingLeft: 24 }}>
            {row.chips.map(l => (
              <span key={l.t} style={{ fontSize: 11.5, fontWeight: 500, padding: '2px 9px', borderRadius: 999, background: l.bg, color: l.fg, whiteSpace: 'nowrap' }}>{l.t}</span>
            ))}
          </div>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, paddingLeft: 24, fontSize: 12.5, color: '#8b949e', minWidth: 0 }}>
          <RepoAv repo={row.repo} size={16} />
          <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{row.repo}</span>
          <span>·</span>
          <Av login={row.author} size={16} />
          <span style={{ whiteSpace: 'nowrap' }}>{row.author}</span>
          <span>·</span>
          <span style={{ whiteSpace: 'nowrap' }}>{row.time}</span>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, color: '#57606a' }}>
        {row.status === 'running' && <><span style={{ width: 9, height: 9, borderRadius: '50%', background: '#2da44e', flexShrink: 0 }} />Running</>}
        {row.status === 'failed' && <><span style={{ color: '#cf222e', display: 'inline-flex', flexShrink: 0 }}><CiFailIcon /></span>Failed</>}
        {row.status === 'passed' && <><span style={{ color: '#1a7f37', display: 'inline-flex', flexShrink: 0 }}><CiPassIcon /></span>Passed</>}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 9, fontSize: 13, fontWeight: 600 }}>
        {row.add > 0 && <span style={{ color: '#1a7f37' }}>+{row.add}</span>}
        {row.del > 0 && <span style={{ color: '#cf222e' }}>−{row.del}</span>}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12.5, color: '#8b949e' }}>
        <CommentBubbleIcon />{row.comments}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {row.hasAgent && (
          <span title="Agent review assigned" style={{ color: '#8b5cf6', display: 'inline-flex' }}><RobotIcon /></span>
        )}
      </div>
    </div>
  );
}

function NarrowRow({ row }: { row: PullRow }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
        <span style={{ marginTop: 2, display: 'inline-flex' }}><KindIcon row={row} size={15} /></span>
        <span style={{ fontSize: 15, fontWeight: 500, color: '#17191c', lineHeight: 1.4, minWidth: 0 }}>
          {row.title} <span style={{ color: '#8b949e', fontWeight: 400 }}>#{row.num}</span>
        </span>
      </div>
      {row.chips.length > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 5, flexWrap: 'wrap', paddingLeft: 23 }}>
          {row.chips.map(l => (
            <span key={l.t} style={{ fontSize: 11, fontWeight: 500, padding: '2px 8px', borderRadius: 999, background: l.bg, color: l.fg, whiteSpace: 'nowrap' }}>{l.t}</span>
          ))}
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', paddingLeft: 23 }}>
        {row.status === 'running' && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, color: '#57606a', border: '1px solid #e1e5e9', borderRadius: 999, padding: '1px 8px' }}>
            <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#2da44e' }} />Running
          </span>
        )}
        {row.status === 'failed' && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11.5, color: '#57606a', border: '1px solid #e1e5e9', borderRadius: 999, padding: '1px 8px' }}>
            <span style={{ color: '#cf222e', fontWeight: 700 }}>✕</span>Failed
          </span>
        )}
        {row.status === 'passed' && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11.5, color: '#57606a', border: '1px solid #e1e5e9', borderRadius: 999, padding: '1px 8px' }}>
            <span style={{ color: '#1a7f37', fontWeight: 700 }}>✓</span>Passed
          </span>
        )}
        {row.add > 0 && <span style={{ fontSize: 11.5, fontWeight: 600, color: '#1a7f37', background: '#dafbe1', borderRadius: 999, padding: '2px 8px' }}>+{shortCount(row.add)}</span>}
        {row.del > 0 && <span style={{ fontSize: 11.5, fontWeight: 600, color: '#cf222e', background: '#ffebe9', borderRadius: 999, padding: '2px 8px' }}>−{shortCount(row.del)}</span>}
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11.5, color: '#8b949e' }}>
          <CommentBubbleIcon size={12} />{row.comments}
        </span>
        {row.hasAgent && (
          <span title="Agent review assigned" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11.5, fontWeight: 600, color: '#7c3aed' }}><RobotIcon size={14} /></span>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 5, paddingLeft: 23, fontSize: 12, color: '#8b949e', minWidth: 0 }}>
        <RepoAv repo={row.repo} size={16} />
        <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{row.repo}</span>
        <span>·</span>
        <Av login={row.author} size={16} />
        <span style={{ whiteSpace: 'nowrap' }}>{row.author}</span>
        <span>·</span>
        <span style={{ whiteSpace: 'nowrap' }}>{row.time}</span>
      </div>
    </div>
  );
}

export default function PullRowItem({ row, wide, selected, onPick }: {
  row: PullRow;
  wide: boolean;
  selected: boolean;
  onPick: () => void;
}) {
  return (
    <div
      className="pl-hov-row"
      onClick={onPick}
      style={{ padding: '11px 12px', borderRadius: 10, background: selected ? '#eef0f3' : 'transparent', cursor: 'pointer' }}
    >
      {wide ? <WideRow row={row} /> : <NarrowRow row={row} />}
    </div>
  );
}
