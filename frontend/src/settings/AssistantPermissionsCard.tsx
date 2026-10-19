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
import { remoteErrorMessage } from '../remoteError';
import type { AssistantPermissionsDto } from '../types';
import { SettingsCard, SettingsRow, Toggle } from './primitives';

/** What the Assistant panel may do from the user's words. Every switch starts off. */
export default function AssistantPermissionsCard() {
  const [value, setValue] = useState<AssistantPermissionsDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void window.bridge.getAssistantPermissions().then(next => { if (current) setValue(next); })
      .catch((failure: unknown) => { if (current) setError(remoteErrorMessage(failure)); });
    return () => { current = false; };
  }, []);

  const save = (next: AssistantPermissionsDto) => {
    setBusy(true);
    setError(null);
    window.bridge.saveAssistantPermissions(next).then(setValue).catch((failure: unknown) => setError(remoteErrorMessage(failure))).finally(() => setBusy(false));
  };

  return <>
    <div className="setw-section"><span className="setw-section-title">Assistant</span></div>
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
    <SettingsCard>
      {value === null ? <div className="setw-row-empty">Loading the Assistant’s switches…</div> : <>
        <SettingsRow title="Assistant can start tasks" subtitle="When you ask in the Assistant panel. Uses the automation settings above.">
          <Toggle label="Assistant can start tasks" on={value.startTasks} disabled={busy} onChange={startTasks => save({ ...value, startTasks })} />
        </SettingsRow>
        <SettingsRow title="Assistant can answer tasks"
          subtitle="Allow once or deny commands inside the sandbox, answer questions, and send your replies. Everything else stays a button.">
          <Toggle label="Assistant can answer tasks" on={value.answerTasks} disabled={busy} onChange={answerTasks => save({ ...value, answerTasks })} />
        </SettingsRow>
        <SettingsRow title="Assistant can change Epics and work items"
          subtitle="Mark issues, tickets and work items as Epics, add work to an Epic, and create work items titled with your words. All of it stays local; unmarking an Epic stays a button.">
          <Toggle label="Assistant can change Epics and work items" on={value.changeWork} disabled={busy} onChange={changeWork => save({ ...value, changeWork })} />
        </SettingsRow>
        <SettingsRow title="Assistant can acknowledge and snooze items"
          subtitle="Clear a Needs attention item until something new happens, or snooze it for an hour, until tomorrow or until Monday. Bringing one back stays a button.">
          <Toggle label="Assistant can acknowledge and snooze items" on={value.markAttention} disabled={busy}
            onChange={markAttention => save({ ...value, markAttention })} />
        </SettingsRow>
      </>}
    </SettingsCard>
  </>;
}
