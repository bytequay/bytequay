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
import { useGoogleHost } from './host';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Archive, ArrowUpRight, CalendarDays, ChevronDown, ChevronRight, ChevronUp, CircleDot, ExternalLink, GitMerge, GitPullRequest, Inbox, LoaderCircle, MailOpen, PenLine, RefreshCw, Reply,
  Search, ShieldCheck, Sparkles, Check } from 'lucide-react';
import { gmailLink, type GoogleMail as Mail, type GoogleSnapshot, type GoogleStatus } from '../contracts';
import { githubNotification, mailGroups, mailLinks, mailParagraphs, mailSender, mailSource, mailSubject, mailTime, mergedNotice, type MailBlock, type MailSource, type MailSubject } from './mailView';
import GoogleMailComposer from './GoogleMailComposer';
import { relativeTime } from '@bytequay/ui/relativeTime';

export type MailDraft = { reply: Mail | null; recipients: string[] };

const FILTERS = [
  { label: 'Inbox', query: 'in:inbox' },
  { label: 'Unread', query: 'in:inbox is:unread' },
  { label: 'GitHub', query: 'in:inbox from:notifications@github.com' },
];
const SOURCES = { github: 'GitHub', calendar: 'Calendar invite', google: 'Google account' };

function SourceIcon({ source, size }: { source: MailSource; size: number }) {
  if (source === 'calendar') return <CalendarDays size={size} aria-hidden="true" />;
  if (source === 'google') return <ShieldCheck size={size} aria-hidden="true" />;
  return <svg width={size} height={size} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M8 0a8 8 0 0 0-2.53 15.59c.4.07.55-.17.55-.38v-1.34c-2.23.48-2.7-1.07-2.7-1.07-.36-.93-.89-1.17-.89-1.17-.73-.5.05-.49.05-.49.8.06 1.23.83 1.23.83.71 1.22 1.87.87 2.33.66.07-.52.28-.87.5-1.07-1.77-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.6 7.6 0 0 1 4 0c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48v2.19c0 .21.15.46.55.38A8 8 0 0 0 8 0Z" /></svg>;
}

function Labels({ mail, source = null, open }: { mail: Mail; source?: MailSource; open?: (url: string) => void }) {
  const subject = mailSubject(mail.subject);
  const merged = subject.kind === 'PR' && mergedNotice(mail);
  const href = mailLinks(`#${subject.number}`, subject).find(part => part.href)?.href;
  const label = <>{merged ? <GitMerge size={11} aria-hidden="true" /> : subject.kind === 'PR' ? <GitPullRequest size={11} aria-hidden="true" /> : <CircleDot size={11} aria-hidden="true" />}
    {merged ? 'Merged ' : ''}#{subject.number}</>;
  if (!source && !subject.repository) return null;
  return <span className="google-labels">
    {source && <span className="google-label"><SourceIcon source={source} size={12} />{SOURCES[source]}</span>}
    {subject.repository && <span className="google-label is-mono">{subject.repository}</span>}
    {subject.kind && (open && href
      ? <button type="button" className={`google-label google-link ${merged ? 'is-merged' : 'is-open'}`} onClick={() => open(href)}>{label}</button>
      : <span className={`google-label ${merged ? 'is-merged' : 'is-open'}`}>{label}</span>)}
  </span>;
}

/** Mail text stays escaped; only validated GitHub destinations become links. */
function Block({ block, subject, open }: { block: MailBlock; subject: MailSubject; open: (url: string) => void }) {
  if ('lines' in block) return <div className="google-quote">
    {block.file && <div className="google-quote__file">{block.file}</div>}
    <div className="google-quote__lines" tabIndex={0} role="region" aria-label="Quoted code changes">{block.lines.map((line, index) => <div key={index} className={`is-${line.tone}`}><span>{line.sign}</span><code>{line.text || ' '}</code></div>)}</div>
  </div>;
  const linked = (value: string) => mailLinks(value, subject).map(({ text, href }, index) =>
    href ? <button type="button" key={index} className="google-link" onClick={() => open(href)}>{text}</button> : text);
  if ('items' in block) {
    const List = block.ordered ? 'ol' : 'ul';
    return <List start={block.start}>{block.items.map((item, index) => <li key={index}>{linked(item)}</li>)}</List>;
  }
  return <p>{linked(block.text)}</p>;
}

export default function GoogleMail({ status, data, busy, hidden, compose, onCompose, act, open, onAsk }: {
  status: GoogleStatus; data: GoogleSnapshot; busy: boolean; hidden: boolean; compose: MailDraft | null; onCompose: (draft: MailDraft | null) => void;
  act: (action: () => Promise<GoogleStatus>) => Promise<void>; open: (url: string) => void; onAsk: (mail: Mail) => void;
}) {
  const { bridge } = useGoogleHost();
  const [query, setQuery] = useState(data.query || 'in:inbox');
  const [selected, setSelected] = useState<string | null>(null);
  const [details, setDetails] = useState(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const [loadingReply, setLoadingReply] = useState(false);
  const [replyError, setReplyError] = useState<string | null>(null);
  const [archiving, setArchiving] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  useEffect(() => { if (data.query) setQuery(data.query); }, [data.query]);
  useEffect(() => {
    if (archiving && !status.operation) {
      if (!status.error) { setSelected(null); setToast('Archived'); }
      setArchiving(false);
    }
  }, [archiving, status.operation, status.error]);
  useEffect(() => {
    if (toast === null) return;
    const timer = window.setTimeout(() => setToast(null), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const composing = compose !== null && compose.reply === null;
  const message = composing ? undefined : compose?.reply ?? data.mail.find(item => item.id === selected) ?? data.mail[0];
  const replying = message !== undefined && compose?.reply?.id === message.id;
  const canReply = message !== undefined && compose === null && !busy && !loadingReply;
  const startReply = useCallback(async (reply: Mail) => {
    if (!canReply) return;
    setLoadingReply(true); setReplyError(null);
    try {
      const recipients = await bridge.getGoogleReplyRecipients(status.profile, reply.id);
      if (mounted.current) onCompose({ reply, recipients });
    }
    catch (cause) { if (mounted.current) setReplyError(cause instanceof Error ? cause.message : 'Could not read the reply address. Open Gmail to reply.'); }
    finally { if (mounted.current) setLoadingReply(false); }
  }, [canReply, bridge, status.profile, onCompose]);
  useEffect(() => {
    if (hidden || !canReply) return;
    const onKey = (event: KeyboardEvent) => {
      const target = event.target instanceof Element ? event.target : null;
      if (event.key !== 'r' || event.metaKey || event.ctrlKey || event.altKey || target?.closest('input, textarea, select, [contenteditable]')
        || !(target === document.body || target?.closest('.google-screen'))) return;
      event.preventDefault();
      void startReply(message);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [hidden, canReply, message, startReply]);

  const search = (next: string, pageToken = '') => { if (compose !== null || busy || loadingReply) return; setSelected(null); setQuery(next); void act(() => bridge.refreshGoogleMail(next, pageToken)); };
  const select = (id: string) => { if (compose !== null || loadingReply) return; setSelected(id); setDetails(false); };
  const position = message ? data.mail.findIndex(item => item.id === message.id) : -1;
  const sender = message ? mailSender(message.from) : null;
  const source = sender ? mailSource(sender.email) : null;
  const github = message && source === 'github' ? githubNotification(message) : null;
  const actor = github ? mailSender(github.actor.replace(/^@/, '')) : null;
  const subject = message ? mailSubject(message.subject) : null;
  const sentAt = message ? new Date(message.date) : null;
  const composer = (reply: Mail | null) => <GoogleMailComposer key={`${status.profile}:${reply?.id ?? 'new'}`} profile={status.profile} email={data.email} reply={reply} recipients={compose?.recipients ?? []} status={status} busy={busy} act={act}
    onClose={() => onCompose(null)} onSent={() => { onCompose(null); setToast(reply ? 'Reply sent' : 'Email sent'); }} />;

  return <div className="google-pane" hidden={hidden}>
    <form className="google-toolbar" onSubmit={event => { event.preventDefault(); search(query); }}>
      <label className="google-search"><Search size={15} aria-hidden="true" />
        <input aria-label="Search Gmail" disabled={compose !== null || loadingReply} value={query} maxLength={1000} placeholder="Search Gmail, e.g. from:someone newer_than:7d" onChange={event => setQuery(event.target.value)} /><kbd>⏎</kbd></label>
      <div className="google-segmented" role="group" aria-label="Mail filter">{FILTERS.map(filter =>
        <button type="button" key={filter.label} aria-pressed={query === filter.query} disabled={busy || compose !== null || loadingReply} onClick={() => search(filter.query)}>{filter.label}</button>)}</div>
      <button type="submit" className="google-square-btn" aria-label="Search / refresh" title="Search / refresh" disabled={busy || compose !== null || loadingReply}><RefreshCw size={14} /></button>
      <button type="button" className="google-primary-btn" disabled={busy || compose !== null || loadingReply} onClick={() => onCompose({ reply: null, recipients: [] })}><PenLine size={14} aria-hidden="true" />Compose</button>
    </form>
    {replyError && <p className="google-screen__error" role="alert">{replyError}</p>}
    {loadingReply && <p className="google-status" role="status">Reading reply address…</p>}
    <div className="google-mail">
      <div className="google-mail__side">
        <div className="google-status" role="status">{status.operation ? <><LoaderCircle className="pr-task-spinner" size={12} aria-hidden="true" /><span>{status.operation}…</span></>
          : data.mailSavedAt ? <><span className="google-dot" /><span>Updated {mailTime(data.mailSavedAt)} · {data.mail.length} messages · <code>{data.query || 'All mail'}</code></span></>
            : <span>No cached data yet. Refresh to load from Google.</span>}</div>
        <div className="google-mail__list" aria-label="Cached messages">
          {data.mail.length === 0 ? status.operation ? Array.from({ length: 6 }, (_, index) => <div key={index} className="google-skeleton" aria-hidden="true"><span /><div><span /><span /><span /></div></div>)
            : <div className="google-empty"><span className="google-empty__icon"><Inbox size={18} aria-hidden="true" /></span>
              <strong>{data.mailSavedAt ? 'No messages matched' : 'Nothing cached yet'}</strong>
              <span>{data.mailSavedAt ? 'No messages matched the current search.' : 'Refresh to load your first page of mail. Up to 25 messages are kept in memory until you quit.'}</span>
              {!data.mailSavedAt && <button type="button" className="setw-btn" disabled={busy || compose !== null || loadingReply} onClick={() => search('in:inbox')}><RefreshCw size={13} aria-hidden="true" />Load inbox</button>}
            </div>
            : mailGroups(data.mail).map(group => <section key={group.label}><h3>{group.label}</h3>{group.items.map(item => {
              const from = mailSender(item.from);
              const on = message?.id === item.id;
              return <button type="button" key={item.id} className={`google-mail__item${on ? ' is-selected' : ''}${item.unread ? ' is-unread' : ''}`} aria-pressed={on} disabled={compose !== null || loadingReply} onClick={() => select(item.id)}>
                <span className={`google-avatar is-tone-${from.tone}`} aria-hidden="true">{from.initials}{mailSource(from.email) === 'github' && <span className="google-avatar__badge"><SourceIcon source="github" size={10} /></span>}</span>
                <span className="google-mail__text">
                  <span className="google-mail__from"><span>{from.name}</span><time>{mailTime(item.date)}</time>{item.unread && <span aria-label="Unread" className="google-mail__unread" />}</span>
                  <span className="google-mail__subject">{mailSubject(item.subject).title}</span>
                  <span className="google-mail__snippet">{item.snippet}</span>
                  <Labels mail={item} />
                </span>
              </button>;
            })}</section>)}
        </div>
        <footer className="google-mail__foot"><span>25 per page · Gemini can search beyond it</span>
          {data.nextPageToken && <button type="button" className="setw-btn" disabled={busy || compose !== null || loadingReply || query !== data.query} onClick={() => search(data.query, data.nextPageToken)}>Next page<ChevronRight size={13} aria-hidden="true" /></button>}</footer>
      </div>
      <article className="google-reader">
        {composing ? composer(null) : !message || !sender || !subject ? <div className="google-reader__none"><MailOpen size={22} aria-hidden="true" />Select a message to read it.</div> : <>
          <div className="google-reader__bar">
            <button type="button" className="setw-btn" disabled={!canReply} onClick={() => { void startReply(message); }}><Reply size={14} aria-hidden="true" />Reply</button>
            <button type="button" className="setw-btn" disabled={busy || compose !== null || loadingReply} onClick={() => { void act(async () => { const next = await bridge.archiveGoogleMail(status.profile, message.id); setArchiving(true); return next; }); }}><Archive size={14} aria-hidden="true" />Archive</button>
            <button type="button" className="setw-btn google-ask" onClick={() => onAsk(message)}><Sparkles size={14} aria-hidden="true" />Ask Gemini</button>
            <span className="google-spacer" />
            <button type="button" className="google-quiet-btn" onClick={() => open(gmailLink(message, data.email))}>Open Gmail<ExternalLink size={13} aria-hidden="true" /></button>
            <button type="button" className="google-icon-btn" aria-label="Newer" title="Newer" disabled={compose !== null || loadingReply || position <= 0} onClick={() => select(data.mail[position - 1].id)}><ChevronUp size={15} /></button>
            <button type="button" className="google-icon-btn" aria-label="Older" title="Older" disabled={compose !== null || loadingReply || position < 0 || position >= data.mail.length - 1} onClick={() => select(data.mail[position + 1].id)}><ChevronDown size={15} /></button>
          </div>
          <div className="google-reader__body">
            <Labels mail={message} source={source} open={open} />
            <h2>{subject.title}</h2>
            <div className="google-reader__from">
              <span className={`google-avatar is-large is-tone-${sender.tone}`} aria-hidden="true">{sender.initials}</span>
              <div><div><strong>{sender.name}</strong>{sender.name !== sender.email && <span>{sender.email}</span>}</div>
                <button type="button" aria-expanded={details} onClick={() => setDetails(!details)}>to {message.to.replace(/\s*<.*>/, '').replace(/"/g, '') || 'me'}{details ? <ChevronUp size={12} /> : <ChevronDown size={12} />}</button></div>
              <div className="google-reader__when">{sentAt && !Number.isNaN(sentAt.getTime()) && <><time>{mailTime(message.date)}</time><span>{relativeTime(sentAt.getTime(), { style: 'long' })}</span></>}</div>
            </div>
            {details && <dl className="google-reader__details"><dt>From</dt><dd>{message.from}</dd><dt>To</dt><dd>{message.to}</dd><dt>Date</dt><dd>{message.date}</dd><dt>Message ID</dt><dd><code>{github?.messageId || message.id}</code></dd></dl>}
            {github && actor ? <div className="google-notice">
              <div className="google-notice__head"><span className={`google-avatar is-small is-tone-${actor.tone}`} aria-hidden="true">{actor.initials}</span><strong>{github.actor}</strong><span>{github.action}</span></div>
              <div className="google-notice__body google-mail-text">{github.blocks.map((block, index) => <Block key={index} block={block} subject={subject} open={open} />)}</div>
              {(github.reason || github.link) && <div className="google-notice__foot"><span>{github.reason}</span>
                {github.link && <button type="button" className="google-link" onClick={() => open(github.link ?? '')}>View on GitHub<ArrowUpRight size={13} aria-hidden="true" /></button>}</div>}
            </div> : <div className="google-mail-text">{mailParagraphs(message).map((text, index) => <Block key={index} block={{ text }} subject={subject} open={open} />)}</div>}
            {replying ? composer(message) : compose === null && <button type="button" className="google-quick-reply" disabled={!canReply} onClick={() => { void startReply(message); }}>
              <Reply size={15} aria-hidden="true" /><span>Reply to {sender.name}…</span><kbd>R</kbd></button>}
          </div>
        </>}
        {toast !== null && <div className="google-toast" role="status"><Check size={15} aria-hidden="true" />{toast}</div>}
      </article>
    </div>
  </div>;
}
