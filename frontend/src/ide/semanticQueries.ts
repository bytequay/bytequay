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
/* eslint-disable import/no-unresolved -- Internal APIs are pinned with the modular runtime. */
import { getService } from '@codingame/monaco-vscode-api';
import { CancellationTokenSource } from '@codingame/monaco-vscode-api/vscode/vs/base/common/cancellation';
import { Position } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/core/position';
import { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import { ILanguageFeaturesService } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/services/languageFeatures.service';
import { ITextModelService } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/services/resolverService.service';
import { ITextFileService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/textfile/common/textfiles.service';
import { IWorkingCopyService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/workingCopy/common/workingCopyService.service';
import { IMarkerService } from '@codingame/monaco-vscode-api/vscode/vs/platform/markers/common/markers.service';
import { WorkspaceSymbolProviderRegistry } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/search/common/search';
import { CallHierarchyProviderRegistry } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/callHierarchy/common/callHierarchy';
import type { IRange } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/core/range';
import type { DocumentSymbol, LocationLink } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/languages';
import type { CancellationToken } from '@codingame/monaco-vscode-api/vscode/vs/base/common/cancellation';
import { parseCodeQuery, queryFailure, workspacePath, type CodeQuery, type CodeQueryItem, type CodeQueryReply } from './codeQuery';
import { serviceLanguages } from './languages';

function cancelled(token: CancellationToken) {
  if (token.isCancellationRequested) throw new Error('Code query cancelled');
}

async function pause(token: CancellationToken) {
  cancelled(token);
  await new Promise<void>(resolve => {
    const timer = setTimeout(done, 200);
    const listener = token.onCancellationRequested(done);
    function done() { clearTimeout(timer); listener.dispose(); resolve(); }
  });
}

/** Uses the same registered providers and document models as the visible editor, including cancellation. */
export async function semanticQuery(workspace: URI, input: CodeQuery, token: CancellationToken): Promise<CodeQueryReply> {
  const query = parseCodeQuery(input);
  const [models, features, copies, markers, textFiles] = await Promise.all([
    getService(ITextModelService), getService(ILanguageFeaturesService), getService(IWorkingCopyService), getService(IMarkerService), getService(ITextFileService),
  ]);
  const localPath = (uri: URI): string | undefined => {
    if (uri.scheme !== workspace.scheme || uri.authority !== workspace.authority || !uri.path.startsWith(workspace.path + '/')) return;
    const relative = uri.path.slice(workspace.path.length + 1);
    return workspacePath(relative) ? relative : undefined;
  };
  const dirty = () => copies.dirtyWorkingCopies.some(copy => localPath(copy.resource) !== undefined);
  if (dirty()) return queryFailure('UNSAVED', 'The editor has unsaved source changes. Save or discard them before querying the on-disk working copy.');
  cancelled(token);
  // A file watcher can lag an agent's write. Reload clean open models before consulting their shared language index.
  for (const file of textFiles.files.models) {
    if (localPath(file.resource) !== undefined) await textFiles.files.resolve(file.resource, { reload: { async: false } });
    cancelled(token);
  }
  if (dirty()) return queryFailure('UNSAVED', 'Source was edited while preparing the query. Save or discard it before retrying.');
  const reference = await models.createModelReference(URI.joinPath(workspace, query.path));
  const model = reference.object.textEditorModel;
  let changed = false;
  const changes = copies.onDidChangeContent(copy => { if (localPath(copy.resource) !== undefined) changed = true; });
  const version = model.getVersionId();
  const language = model.getLanguageId();
  const position = new Position(query.line ?? 1, query.column ?? 1);
  const items: CodeQueryItem[] = [];
  const seen = new Set<string>();
  let truncated = false;
  let external = false;
  const locate = (uri: URI, range: IRange) => {
    const path = localPath(uri);
    if (!path) { external = true; return undefined; }
    return { path, line: range.startLineNumber, column: range.startColumn, endLine: range.endLineNumber, endColumn: range.endColumn };
  };
  const add = (uri: URI, range: IRange, detail: Partial<CodeQueryItem> = {}) => {
    const location = locate(uri, range);
    if (!location) return;
    const item = { ...detail, ...location };
    const key = JSON.stringify(item);
    if (seen.has(key)) return;
    seen.add(key);
    if (items.length < query.limit) items.push(item); else truncated = true;
  };
  try {
    if (!serviceLanguages.includes(language)) return queryFailure('UNSUPPORTED', `Semantic queries are not configured for ${language}. Use file and text search.`);
    if (!model.validatePosition(position).equals(position)) return queryFailure('STALE', 'The requested position is outside the current file. Read the source again.');
    const available = () => {
      switch (query.operation) {
        case 'SYMBOLS': return features.documentSymbolProvider.has(model) && WorkspaceSymbolProviderRegistry.all().length > 0;
        case 'DOCUMENT_SYMBOLS': case 'DIAGNOSTICS': return features.documentSymbolProvider.has(model);
        case 'DEFINITIONS': return features.definitionProvider.has(model);
        case 'REFERENCES': return features.referenceProvider.has(model);
        case 'IMPLEMENTATIONS': return features.implementationProvider.has(model);
        case 'INCOMING_CALLS': case 'OUTGOING_CALLS': return CallHierarchyProviderRegistry.has(model);
      }
    };
    const until = Date.now() + 20_000;
    while (!available() && Date.now() < until) await pause(token);
    cancelled(token);
    if (!available()) return queryFailure(features.documentSymbolProvider.has(model) ? 'UNSUPPORTED' : 'UPDATING',
      'The language service has not registered this capability. Check project import and toolchain status; retry if it is still loading.');
    if (query.operation === 'SYMBOLS') {
      for (const provider of WorkspaceSymbolProviderRegistry.all()) {
        cancelled(token);
        for (const symbol of await provider.provideWorkspaceSymbols(query.query ?? '', token) ?? []) {
          add(symbol.location.uri, symbol.location.range, { name: symbol.name.slice(0, 2000), kind: symbol.kind });
        }
      }
    } else if (query.operation === 'DOCUMENT_SYMBOLS') {
      for (const provider of features.documentSymbolProvider.ordered(model)) {
        const pending: DocumentSymbol[] = [...await provider.provideDocumentSymbols(model, token) ?? []].reverse();
        for (let symbol = pending.pop(); symbol !== undefined; symbol = pending.pop()) {
          add(model.uri, symbol.selectionRange, { name: symbol.name.slice(0, 2000), kind: symbol.kind });
          if (truncated) break;
          if (symbol.children) pending.push(...[...symbol.children].reverse());
        }
      }
    } else if (query.operation === 'DIAGNOSTICS') {
      for (const marker of markers.read({ resource: model.uri })) {
        add(marker.resource, marker, { message: marker.message.slice(0, 2000), severity: marker.severity });
      }
    } else if (query.operation === 'INCOMING_CALLS' || query.operation === 'OUTGOING_CALLS') {
      for (const provider of CallHierarchyProviderRegistry.ordered(model)) {
        const session = await provider.prepareCallHierarchy(model, position, token);
        if (!session) continue;
        try {
          for (const root of session.roots) {
            cancelled(token);
            if (query.operation === 'INCOMING_CALLS') {
              for (const call of await provider.provideIncomingCalls(root, token) ?? []) {
                const calls = call.fromRanges.flatMap(range => { const location = locate(call.from.uri, range); return location ? [location] : []; });
                if (calls.length > 40) truncated = true;
                add(call.from.uri, call.from.selectionRange, { name: call.from.name.slice(0, 2000), kind: call.from.kind, calls: calls.slice(0, 40) });
              }
            } else {
              for (const call of await provider.provideOutgoingCalls(root, token) ?? []) {
                const calls = call.fromRanges.flatMap(range => { const location = locate(root.uri, range); return location ? [location] : []; });
                if (calls.length > 40) truncated = true;
                add(call.to.uri, call.to.selectionRange, { name: call.to.name.slice(0, 2000), kind: call.to.kind, calls: calls.slice(0, 40) });
              }
            }
          }
        } finally { session.dispose(); }
      }
    } else {
      const links: LocationLink[] = [];
      if (query.operation === 'REFERENCES') {
        for (const provider of features.referenceProvider.ordered(model)) links.push(...await provider.provideReferences(model, position, { includeDeclaration: true }, token) ?? []);
      } else {
        const values = query.operation === 'DEFINITIONS'
          ? await Promise.all(features.definitionProvider.ordered(model).map(provider => provider.provideDefinition(model, position, token)))
          : await Promise.all(features.implementationProvider.ordered(model).map(provider => provider.provideImplementation(model, position, token)));
        for (const value of values) if (value) links.push(...(Array.isArray(value) ? value : [value]));
      }
      for (const link of links) add(link.uri, link.targetSelectionRange ?? link.range);
    }
    cancelled(token);
    if (dirty()) return queryFailure('UNSAVED', 'Source was edited in the IDE during the query. Save or discard it before retrying.');
    if (changed || model.getVersionId() !== version) return queryFailure('STALE', 'The source changed during the query. Read the current source and retry.');
    return { status: 'READY', items, truncated, message: (query.operation === 'DIAGNOSTICS'
      ? 'Current published diagnostics; an empty result is not proof that a build or test passed.'
      : 'Language-service results for imported projects. Coverage may be incomplete while project analysis is running.')
      + (external ? ' Locations outside this working copy were omitted.' : '') };
  } finally { changes.dispose(); reference.dispose(); }
}

export function installSemanticQueries(workspace: URI): () => void {
  const active = new Map<string, CancellationTokenSource>();
  const stop = window.ideWorkbench.onQuery(event => {
    if (event.cancel) { active.get(event.id)?.cancel(); return; }
    if (active.has(event.id)) return;
    if (active.size >= 4) {
      window.ideWorkbench.queryReply(event.id, queryFailure('UPDATING', 'Previous language queries are still stopping. Retry after they finish.'));
      return;
    }
    const source = new CancellationTokenSource();
    active.set(event.id, source);
    const timeout = setTimeout(() => source.cancel(), Math.max(0, event.expiresAt - Date.now()));
    void semanticQuery(workspace, event.query, source.token).catch(error => queryFailure(source.token.isCancellationRequested ? 'CANCELLED' : 'UNAVAILABLE',
      String(error).slice(0, 4000))).then(reply => window.ideWorkbench.queryReply(event.id, reply)).finally(() => {
      clearTimeout(timeout); active.delete(event.id); source.dispose();
    });
  });
  window.ideWorkbench.queryReady();
  return () => { stop(); for (const source of active.values()) source.cancel(); };
}
