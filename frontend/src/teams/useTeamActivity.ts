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
import { errorMessage } from '../errorMessage';
import type { TeamActivityQuery, TeamActivitySnapshot } from './types';
import { TEAMS_CHANGED } from './useTeams';

/**
 * Page reads are local; only the user's Refresh action starts a collection pass. A new query keeps the last
 * data on screen until its own answer arrives, and a failed refresh keeps it too.
 */
export function useTeamActivity(accountId: number | undefined, { circleId, memberId, days = 7, limit = 100, filter = 'all' }: TeamActivityQuery = {}) {
  const [snapshot, setSnapshot] = useState<TeamActivitySnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const generation = useRef(0);
  const account = useRef(accountId);
  const refreshInFlight = useRef(false);
  const load = useCallback(async (refresh = false) => {
    if (accountId === undefined || refresh && refreshInFlight.current) return;
    if (refresh) { refreshInFlight.current = true; setRefreshing(true); setRefreshError(null); }
    const request = ++generation.current;
    try {
      const query = { circleId, memberId, days, limit, filter };
      const result = await (refresh ? window.bridge.refreshTeamActivity(accountId, query) : window.bridge.getTeamActivity(accountId, query));
      if (request !== generation.current) return;
      setSnapshot(result); setError(null);
    }
    catch (failure) {
      // A refresh failure outlives a filter change but never an account change.
      if (refresh && account.current === accountId) setRefreshError(errorMessage(failure));
      else if (request === generation.current) setError(errorMessage(failure));
    }
    finally {
      if (refresh) { refreshInFlight.current = false; setRefreshing(false); }
    }
  }, [accountId, circleId, memberId, days, limit, filter]);

  useEffect(() => {
    account.current = accountId;
    setSnapshot(null); setError(null); setRefreshError(null);
  }, [accountId]);

  useEffect(() => {
    if (accountId !== undefined) void load();
    const reload = () => { if (!refreshInFlight.current) void load(); };
    const invalidate = () => { generation.current++; };
    const timer = window.setInterval(() => { if (!document.hidden) reload(); }, 30_000);
    window.addEventListener(TEAMS_CHANGED, reload);
    window.addEventListener('bytequay:pr-attention-updated', reload);
    const stopSync = window.bridge.onPullRequestsSynced(reload);
    return () => {
      invalidate();
      window.clearInterval(timer);
      window.removeEventListener(TEAMS_CHANGED, reload);
      window.removeEventListener('bytequay:pr-attention-updated', reload);
      stopSync();
    };
  }, [accountId, load]);

  return { snapshot, refreshing, error, refreshError, dismissRefreshError: () => setRefreshError(null), refresh: () => load(true), reload: () => load() };
}
