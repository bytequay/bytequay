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
import { errorMessage } from '../errorMessage';

export const TEAMS_CHANGED = 'bytequay:teams-changed';
export const GITHUB_ACCOUNT_CHANGED = 'bytequay:github-account-changed';
const CACHE_KEY = 'teams:directories';
/** One directory per hosting account; null until the first read answers. */
type TeamsState = { directories: TeamDirectory[] | null; loading: boolean; error: string | null };
const EMPTY: TeamsState = { directories: null, loading: false, error: null };
const listeners = new Set<() => void>();
let generation = 0;
let pending: Promise<void> | null = null;
let listening = false;
const snapshot = (): TeamsState => getCached<TeamsState>(CACHE_KEY) ?? EMPTY;
const publish = (state: TeamsState) => { setCached(CACHE_KEY, state); listeners.forEach(listener => listener()); };

/** A local directory read is shared by the page and attention cards. */
export function refreshTeams(): Promise<void> {
  if (pending !== null) return pending;
  if (typeof window === 'undefined' || window.bridge?.getTeams === undefined) return Promise.resolve();
  listen();
  const request = ++generation;
  publish({ ...snapshot(), loading: true, error: null });
  pending = window.bridge.getTeams().then(directories => {
    if (request === generation) publish({ directories, loading: false, error: null });
  }).catch((error: unknown) => {
    if (request === generation) publish({ directories: null, loading: false, error: errorMessage(error) });
  }).finally(() => { if (request === generation) pending = null; });
  return pending;
}

/**
 * A mutation's answer replaces its account's directory at once. A read already in flight may predate it, and an account
 * may have left meanwhile, so a fresh read follows.
 */
function changed(event: Event): void {
  const directory = (event as CustomEvent<TeamDirectory>).detail;
  const { directories } = snapshot();
  generation++;
  pending = null;
  if (directories !== null) publish({ directories: directories.map(item => item.accountId === directory.accountId ? directory : item), loading: false, error: null });
  void refreshTeams();
}

/** The desktop sign-in can add or remove a directory. */
function accountChanged(): void {
  generation++;
  pending = null;
  void refreshTeams();
}

function listen(): void {
  if (!listening && typeof window !== 'undefined') {
    // Keep these installed while the Teams page is closed so a sign-in change reloads the cached directories.
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

export function useTeams(): TeamsState {
  const state = useSyncExternalStore(subscribe, snapshot, snapshot);
  useEffect(() => { void refreshTeams(); }, []);
  return state;
}
