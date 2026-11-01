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
import { History, SquarePen } from 'lucide-react';
import Composer from '../Composer';
import { IconButton } from '../cards/Card';
import type { WorkItem } from '../home/work';
import { openAssistant } from './model';
import AssistantPills, { useNotices, useWaiting } from './Pills';
import '../css/assistant.css';

/**
 * The Assistant's composer on Home, under the My work line. Typing stays on Home; Send opens the Assistant on the conversation with the
 * message, and a pill with something in it opens the Assistant on that view.
 */
export default function HomeComposer({ work }: { work: WorkItem[] }) {
  const waiting = useWaiting(useNotices());
  const [draft, setDraft] = useState('');
  const submit = () => {
    const text = draft.trim();
    if (text === '') return;
    // The Assistant keeps a message it could not send in its own composer, so nothing typed here is lost.
    openAssistant({ message: text });
    setDraft('');
  };
  return (
    <div className="assistant-home">
      <Composer aria-label="Message the Assistant" placeholder="Ask, or tell it what to start" value={draft} maxLength={16 * 1024}
        onChange={event => setDraft(event.target.value)} onSubmit={submit} submitEnabled={draft.trim() !== ''}
        submitLabel="Send to the Assistant" submitTitle="Send (Enter)">
        <span className="assistant-spacer" />
        <IconButton label="New conversation" title="New conversation" onClick={() => openAssistant({ fresh: true })}><SquarePen size={15} aria-hidden="true" /></IconButton>
        <IconButton label="Conversations" title="History" onClick={() => openAssistant({ history: true })}><History size={15} aria-hidden="true" /></IconButton>
      </Composer>
      <AssistantPills waiting={waiting} needsYou={work.some(item => item.lane === 'needs')} open={null} onPick={view => openAssistant({ view })} />
    </div>
  );
}
