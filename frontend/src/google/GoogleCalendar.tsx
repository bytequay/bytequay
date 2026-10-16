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
import { clock, shortDate } from '../displayText';
import { useLayoutEffect, useRef, useState } from 'react';
import { Calendar, CalendarClock, ChevronDown, ChevronLeft, ChevronRight, Clock, ExternalLink, LoaderCircle, RefreshCw, Sparkles, X } from 'lucide-react';
import type { GoogleEvent, GoogleSnapshot, GoogleStatus } from './contracts';
import { agendaRange, dayLayout, eventTime, googleLink, localDate } from './contracts';

const HOUR = 46;
const iso = (value: Date) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;

export function calendarDays(date: string, days: number): string[] {
  return Array.from({ length: days }, (_, index) => {
    const value = new Date(`${date}T12:00:00`); value.setDate(value.getDate() + index);
    return iso(value);
  });
}

export function rangeLabel(date: string, days: number): string {
  const start = new Date(`${date}T12:00:00`);
  if (days === 1) return start.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
  const end = new Date(start); end.setDate(end.getDate() + days - 1);
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).formatRange(start, end);
}

/** A day or week time grid over the cached range; changing the view or the dates never fetches. */
export default function GoogleCalendar({ status, data, busy, hidden, act, open, onAsk }: {
  status: GoogleStatus; data: GoogleSnapshot; busy: boolean; hidden: boolean;
  act: (action: () => Promise<GoogleStatus>) => Promise<void>; open: (url: string) => void; onAsk: (event: GoogleEvent) => void;
}) {
  const [calendar, setCalendar] = useState(data.calendarId || 'primary');
  const [date, setDate] = useState(localDate);
  const [days, setDays] = useState(7);
  const [picked, setPicked] = useState<string | null>(null);
  const grid = useRef<HTMLDivElement>(null);
  const scrolled = useRef(false);
  // The working day opens in view; a hidden pane cannot scroll, so this waits for the first showing.
  useLayoutEffect(() => {
    if (hidden || scrolled.current || !grid.current) return;
    grid.current.scrollTop = 8 * HOUR - 12;
    scrolled.current = true;
  }, [hidden]);

  const today = localDate();
  const now = new Date();
  const range = agendaRange(date, days);
  const cached = Boolean(data.timeMin) && data.calendarId === calendar && new Date(data.timeMin) <= new Date(range.timeMin) && new Date(data.timeMax) >= new Date(range.timeMax);
  // Steps from the latest date, so quick repeated clicks each count.
  const move = (offset: number) => { setDate(current => { const value = new Date(`${current}T12:00:00`); value.setDate(value.getDate() + offset); return iso(value); }); setPicked(null); };
  const refresh = () => { void act(() => window.bridge.refreshGoogleCalendar(calendar, range.timeMin, range.timeMax)); };
  const columns = { gridTemplateColumns: `52px repeat(${days}, minmax(0, 1fr))` };
  const week = calendarDays(date, days).map(day => {
    const start = new Date(`${day}T00:00:00`); const end = new Date(start); end.setDate(end.getDate() + 1);
    const span = (end.getTime() - start.getTime()) / 3_600_000;
    const timed = cached ? data.events.filter(event => !event.allDay && new Date(event.start) < end && new Date(event.end) > start).map(event => ({
      event, start: Math.max(0, (new Date(event.start).getTime() - start.getTime()) / 3_600_000), end: Math.min(span, (new Date(event.end).getTime() - start.getTime()) / 3_600_000),
    })) : [];
    const layout = dayLayout(timed);
    return { day, date: new Date(`${day}T12:00:00`), allDay: cached ? data.events.filter(event => event.allDay && event.start <= day && event.end > day) : [],
      timed: timed.map((item, index) => ({ ...item, ...layout[index] })) };
  });
  const event = cached ? data.events.find(item => item.id === picked) : undefined;
  const link = event ? googleLink(event.url) : null;
  const lastCached = new Date(data.timeMax); lastCached.setDate(lastCached.getDate() - 1);

  return <div className="google-pane" hidden={hidden}>
    <div className="google-toolbar">
      <button type="button" className="setw-btn" onClick={() => { setDate(today); setPicked(null); }}>Today</button>
      <span className="google-cal__nav"><button type="button" className="google-icon-btn" aria-label="Previous" title="Previous" onClick={() => move(-days)}><ChevronLeft size={16} /></button>
        <button type="button" className="google-icon-btn" aria-label="Next" title="Next" onClick={() => move(days)}><ChevronRight size={16} /></button></span>
      <strong className="google-cal__range">{rangeLabel(date, days)}</strong>
      <span className="google-spacer" />
      <label className="google-select"><span className="google-select__swatch" />
        <select aria-label="Calendar" value={calendar} onChange={event => { setCalendar(event.target.value); setPicked(null); }}><option value="primary">Primary calendar</option>
          {data.calendars.filter(item => item.id !== 'primary').map(item => <option key={item.id} value={item.id}>{item.summary || item.id}</option>)}</select><ChevronDown size={13} aria-hidden="true" /></label>
      <div className="google-segmented" role="group" aria-label="Calendar view">
        <button type="button" aria-pressed={days === 1} onClick={() => { setDays(1); setPicked(null); }}>Day</button>
        <button type="button" aria-pressed={days === 7} onClick={() => { setDays(7); setPicked(null); }}>Week</button></div>
      <button type="button" className="google-square-btn" aria-label="Refresh agenda" title="Refresh agenda" disabled={busy} onClick={refresh}><RefreshCw size={14} /></button>
    </div>
    <div className="google-status is-bar" role="status">{status.operation ? <><LoaderCircle className="pr-task-spinner" size={12} aria-hidden="true" /><span>{status.operation}…</span></>
      : cached ? <><span className="google-dot" /><span>Cached {shortDate(new Date(data.timeMin))} – {shortDate(lastCached)}{data.calendarSavedAt && ` · updated ${clock(new Date(data.calendarSavedAt))}`}</span></>
        : <><span className="google-dot is-stale" /><span>Not cached — refresh to load this range</span></>}</div>
    <div className={`google-cal${days === 1 ? ' is-day' : ''}`} aria-label={days === 1 ? 'Day calendar' : 'Week calendar'}>
      <div className="google-cal__head" style={columns}><div />{week.map(({ day, date: value }) => <div key={day} className={day === today ? 'is-today' : ''}>
        <span>{value.toLocaleDateString([], { weekday: 'short' })}</span><strong>{value.getDate()}</strong></div>)}</div>
      <div className="google-cal__allday" style={columns}><div>all-day</div>{week.map(({ day, allDay }) => <div key={day}>{allDay.map(item =>
        <button type="button" key={item.id} className="google-cal__chip" aria-pressed={picked === item.id} onClick={() => setPicked(picked === item.id ? null : item.id)}>{item.summary || 'Untitled event'}</button>)}</div>)}</div>
      <div className="google-cal__scroll" ref={grid}><div className="google-cal__grid" style={columns}>
        <div className="google-cal__hours">{Array.from({ length: 24 }, (_, hour) => <div key={hour}>{hour > 0 && <span>{clock(new Date(2000, 0, 1, hour))}</span>}</div>)}</div>
        {week.map(({ day, date: value, timed }) => <div key={day} className={`google-cal__day${[0, 6].includes(value.getDay()) ? ' is-weekend' : ''}`}>
          {timed.map(({ event: item, start, end, column, columns: count }) => {
            const tall = end - start >= 0.5;
            return <button type="button" key={item.id} className={`google-cal__event${tall ? '' : ' is-short'}${new Date(item.end) < now ? ' is-past' : ''}`} aria-pressed={picked === item.id}
              style={{ top: start * HOUR + 1, height: Math.max(18, (end - start) * HOUR - 2), left: `calc(${column / count * 100}% + 3px)`, width: `calc(${100 / count}% - 7px)` }}
              onClick={() => setPicked(picked === item.id ? null : item.id)}>
              <strong>{item.summary || 'Untitled event'}</strong>{tall && <span>{clock(new Date(item.start))} – {clock(new Date(item.end))}</span>}</button>;
          })}
          {day === today && <div className="google-cal__now" style={{ top: (now.getHours() + now.getMinutes() / 60) * HOUR }} />}
        </div>)}
      </div></div>
      {!cached && !status.operation && <div className="google-cal__empty"><CalendarClock size={20} aria-hidden="true" /><strong>No cached events for this range</strong>Refresh to load it from Google.
        <button type="button" className="setw-btn" disabled={busy} onClick={refresh}><RefreshCw size={13} aria-hidden="true" />Refresh agenda</button></div>}
      {event && <section className="google-cal__detail" aria-label="Event details">
        <header><span className="google-select__swatch" /><strong>{event.summary || 'Untitled event'}</strong>
          <button type="button" className="google-icon-btn" aria-label="Close" title="Close" onClick={() => setPicked(null)}><X size={14} /></button></header>
        <div className="google-cal__facts"><span><Clock size={14} aria-hidden="true" />{eventTime(event)}</span>
          <span><Calendar size={14} aria-hidden="true" />{data.calendars.find(item => item.id === data.calendarId)?.summary || 'Primary'} calendar</span></div>
        {event.description && <p>{event.description}</p>}
        <div className="google-cal__actions">{link && <button type="button" className="setw-btn" onClick={() => open(link)}>Open event<ExternalLink size={13} aria-hidden="true" /></button>}
          <button type="button" className="setw-btn google-ask" onClick={() => onAsk(event)}><Sparkles size={13} aria-hidden="true" />Ask Gemini</button></div>
      </section>}
    </div>
  </div>;
}
