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
/* eslint-disable import/no-unresolved -- The pinned modular packages export these paths; Vite and TypeScript resolve them. */
import { useSyncExternalStore } from 'react';
import { IStatusbarService } from '@codingame/monaco-vscode-api';
import { Disposable } from '@codingame/monaco-vscode-api/vscode/vs/base/common/lifecycle';
import { Event } from '@codingame/monaco-vscode-api/vscode/vs/base/common/event';
import { stripIcons } from '@codingame/monaco-vscode-api/vscode/vs/base/common/iconLabels';
import { SyncDescriptor } from '@codingame/monaco-vscode-api/vscode/vs/platform/instantiation/common/descriptors';
import type { IStatusbarEntry } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/statusbar/browser/statusbar';

// The status bar is hidden, and the modular runtime's default status bar service drops every entry. Keep the busy ones,
// such as a language server's project import, so a surface waiting on that work can say what it is waiting for.
const busy = new Map<number, string>();
const listeners = new Set<() => void>();
let entries = 0;
let searches = 0;
const notify = () => listeners.forEach(listener => listener());
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };

function record(key: number, entry: IStatusbarEntry | undefined) {
  const text = entry && stripIcons(entry.text).trim();
  // Re-inserting keeps the most recently reported work last.
  const was = busy.delete(key);
  if (entry && text && (entry.showProgress || entry.text.includes('~spin)'))) busy.set(key, text);
  else if (!was) return;
  notify();
}

class StatusEntries {
  readonly _serviceBrand: undefined = undefined;
  readonly onDidChangeEntryVisibility = Event.None;
  addEntry(entry: IStatusbarEntry) {
    const key = entries++;
    record(key, entry);
    return { update: (changed: IStatusbarEntry) => record(key, changed), dispose: () => record(key, undefined) };
  }
  isEntryVisible() { return false; }
  updateEntryVisibility() { /* Nothing is shown. */ }
  overrideEntry() { return Disposable.None; }
  overrideStyle() { return Disposable.None; }
  focus() { /* Nothing is shown. */ }
  focusNextEntry() { /* Nothing is shown. */ }
  focusPreviousEntry() { /* Nothing is shown. */ }
  isEntryFocused() { return false; }
}

export default function getStatusServiceOverride() {
  return { [IStatusbarService.toString()]: new SyncDescriptor(StatusEntries) };
}

/** The percentage in a status such as "Java: 45% Importing Maven project(s)"; work without one gets a moving bar. */
export function progressOf(status: string | undefined): number | undefined {
  const done = status?.match(/(\d{1,3})%/)?.[1];
  return done === undefined ? undefined : Math.min(100, Number(done));
}

/** The latest status a busy extension reported, such as "Java: 45% Importing Maven project(s)". */
export function useBusyStatus(): string | undefined {
  return useSyncExternalStore(subscribe, () => [...busy.values()].at(-1));
}

/** Marks a search the user is waiting on until the returned function runs, so the tools bar can say what it waits for. */
export function waitForLanguageService(): () => void {
  searches++; notify();
  return () => { searches--; notify(); };
}

export function useSearchWaiting(): boolean {
  return useSyncExternalStore(subscribe, () => searches > 0);
}
