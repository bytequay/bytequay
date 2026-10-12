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
import { CalendarDays, ChevronDown, Mail, Settings, UserPlus } from 'lucide-react';
import { eventTime, type GoogleEvent, type GoogleMail as GoogleMessage } from './contracts';
import { useGoogle } from './useGoogle';
import { settingsRoute } from '../settings/routes';
import GoogleChat, { type ChatContext } from './GoogleChat';
import GoogleMail, { type MailDraft } from './GoogleMail';
import GoogleCalendar from './GoogleCalendar';
import '../css/pulls.css';
import '../css/agents.css';
import './google.css';

export default function GoogleScreen() {
  const { status, error, busy, act, reload } = useGoogle();
  const [tab, setTab] = useState<'mail' | 'calendar'>('mail');
  const [compose, setCompose] = useState<MailDraft | null>(null);
  const [context, setContext] = useState<ChatContext | null>(null);
  const [linkError, setLinkError] = useState<string | null>(null);
  const data = status?.snapshot;
  useEffect(() => { setCompose(null); setContext(null); }, [status?.profile]);
  const open = (url: string) => { setLinkError(null); void window.bridge.openExternal(url).catch(cause => setLinkError(cause instanceof Error ? cause.message : 'Unable to open Google.')); };
  const unread = data?.mail.filter(item => item.unread).length ?? 0;
  const askMail = (mail: GoogleMessage) => setContext({ kind: 'mail', label: mail.subject || '(No subject)',
    detail: `About the email “${mail.subject || '(No subject)'}” from ${mail.from} (Gmail message ID ${mail.id}):` });
  const askEvent = (event: GoogleEvent) => setContext({ kind: 'event', label: event.summary || 'Untitled event',
    detail: `About the calendar event “${event.summary || 'Untitled event'}” (${eventTime(event)}, event ID ${event.id}):` });
  return <section className="google-screen">
    <header className="google-screen__header"><div><h1>Google Apps</h1><p>{data?.email || 'Your mail and calendar, kept close to your work.'}</p></div><div className="google-screen__accounts">
      {status && data && <><label className="google-select"><span className="google-avatar is-small is-tone-0" aria-hidden="true">{data.email.slice(0, 1).toUpperCase()}</span>
        <select aria-label="Google account" value={status.profile} disabled={busy || compose !== null} onChange={event => { void act(() => window.bridge.selectGoogleAccount(event.target.value)); }}>
          {status.accounts.map(account => <option key={account.profile} value={account.profile}>{account.email}</option>)}</select><ChevronDown size={13} aria-hidden="true" /></label>
      <button type="button" className="setw-btn" disabled={busy || compose !== null} onClick={() => { void act(window.bridge.addGoogleAccount); }}><UserPlus size={14} aria-hidden="true" />Add account</button></>}
      <a className="setw-btn" href={settingsRoute('google')}><Settings size={14} aria-hidden="true" />Connection settings</a></div></header>
    {(error || linkError) && <div className="google-screen__error" role="alert">{error || linkError} <button type="button" onClick={() => { void reload(); }}>Check again</button></div>}
    {!status ? <p className="google-screen__loading" role="status">Loading Google Apps…</p> : !data ? <div className="google-screen__empty"><span><Mail size={20} aria-hidden="true" /></span><h2>Connect your Google account</h2>
      <p>Read Gmail and view your agenda here. Mail and events are cached in memory until you quit.</p><a className="google-primary-btn" href={settingsRoute('google')}>Set up Google Apps</a></div>
      : <div className="google-screen__workspace"><div className="google-screen__content">
        <nav className="google-screen__tabs" aria-label="Google apps">
          <button type="button" aria-pressed={tab === 'mail'} onClick={() => setTab('mail')}><Mail size={16} aria-hidden="true" />Gmail{unread > 0 && <span aria-label={`${unread} unread`}>{unread}</span>}</button>
          <button type="button" aria-pressed={tab === 'calendar'} onClick={() => setTab('calendar')}><CalendarDays size={16} aria-hidden="true" />Calendar</button></nav>
        <GoogleMail key={`mail:${status.profile}`} status={status} data={data} busy={busy} hidden={tab !== 'mail'} compose={compose} onCompose={setCompose} act={act} open={open} onAsk={askMail} />
        <GoogleCalendar key={`calendar:${status.profile}`} status={status} data={data} busy={busy} hidden={tab !== 'calendar'} act={act} open={open} onAsk={askEvent} />
      </div><GoogleChat key={status.profile} profile={status.profile} email={data.email} context={context} onContext={setContext} /></div>}
  </section>;
}
