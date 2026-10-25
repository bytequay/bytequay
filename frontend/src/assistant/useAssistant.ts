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
import { pollVisible } from '../ide/pollVisible';
import { remoteErrorMessage } from '../remoteError';
import type { AssistantSummaryDto, AssistantViewDto } from '../types';

/** How often the open panel looks again while a reply is being written. */
const RUNNING_POLL_MS = 1000;

export type Assistant = {
  /** The conversation on screen; null until it has been read. */
  view: AssistantViewDto | null;
  /** The most recent conversations, newest first. */
  conversations: AssistantSummaryDto[];
  /** Why the last thing the user asked for failed; background reads stay quiet. */
  error: string | null;
  sending: boolean;
  /** Resolves whether the message was taken, so the composer keeps a draft that was not. */
  send: (text: string, visible: string[], onScreen: string | null) => Promise<boolean>;
  stop: () => void;
  retry: () => void;
  /** Reads the conversation again, such as after a card's button left a receipt. */
  refresh: () => void;
};

/**
 * One conversation and the history while the panel is open: read on open, focus and a switch of conversation, and every second while a
 * reply runs. A conversation id nobody wrote in yet reads as empty, and the first message begins it.
 */
export function useAssistant(open: boolean, conversation: string): Assistant {
  const [view, setView] = useState<AssistantViewDto | null>(null);
  const [conversations, setConversations] = useState<AssistantSummaryDto[]>([]);
  const [error, setError] = useState<{ conversation: string; message: string } | null>(null);
  const [sending, setSending] = useState(false);
  const request = useRef(0);
  // A message whose send failed keeps its key, so sending it again cannot add it twice if the first one did arrive.
  const pending = useRef<{ conversation: string; text: string; key: string } | null>(null);

  const load = useCallback(async () => {
    const ticket = ++request.current;
    try {
      const [next, recent] = await Promise.all([window.bridge.getAssistantConversation(conversation), window.bridge.listAssistantConversations()]);
      if (ticket !== request.current) return;
      setView(next);
      setConversations(recent);
    }
    catch {
      // A failed background read keeps what is on screen and the next read retries.
    }
  }, [conversation]);

  useEffect(() => {
    if (!open) return;
    void load();
    const focused = () => { void load(); };
    window.addEventListener('focus', focused);
    return () => window.removeEventListener('focus', focused);
  }, [load, open]);

  // A reply in another conversation still moves the history's spinner.
  const running = view?.running === true || conversations.some(each => each.running);
  useEffect(() => open && running ? pollVisible(load, RUNNING_POLL_MS) : undefined, [load, open, running]);

  const act = useCallback(async (action: () => Promise<unknown>): Promise<boolean> => {
    setError(null);
    try {
      await action();
      return true;
    }
    catch (failure) {
      setError({ conversation, message: remoteErrorMessage(failure) });
      return false;
    }
    finally {
      await load();
    }
  }, [conversation, load]);

  const send = useCallback(async (text: string, visible: string[], onScreen: string | null) => {
    const old = pending.current;
    const message = old !== null && old.conversation === conversation && old.text === text ? old : { conversation, text, key: crypto.randomUUID() };
    pending.current = message;
    setSending(true);
    const sent = await act(() => window.bridge.sendAssistantMessage(conversation, text, visible, onScreen, message.key));
    if (sent) pending.current = null;
    setSending(false);
    return sent;
  }, [act, conversation]);

  const stop = useCallback(() => { void act(() => window.bridge.stopAssistant()); }, [act]);
  const retry = useCallback(() => { void act(() => window.bridge.retryAssistant(conversation)); }, [act, conversation]);
  const refresh = useCallback(() => { void load(); }, [load]);

  // What was read for the conversation before a switch stays off screen until the new one arrives.
  return {
    view: view?.id === conversation ? view : null, conversations, error: error?.conversation === conversation ? error.message : null, sending, send, stop, retry, refresh,
  };
}
