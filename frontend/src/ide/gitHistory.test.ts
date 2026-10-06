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
import { afterEach, expect, it, vi } from 'vitest';
import type { SourceControlHistoryProvider, TextDocumentContentProvider } from 'vscode';
import { installGitHistory } from './gitHistory';
import type { IdeRuntime } from './runtime';
import type { HistoryComparison, HistoryCommit } from './contracts';
// eslint-disable-next-line import/no-unresolved -- The pinned package exports this path.
import { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';

it('pins graph pages, avoids revealing an absent file-history HEAD and opens immutable rename and deletion diffs', async () => {
  const request = vi.fn(async () => ({ commits: [] as HistoryCommit[], content: 'historical contents' }));
  vi.stubGlobal('window', { ideWorkbench: { request } });
  const scm = { inputBox: { visible: true }, historyProvider: undefined as SourceControlHistoryProvider | undefined, dispose: vi.fn() };
  const execute = vi.fn();
  let contents: TextDocumentContentProvider | undefined;
  const runtime = { workspace: URI.parse('vscode-remote://localhost/worktree'), views: { getViewDescriptorById: (): undefined => undefined }, api: {
    Uri: URI, EventEmitter: class { event = vi.fn(); fire = vi.fn(); dispose = vi.fn(); },
    scm: { createSourceControl: () => scm }, commands: { executeCommand: execute },
    workspace: { registerTextDocumentContentProvider: (_scheme: string, provider: TextDocumentContentProvider) => { contents = provider; return { dispose: vi.fn() }; } },
  } } as unknown as IdeRuntime;
  const host = { kind: 'task', taskId: 'task', workspaceId: 'workspace' } as const;
  const history = installGitHistory(runtime, host);
  const old = 'a'.repeat(40), head = 'b'.repeat(40);
  history.updateRevision(head);
  expect(scm.inputBox.visible).toBe(false);
  await scm.historyProvider?.provideHistoryItems({ skip: 100, historyItemRefs: [old] }, { isCancellationRequested: false } as never);
  expect(request).toHaveBeenLastCalledWith('readCodeHistory', host, { revision: old, path: undefined, offset: 100 });
  await history.focus('renamed 測試.go');
  expect(scm.historyProvider?.currentHistoryItemRef).toBeUndefined();
  await history.focus();
  expect(scm.historyProvider?.currentHistoryItemRef?.revision).toBe(head);
  const comparison: HistoryComparison = { commit: { sha: head, parents: [old], author: 'Author', authoredAt: '2024-01-01T00:00:00Z', message: 'Rename' }, parent: old, files: [], truncated: false };
  await history.openDiff(comparison, { status: 'R100', path: 'renamed 測試.go', originalPath: 'original.go' });
  const before = execute.mock.calls.at(-1)?.[1] as URI;
  const after = execute.mock.calls.at(-1)?.[2] as URI;
  expect(before.path).toBe('/original.go'); expect(after.path).toBe('/renamed 測試.go');
  expect(JSON.parse(before.query)).toEqual({ revision: old }); expect(JSON.parse(after.query)).toEqual({ revision: head });
  await contents?.provideTextDocumentContent(after, {} as never);
  expect(request).toHaveBeenLastCalledWith('readHistorySnapshot', host, { revision: head, path: 'renamed 測試.go' });
  await history.openDiff(comparison, { status: 'D', path: 'removed.go', originalPath: null });
  const empty = execute.mock.calls.at(-1)?.[2] as URI;
  expect(await contents?.provideTextDocumentContent(empty, {} as never)).toBe('');
  history.dispose(); expect(scm.dispose).toHaveBeenCalledOnce();
});

afterEach(() => { vi.unstubAllGlobals(); });
