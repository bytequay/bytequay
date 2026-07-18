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
import { useEffect, useState } from 'react';
import CurrentUserAvatar from '../CurrentUserAvatar';
import { renderMarkdown, type MarkdownRepoContext } from '../markdown';
import { relativeTime } from '../notificationDisplay';
import type { ReviewThreadDto } from '../types';
import { assocLabel } from './changesModel';
import { Av } from './atoms';

/**
 * The two comment rows injected into a diff card's code table, transcribed
 * from the DC prototype (Pull Requests.dc.html): the inline composer row
 * (isComposer) and the GitHub review-thread row (isThread). Data actions
 * reuse the exact bridge calls InlineReviewThread makes (replyToReviewThread /
 * setReviewThreadResolved); only the markup is the prototype's.
 */

const SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif";
const chipStyle = { fontSize: 10.5, fontWeight: 500, color: '#59636e', border: '1px solid #d5dbe1', borderRadius: 999, padding: '1px 8px' } as const;
const grayBtnStyle = { padding: '5px 12px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 7, fontSize: 12, fontWeight: 600, color: '#454c54', cursor: 'pointer' } as const;
const toolIcon = { width: 24, height: 24, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 6 } as const;

/** The "smiley +" add-reaction pill; the reactions write path isn't wired. */
export function EmojiPlusPill() {
  return (
    <span className="pl-hov-btn" title="Not wired yet" style={{ display: 'inline-flex', alignItems: 'center', gap: 2, width: 32, height: 23, border: '1px solid #d5dbe1', borderRadius: 999, justifyContent: 'center', color: '#59636e', cursor: 'pointer', fontSize: 10 }}>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9" /><path d="M8.5 14.5a4.2 4.2 0 0 0 7 0" /><path d="M9 9.5h.01" /><path d="M15 9.5h.01" /></svg>
      +
    </span>
  );
}

export function AiReplyButton() {
  return (
    <button className="pl-hov-btn" title="Not wired yet" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '5px 11px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 7, fontSize: 12, fontWeight: 600, color: '#1f2328', cursor: 'pointer' }}>
      <span style={{ width: 15, height: 15, borderRadius: 4, background: 'linear-gradient(135deg,#ec4899,#a855f7)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#fff' }}>
        <svg width="9" height="9" viewBox="0 0 24 24" fill="currentColor"><path d="M12 3l1.9 5.6 5.6 1.9-5.6 1.9L12 18l-1.9-5.6L4.5 10.5l5.6-1.9z" /></svg>
      </span>
      AI Reply<span style={{ color: '#8b949e', fontWeight: 700, marginLeft: 2 }}>⋮</span>
    </button>
  );
}

/** Prototype composer row — "Add a comment on line R17". Submits through the
 *  local-draft path (addLocalPrComment) the parent wires in. */
export function InlineComposerRow({ label, repoCtx, onSubmit, onCancel }: {
  label: string;
  repoCtx: MarkdownRepoContext;
  onSubmit: (body: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [seg, setSeg] = useState<'write' | 'preview'>('write');
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const disabled = draft.trim().length === 0 || busy;
  const submit = () => {
    if (disabled) return;
    setBusy(true);
    onSubmit(draft.trim()).catch(() => { /* poll reconciles; keep the draft */ }).finally(() => setBusy(false));
  };
  return (
    <tr>
      <td className="ln" /><td className="ln" />
      <td style={{ padding: '8px 14px', background: '#fff', whiteSpace: 'normal', lineHeight: 1.5 }}>
        <div style={{ fontFamily: SANS, whiteSpace: 'normal', lineHeight: 1.5, border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', boxShadow: '0 4px 12px rgba(0,0,0,0.06)', maxWidth: 640 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px 8px' }}>
            <CurrentUserAvatar size={24} />
            <span style={{ fontSize: 13, fontWeight: 600, color: '#17191c' }}>Add a comment on line{label.includes(' to ') ? 's' : ''} {label}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '0 10px', borderBottom: '1px solid #e7e9ec' }}>
            <span onClick={() => setSeg('write')} style={{ padding: '6px 12px', fontSize: 12.5, fontWeight: seg === 'write' ? 600 : 500, color: seg === 'write' ? '#17191c' : '#59636e', borderBottom: `2px solid ${seg === 'write' ? '#c2632a' : 'transparent'}`, cursor: 'pointer' }}>Write</span>
            <span onClick={() => setSeg('preview')} style={{ padding: '6px 12px', fontSize: 12.5, fontWeight: seg === 'preview' ? 600 : 500, color: seg === 'preview' ? '#17191c' : '#59636e', borderBottom: `2px solid ${seg === 'preview' ? '#c2632a' : 'transparent'}`, cursor: 'pointer' }}>Preview</span>
            <span style={{ flex: 1 }} />
            <span style={{ display: 'inline-flex', gap: 1, color: '#59636e' }}>
              <span className="pl-hov-ic" title="Not wired yet" style={{ ...toolIcon, fontSize: 12, fontWeight: 700 }}>H</span>
              <span className="pl-hov-ic" title="Not wired yet" style={toolIcon}><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M7 4h6a3.5 3.5 0 0 1 0 7H7z" /><path d="M7 11h7a3.5 3.5 0 0 1 0 7H7z" /></svg></span>
              <span className="pl-hov-ic" title="Not wired yet" style={toolIcon}><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M13 4h5" /><path d="M6 20h5" /><path d="M15 4 9 20" /></svg></span>
              <span className="pl-hov-ic" title="Not wired yet" style={toolIcon}><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M8 6h13" /><path d="M8 12h13" /><path d="M8 18h13" /><path d="M3 6h.01" /><path d="M3 12h.01" /><path d="M3 18h.01" /></svg></span>
              <span className="pl-hov-ic" title="Not wired yet" style={toolIcon}><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" /><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></svg></span>
            </span>
          </div>
          {seg === 'write' ? (
            <textarea
              placeholder="Leave a comment"
              value={draft}
              autoFocus
              onChange={e => setDraft(e.target.value)}
              onKeyDown={e => {
                if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
                if (e.key === 'Escape') onCancel();
              }}
              style={{ display: 'block', width: '100%', minHeight: 96, border: 0, outline: 'none', resize: 'vertical', padding: '11px 14px', fontSize: 13, lineHeight: 1.6, color: '#1f2328', background: 'transparent', fontFamily: 'inherit' }}
            />
          ) : draft.trim().length > 0 ? (
            <div
              style={{ minHeight: 96, padding: '11px 14px', fontSize: 13, lineHeight: 1.6, color: '#1f2328' }}
              dangerouslySetInnerHTML={{ __html: renderMarkdown(draft, repoCtx) }}
            />
          ) : (
            <div style={{ minHeight: 96, padding: '11px 14px', fontSize: 13, color: '#8b949e', fontStyle: 'italic' }}>Nothing to preview</div>
          )}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px', borderTop: '1px solid #e7e9ec' }}>
            <span title="Not wired yet" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#59636e', cursor: 'pointer' }}>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m21.4 11.05-8.5 8.5a5.5 5.5 0 0 1-7.78-7.78l8.5-8.5a3.67 3.67 0 0 1 5.19 5.19l-8.5 8.5a1.83 1.83 0 0 1-2.6-2.6l7.8-7.8" /></svg>
              Paste, drop, or click to add files
            </span>
            <span style={{ flex: 1 }} />
            <button className="pl-hov-btn" onClick={onCancel} style={grayBtnStyle}>Cancel</button>
            <button
              onClick={submit}
              disabled={disabled}
              style={{ padding: '5px 13px', border: '1px solid #d5dbe1', background: '#eff2f5', borderRadius: 7, fontSize: 12, fontWeight: 600, color: disabled ? '#8b949e' : '#1f2328', cursor: disabled ? 'default' : 'pointer' }}
            >
              {busy ? 'Adding…' : 'Add review comment'}
            </button>
            <AiReplyButton />
          </div>
        </div>
      </td>
    </tr>
  );
}

/** Prototype resolved-thread row — a live GitHub review thread under its
 *  anchor line. Reply / resolve / unresolve reuse the InlineReviewThread
 *  bridge calls verbatim. */
export function InlineThreadRow({ thread, prAuthor, repo, prId, prNumber, repoCtx, onChanged }: {
  thread: ReviewThreadDto;
  prAuthor: string | null;
  repo: string;
  prId: number;
  prNumber: number;
  repoCtx: MarkdownRepoContext;
  onChanged: () => void;
}) {
  const [resolvedLocal, setResolvedLocal] = useState<boolean | null>(thread.resolved ?? null);
  const [foldOverride, setFoldOverride] = useState<boolean | null>(null);
  const [replying, setReplying] = useState(false);
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setResolvedLocal(thread.resolved ?? null); }, [thread.resolved]);
  const resolved = resolvedLocal === true;
  const open = !(foldOverride ?? resolved);

  const setResolved = async (next: boolean) => {
    if (busy) return;
    setBusy(true);
    setResolvedLocal(next);
    try {
      await window.bridge.setReviewThreadResolved(repo, prId, thread.rootGithubId, next);
      onChanged();
    }
    catch (e) {
      setResolvedLocal(!next);
      setError(e instanceof Error ? e.message : String(e));
    }
    finally { setBusy(false); }
  };
  const sendReply = async () => {
    const body = reply.trim();
    if (body.length === 0 || busy) return;
    setBusy(true);
    try {
      await window.bridge.replyToReviewThread(repo, prNumber, thread.rootGithubId, body);
      setReply('');
      setReplying(false);
      onChanged();
    }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  const sideLetter = (s: string | null) => (s === 'LEFT' ? 'L' : 'R');
  return (
    <tr>
      <td className="ln" /><td className="ln" />
      <td style={{ padding: '5px 14px', background: resolved ? '#f2fbf4' : '#fff', whiteSpace: 'normal', lineHeight: 1.5 }}>
        <div style={{ fontFamily: SANS, whiteSpace: 'normal', lineHeight: 1.5, border: '1px solid #d5dbe1', borderRadius: 10, maxWidth: 640, background: '#fff', overflow: 'hidden' }}>
          <div className="pl-hov-btn" onClick={() => setFoldOverride(open)} style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '4px 10px', cursor: 'pointer' }}>
            <span style={{ display: 'inline-flex', color: '#8b949e', flexShrink: 0, transform: open ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.15s' }}>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /></svg>
            </span>
            <span style={{ fontSize: 12.5, color: '#1f2328' }}>
              Comment on line <b>{thread.line === null ? '?' : `${sideLetter(thread.side)}${thread.line}`}</b>
            </span>
            <span style={{ flex: 1 }} />
            {resolved && <span style={{ fontSize: 11, fontWeight: 600, color: '#57606a', border: '1px solid #d5dbe1', borderRadius: 999, padding: '1px 9px' }}>Resolved</span>}
            {resolved && (
              <span
                className="pl-hov-btn"
                title="Undo resolve"
                onClick={e => { e.stopPropagation(); void setResolved(false); }}
                style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 22, height: 22, color: '#57606a', cursor: 'pointer', border: '1px solid #d5dbe1', borderRadius: 6, flexShrink: 0 }}
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 14 4 9l5-5" /><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" /></svg>
              </span>
            )}
          </div>
          {open && (
            <>
              {thread.messages.map((msg, i) => (
                <div key={msg.githubId} style={{ borderTop: i === 0 ? '1px solid #e7e9ec' : undefined, padding: '12px 14px 2px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Av login={msg.author ?? ''} size={24} />
                    <span style={{ fontSize: 12.5, fontWeight: 600, color: '#17191c' }}>{msg.author ?? 'unknown'}</span>
                    <span style={{ fontSize: 11.5, color: '#8b949e' }}>{msg.createdAt !== null ? relativeTime(msg.createdAt) : ''}</span>
                    <span style={{ flex: 1 }} />
                    {assocLabel(msg.authorAssociation) !== null && <span style={chipStyle}>{assocLabel(msg.authorAssociation)}</span>}
                    {msg.author !== null && msg.author === prAuthor && <span style={chipStyle}>Author</span>}
                    <span title="Not wired yet" style={{ color: '#8b949e', cursor: 'pointer', letterSpacing: 1, fontWeight: 700 }}>···</span>
                  </div>
                  <div
                    style={{ fontSize: 13, color: '#1f2328', lineHeight: 1.7, marginTop: 7, paddingLeft: 32 }}
                    dangerouslySetInnerHTML={{ __html: renderMarkdown(msg.body, repoCtx) }}
                  />
                  <div style={{ paddingLeft: 32, marginTop: 7 }}><EmojiPlusPill /></div>
                </div>
              ))}
              <div style={{ padding: '12px 14px 0' }}>
                {replying ? (
                  <>
                    <textarea
                      autoFocus
                      value={reply}
                      placeholder="Write a reply"
                      onChange={e => setReply(e.target.value)}
                      onKeyDown={e => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); void sendReply(); } }}
                      style={{ display: 'block', width: '100%', minHeight: 56, border: '1px solid #d5dbe1', borderRadius: 7, padding: '7px 12px', fontSize: 12.5, lineHeight: 1.55, color: '#1f2328', background: '#fff', outline: 'none', resize: 'vertical', fontFamily: 'inherit' }}
                    />
                    <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                      <button className="pl-hov-btn" onClick={() => void sendReply()} disabled={busy || reply.trim().length === 0} style={grayBtnStyle}>{busy ? 'Sending…' : 'Reply'}</button>
                      <button className="pl-hov-btn" onClick={() => { setReplying(false); setReply(''); setError(null); }} style={grayBtnStyle}>Cancel</button>
                    </div>
                  </>
                ) : (
                  <div onClick={() => setReplying(true)} style={{ border: '1px solid #d5dbe1', borderRadius: 7, padding: '7px 12px', fontSize: 12.5, color: '#8b949e', cursor: 'text', background: '#fff' }}>Write a reply</div>
                )}
              </div>
              {error !== null && <div style={{ padding: '8px 14px 0', fontSize: 12, color: '#cf222e' }}>{error}</div>}
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px 12px' }}>
                {resolvedLocal !== null && (
                  <button className="pl-hov-btn" onClick={() => void setResolved(!resolved)} disabled={busy} style={{ ...grayBtnStyle, flexShrink: 0 }}>
                    {resolved ? 'Unresolve comment' : 'Resolve comment'}
                  </button>
                )}
                {resolved && thread.resolvedBy != null && thread.resolvedBy.length > 0 && (
                  <span style={{ fontSize: 12, color: '#59636e' }}><b style={{ color: '#17191c', fontWeight: 600 }}>{thread.resolvedBy}</b> marked this comment as resolved</span>
                )}
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
}
