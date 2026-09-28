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
import type { TeamActivityQuery, TeamActivitySnapshot } from './types';
import { GITHUB_ACCOUNT_CHANGED, TEAMS_CHANGED } from './useTeams';

/** Page reads are local; only the user's Refresh action starts a collection pass. */
export function useTeamActivity(accountLogin: string | undefined, { circleId, memberId, days = 7, limit = 100, filter = 'all' }: TeamActivityQuery = {}) {
  const [snapshot, setSnapshot] = useState<TeamActivitySnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const generation = useRef(0);
  const refreshInFlight = useRef(false);
  const load = useCallback(async (refresh = false) => {
    if (!accountLogin || refresh && refreshInFlight.current) return;
    if (refresh) { refreshInFlight.current = true; setRefreshing(true); }
    const request = ++generation.current;
    try {
      const query = { circleId, memberId, days, limit, filter };
      const result = await (refresh ? window.bridge.refreshTeamActivity(query) : window.bridge.getTeamActivity(query));
      if (request !== generation.current) return;
      if (result.accountLogin.toLowerCase() !== accountLogin.toLowerCase()) { setSnapshot(null); return; }
      setSnapshot(result); setError(null);
    }
    catch (failure) {
      if (request === generation.current) setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      if (refresh) { refreshInFlight.current = false; setRefreshing(false); }
      if (request === generation.current) setLoading(false);
    }
  }, [accountLogin, circleId, memberId, days, limit, filter]);

  useEffect(() => {
    setSnapshot(null); setError(null); setLoading(Boolean(accountLogin));
    if (accountLogin) void load();
    const reload = () => { if (!refreshInFlight.current) void load(); };
    const invalidate = () => { generation.current++; };
    const clear = () => { invalidate(); setSnapshot(null); setError(null); };
    const changed = () => { clear(); reload(); };
    const timer = window.setInterval(reload, 30_000);
    window.addEventListener(TEAMS_CHANGED, changed);
    window.addEventListener(GITHUB_ACCOUNT_CHANGED, clear);
    return () => {
      invalidate();
      window.clearInterval(timer);
      window.removeEventListener(TEAMS_CHANGED, changed);
      window.removeEventListener(GITHUB_ACCOUNT_CHANGED, clear);
    };
  }, [accountLogin, load]);

  return { snapshot, loading, refreshing, error, refresh: () => load(true) };
}
