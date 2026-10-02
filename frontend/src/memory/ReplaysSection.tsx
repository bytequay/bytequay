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
import { BookDashed, BookMarked, ChevronRight, TriangleAlert } from 'lucide-react';
import { shortPath } from '../pulls/agent-review/model';
import { Icon, Spinner } from '../repository-context/icons';
import { plural, shortDate } from '../repository-context/model';
import { age } from './model';
import { raised, replayFiles, replayRunning, totals, type ReplayCommentDto, type ReplayDto, type ReplayFindingDto, type ReplaySideDto } from './replays';

const SIDE = (memory: boolean) => memory ? 'With memory' : 'Without memory';
const SideIcon = ({ memory }: { memory: boolean }) => memory ? <BookMarked size={13} aria-hidden="true" /> : <BookDashed size={13} aria-hidden="true" />;

/** What one side raised of the maintainers' comments, as an icon and a count; the sentence is on hover. */
function Score({ memory, count, total }: { memory: boolean; count: number; total: number }) {
  return <span className="mem-chip" title={`${SIDE(memory)}: raised ${count} of ${plural(total, 'maintainer comment')}`}><SideIcon memory={memory} />{count}/{total}</span>;
}

function sideState(side: ReplaySideDto): string {
  if (side.state === 'RUNNING') return side.waiting ? 'Waiting for approval' : 'Running';
  return { DONE: 'Done', INCOMPLETE: 'Incomplete', STOPPED: 'Stopped', FAILED: 'Failed', GONE: 'Gone' }[side.state];
}

/** "R12–R14" for a comment or finding's lines, the way review findings name their range. */
function range(side: string | null, start: number | null, end: number | null): string {
  if (end === null) return '';
  const prefix = side === 'LEFT' ? 'L' : 'R';
  return start === null || start === end ? `${prefix}${end}` : `${prefix}${start}–${prefix}${end}`;
}

/** Holdout replays: what each side raised of the maintainers' comments, over the finished replays and per replay. */
export function ReplaysSection({ replays, now, onOpen }: { replays: ReplayDto[]; now: number; onOpen: (id: string) => void }) {
  const sum = totals(replays);
  return <section className="mem-section" aria-label="Replays">
    <div className="mem-section__bar">
      <h2 className="mem-section__title">Replays · {replays.length}</h2>
      <span className="rcp-spacer" />
      {sum.replays > 0 && <span className="mem-replay__sum" title={`Over ${plural(sum.replays, 'finished replay')}`}>
        <Score memory count={sum.withMemory} total={sum.comments} /><Score memory={false} count={sum.withoutMemory} total={sum.comments} /></span>}
    </div>
    {replays.length === 0
      ? <div className="rcp-empty">No replay yet. Replay review on a merged pull request runs Agent Review twice at the head its maintainers first reviewed, with
        memory and without, and sets both next to what the maintainers said. Five to ten replays show whether memory makes reviews raise what maintainers raise.</div>
      : <div className="mem-box">{replays.map(replay => <button type="button" key={replay.id} className="mem-replay" onClick={() => onOpen(replay.id)}>
        <span className="mem-replay__main">
          <span className="mem-replay__title">#{replay.number} {replay.title}</span>
          <span className="mem-muted">{replay.head.slice(0, 7)} · first reviewed by @{replay.reviewer}{replay.fallback !== null && ' · not the first reviewed head'}</span>
        </span>
        <span className="mem-replay__side">
          {replayRunning(replay)
            ? <span className="mem-chip"><Spinner size={12} />{replay.sides.some(side => side.waiting) ? 'Waiting for approval' : 'Running'}</span>
            : replay.sides.map(side => side.state === 'DONE' || side.state === 'INCOMPLETE'
              ? <Score key={side.reviewId} memory={side.memory} count={raised(replay, side.memory)} total={replay.comments.length} />
              : <span key={side.reviewId} className="mem-chip mem-warn" title={`${SIDE(side.memory)}: ${side.outcome ?? sideState(side)}`}><SideIcon memory={side.memory} />
                {sideState(side)}</span>)}
          <span className="rcp-age">{age(replay.createdAt, now)}</span>
          <ChevronRight size={14} aria-hidden="true" className="mem-chevron" />
        </span>
      </button>)}</div>}
  </section>;
}

/** One replay file by file: each maintainer comment with whether each side raised it, which the user can change, and the findings no comment matched. */
export function ReplayDrawer({ replay, busy, now, onClose, onMark, onDelete, onOpenReview, onOpenUrl }: {
  replay: ReplayDto; busy: boolean; now: number; onClose: () => void; onMark: (commentId: string, memory: boolean, raised: boolean) => void; onDelete: () => void;
  onOpenReview: (reviewId: string) => void; onOpenUrl: (url: string) => void;
}) {
  const labels = new Map(replay.sides.flatMap(side => side.findings.map(finding => [finding.id, finding.label] as const)));
  return <>
    <div className="rcp-drawer__head"><div className="rcp-drawer__top">
      <span className="mem-kind">Replay</span>
      <button type="button" className="rcp-link" onClick={() => onOpenUrl(replay.url)}>#{replay.number}</button>
      <span className="rcp-spacer" />
      <button type="button" className="rcp-close" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>
    </div></div>
    <div className="rcp-drawer__body mem-drawer">
      <p className="mem-drawer__text">{replay.title}</p>
      <div className="mem-meta"><span>{replay.head.slice(0, 7)}, the head when @{replay.reviewer} first reviewed</span><span>{shortDate(replay.cutoff, now)}</span></div>
      {replay.fallback !== null && <div className="mem-flag"><TriangleAlert size={15} aria-hidden="true" /><span className="mem-flag__text">{replay.fallback}</span></div>}
      {replay.excludedMemories > 0 && <div className="mem-muted">The side with memory did not get {plural(replay.excludedMemories, 'memory', 'memories')} from this pull
        request or written after that review.</div>}
      <div className="mem-replay-sides">{replay.sides.map(side => <div className="mem-replay-side" key={side.reviewId}>
        <div className="mem-replay-side__title"><SideIcon memory={side.memory} />{SIDE(side.memory)}</div>
        <div className="mem-muted" title={side.outcome ?? undefined}>{sideState(side)} · {plural(side.findings.length, 'finding')} · raised {raised(replay, side.memory)} of {replay.comments.length}</div>
        <button type="button" className="rcp-link" onClick={() => onOpenReview(side.reviewId)}>Open review</button>
      </div>)}</div>
      {replayFiles(replay).map(file => <div className="mem-replay-file" key={file.path}>
        <h3 className="mem-label" title={file.path === '' ? undefined : file.path}><code>{file.path === '' ? 'Whole change' : shortPath(file.path, 64)}</code></h3>
        {file.comments.map(comment => <Comment key={comment.id} comment={comment} labels={labels} busy={busy} onMark={onMark} onOpenUrl={onOpenUrl} />)}
        {file.others.map(other => <Finding key={other.finding.id} memory={other.memory} finding={other.finding} />)}
      </div>)}
    </div>
    <div className="rcp-drawer__foot mem-foot">
      <span className="mem-muted">Click Raised or Missed to change a call; nothing is posted.</span>
      <span className="rcp-spacer" />
      <button type="button" className="rcp-btn rcp-btn--sm rcp-btn--danger" disabled={busy || replayRunning(replay)} onClick={onDelete}>Delete replay</button>
    </div>
  </>;
}

function Comment({ comment, labels, busy, onMark, onOpenUrl }: {
  comment: ReplayCommentDto; labels: Map<string, string>; busy: boolean; onMark: (commentId: string, memory: boolean, raised: boolean) => void;
  onOpenUrl: (url: string) => void;
}) {
  return <div className="mem-replay-comment">
    <div className="mem-replay-comment__head"><span>@{comment.author}</span><span className="mem-muted">{range(comment.side, comment.startLine, comment.line)}</span>
      {!comment.onReplayedHead && <span className="mem-muted" title="Written on a later head; the replay may not have seen this code">later head</span>}
      <span className="rcp-spacer" />
      <button type="button" className="rcp-link" onClick={() => onOpenUrl(comment.url)}>Open</button></div>
    <div className="mem-replay-comment__body">{comment.body}</div>
    <div className="mem-replay-marks">{comment.marks.map(mark => <button type="button" key={String(mark.memory)} disabled={busy} aria-pressed={mark.raised}
      className={`mem-mark${mark.raised ? ' is-raised' : ''}`} onClick={() => onMark(comment.id, mark.memory, !mark.raised)}
      title={`${SIDE(mark.memory)}: ${mark.raised ? 'raised' : 'missed'}${mark.corrected ? ', your call'
        : mark.findings.length > 0 ? ` by ${mark.findings.map(id => labels.get(id) ?? id).join(', ')}` : ''}`}>
      <SideIcon memory={mark.memory} />{mark.raised ? 'Raised' : 'Missed'}{mark.corrected && <span className="mem-muted"> · yours</span>}
    </button>)}</div>
  </div>;
}

function Finding({ memory, finding }: { memory: boolean; finding: ReplayFindingDto }) {
  return <div className="mem-replay-finding" title={`${SIDE(memory)} found this; no maintainer comment sits near it`}>
    <SideIcon memory={memory} /><span className="mem-muted">{finding.label} {range(finding.side, finding.startLine, finding.endLine)}</span><span>{finding.title}</span>
  </div>;
}
