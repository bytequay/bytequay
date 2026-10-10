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
import { CalendarDays, ExternalLink, Mail, RefreshCw, Search, Settings } from 'lucide-react';
import { agendaRange, gmailLink, localDate } from './contracts';
import { useGoogle } from './useGoogle';
import { settingsRoute } from '../settings/routes';
import GoogleChat from './GoogleChat';
import GoogleMailComposer from './GoogleMailComposer';
import GoogleCalendar from './GoogleCalendar';
import type { GoogleMail } from './contracts';
import '../css/markdown-editor.css';
import '../css/pulls.css';
import '../css/agent-review.css';
import './google.css';

export default function GoogleScreen() {
  const { status, error, busy, act, reload } = useGoogle();
  const [tab, setTab] = useState<'mail' | 'calendar'>('mail');
  const [days, setDays] = useState(7);
  const [compose, setCompose] = useState<{ reply: GoogleMail | null } | null>(null);
  const [query, setQuery] = useState('in:inbox');
  const [calendar, setCalendar] = useState('primary');
  const [date, setDate] = useState(localDate);
  const [selected, setSelected] = useState<string | null>(null);
  const [linkError, setLinkError] = useState<string | null>(null);
  const data = status?.snapshot;
  const message = data?.mail.find(item => item.id === selected) ?? data?.mail[0];
  useEffect(() => { setCompose(null); setSelected(null); }, [status?.profile]);
  const savedQuery = data?.query;
  const savedCalendar = data?.calendarId;
  useEffect(() => { if (savedQuery !== undefined) setQuery(savedQuery); }, [data?.accountId, savedQuery]);
  useEffect(() => { if (savedCalendar !== undefined) setCalendar(savedCalendar); }, [data?.accountId, savedCalendar]);
  const open = (url: string) => { setLinkError(null); void window.bridge.openExternal(url).catch(cause => setLinkError(cause instanceof Error ? cause.message : 'Unable to open Google.')); };
  const savedAt = tab === 'mail' ? data?.mailSavedAt : data?.calendarSavedAt;
  return <section className="google-screen">
    <header className="google-screen__header"><div><h1>Google Apps</h1><p>{data?.email || 'Your mail and calendar, kept close to your work.'}</p></div><div className="google-screen__accounts">{status && <><select aria-label="Google account" value={status.profile} disabled={busy || compose !== null} onChange={event => { void act(() => window.bridge.selectGoogleAccount(event.target.value)); }}>{status.accounts.map(account => <option key={account.profile} value={account.profile}>{account.email}</option>)}</select><button type="button" className="setw-btn" disabled={busy || compose !== null} onClick={() => { void act(window.bridge.addGoogleAccount); }}>Add account</button></>}<a className="setw-btn" href={settingsRoute('google')}><Settings size={15} />Connection settings</a></div></header>
    {(error || linkError) && <div className="google-screen__error" role="alert">{error || linkError} <button type="button" onClick={() => { void reload(); }}>Check again</button></div>}
    {!status ? <p role="status">Loading Google Apps…</p> : !data ? <div className="google-screen__empty"><Mail size={32} /><h2>Connect your Google account</h2><p>Read Gmail and view your agenda here. Mail and events are cached in memory until you quit.</p><a className="setw-btn-primary" href={settingsRoute('google')}>Set up Google Apps</a></div> : <>
      <div className="google-screen__workspace"><div className="google-screen__content"><nav className="google-screen__tabs" aria-label="Google apps"><button type="button" aria-pressed={tab === 'mail'} onClick={() => setTab('mail')}><Mail size={16} />Gmail</button><button type="button" aria-pressed={tab === 'calendar'} onClick={() => setTab('calendar')}><CalendarDays size={16} />Calendar</button></nav>
      <div className="google-screen__toolbar">
        {tab === 'mail' ? <form onSubmit={event => { event.preventDefault(); void act(() => window.bridge.refreshGoogleMail(query, '')); }}><Search size={16} /><input aria-label="Search Gmail" value={query} maxLength={1000} placeholder="Search Gmail, e.g. from:someone newer_than:7d" onChange={event => setQuery(event.target.value)} /><button type="submit" className="setw-btn" disabled={busy}><RefreshCw size={14} />Search / refresh</button><button type="button" className="setw-btn" disabled={busy || compose !== null} onClick={() => setCompose({ reply: null })}>Compose</button></form>
          : <form onSubmit={event => { event.preventDefault(); const range = agendaRange(date, days); void act(() => window.bridge.refreshGoogleCalendar(calendar, range.timeMin, range.timeMax)); }}><select aria-label="Calendar" value={calendar} onChange={event => setCalendar(event.target.value)}><option value="primary">Primary calendar</option>{data.calendars.filter(item => item.id !== 'primary').map(item => <option key={item.id} value={item.id}>{item.summary || item.id}</option>)}</select><select aria-label="Calendar view" value={days} onChange={event => setDays(Number(event.target.value))}><option value={1}>Day</option><option value={7}>Week</option></select><label>Starting <input type="date" required aria-label="Agenda start date" value={date} onChange={event => setDate(event.target.value)} /></label><button type="submit" className="setw-btn" disabled={busy || !date}><RefreshCw size={14} />Refresh agenda</button></form>}
      </div>
      <div className="google-screen__coverage" role="status">{status.operation ? `${status.operation}…` : savedAt ? `Updated ${new Date(savedAt).toLocaleString()}` : 'No cached data yet. Refresh to load from Google.'}{tab === 'mail' && data.mailSavedAt && ` · ${data.mail.length} messages · ${data.query || 'All mail'}`}</div>
      {compose && <GoogleMailComposer key={`${status.profile}:${compose.reply?.id ?? 'new'}`} profile={status.profile} email={data.email} reply={compose.reply} status={status} busy={busy} act={act} onClose={() => setCompose(null)} />}
      {tab === 'mail' ? <>
        <div className="google-screen__mail"><div className="google-screen__list" aria-label="Cached messages">{data.mail.length === 0 ? <p className="google-screen__placeholder">{data.mailSavedAt ? 'No messages matched the current search.' : 'Search or refresh to load your first inbox page.'}</p> : data.mail.map(item => <button type="button" key={item.id} className={message?.id === item.id ? 'is-selected' : ''} aria-pressed={message?.id === item.id} onClick={() => setSelected(item.id)}><span className="google-screen__sender">{item.unread && <span aria-label="Unread" className="google-screen__unread" />}{item.from}</span><strong>{item.subject || '(No subject)'}</strong><span>{item.snippet}</span><small>{item.date}</small></button>)}</div>
          <article className="google-screen__message">{message ? <><div className="google-screen__message-heading"><h2>{message.subject || '(No subject)'}</h2><button type="button" className="setw-btn" onClick={() => open(gmailLink(message, data.email))}>Open Gmail <ExternalLink size={13} /></button><button type="button" className="setw-btn" disabled={busy || compose !== null} onClick={() => setCompose({ reply: message })}>Reply</button><button type="button" className="setw-btn" disabled={busy} onClick={() => { void act(() => window.bridge.archiveGoogleMail(status.profile, message.id)); }}>Archive</button></div><dl><dt>From</dt><dd>{message.from}</dd><dt>To</dt><dd>{message.to}</dd><dt>Date</dt><dd>{message.date}</dd></dl><pre>{message.body || message.snippet}</pre></> : <p className="google-screen__placeholder">Select a message to read it.</p>}</article></div>
        <footer className="google-screen__footer"><span>Up to 25 cached messages per page. Gemini can search beyond this page.</span>{data.nextPageToken && <button type="button" className="setw-btn" disabled={busy || query !== data.query} onClick={() => { void act(() => window.bridge.refreshGoogleMail(data.query, data.nextPageToken)); }}>Next page</button>}</footer>
      </> : <><p className="google-screen__coverage">{data.timeMin ? `Cached range: ${new Date(data.timeMin).toLocaleDateString()} – ${new Date(data.timeMax).toLocaleDateString()}. Refresh to load the selected view.` : 'Refresh to load the selected view.'}</p><GoogleCalendar data={data} date={date} days={days} open={open} /></>}
      </div><GoogleChat key={status.profile} profile={status.profile} email={data.email} /></div>
    </>}
  </section>;
}
