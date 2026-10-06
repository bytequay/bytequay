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
// eslint-disable-next-line @typescript-eslint/triple-slash-reference -- The pinned proposal is a module augmentation without an importable module.
/// <reference path="../../node_modules/@codingame/monaco-vscode-api/vscode-dts/vscode.proposed.scmHistoryProvider.d.ts" />
import type { SourceControlHistoryItem, SourceControlHistoryItemRefsChangeEvent } from 'vscode';
import type { CodeHostRef, ReviewCodeFileDto } from '../pulls/code-tests/contracts';
import type { CodeHistory, HistoryCommit, HistoryComparison, HistoryChange } from './contracts';
import type { IdeRuntime } from './runtime';

export function installGitHistory(runtime: IdeRuntime, host: CodeHostRef) {
  const { api } = runtime;
  const scm = api.scm.createSourceControl('bytequay-history', 'Local Git history', api.Uri.parse(runtime.workspace.toString()));
  scm.inputBox.visible = false;
  const refs = new api.EventEmitter<void>();
  const changed = new api.EventEmitter<SourceControlHistoryItemRefsChangeEvent>();
  let revision: string | undefined;
  let path: string | undefined;
  const readCommit = (revision: string, parent?: string) => window.ideWorkbench.request('readHistoryCommit', host, { revision, parent }) as Promise<HistoryComparison>;
  const snapshot = (revision: string | null, path: string) => api.Uri.from({ scheme: 'bytequay-git', path: '/' + path, query: JSON.stringify({ revision }) });
  const contents = api.workspace.registerTextDocumentContentProvider('bytequay-git', {
    provideTextDocumentContent: async uri => {
      const value: unknown = JSON.parse(uri.query);
      if (!value || typeof value !== 'object' || !('revision' in value)) throw new Error('Invalid Git snapshot');
      if (value.revision === null) return '';
      const file = await window.ideWorkbench.request('readHistorySnapshot', host, { revision: value.revision, path: uri.path.slice(1) }) as ReviewCodeFileDto;
      if (file.content === null) throw new Error(file.unavailableReason ?? 'This snapshot has no text contents');
      return file.content;
    },
  });
  const item = (commit: HistoryCommit): SourceControlHistoryItem => ({ id: commit.sha, parentIds: commit.parents, subject: commit.message.split('\n')[0],
    message: commit.message, displayId: commit.sha.slice(0, 8), author: commit.author, timestamp: Date.parse(commit.authoredAt) });
  const change = (comparison: HistoryComparison, file: HistoryChange) => ({
    uri: api.Uri.joinPath(api.Uri.parse(runtime.workspace.toString()), file.path),
    originalUri: comparison.parent && !file.status.startsWith('A') ? snapshot(comparison.parent, file.originalPath ?? file.path) : undefined,
    modifiedUri: file.status.startsWith('D') ? undefined : snapshot(comparison.commit.sha, file.path),
  });
  scm.historyProvider = {
    // HEAD may not touch the selected file. Advertising it as a file-history item makes the native reveal loop search forever.
    get currentHistoryItemRef() { return revision && !path ? { id: revision, name: 'Task HEAD', revision } : undefined; },
    currentHistoryItemRemoteRef: undefined, currentHistoryItemBaseRef: undefined,
    onDidChangeCurrentHistoryItemRefs: refs.event, onDidChangeHistoryItemRefs: changed.event,
    provideHistoryItemRefs: () => revision ? [{ id: revision, name: 'Task HEAD', revision }] : [],
    provideHistoryItems: async (options, token) => {
      const selectedRevision = options.historyItemRefs?.[0] ?? revision;
      if (!selectedRevision || token.isCancellationRequested) return [];
      const history = await window.ideWorkbench.request('readCodeHistory', host, { revision: selectedRevision, path, offset: options.skip ?? 0 }) as CodeHistory;
      return token.isCancellationRequested ? [] : history.commits.map(item);
    },
    provideHistoryItemChanges: async (revision, parent, token) => {
      const comparison = await readCommit(revision, parent);
      if (comparison.truncated) void api.window.showWarningMessage('The file list for this commit is truncated.');
      return token.isCancellationRequested ? [] : comparison.files.map(file => change(comparison, file));
    },
    resolveHistoryItem: async revision => item((await readCommit(revision)).commit),
    resolveHistoryItemRefsCommonAncestor: () => revision,
    resolveHistoryItemChatContext: () => '', resolveHistoryItemChangeRangeChatContext: () => '',
  };
  const historyView = runtime.views.getViewDescriptorById('workbench.scm.history');
  if (historyView) runtime.views.moveViewToLocation(historyView, 1, 'ByteQuay');
  return {
    readCommit,
    updateRevision(value: string) { if (revision !== value) { revision = value; refs.fire(); } },
    async focus(file?: string) { path = file; refs.fire(); await api.commands.executeCommand('workbench.scm.history.focus'); },
    async openDiff(comparison: HistoryComparison, file: HistoryChange) {
      const uris = change(comparison, file);
      await api.commands.executeCommand('vscode.diff', uris.originalUri ?? snapshot(null, file.path), uris.modifiedUri ?? snapshot(null, file.path),
        `${file.path} · ${comparison.parent?.slice(0, 8) ?? 'empty'} → ${comparison.commit.sha.slice(0, 8)}`, { preview: true });
    },
    dispose() { contents.dispose(); scm.dispose(); refs.dispose(); changed.dispose(); },
  };
}
