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

import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { prDetail } from './pullRequest';
import type { Bridge, PullRequestDetailDto, PullRequestDetailRefreshDto, PullRequestDto } from '../../types';
import { assert } from '../../fixtures/harness';
import { ZOOM_REFERENCE } from '../../githubReference';
import { usePullRequestDetail, type PullRequestDetailSource } from '../usePullRequestDetail';

const cached = prDetail({
  id: 7, repo: 'owner/repo', number: 7, title: 'Cached PR', htmlUrl: null,
  mergeable: null, mergeableState: null, author: 'alice', updatedAt: '2026-01-01T00:00:00Z',
  body: '',
  ciStatus: 'PENDING',
  viewerCanWrite: false,
});

export async function run(): Promise<string> {
  const originalBridge = window.bridge;
  const originalTimeout = window.setTimeout;
  const originalClearTimeout = window.clearTimeout;
  const originalNow = Date.now;
  const hidden = Object.getOwnPropertyDescriptor(document, 'hidden');
  let invisible = false;
  let now = Date.parse('2026-01-02T00:00:00Z');
  let timerId = 0;
  const timers = new Map<number, { at: number; call: () => void }>();
  window.setTimeout = ((handler: TimerHandler, delay = 0) => {
    assert(typeof handler === 'function', 'Timers must use functions');
    const id = ++timerId;
    timers.set(id, { at: now + delay, call: () => handler() });
    return id;
  }) as typeof window.setTimeout;
  window.clearTimeout = id => { if (typeof id === 'number') timers.delete(id); };
  Date.now = () => now;
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => invisible });
  const firstRead = Promise.withResolvers<PullRequestDetailDto>();
  const calls: { checksOnly: boolean; opened: boolean; answer: ReturnType<typeof Promise.withResolvers<PullRequestDetailRefreshDto>> }[] = [];
  let manualCalls = 0;
  const bridge = {
    fetchPullRequestDetailByRef: async () => firstRead.promise,
    refreshPullRequestDetailByRef: (_repo, _number, checksOnly, opened) => {
      const answer = Promise.withResolvers<PullRequestDetailRefreshDto>();
      calls.push({ checksOnly: checksOnly === true, opened: opened === true, answer });
      return answer.promise;
    },
    refreshAllPullRequestDetailByRef: async () => { manualCalls++; return cached; },
  } satisfies Pick<Bridge, 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef' | 'refreshAllPullRequestDetailByRef'>;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let source: PullRequestDetailSource | undefined;
  function Probe({ active }: { active: boolean }) {
    source = usePullRequestDetail({ repo: cached.repo, number: cached.number }, { active });
    return <div>{source.detail?.title}</div>;
  }
  const settle = async () => {
    await new Promise<void>(resolve => originalTimeout(resolve, 0));
    await new Promise<void>(resolve => originalTimeout(resolve, 0));
  };
  const advance = async (milliseconds: number) => {
    now += milliseconds;
    for (const [id, timer] of [...timers]) {
      if (timer.at <= now) { timers.delete(id); timer.call(); }
    }
    await settle();
  };
  const complete = async (index: number, ciStatus: PullRequestDetailDto['ciStatus'] = 'PENDING') => {
    calls[index].answer.resolve({ full: true, detail: { ...cached, title: 'Refreshed PR', ciStatus }, status: null });
    await settle();
  };
  const activity = (version: number) => window.dispatchEvent(new CustomEvent('bytequay:pr-attention-updated', {
    detail: [{ repo: cached.repo, number: 7, updatedAt: cached.updatedAt, activityAt: String(version), ciStatus: 'PENDING',
      commentCount: version, headPushedAt: null, reviewDecision: null } as PullRequestDto],
  }));
  try {
    flushSync(() => root.render(<Probe active />));
    await settle();
    assert(Number(calls.length) === 0, 'The stored snapshot must load before any foreground refresh');
    firstRead.resolve(cached);
    await settle();
    assert(host.textContent === 'Cached PR', 'Cached detail must render before the remote response');
    await advance(0);
    assert(Number(calls.length) === 1 && !calls[0].checksOnly && calls[0].opened, 'Opening must request fresh discussion after showing cached detail');
    await advance(60_000);
    assert(Number(calls.length) === 1, 'A slow refresh must not overlap another poll');
    await complete(0);
    assert(String(host.textContent) === 'Refreshed PR', 'The opening response must replace the cached detail without another click');
    await advance(29_999);
    assert(Number(calls.length) === 1, 'Pending CI must wait thirty seconds');
    await advance(1);
    assert(Number(calls.length) === 2 && calls[1].checksOnly && !calls[1].opened, 'Pending CI must use the checks-only path');
    await complete(1);
    await advance(30_000);
    assert(Number(calls.length) === 3 && !calls[2].checksOnly && !calls[2].opened, 'Periodic metadata refresh must keep the slower discussion freshness');
    await complete(2, 'PASSING');
    await advance(30_000);
    assert(Number(calls.length) === 3, 'Settled CI must stop thirty-second checks');
    await advance(30_000);
    assert(Number(calls.length) === 4 && !calls[3].checksOnly && !calls[3].opened, 'Settled PRs keep polling without repeatedly requesting opening freshness');
    await complete(3);
    activity(1);
    await advance(0);
    assert(Number(calls.length) === 4, 'An unchanged initial attention snapshot must not refresh again');
    activity(2);
    await advance(0);
    assert(Number(calls.length) === 5 && !calls[4].checksOnly, 'Known activity must refresh discussion immediately');
    activity(3);
    await advance(0);
    assert(Number(calls.length) === 5, 'Activity arriving during a refresh must wait for it');
    await complete(4);
    await advance(0);
    assert(Number(calls.length) === 6, 'One newer activity observed in flight must be checked afterward');
    const manual = source?.refresh();
    await settle();
    assert(Number(manualCalls) === 0, 'Manual refresh must join the foreground request boundary');
    await complete(5);
    await manual;
    assert(Number(manualCalls) === 1, 'Explicit full refresh must run after the in-flight request');
    flushSync(() => root.render(<Probe active={false} />));
    await advance(120_000);
    assert(Number(calls.length) === 6, 'A hidden PR surface must stop polling');
    flushSync(() => root.render(<Probe active />));
    await settle(); await advance(0);
    assert(Number(calls.length) === 7 && calls[6].opened, 'Returning to the PR must request fresh discussion again');
    await complete(6);
    invisible = true;
    document.dispatchEvent(new Event('visibilitychange'));
    await advance(120_000);
    assert(Number(calls.length) === 7, 'An invisible window must make no foreground calls');
    invisible = false;
    document.dispatchEvent(new Event('visibilitychange'));
    await advance(0);
    assert(Number(calls.length) === 8 && calls[7].opened, 'Returning to the window must request fresh discussion again');
    const resetAt = now + 120_000;
    calls[7].answer.reject(new Error(`GitHub core API rate limit reached; retry after ${new Date(resetAt).toISOString()}`));
    await settle();
    assert(source?.error === null, 'A failed background poll must not report over the detail on screen');
    activity(4); activity(5);
    await advance(60_000);
    assert(Number(calls.length) === 8, 'Known updates must respect the reported rate-limit pause');
    await advance(61_000);
    assert(Number(calls.length) === 9 && calls[8].opened, 'Opening freshness must survive a failed request and the rate-limit pause');
    await complete(8);
    window.dispatchEvent(new CustomEvent(ZOOM_REFERENCE, { detail: { kind: 'pull', repo: cached.repo, number: 8 } }));
    await advance(0);
    assert(Number(calls.length) === 9, 'Opening a different PR must not refresh this one');
    window.dispatchEvent(new CustomEvent(ZOOM_REFERENCE, { detail: { kind: 'pull', repo: cached.repo, number: cached.number } }));
    await advance(0);
    assert(Number(calls.length) === 10 && calls[9].opened, 'Clicking a notification for an already visible PR must request opening freshness');
    await complete(9);
    return 'Cached-first detail, separate CI/discussion cadence, immediate changes, no overlap, and visibility/backoff guards';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    window.setTimeout = originalTimeout;
    window.clearTimeout = originalClearTimeout;
    Date.now = originalNow;
    if (hidden) Object.defineProperty(document, 'hidden', hidden);
    else Reflect.deleteProperty(document, 'hidden');
    Object.assign(window, { bridge: originalBridge });
  }
}
