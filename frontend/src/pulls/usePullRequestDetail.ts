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
import {
  mergePullRequestWriteDetail,
  remoteErrorMessage,
  type PullDetailUpdater,
} from './PullOverview';
import type {
  PullRequestDetailDto,
  PullRequestDetailRefreshDto,
  PullRequestStatusRefreshDto,
} from '../types';

export const DETAIL_REFRESH_INTERVAL = 5_000;

export function mergePullRequestDetailStatus<T>(
  current: T,
  status: PullRequestStatusRefreshDto,
): T & PullRequestStatusRefreshDto {
  return { ...current, ...status };
}

/**
 * How long to wait before the next detail refresh.
 *
 * GitHub answers a spent rate limit with the reset instant, so a refused poll waits for the
 * window to reopen instead of spending the next five seconds asking again.
 */
export function pullRequestDetailRefreshDelay(failure: unknown, now = Date.now()): number {
  const match = remoteErrorMessage(failure).match(/GitHub \S+ API rate limit reached; retry after (\S+)/);
  const resetAt = match ? Date.parse(match[1]) : Number.NaN;
  return Number.isNaN(resetAt)
    ? DETAIL_REFRESH_INTERVAL
    : Math.max(DETAIL_REFRESH_INTERVAL, resetAt - now + 1_000);
}

/** Addresses one pull request the way GitHub does, so a late response can be matched to its row. */
interface PullRequestTarget {
  repo: string;
  number: number;
}

function pullRequestKey(target: PullRequestTarget | null): string | null {
  return target === null ? null : `${target.repo.toLowerCase()}#${target.number}`;
}

export interface PullRequestDetailSource {
  /** The detail for the requested pull request, or null while it is unread. */
  detail: PullRequestDetailDto | null;
  loading: boolean;
  error: string | null;
  /** Rebuilds the whole snapshot from GitHub — what a refresh control calls. */
  refresh: () => Promise<void>;
  update: PullDetailUpdater;
  replace: (detail: PullRequestDetailDto) => void;
}

interface PullRequestDetailOptions {
  /** Skips the read entirely while the surface showing it is hidden. */
  active?: boolean;
  /** Told when a whole new detail lands, so a list row or an open diff can follow it. */
  onDetail?: (detail: PullRequestDetailDto) => void;
  /** Told when only the check/merge status moved, which a status refresh reports without a timeline. */
  onStatus?: (status: PullRequestStatusRefreshDto) => void;
}

/**
 * One pull request's detail, read once and then kept current.
 *
 * The stored snapshot behind `GET /prs/detail` has no age: it is whatever the last refresh wrote,
 * which may be hours old. Reading it once therefore freezes the timeline at that moment — a merge
 * queue can accept and evict a pull request several times without a single one of those events
 * appearing. So every surface reads the snapshot for a fast first paint and then polls the
 * conditional refresh, which refetches the whole timeline whenever GitHub reports the pull request
 * changed and costs one 304 when it has not.
 */
export function usePullRequestDetail(
  target: PullRequestTarget | null,
  { active = true, onDetail, onStatus }: PullRequestDetailOptions = {},
): PullRequestDetailSource {
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The detail as last applied, so a status merge and a write merge both build on current data
  // without waiting for a render.
  const applied = useRef<PullRequestDetailDto | null>(null);
  // Which pull request `applied` holds, so a detail read for an earlier one is never drawn under
  // the current one and merely hiding the surface does not throw away what it already showed.
  const appliedKey = useRef<string | null>(null);
  // Bumped by every local edit. A response that left before the edit is dropped rather than
  // allowed to revert it.
  const mutationVersion = useRef(0);
  const refreshInFlight = useRef<Promise<unknown> | null>(null);
  const repo = target?.repo ?? null;
  const number = target?.number ?? null;
  const key = pullRequestKey(target);
  // Effects read the callbacks through a ref so a caller passing inline arrows cannot restart the
  // poll on every render.
  const listeners = useRef({ onDetail, onStatus });
  listeners.current = { onDetail, onStatus };

  const apply = useCallback((value: PullRequestDetailDto, requestMutationVersion?: number) => {
    if (requestMutationVersion !== undefined && requestMutationVersion !== mutationVersion.current) return;
    applied.current = value;
    appliedKey.current = pullRequestKey(value);
    setDetail(value);
    listeners.current.onDetail?.(value);
  }, []);

  useEffect(() => {
    if (repo === null || number === null || key === null) {
      applied.current = null;
      appliedKey.current = null;
      setDetail(null);
      return;
    }
    // Only a different pull request clears what is on screen. Hiding the surface stops the polling
    // but keeps the last read, so coming back to the tab does not flash an empty panel.
    if (appliedKey.current !== key) {
      applied.current = null;
      appliedKey.current = null;
      setDetail(null);
      setError(null);
    }
    if (!active) return;
    let cancelled = false;
    let refreshTimer: number | undefined;
    let polling = false;
    const known = applied.current !== null;
    setLoading(!known);

    // A hidden window spends requests nobody is reading, so the loop stops rather than idling. It
    // has no pending timer while hidden, which is what lets the visibility listener restart it
    // without racing one.
    const schedule = (delay: number) => {
      if (cancelled || document.hidden || polling || refreshTimer !== undefined) return;
      refreshTimer = window.setTimeout((): void => {
        refreshTimer = undefined;
        void poll();
      }, delay);
    };

    const poll = async (): Promise<void> => {
      polling = true;
      let nextDelay = DETAIL_REFRESH_INTERVAL;
      let request: Promise<PullRequestDetailRefreshDto> | null = null;
      try {
        while (refreshInFlight.current !== null) {
          await refreshInFlight.current.then((): undefined => undefined, (): undefined => undefined);
        }
        if (cancelled || document.hidden) return;
        const requestMutationVersion = mutationVersion.current;
        request = window.bridge.refreshPullRequestDetailByRef(repo, number);
        refreshInFlight.current = request;
        const value = await request;
        if (cancelled) return;
        if (value.full) {
          apply(value.detail, requestMutationVersion);
        }
        else if (applied.current !== null) {
          const next = mergePullRequestDetailStatus(applied.current, value.status);
          applied.current = next;
          setDetail(next);
          listeners.current.onStatus?.(value.status);
        }
        setError(null);
      }
      catch (failure) {
        nextDelay = pullRequestDetailRefreshDelay(failure);
        if (!cancelled) setError(remoteErrorMessage(failure));
      }
      finally {
        if (refreshInFlight.current === request) refreshInFlight.current = null;
        polling = false;
        schedule(nextDelay);
      }
    };

    // Coming back deserves current data now, not up to five seconds from now.
    const resume = () => schedule(0);
    document.addEventListener('visibilitychange', resume);

    // The stored snapshot is only worth a round trip when there is nothing on screen yet; a surface
    // being shown again already has one, and goes straight to asking GitHub what changed.
    const initialMutationVersion = mutationVersion.current;
    (known
      ? Promise.resolve()
      : window.bridge.fetchPullRequestDetailByRef(repo, number)
        .then(value => { if (!cancelled) apply(value, initialMutationVersion); })
        .catch(failure => { if (!cancelled) setError(remoteErrorMessage(failure)); }))
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
        schedule(0);
      });

    return () => {
      cancelled = true;
      document.removeEventListener('visibilitychange', resume);
      if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
    };
  }, [active, apply, key, number, repo]);

  const refresh = useCallback(async () => {
    if (repo === null || number === null) return;
    const requestMutationVersion = mutationVersion.current;
    const request = window.bridge.refreshAllPullRequestDetailByRef(repo, number);
    refreshInFlight.current = request;
    try {
      apply(await request, requestMutationVersion);
      setError(null);
    }
    catch (failure) {
      setError(remoteErrorMessage(failure));
    }
    finally {
      if (refreshInFlight.current === request) refreshInFlight.current = null;
    }
  }, [apply, number, repo]);

  const update = useCallback<PullDetailUpdater>((updatedRepo, updatedNumber, change) => {
    const current = applied.current;
    if (current === null
      || current.repo.toLowerCase() !== updatedRepo.toLowerCase()
      || current.number !== updatedNumber) return;
    const next = change(current);
    mutationVersion.current += 1;
    applied.current = next;
    setDetail(next);
  }, []);

  // A write returns the pull request as GitHub now has it, which does not carry the reactions,
  // label edits and thread resolutions this session applied locally. Merging keeps them.
  const replace = useCallback((value: PullRequestDetailDto) => {
    const current = applied.current;
    const next = current !== null
      && current.repo.toLowerCase() === value.repo.toLowerCase()
      && current.number === value.number
      ? mergePullRequestWriteDetail(current, value)
      : value;
    mutationVersion.current += 1;
    applied.current = next;
    setDetail(next);
  }, []);

  return { detail: key === null ? null : detail, loading, error, refresh, update, replace };
}
