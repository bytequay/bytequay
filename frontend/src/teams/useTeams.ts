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
import { useEffect, useSyncExternalStore } from 'react';
import { getCached, setCached } from '../dataCache';
import type { TeamDirectory } from './types';

export const TEAMS_CHANGED = 'bytequay:teams-changed';
export const GITHUB_ACCOUNT_CHANGED = 'bytequay:github-account-changed';
const CACHE_KEY = 'teams:directory';
type TeamsState = { directory: TeamDirectory | null; loading: boolean; error: string | null };
const EMPTY: TeamsState = { directory: null, loading: false, error: null };
const listeners = new Set<() => void>();
let generation = 0;
let pending: Promise<void> | null = null;
let listening = false;
let accountChanging = false;
const snapshot = (): TeamsState => getCached<TeamsState>(CACHE_KEY) ?? EMPTY;
const publish = (state: TeamsState) => { setCached(CACHE_KEY, state); listeners.forEach(listener => listener()); };

/** A local directory read is shared by the page and attention cards. */
export function refreshTeams(): Promise<void> {
  if (pending !== null) return pending;
  if (typeof window === 'undefined' || window.bridge?.getTeams === undefined) return Promise.resolve();
  listen();
  const request = ++generation;
  publish({ ...snapshot(), loading: true, error: null });
  pending = window.bridge.getTeams().then(directory => {
    if (request === generation) {
      accountChanging = false;
      publish({ directory, loading: false, error: null });
    }
  }).catch((error: unknown) => {
    if (request === generation) publish({ directory: null, loading: false, error: error instanceof Error ? error.message : String(error) });
  }).finally(() => { if (request === generation) pending = null; });
  return pending;
}

function changed(event: Event): void {
  const directory = (event as CustomEvent<TeamDirectory | undefined>).detail;
  const account = snapshot().directory?.accountLogin;
  // A mutation started before a credential change may finish after it. Only the account read can establish the new owner.
  if (directory !== undefined && (accountChanging || account !== undefined && directory.accountLogin.toLowerCase() !== account.toLowerCase())) return;
  generation++;
  pending = null;
  publish({ directory: directory ?? null, loading: false, error: null });
  if (directory === undefined) void refreshTeams();
}

function accountChanged(): void {
  generation++;
  pending = null;
  accountChanging = true;
  publish(EMPTY);
  void refreshTeams();
}

function listen(): void {
  if (!listening && typeof window !== 'undefined') {
    // Keep these installed while the Teams page is closed so an account switch clears its cached directory immediately.
    window.addEventListener(TEAMS_CHANGED, changed);
    window.addEventListener(GITHUB_ACCOUNT_CHANGED, accountChanged);
    listening = true;
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  listen();
  return () => { listeners.delete(listener); };
}

export function updateTeams(directory: TeamDirectory): void {
  listen();
  window.dispatchEvent(new CustomEvent(TEAMS_CHANGED, { detail: directory }));
}

export function useTeams(viewerLogin?: string | null): TeamsState {
  const state = useSyncExternalStore(subscribe, snapshot, snapshot);
  useEffect(() => { void refreshTeams(); }, [viewerLogin]);
  const matches = viewerLogin === undefined || viewerLogin !== null && state.directory?.accountLogin.toLowerCase() === viewerLogin.toLowerCase();
  return matches ? state : { ...state, directory: null };
}
