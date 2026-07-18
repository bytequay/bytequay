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
import { renderMarkdown, type MarkdownRepoContext } from '../markdown';
import { relativeTime } from '../notificationDisplay';
import type { ActivityItemDto, DiffFileDto } from '../types';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import { assocLabel, lastTwoSegments, snippetRowFor } from './changesModel';
import { buildOpenedCard, isBotActor } from './detailModel';
import type { PullRow } from './model';
import { Av } from './atoms';
import { AiReplyButton, EmojiPlusPill } from './PullDiffCommentRows';

/**
 * The Changes tab's right review sidebar, transcribed from the DC prototype
 * (Pull Requests.dc.html): Review | History segmented tabs, pending-comment
 * cards (jump / resolve / delete), past review activity as cards, and the
 * "Add a comment" mini-composer footer.
 */

const chipStyle = { fontSize: 10.5, fontWeight: 500, color: '#59636e', border: '1px solid #d5dbe1', borderRadius: 999, padding: '1px 8px' } as const;
const MONO = "'SF Mono',ui-monospace,Menlo,monospace";

function Chevron({ deg = 0, size = 11 }: { deg?: number; size?: number }) {
  return (
    <span style={{ display: 'inline-flex', color: '#8b949e', flexShrink: 0, transform: `rotate(${deg}deg)` }}>
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /></svg>
    </span>
  );
}

function PendingCard({ c, files, login, onJump, onResolve, onDelete }: {
  c: LocalPRComment;
  files: DiffFileDto[] | null;
  login: string;
  onJump: (filePath: string, side: 'LEFT' | 'RIGHT', line: number) => void;
  onResolve: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const file = c.filePath !== null ? files?.find(f => f.filename === c.filePath) ?? null : null;
  const snippet = file !== null && c.lineNumber !== null ? snippetRowFor(file.patch, c.side, c.lineNumber) : null;
  const jumpable = c.filePath !== null && c.lineNumber !== null;
  return (
    <div style={{ border: '1px solid #d5dbe1', borderRadius: 10, overflow: 'hidden', marginTop: 4 }}>
      <div
        className="pl-hov-phead"
        title={jumpable ? 'Jump to line' : undefined}
        onClick={jumpable ? () => onJump(c.filePath!, c.side, c.lineNumber!) : undefined}
        style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 10px', background: '#f6f8fa', borderBottom: '1px solid #e7e9ec', cursor: 'pointer' }}
      >
        <Chevron deg={90} />
        <span style={{ fontFamily: MONO, fontSize: 11, fontWeight: 600, color: '#17191c', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>
          {c.filePath !== null ? lastTwoSegments(c.filePath) : 'Pull request'}
        </span>
        {c.lineNumber !== null && <span style={{ fontSize: 11, color: '#59636e', flexShrink: 0 }}>Line {c.lineNumber}</span>}
        <span
          title="Mark resolved"
          onClick={e => { e.stopPropagation(); onResolve(c.id); }}
          style={{ marginLeft: 'auto', display: 'inline-flex', color: '#57606a', flexShrink: 0, cursor: 'pointer' }}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9" /><path d="m8.5 12.5 2.5 2.5 4.5-5" /></svg>
        </span>
        <span
          className="pl-hov-trash"
          title="Delete comment"
          onClick={e => { e.stopPropagation(); onDelete(c.id); }}
          style={{ display: 'inline-flex', color: '#57606a', flexShrink: 0, cursor: 'pointer' }}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18" /><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /><path d="M10 11v6" /><path d="M14 11v6" /></svg>
        </span>
      </div>
      {snippet !== null && (
        <table className="pl-code"><tbody>
          <tr className={snippet.cls}><td className="ln">{snippet.oldLn}</td><td className="ln">{snippet.newLn}</td><td className="src">{snippet.sign}{snippet.text}</td></tr>
        </tbody></table>
      )}
      <div style={{ padding: '10px 12px 12px', borderTop: '1px solid #e7e9ec' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
          <Av login={c.author === 'you' ? login : c.author} size={22} />
          <span style={{ fontSize: 12.5, fontWeight: 600, color: '#17191c' }}>{c.author === 'you' ? login : c.author}</span>
          <span style={{ fontSize: 11.5, color: '#8b949e' }}>{relativeTime(new Date(c.createdAt).toISOString())}</span>
          <span style={{ flex: 1 }} />
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10.5, fontWeight: 600, color: '#9a6700', border: '1px solid rgba(154,103,0,0.4)', borderRadius: 999, padding: '1px 8px' }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#bf8700' }} />
            Pending
          </span>
          <span style={chipStyle}>Contributor</span>
        </div>
        <textarea
          className="pl-pend-ta"
          value={c.body}
          readOnly
          title="Editing not wired yet"
          style={{ display: 'block', width: '100%', border: '1px solid transparent', borderRadius: 6, outline: 'none', resize: 'vertical', padding: '4px 6px', marginTop: 6, fontSize: 12.5, lineHeight: 1.55, color: '#1f2328', background: 'transparent', fontFamily: 'inherit', minHeight: 46 }}
        />
        <div style={{ marginTop: 9 }}><EmojiPlusPill /></div>
        <div title="Not wired yet" style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 9, fontSize: 12.5, color: '#59636e', cursor: 'pointer' }}>
          No replies<Chevron deg={0} size={12} />
        </div>
      </div>
    </div>
  );
}

function HistoryCard({ item, prAuthor, repoCtx }: { item: ActivityItemDto; prAuthor: string | null; repoCtx: MarkdownRepoContext }) {
  const actor = item.actor.replace(/^@/, '');
  const bot = isBotActor(actor);
  const name = actor.replace(/\[bot]$/i, '');
  return (
    <div style={{ borderBottom: '1px solid #eef1f4', padding: '12px 0 14px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Av login={actor} size={26} square={bot} />
        <span style={{ minWidth: 0, flex: 1 }}>
          <span style={{ display: 'block', fontSize: 12.5, fontWeight: 600, color: '#17191c', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {name}{bot && <span style={{ fontWeight: 400, color: '#8b949e' }}>[bot]</span>}
          </span>
          <span style={{ display: 'block', fontSize: 11.5, color: '#8b949e' }}>
            {item.eventType === 'reviewed' ? 'reviewed' : 'commented'}{item.timestamp !== null ? ` ${relativeTime(item.timestamp)}` : ''}
          </span>
        </span>
        {assocLabel(item.authorAssociation) !== null && <span style={chipStyle}>{assocLabel(item.authorAssociation)}</span>}
        {actor === prAuthor && <span style={chipStyle}>Author</span>}
      </div>
      {item.body !== null && item.body.trim().length > 0 && (
        <div
          style={{ fontSize: 12.5, color: '#1f2328', lineHeight: 1.55, marginTop: 9 }}
          dangerouslySetInnerHTML={{ __html: renderMarkdown(item.body, repoCtx) }}
        />
      )}
      <div style={{ marginTop: 10 }}><EmojiPlusPill /></div>
      <div title="Not wired yet" style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 10, fontSize: 12.5, color: '#59636e', cursor: 'pointer' }}>
        Quote reply<Chevron deg={0} size={12} />
      </div>
    </div>
  );
}

export default function PullReviewSidebar({
  row, bundle, files, pending, activity, width, login, onJump, onResolve, onDelete, onComment,
}: {
  row: PullRow;
  bundle: LocalPRBundle;
  files: DiffFileDto[] | null;
  pending: LocalPRComment[];
  activity: ActivityItemDto[];
  width: number;
  login: string;
  onJump: (filePath: string, side: 'LEFT' | 'RIGHT', line: number) => void;
  onResolve: (id: string) => void;
  onDelete: (id: string) => void;
  /** Posts a PR-level comment (same bridge decision as the Overview tab);
   *  undefined while the bundle is loading. */
  onComment?: (body: string) => Promise<void>;
}) {
  const [tab, setTab] = useState<'review' | 'history'>('review');
  const [seg, setSeg] = useState<'write' | 'preview'>('write');
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [owner, repoName] = row.repo.split('/');
  const repoCtx: MarkdownRepoContext = { owner: owner ?? '', repo: repoName ?? '' };
  const prAuthor = (bundle.pr.author ?? row.author).replace(/^@/, '');
  const history = activity.filter(a => a.eventType === 'reviewed' || a.eventType === 'commented');
  const opened = buildOpenedCard(row, bundle);
  const submit = () => {
    const body = draft.trim();
    if (body.length === 0 || onComment === undefined || busy) return;
    setBusy(true);
    onComment(body)
      .then(() => setDraft(''))
      .catch(() => { /* poll reconciles; keep the draft */ })
      .finally(() => setBusy(false));
  };
  return (
    <div style={{ width, flexShrink: 0, display: 'flex', flexDirection: 'column', minHeight: 0, background: '#fff' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '8px 10px', borderBottom: '1px solid #e7e9ec', flexShrink: 0 }}>
        {([['review', 'Review'], ['history', 'History']] as const).map(([key, label]) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            style={{ padding: '4px 12px', border: 0, borderRadius: 7, background: tab === key ? '#e7e9ec' : 'transparent', fontSize: 12.5, fontWeight: tab === key ? 600 : 500, color: tab === key ? '#17191c' : '#59636e', cursor: 'pointer' }}
          >
            {label}
          </button>
        ))}
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '2px 14px 10px' }}>
        {tab === 'review' ? (
          pending.length > 0 ? (
            <>
              <div style={{ padding: '10px 2px 6px', fontSize: 12, color: '#59636e', textAlign: 'center' }}>
                Showing {pending.length} of {bundle.comments.length} comments
              </div>
              {pending.map(c => (
                <PendingCard key={c.id} c={c} files={files} login={login} onJump={onJump} onResolve={onResolve} onDelete={onDelete} />
              ))}
            </>
          ) : (
            <div style={{ padding: '18px 2px', fontSize: 12.5, color: '#8b949e' }}>No pending review comments.</div>
          )
        ) : history.length > 0 ? (
          history.map((item, i) => <HistoryCard key={i} item={item} prAuthor={prAuthor} repoCtx={repoCtx} />)
        ) : (
          <div style={{ padding: '12px 0 14px', borderBottom: '1px solid #eef1f4' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Av login={opened.author} size={24} square={opened.bot} />
              <span style={{ minWidth: 0 }}>
                <span style={{ display: 'block', fontSize: 12.5, fontWeight: 600, color: '#17191c' }}>{opened.author}</span>
                <span style={{ display: 'block', fontSize: 11.5, color: '#8b949e' }}>opened pull request · {opened.time}</span>
              </span>
            </div>
            {opened.description !== null && (opened.description.trim().length > 0 ? (
              <div
                style={{ fontSize: 12.5, color: '#1f2328', lineHeight: 1.55, marginTop: 8 }}
                dangerouslySetInnerHTML={{ __html: renderMarkdown(opened.description, repoCtx) }}
              />
            ) : (
              <div style={{ fontSize: 12.5, color: '#8b949e', fontStyle: 'italic', marginTop: 8 }}>No description provided.</div>
            ))}
          </div>
        )}
      </div>
      <div style={{ borderTop: '1px solid #e7e9ec', padding: '10px 12px 12px', flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, fontWeight: 600, color: '#17191c' }}>
          <Chevron deg={90} />Add a comment
        </div>
        <div style={{ border: '1px solid #d5dbe1', borderRadius: 8, marginTop: 9, background: '#fff' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '5px 8px', borderBottom: '1px solid #e7e9ec', background: '#f6f8fa', borderRadius: '8px 8px 0 0' }}>
            {([['write', 'Write'], ['preview', 'Preview']] as const).map(([key, label]) => (
              <span
                key={key}
                onClick={() => setSeg(key)}
                style={key === seg
                  ? { padding: '3px 10px', fontSize: 12, fontWeight: 600, color: '#17191c', background: '#fff', border: '1px solid #d0d7de', borderRadius: 6, cursor: 'pointer' }
                  : { padding: '3px 10px', fontSize: 12, color: '#59636e', cursor: 'pointer' }}
              >
                {label}
              </span>
            ))}
            <span style={{ flex: 1 }} />
            <span style={{ display: 'inline-flex', gap: 1, color: '#59636e' }}>
              <span className="pl-hov-ic" title="Not wired yet" style={{ width: 22, height: 22, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 5, fontSize: 11, fontWeight: 700 }}>H</span>
              <span className="pl-hov-ic" title="Not wired yet" style={{ width: 22, height: 22, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 5 }}><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M7 4h6a3.5 3.5 0 0 1 0 7H7z" /><path d="M7 11h7a3.5 3.5 0 0 1 0 7H7z" /></svg></span>
              <span className="pl-hov-ic" title="Not wired yet" style={{ width: 22, height: 22, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 5 }}><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M13 4h5" /><path d="M6 20h5" /><path d="M15 4 9 20" /></svg></span>
            </span>
          </div>
          {seg === 'write' ? (
            <textarea
              placeholder="Leave a comment"
              value={draft}
              onChange={e => setDraft(e.target.value)}
              onKeyDown={e => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); } }}
              style={{ display: 'block', width: '100%', minHeight: 64, border: 0, outline: 'none', resize: 'vertical', padding: '9px 11px', fontSize: 12.5, lineHeight: 1.55, color: '#1f2328', background: 'transparent', borderRadius: '0 0 8px 8px', fontFamily: 'inherit' }}
            />
          ) : draft.trim().length > 0 ? (
            <div style={{ minHeight: 64, padding: '9px 11px', fontSize: 12.5, lineHeight: 1.55, color: '#1f2328' }} dangerouslySetInnerHTML={{ __html: renderMarkdown(draft, repoCtx) }} />
          ) : (
            <div style={{ minHeight: 64, padding: '9px 11px', fontSize: 12.5, color: '#8b949e', fontStyle: 'italic' }}>Nothing to preview</div>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 9 }}>
          <span title="Not wired yet" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12, color: '#59636e', cursor: 'pointer' }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m21.4 11.05-8.5 8.5a5.5 5.5 0 0 1-7.78-7.78l8.5-8.5a3.67 3.67 0 0 1 5.19 5.19l-8.5 8.5a1.83 1.83 0 0 1-2.6-2.6l7.8-7.8" /></svg>
            Add Files
          </span>
          <span style={{ flex: 1 }} />
          <button
            className="pl-hov-btn"
            onClick={submit}
            disabled={draft.trim().length === 0 || busy || onComment === undefined}
            style={{ padding: '5px 12px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 7, fontSize: 12, fontWeight: 600, color: '#454c54', cursor: 'pointer' }}
          >
            {busy ? 'Sending…' : 'Comment'}
          </button>
          <AiReplyButton />
        </div>
      </div>
    </div>
  );
}
