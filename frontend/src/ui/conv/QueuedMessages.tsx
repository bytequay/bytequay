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
import type { QueuedMessage } from '../../threads/useMessageQueue';

/**
 * Pending messages the user queued while the agent was working — shown at the
 * foot of the conversation, just above the "working" indicator. Each sends
 * automatically when the agent goes idle; clicking one pulls it back into the
 * composer to edit, and × drops it.
 */
export function QueuedMessages({ messages, onEdit, onRemove }: {
  messages: QueuedMessage[];
  /** Pull this queued message back into the composer to modify it. */
  onEdit: (id: string) => void;
  onRemove: (id: string) => void;
}) {
  if (messages.length === 0) return null;
  return (
    <div className="queued-list" aria-label="Queued messages">
      {messages.map(m => (
        <div key={m.id} className="queued-msg">
          <button
            type="button"
            className="queued-msg__body"
            title="Click to edit before it sends"
            onClick={() => onEdit(m.id)}
          >
            <span className="queued-msg__tag">queued</span>
            <span className="queued-msg__text">{m.text}</span>
          </button>
          <button
            type="button"
            className="queued-msg__remove"
            aria-label="Remove queued message"
            title="Remove"
            onClick={() => onRemove(m.id)}
          >×</button>
        </div>
      ))}
    </div>
  );
}
