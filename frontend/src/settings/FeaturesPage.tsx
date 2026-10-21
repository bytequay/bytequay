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
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import type { GoogleStatus } from '@bytequay/google/contracts';
import { useAgentAvailability } from '../agents/availability';
import { SETUP_CHANGED } from '../onboarding/setup';
import { remoteErrorMessage } from '../remoteError';
import type { AgendaDay, AgendaScheduleDto, AssistantPermissionsDto, QuietHoursDto, SetupPreferencesDto } from '../types';
import { SettingsCard, SettingsRow, TimePill, Toggle } from './primitives';
import { settingsRoute } from './routes';

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

function useFeatureError() {
  const [error, setError] = useState<string | null>(null);
  const fail = useCallback((failure: unknown) => setError(remoteErrorMessage(failure)), []);
  return { error, fail, clear: () => setError(null) };
}

/**
 * A feature that runs on the global Development agent: off until the user turns it on, its own settings only while on.
 * Without a usable Development agent it can still be turned off, never on.
 */
export function FeatureCard({ section, title, subtitle, error, on, busy, footer, onChange, children }: {
  section: string;
  title: string;
  subtitle?: string;
  error: string | null;
  on: boolean;
  busy: boolean;
  footer?: ReactNode;
  onChange: (on: boolean) => void;
  children: ReactNode;
}) {
  const agent = useAgentAvailability();
  const note = !agent.loading && !agent.development
    ? <>Choose an available global Development model in <a href={settingsRoute('models')}>Models</a>.</> : footer;
  return <>
    <div className="setw-section">
      <span className="setw-section-title">{section}</span>
      <span className="setw-section-note">runs on your Development agent</span>
    </div>
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
    <SettingsCard footer={note === undefined ? undefined : <div className="setw-foot-row"><span>{note}</span></div>}>
      <SettingsRow title={title} subtitle={subtitle}>
        <Toggle label={title} on={on} disabled={busy || !on && !agent.development} onChange={onChange} />
      </SettingsRow>
      {on && children}
    </SettingsCard>
  </>;
}

function TodayFeature({ quietHours }: { quietHours: QuietHoursDto | null }) {
  const [schedule, setSchedule] = useState<AgendaScheduleDto | null>(null);
  const [google, setGoogle] = useState<GoogleStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const saving = useRef(false);
  const { error, fail, clear } = useFeatureError();

  useEffect(() => {
    let current = true;
    void window.bridge.getAgendaSchedule().then(value => { if (current) setSchedule(value); }).catch(fail);
    // Without Google Apps the calendar row stays hidden; there is nothing to nudge toward here.
    void window.bridge.getGoogleStatus().then(value => { if (current) setGoogle(value); }).catch((): void => undefined);
    return () => { current = false; };
  }, [fail]);
  const account = google?.snapshot ?? null;

  const save = (next: AgendaScheduleDto) => {
    if (saving.current) return;
    saving.current = true;
    setBusy(true);
    clear();
    window.bridge.saveAgendaSchedule(next).then(setSchedule).catch(fail).finally(() => { saving.current = false; setBusy(false); });
  };

  return (
    <FeatureCard section="Today" title="Enable daily agenda"
      error={error} on={schedule?.enabled === true} busy={busy || schedule === null}
      footer={schedule?.enabled === true && inQuietHours(schedule.minute, quietHours) ? 'It runs when quiet hours end.' : undefined}
      onChange={enabled => { if (schedule !== null) save({ ...schedule, enabled }); }}>
      {schedule !== null && <>
        <SettingsRow title="Make it at">
          <TimePill ariaLabel="Daily agenda time" minute={schedule.minute} disabled={busy} onSelect={minute => save({ ...schedule, minute })} />
        </SettingsRow>
        <SettingsRow title="Working days" subtitle="The last one also looks back over the week">
          <span className="setw-days" role="group" aria-label="Working days">
            {DAYS.map(({ day, letter, name }) => {
              const on = schedule.days.includes(day);
              return (
                <button key={day} type="button" className={`setw-day${on ? ' is-on' : ''}`} aria-pressed={on} aria-label={name} title={name} disabled={busy}
                  onClick={() => save({ ...schedule, days: on ? schedule.days.filter(value => value !== day) : [...schedule.days, day] })}>
                  {letter}
                </button>
              );
            })}
          </span>
        </SettingsRow>
        {account !== null && (
          <SettingsRow title="Use my calendar's busy times" subtitle={account.email}>
            <Toggle on={schedule.calendarAccountId === account.accountId} label="Use my calendar's busy times in the daily agenda" disabled={busy}
              onChange={on => save({ ...schedule, calendarAccountId: on ? account.accountId : null })} />
          </SettingsRow>
        )}
      </>}
    </FeatureCard>
  );
}

function AssistantFeature() {
  const [setup, setSetup] = useState<SetupPreferencesDto | null>(null);
  const [busy, setBusy] = useState(false);
  const { error, fail, clear } = useFeatureError();

  useEffect(() => {
    let current = true;
    void window.bridge.getSetupPreferences?.().then(value => { if (current) setSetup(value); }).catch(fail);
    return () => { current = false; };
  }, [fail]);

  return (
    <FeatureCard section="Assistant" title="Enable Assistant" subtitle="Task notices stay on when it’s off."
      error={error} on={setup?.assistantEnabled === true} busy={busy || setup === null}
      onChange={assistantEnabled => {
        if (setup === null) return;
        setBusy(true);
        clear();
        window.bridge.saveSetupPreferences({ ...setup, assistantEnabled }).then(value => {
          setSetup(value);
          window.dispatchEvent(new Event(SETUP_CHANGED));
        }).catch(fail).finally(() => setBusy(false));
      }}>
      <AssistantPermissionRows onError={fail} />
    </FeatureCard>
  );
}

/** What the Assistant panel may do from the user's words. Every switch starts off. */
function AssistantPermissionRows({ onError }: { onError: (failure: unknown) => void }) {
  const [value, setValue] = useState<AssistantPermissionsDto | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let current = true;
    void window.bridge.getAssistantPermissions().then(next => { if (current) setValue(next); }).catch(onError);
    return () => { current = false; };
  }, [onError]);

  if (value === null) return <div className="setw-row-empty">Loading the Assistant’s switches…</div>;
  const save = (next: AssistantPermissionsDto) => {
    setBusy(true);
    window.bridge.saveAssistantPermissions(next).then(setValue).catch(onError).finally(() => setBusy(false));
  };
  return <>
    <SettingsRow title="Assistant can start tasks" subtitle={<>Uses your <a href={settingsRoute('workflow')}>Workflow</a> automation.</>}>
      <Toggle label="Assistant can start tasks" on={value.startTasks} disabled={busy} onChange={startTasks => save({ ...value, startTasks })} />
    </SettingsRow>
    <SettingsRow title="Assistant can answer tasks"
      subtitle="Allow once or deny sandboxed commands, answer questions, and send your replies.">
      <Toggle label="Assistant can answer tasks" on={value.answerTasks} disabled={busy} onChange={answerTasks => save({ ...value, answerTasks })} />
    </SettingsRow>
    <SettingsRow title="Assistant can change Epics and work items" subtitle="Nothing goes to GitHub.">
      <Toggle label="Assistant can change Epics and work items" on={value.changeWork} disabled={busy} onChange={changeWork => save({ ...value, changeWork })} />
    </SettingsRow>
    <SettingsRow title="Assistant can acknowledge and snooze items">
      <Toggle label="Assistant can acknowledge and snooze items" on={value.markAttention} disabled={busy}
        onChange={markAttention => save({ ...value, markAttention })} />
    </SettingsRow>
  </>;
}

/** Today and Assistant: the two features that need both the user's opt-in and a usable Development agent. */
export default function FeaturesPage({ quietHours }: { quietHours: QuietHoursDto | null }) {
  return <div className="setw-page">
    <h1>Features</h1>
    <TodayFeature quietHours={quietHours} />
    <AssistantFeature />
  </div>;
}
