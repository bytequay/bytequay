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
import { remoteErrorMessage } from './remoteError';

type Thread = { id: string; version: number; createdAt: string };
const EMPTY: never[] = [];

/** Polls durable threads without letting an older read replace a write or another task's state. */
export function useThreadSnapshot<Q, T extends Thread>(query: Q | null, active: boolean | undefined, load: (query: Q) => Promise<T[]>) {
  const key = JSON.stringify(query);
  const [snapshot, setSnapshot] = useState<{ key: string; items: T[] }>({ key, items: EMPTY });
  const [error, setError] = useState<string | null>(null);
  const items = snapshot.key === key ? snapshot.items : EMPTY;
  const latest = useRef({ key, query, items, load });
  latest.current = { key, query, items, load };
  const epoch = useRef(0);

  const refresh = useCallback(async () => {
    const current = latest.current;
    if (current.query === null) return;
    const version = ++epoch.current;
    try {
      const items = await current.load(current.query);
      if (latest.current.key === current.key && epoch.current === version) {
        // Most polls return what is shown, and a new array would re-render the whole task screen every three seconds.
        // Versions alone miss fields the server derives on read, such as a question still in flight.
        if (JSON.stringify(items) !== JSON.stringify(latest.current.items)) {
          latest.current.items = items;
          setSnapshot({ key: current.key, items });
        }
        setError(null);
      }
    }
    catch (failure) {
      if (latest.current.key === current.key && epoch.current === version) setError(remoteErrorMessage(failure));
    }
  }, []);

  useEffect(() => {
    if (active === false || latest.current.query === null) return;
    let cancelled = false;
    const pendingEpoch = epoch;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async (): Promise<void> => {
      await refresh();
      if (!cancelled) timer = setTimeout(() => { void poll(); }, 3000);
    };
    void poll();
    return () => { cancelled = true; clearTimeout(timer); pendingEpoch.current++; };
  }, [key, active, refresh]);

  const accept = useCallback((thread: T, scope: string): void => {
    if (latest.current.key !== scope) return;
    epoch.current++;
    if (latest.current.items.some(item => item.id === thread.id && item.version > thread.version)) return;
    const items = [...latest.current.items.filter(item => item.id !== thread.id), thread]
      .sort((left, right) => left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id));
    latest.current.items = items;
    setSnapshot({ key: scope, items });
    setError(null);
  }, []);

  return { items, latest, error, setError, refresh, accept };
}
