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
import { useEffect, useRef, useState } from 'react';
import type { GoogleStatus } from '../google/contracts';
import type { AgendaDay, AgendaScheduleDto, QuietHoursDto } from '../types';
import { SettingsCard, SettingsRow, TimePill, Toggle } from './primitives';

const DAYS: { day: AgendaDay; letter: string; name: string }[] = [
  { day: 'MONDAY', letter: 'M', name: 'Monday' },
  { day: 'TUESDAY', letter: 'T', name: 'Tuesday' },
  { day: 'WEDNESDAY', letter: 'W', name: 'Wednesday' },
  { day: 'THURSDAY', letter: 'T', name: 'Thursday' },
  { day: 'FRIDAY', letter: 'F', name: 'Friday' },
  { day: 'SATURDAY', letter: 'S', name: 'Saturday' },
  { day: 'SUNDAY', letter: 'S', name: 'Sunday' },
];

/** Whether a minute of the day falls inside quiet hours, which may run past midnight. */
export function inQuietHours(minute: number, quiet: QuietHoursDto | null): boolean {
  if (quiet === null || !quiet.enabled || quiet.startMinute === quiet.endMinute) return false;
  return quiet.startMinute < quiet.endMinute
    ? minute >= quiet.startMinute && minute < quiet.endMinute
    : minute >= quiet.startMinute || minute < quiet.endMinute;
}

/** When the daily agenda is made: on or off, the time, and the working days; the last of them carries the week's recap. */
export default function AgendaScheduleCard({ quietHours, onError }: { quietHours: QuietHoursDto | null; onError: (error: unknown) => void }) {
  const [schedule, setSchedule] = useState<AgendaScheduleDto | null>(null);
  const [google, setGoogle] = useState<GoogleStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const saving = useRef(false);

  useEffect(() => {
    let current = true;
    void window.bridge.getAgendaSchedule().then(value => { if (current) setSchedule(value); }).catch(onError);
    // Without Google Apps the calendar row stays hidden; there is nothing to nudge toward here.
    void window.bridge.getGoogleStatus().then(value => { if (current) setGoogle(value); }).catch((): void => undefined);
    return () => { current = false; };
  }, [onError]);
  const account = google?.snapshot ?? null;

  const save = (next: AgendaScheduleDto) => {
    if (saving.current) return;
    saving.current = true;
    setBusy(true);
    window.bridge.saveAgendaSchedule(next).then(setSchedule).catch(onError).finally(() => { saving.current = false; setBusy(false); });
  };

  return (
    <SettingsCard footer={schedule !== null && inQuietHours(schedule.minute, quietHours) ? <span>It runs when quiet hours end.</span> : undefined}>
      {schedule === null ? <div className="setw-row-empty">Loading the agenda schedule…</div> : <>
        <SettingsRow title="Daily agenda" subtitle="Suggestions on Home · this Mac’s local time">
          <TimePill ariaLabel="Daily agenda time" minute={schedule.minute} disabled={!schedule.enabled || busy}
            onSelect={minute => save({ ...schedule, minute })} />
          <Toggle on={schedule.enabled} label="Make a daily agenda" disabled={busy} onChange={enabled => save({ ...schedule, enabled })} />
        </SettingsRow>
        <SettingsRow title="Working days" subtitle="The last one also looks back over the week">
          <span className="setw-days" role="group" aria-label="Working days">
            {DAYS.map(({ day, letter, name }) => {
              const on = schedule.days.includes(day);
              return (
                <button key={day} type="button" className={`setw-day${on ? ' is-on' : ''}`} aria-pressed={on} aria-label={name} title={name}
                  disabled={!schedule.enabled || busy}
                  onClick={() => save({ ...schedule, days: on ? schedule.days.filter(value => value !== day) : [...schedule.days, day] })}>
                  {letter}
                </button>
              );
            })}
          </span>
        </SettingsRow>
        {account !== null && (
          <SettingsRow title="Use my calendar's busy times" subtitle={`${account.email} · only start and end times are used; titles stay on this Mac`}>
            <Toggle on={schedule.calendarAccountId === account.accountId} label="Use my calendar's busy times in the daily agenda" disabled={!schedule.enabled || busy}
              onChange={on => save({ ...schedule, calendarAccountId: on ? account.accountId : null })} />
          </SettingsRow>
        )}
      </>}
    </SettingsCard>
  );
}
