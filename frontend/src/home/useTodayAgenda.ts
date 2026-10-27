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
import { useAgentAvailability } from '../agents/availability';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { pollVisible } from '../ide/pollVisible';
import type { AgendaDto } from '../types';
import { loadHandled } from './attention';
import { withoutSnoozed } from './today';

/** How often a Home looks again while today's agenda is being made. */
const MAKING_POLL_MS = 10_000;
/** Pull request refreshes arrive every few seconds; done states only need a look now and then. */
const ATTENTION_REFRESH_MS = 30_000;

export type TodayAgenda = {
  agenda: AgendaDto | null;
  /** Why the last thing the user asked for failed; background reads stay quiet. */
  error: string | null;
  makeAgain: () => void;
  mark: (ref: string, mark: 'DONE' | 'DISMISSED' | null) => void;
  /** Starts the task a row suggests; rejects with why it could not, which the row says. */
  start: (ref: string) => Promise<void>;
  /** Brings back every item set aside for today. */
  showHidden: () => void;
};

/**
 * Today's agenda for one Home: read on open and focus, after pull request refreshes, when the Assistant starts one, and every few seconds
 * while it is being made.
 */
export function useTodayAgenda(workspaceId?: string): TodayAgenda {
  const availability = useAgentAvailability();
  const [agenda, setAgenda] = useState<AgendaDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const request = useRef(0);
  const loadedAt = useRef(0);

  const apply = useCallback((value: AgendaDto, ticket: number) => {
    if (ticket !== request.current) return;
    loadedAt.current = Date.now();
    setAgenda(value);
  }, []);

  const load = useCallback(async () => {
    const ticket = ++request.current;
    try {
      apply(await window.bridge.getAgendaToday(workspaceId), ticket);
    }
    catch {
      // A failed background read keeps what is on screen and the next read retries; a bridge without the agenda, such as a test
      // fixture's, draws no Today section.
    }
  }, [apply, workspaceId]);

  useEffect(() => {
    void load();
    const focused = () => { void load(); };
    const refreshed = () => { if (Date.now() - loadedAt.current >= ATTENTION_REFRESH_MS) void load(); };
    window.addEventListener('focus', focused);
    window.addEventListener('bytequay:agenda-changed', focused);
    window.addEventListener('bytequay:pr-attention-updated', refreshed);
    return () => {
      request.current += 1;
      window.removeEventListener('focus', focused);
      window.removeEventListener('bytequay:agenda-changed', focused);
      window.removeEventListener('bytequay:pr-attention-updated', refreshed);
    };
  }, [load]);

  const making = agenda?.state === 'RUNNING';
  useEffect(() => making ? pollVisible(load, MAKING_POLL_MS) : undefined, [making, load]);

  const act = useCallback((action: () => Promise<AgendaDto>) => {
    setError(null);
    const ticket = ++request.current;
    action().then(() => load()).catch((failure: unknown) => {
      if (ticket !== request.current) return;
      setError(failure instanceof Error ? failure.message : String(failure));
    });
  }, [load]);

  const makeAgain = useCallback(() => { if (availability.development) act(() => window.bridge.makeAgendaAgain()); }, [act, availability.development]);
  const mark = useCallback((ref: string, value: 'DONE' | 'DISMISSED' | null) => act(() => window.bridge.markAgendaItem(ref, value)), [act]);
  const start = useCallback(async (ref: string) => {
    await window.bridge.startAgendaItem(ref);
    await load();
  }, [load]);
  const showHidden = useCallback(() => {
    const hidden = agenda?.items.filter(item => item.status === 'dismissed').map(item => item.ref) ?? [];
    act(async () => {
      let latest: AgendaDto | null = null;
      for (const ref of hidden) latest = await window.bridge.markAgendaItem(ref, null);
      return latest ?? window.bridge.getAgendaToday(workspaceId);
    });
  }, [act, agenda, workspaceId]);

  // The agenda is made once a day, so an item snoozed after that is left out here rather than when it is made.
  const [handled, setHandled] = useState(loadHandled);
  useEffect(() => {
    const changed = () => setHandled(loadHandled());
    window.addEventListener('bytequay:attention-handled-changed', changed);
    return () => window.removeEventListener('bytequay:attention-handled-changed', changed);
  }, []);
  const shown = useMemo(() => agenda === null ? null : withoutSnoozed(agenda, handled, Date.now()), [agenda, handled]);

  return { agenda: shown, error, makeAgain, mark, start, showHidden };
}
