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
import { ZOOM_REFERENCE, type ZoomedReference } from '../githubReference';
import {
  mergePullRequestWriteDetail,
  remoteErrorMessage,
  type PullDetailUpdater,
} from './PullOverview';
import type {
  PullRequestDetailDto,
  PullRequestDto,
  PullRequestDetailRefreshDto,
  PullRequestStatusRefreshDto,
} from '../types';

export const DETAIL_REFRESH_INTERVAL = 60_000;
export const CHECK_REFRESH_INTERVAL = 30_000;

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
 * window to reopen instead of repeating rejected requests.
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

/** Cached-first detail with independent discussion and pending-CI refresh cadences. */
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
  const currentKey = useRef(key);
  currentKey.current = key;
  // Effects read the callbacks through a ref so a caller passing inline arrows cannot restart the
  // poll on every render.
  const listeners = useRef({ onDetail, onStatus });
  listeners.current = { onDetail, onStatus };

  const apply = useCallback((value: PullRequestDetailDto, requestMutationVersion?: number) => {
    if (pullRequestKey(value) !== currentKey.current) return;
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
    let ready = false;
    let refreshRequested = true;
    let refreshOnOpen = true;
    let lastDetailRefresh = 0;
    let retryAt = 0;
    let observedActivity: string | undefined;
    const known = applied.current !== null;
    setLoading(!known);

    const clearTimer = () => {
      if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
      refreshTimer = undefined;
    };
    const schedule = (delay: number) => {
      if (cancelled || !ready || document.hidden || polling || refreshTimer !== undefined) return;
      refreshTimer = window.setTimeout((): void => {
        refreshTimer = undefined;
        void poll();
      }, Math.max(delay, retryAt - Date.now()));
    };

    const poll = async (): Promise<void> => {
      polling = true;
      let request: Promise<PullRequestDetailRefreshDto> | null = null;
      let opened = false;
      try {
        while (refreshInFlight.current !== null) {
          await refreshInFlight.current.then((): undefined => undefined, (): undefined => undefined);
        }
        if (cancelled || document.hidden) return;
        opened = refreshOnOpen;
        refreshOnOpen = false;
        const checksOnly = !opened && !refreshRequested && Date.now() - lastDetailRefresh < DETAIL_REFRESH_INTERVAL;
        if (!checksOnly) refreshRequested = false;
        const requestMutationVersion = mutationVersion.current;
        request = window.bridge.refreshPullRequestDetailByRef(repo, number, checksOnly, opened);
        refreshInFlight.current = request;
        const value = await request;
        if (cancelled) return;
        if (!checksOnly) lastDetailRefresh = Date.now();
        if (value.full) {
          apply(value.detail, requestMutationVersion);
        }
        else if (applied.current !== null && requestMutationVersion === mutationVersion.current) {
          const next = mergePullRequestDetailStatus(applied.current, value.status);
          applied.current = next;
          setDetail(next);
          listeners.current.onStatus?.(value.status);
        }
        retryAt = 0;
        setError(null);
      }
      catch (failure) {
        refreshOnOpen ||= opened;
        retryAt = Date.now() + pullRequestDetailRefreshDelay(failure);
        // Nobody asked for this poll and the next one retries, so a failure is only worth showing
        // when there is no detail on screen. A refresh the reader asks for still reports it.
        if (!cancelled && applied.current === null) setError(remoteErrorMessage(failure));
      }
      finally {
        if (refreshInFlight.current === request) refreshInFlight.current = null;
        polling = false;
        schedule(refreshRequested ? 0 : applied.current?.ciStatus === 'PENDING' ? CHECK_REFRESH_INTERVAL : DETAIL_REFRESH_INTERVAL);
      }
    };

    const resume = () => {
      clearTimer();
      if (!document.hidden) {
        refreshRequested = true;
        refreshOnOpen = true;
        schedule(0);
      }
    };
    const changed = (event: Event) => {
      const row = (event as CustomEvent<PullRequestDto[]>).detail.find(value => pullRequestKey(value) === key);
      if (row === undefined) return;
      const version = JSON.stringify([row.updatedAt, row.activityAt, row.ciStatus, row.headPushedAt, row.reviewDecision, row.commentCount]);
      const current = applied.current;
      const newer = observedActivity !== undefined ? observedActivity !== version
        : current !== null && (Date.parse(row.updatedAt) > Date.parse(current.updatedAt ?? '') || row.ciStatus !== current.ciStatus);
      observedActivity = version;
      if (!newer) return;
      refreshRequested = true;
      clearTimer();
      schedule(0);
    };
    const reopened = (event: Event) => {
      const reference = (event as CustomEvent<ZoomedReference>).detail;
      if (reference.kind === 'pull' && pullRequestKey(reference) === key) resume();
    };
    document.addEventListener('visibilitychange', resume);
    window.addEventListener('bytequay:pr-attention-updated', changed);
    window.addEventListener(ZOOM_REFERENCE, reopened);

    // Backend group freshness makes the initial conditional refresh cheap when the cache is current.
    const initialMutationVersion = mutationVersion.current;
    (known
      ? Promise.resolve()
      : window.bridge.fetchPullRequestDetailByRef(repo, number)
        .then(value => { if (!cancelled) apply(value, initialMutationVersion); })
        .catch(failure => { if (!cancelled) setError(remoteErrorMessage(failure)); }))
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
        ready = true;
        schedule(0);
      });

    return () => {
      cancelled = true;
      document.removeEventListener('visibilitychange', resume);
      window.removeEventListener('bytequay:pr-attention-updated', changed);
      window.removeEventListener(ZOOM_REFERENCE, reopened);
      clearTimer();
    };
  }, [active, apply, key, number, repo]);

  const refresh = useCallback(async () => {
    if (repo === null || number === null || key !== currentKey.current) return;
    while (refreshInFlight.current !== null) {
      await refreshInFlight.current.then((): undefined => undefined, (): undefined => undefined);
    }
    if (key !== currentKey.current) return;
    setLoading(true);
    const requestMutationVersion = mutationVersion.current;
    const request = window.bridge.refreshAllPullRequestDetailByRef(repo, number);
    refreshInFlight.current = request;
    try {
      apply(await request, requestMutationVersion);
      if (key === currentKey.current) setError(null);
    }
    catch (failure) {
      if (key === currentKey.current) setError(remoteErrorMessage(failure));
    }
    finally {
      if (refreshInFlight.current === request) refreshInFlight.current = null;
      if (key === currentKey.current) setLoading(false);
    }
  }, [apply, key, number, repo]);

  const update = useCallback<PullDetailUpdater>((updatedRepo, updatedNumber, change) => {
    const current = applied.current;
    if (current === null
      || pullRequestKey(current) !== currentKey.current
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
    if (pullRequestKey(value) !== currentKey.current) return;
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

  return { detail: key === null || pullRequestKey(detail) !== key ? null : detail, loading, error, refresh, update, replace };
}
