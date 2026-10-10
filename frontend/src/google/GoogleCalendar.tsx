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
import type { GoogleSnapshot } from './contracts';
import { eventTime, googleLink } from './contracts';

export function calendarDays(date: string, days: number): string[] {
  return Array.from({ length: days }, (_, index) => {
    const value = new Date(`${date}T12:00:00`); value.setDate(value.getDate() + index);
    return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
  });
}
export default function GoogleCalendar({ data, date, days, open }: { data: GoogleSnapshot; date: string; days: number; open: (url: string) => void }) {
  return <div className={`google-calendar ${days === 1 ? 'is-day' : ''}`} aria-label={days === 1 ? 'Day calendar' : 'Week calendar'}>
    {calendarDays(date, days).map(day => {
      const start = new Date(`${day}T00:00:00`); const end = new Date(start); end.setDate(end.getDate() + 1);
      const items = data.events.filter(event => event.allDay ? event.start <= day && event.end > day
        : new Date(event.start) < end && new Date(event.end) > start);
      return <section key={day}><h2>{new Date(`${day}T12:00:00`).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' })}</h2>
        {items.map(event => <article key={event.id}><time>{eventTime(event)}</time><h3>{event.summary || 'Untitled event'}</h3>{event.description && <pre>{event.description}</pre>}{googleLink(event.url) && <button type="button" className="setw-link" onClick={() => open(googleLink(event.url) ?? '')}>Open event</button>}</article>)}
        {!items.length && <p>No cached events</p>}
      </section>;
    })}
  </div>;
}
