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
import { useEffect, useRef } from 'react';

const pending = new Map<string, string>();
const CHANGED = 'bytequay:prepared-agent-input';

/** Local draft handoff only. The existing composer retains its explicit Send/Queue/Steer actions. */
export function prepareAgentInput(taskId: string, text: string): void {
  pending.set(taskId, [pending.get(taskId), text].filter(Boolean).join('\n\n'));
  window.dispatchEvent(new Event(CHANGED));
}

export function usePreparedAgentInput(taskId: string | null, append: (text: string) => void): void {
  const receive = useRef(append);
  receive.current = append;
  useEffect(() => {
    if (!taskId) return;
    const take = () => {
      const text = pending.get(taskId);
      if (text === undefined) return;
      pending.delete(taskId);
      receive.current(text);
    };
    window.addEventListener(CHANGED, take); take();
    return () => window.removeEventListener(CHANGED, take);
  }, [taskId]);
}
