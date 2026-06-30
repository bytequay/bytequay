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
import { useCallback, useEffect, useRef, useState } from 'react';

export type QueuedMessage = { id: string; text: string };

/**
 * A client-side outbox for messages typed while the agent is working. Each is
 * held as a pending bubble and auto-sent — one at a time — the moment the
 * agent goes idle; the user can pull one back into the composer to edit it
 * first. Shared by the trunk, task-brain, and stage-detail conversations.
 *
 * @param working true while the agent is busy / the thread is running
 * @param send    fires a message for real (the surface's send-now path, which
 *                flips `working` true again so the next queued item waits)
 */
export function useMessageQueue(working: boolean, send: (text: string) => void) {
  const [queue, setQueue] = useState<QueuedMessage[]>([]);
  // Refs so the stable callbacks read the latest queue + send without
  // re-subscribing the auto-send effect on every render.
  const queueRef = useRef<QueuedMessage[]>(queue);
  queueRef.current = queue;
  const sendRef = useRef(send);
  sendRef.current = send;
  const seq = useRef(0);

  const enqueue = useCallback((text: string) => {
    const t = text.trim();
    if (t.length === 0) return;
    seq.current += 1;
    setQueue(q => [...q, { id: `q${seq.current}`, text: t }]);
  }, []);

  /** Pull a queued message out for editing — removes it and returns its text
   *  (empty when not found) so the caller can drop it into the composer. */
  const takeForEdit = useCallback((id: string): string => {
    const m = queueRef.current.find(x => x.id === id);
    if (m !== undefined) setQueue(q => q.filter(x => x.id !== id));
    return m?.text ?? '';
  }, []);

  const remove = useCallback((id: string) => {
    setQueue(q => q.filter(x => x.id !== id));
  }, []);

  // Auto-send the head as soon as the agent is idle. send() flips `working`
  // true again synchronously, so this fires for exactly one message per idle
  // window — the rest wait their turn.
  useEffect(() => {
    if (working || queue.length === 0) return;
    const [head, ...rest] = queue;
    setQueue(rest);
    sendRef.current(head.text);
  }, [working, queue]);

  return { queue, enqueue, takeForEdit, remove };
}
