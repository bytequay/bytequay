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
/* eslint-disable import/no-unresolved -- Search and outlines share the pinned IDE services. */
import { getService, IInstantiationService, ITextModelService, IOutlineModelService, ILanguageFeaturesService } from '@codingame/monaco-vscode-api';
import { CancellationTokenSource, type CancellationToken } from '@codingame/monaco-vscode-api/vscode/vs/base/common/cancellation';
import { raceCancellationError } from '@codingame/monaco-vscode-api/vscode/vs/base/common/async';
import { prepareQuery, scoreItemFuzzy, compareItemsByFuzzyScore, type FuzzyScorerCache } from '@codingame/monaco-vscode-api/vscode/vs/base/common/fuzzyScorer';
import { matchesFuzzy } from '@codingame/monaco-vscode-api/vscode/vs/base/common/filters';
import type { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import type { IRange } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/core/range';
import { SymbolKind, type DocumentSymbol } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/languages';
import type { ITextModel } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/model';
import { WorkspaceSymbolProviderRegistry } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/search/common/search';
import { ISearchService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/search/common/search.service';
import { QueryBuilder } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/search/common/queryBuilder';
import { resultIsMatch, isFileMatch, type IFileMatch } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/search/common/search';
import { fileName, isTestFile } from '../pulls/code-tests/codeModel';

export type SearchTab = 'All' | 'Classes' | 'Files' | 'Symbols' | 'Text';
export type SearchRow = {
  id: string; uri: URI; range: IRange; name: string; detail: string;
  kind: 'class' | 'field' | 'symbol' | 'file' | 'text'; symbolKind?: SymbolKind;
  resolve?: (token: CancellationToken) => Promise<{ uri: URI; range: IRange }>;
};
export type SearchResults = { rows: SearchRow[]; messages: string[]; limited: boolean };
const limit = 30;
// All searches the workbench's source languages; Text also searches data and logs.
const sourcePattern = '**/*.{java,go,rs,js,jsx,ts,tsx,mjs,cjs,mts,cts}';
const sourceFile = /\.(java|go|rs|[cm]?[jt]sx?)$/i;
// ponytail: bound member fallback to 20 outlines; use language-server member search if broader coverage is needed.
const outlineLimit = 20;
const priority = { class: 0, field: 1, symbol: 2, file: 3, text: 4 };
const start: IRange = { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1 };

function symbolKind(kind: SymbolKind): SearchRow['kind'] {
  if ([SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum, SymbolKind.Struct].includes(kind)) return 'class';
  return [SymbolKind.Field, SymbolKind.Property, SymbolKind.Constant, SymbolKind.Variable].includes(kind) ? 'field' : 'symbol';
}
export function relativeSearchPath(workspace: URI, uri: URI): string | undefined {
  return uri.scheme === workspace.scheme && uri.authority === workspace.authority && uri.path.startsWith(workspace.path + '/')
    ? uri.path.slice(workspace.path.length + 1) : undefined;
}

/** Module labels use the nearest project manifest, including nested Maven, Cargo and Go projects. */
export function searchModule(path: string, manifests: string[], repository: string): string {
  const directory = manifests.find(directory => path.startsWith(directory + '/'));
  return directory ? fileName(directory) : repository;
}

/** Native search honors ignore settings and includes dirty editor buffers. No separate index is built. */
export async function searchCode(workspace: URI, query: string, tab: SearchTab, model: ITextModel | undefined,
  token: CancellationToken, publish: (results: SearchResults) => void): Promise<void> {
  const parentToken = token;
  const prepared = prepareQuery(query);
  const scores: FuzzyScorerCache = {};
  const accessor = {
    getItemLabel: (row: SearchRow) => row.name,
    getItemDescription: (row: SearchRow) => relativeSearchPath(workspace, row.uri)?.replace(/\/[^/]*$/, '') ?? '',
    getItemPath: (row: SearchRow) => relativeSearchPath(workspace, row.uri),
  };
  const relevance = (row: SearchRow) => {
    if (row.kind === 'text') return 3;
    const name = row.name.toLowerCase();
    if (name === prepared.normalizedLowercase || row.kind === 'file' && name.replace(/\.[^.]+$/, '') === prepared.normalizedLowercase) return 0;
    const score = scoreItemFuzzy(row, prepared, true, accessor, scores);
    return score.labelMatch?.length && !score.descriptionMatch?.length ? 1 : 2;
  };
  const compare = (a: SearchRow, b: SearchRow) => Number(relevance(a) !== 0) - Number(relevance(b) !== 0)
    || priority[a.kind] - priority[b.kind]
    || (model ? 0 : Number(isTestFile(a.uri.path)) - Number(isTestFile(b.uri.path)))
    || relevance(a) - relevance(b)
    || (a.kind === 'text' ? 0 : compareItemsByFuzzyScore(a, b, prepared, true, accessor, scores))
    || a.uri.path.localeCompare(b.uri.path) || a.range.startLineNumber - b.range.startLineNumber || a.range.startColumn - b.range.startColumn;
  const rows = new Map<string, SearchRow>();
  const messages = new Set<string>();
  let limited = false;
  const emit = () => {
    if (parentToken.isCancellationRequested) return;
    const sorted = [...rows.values()].sort(compare);
    publish({ rows: sorted.slice(0, limit), messages: [...messages], limited: limited || sorted.length > limit });
  };
  const add = (row: Omit<SearchRow, 'id'>) => {
    if (token.isCancellationRequested || (!model && relativeSearchPath(workspace, row.uri) === undefined)) return;
    if (tab === 'Classes' && row.kind !== 'class' || tab === 'Files' && row.kind !== 'file'
      || tab === 'Symbols' && ['file', 'text'].includes(row.kind) || tab === 'Text' && row.kind !== 'text') return;
    // A source file and its namesake type share one result, upgraded to the type's declaration when available.
    const representsFile = row.kind === 'file' || row.kind === 'class' && row.name === fileName(row.uri.path).replace(/\.[^.]+$/, '');
    const id = representsFile ? `${row.uri}:file` : `${row.uri}:${row.kind}:${row.range.startLineNumber}:${row.kind === 'text' ? row.range.startColumn : row.name}`;
    const previous = rows.get(id);
    if (previous?.kind === 'class' && row.kind === 'file') return;
    rows.set(id, { ...row, detail: previous?.kind === row.kind && previous.detail || row.detail, resolve: previous?.resolve ?? row.resolve, id });
    if (rows.size > limit) {
      limited = true;
      const last = [...rows.values()].sort(compare).at(-1);
      if (last) rows.delete(last.id);
    }
  };
  const attempt = async (name: string, work: (token: CancellationToken) => Promise<void>, budget?: number) => {
    if (token.isCancellationRequested) return;
    const task = new CancellationTokenSource(token);
    const timer = budget === undefined ? undefined : setTimeout(() => {
      limited = true;
      messages.add(`${name} are still loading. Showing available matches; refine your query or try again.`);
      task.cancel();
    }, budget);
    try { await raceCancellationError(work(task.token), task.token); }
    catch (error) { if (!task.token.isCancellationRequested) messages.add(`${name}: ${error instanceof Error ? error.message : String(error)}`); }
    finally { clearTimeout(timer); task.dispose(true); }
    emit();
  };
  if (model) {
    const matches = model.findMatches(query, false, false, false, null, false, limit + 1);
    limited = matches.length > limit;
    matches.slice(0, limit).forEach(match => add({ uri: model.uri, range: match.range, kind: 'text', name: model.getLineContent(match.range.startLineNumber).trim(),
      detail: `${fileName(model.uri.path)}:${match.range.startLineNumber}` }));
    emit(); return;
  }
  const [search, instantiation, models, outlines, features] = await Promise.all([
    getService(ISearchService), getService(IInstantiationService), getService(ITextModelService), getService(IOutlineModelService), getService(ILanguageFeaturesService),
  ]);
  if (token.isCancellationRequested) return;
  const source = new CancellationTokenSource(parentToken);
  token = source.token;
  const timeout = setTimeout(() => {
    limited = true;
    messages.add('Search took too long. Showing available matches; refine your query or try again.');
    source.cancel();
  }, 5000);
  try {
    const builder = instantiation.createInstance(QueryBuilder);
    const candidates = new Map<string, URI>();
    const symbols = tab !== 'Files' && tab !== 'Text';
    const jobs: Promise<void>[] = [];
    if (symbols) {
      const providers = WorkspaceSymbolProviderRegistry.all();
      if (!providers.length) messages.add('Project symbols are not ready. File and text matches remain available while language tools load.');
      for (const provider of providers) jobs.push(attempt('Symbols', async token => {
        for (const symbol of await provider.provideWorkspaceSymbols(query, token) ?? []) {
          if (token.isCancellationRequested) return;
          add({ uri: symbol.location.uri, range: symbol.location.range, kind: symbolKind(symbol.kind), symbolKind: symbol.kind,
            name: symbol.name, detail: symbol.containerName ?? relativeSearchPath(workspace, symbol.location.uri) ?? '',
            resolve: provider.resolveWorkspaceSymbol ? async token => (await provider.resolveWorkspaceSymbol?.(symbol, token) ?? symbol).location : undefined });
        }
      }, 1500));
    }
    if (tab !== 'Text') jobs.push(attempt('Files', async token => {
      const result = await search.fileSearch(builder.file([workspace], { filePattern: query, maxResults: limit, sortByScore: true }), token);
      limited ||= !!result.limitHit;
      for (const file of result.results) {
        if (token.isCancellationRequested) return;
        if (relativeSearchPath(workspace, file.resource) === undefined) continue;
        const row: SearchRow = { id: file.resource.toString(), uri: file.resource, range: start, kind: 'file',
          name: fileName(file.resource.path), detail: relativeSearchPath(workspace, file.resource) ?? '' };
        // Native fuzzy path matches can match only directory names, filling All with unrelated fixtures.
        if (tab !== 'Files' && !prepared.containsPathSeparator && relevance(row) === 2) continue;
        candidates.set(file.resource.toString(), file.resource);
        add(row);
      }
    }));
    const examined = new Set<string>();
    const enrich = async () => {
      if (!symbols || token.isCancellationRequested || !candidates.size) return;
      if ([...rows.values()].filter(row => !['file', 'text'].includes(row.kind)).length >= limit) return;
      const ranked = [...candidates.values()].filter(uri => sourceFile.test(uri.path) && !examined.has(uri.toString())).map(uri => ({
        id: uri.toString(), uri, range: start, kind: 'file' as const, name: fileName(uri.path), detail: relativeSearchPath(workspace, uri) ?? '',
      })).sort(compare);
      const available = outlineLimit - examined.size;
      limited ||= ranked.length > available;
      const remaining = ranked.slice(0, available).map(row => row.uri);
      await Promise.all(Array.from({ length: Math.min(4, remaining.length) }, async () => {
        while (remaining.length && !token.isCancellationRequested) {
          const uri = remaining.shift();
          if (!uri) return;
          examined.add(uri.toString());
          await attempt('File symbols', async token => {
            const reference = await raceCancellationError(models.createModelReference(uri).then(reference => {
              // Model loading is not cancellable; dispose a late reference even after this search has returned.
              if (token.isCancellationRequested) { reference.dispose(); return undefined; }
              return reference;
            }), token);
            if (!reference) return;
            try {
              if (token.isCancellationRequested) return;
              const model = reference.object.textEditorModel;
              if (!features.documentSymbolProvider.has(model)) return;
              const outline = await raceCancellationError(outlines.getOrCreate(model, token), token);
              const visit = (symbols: DocumentSymbol[], container = '') => {
                for (const symbol of symbols) {
                  if (matchesFuzzy(query, symbol.name, true)) add({ uri, range: symbol.selectionRange, kind: symbolKind(symbol.kind), symbolKind: symbol.kind,
                    name: symbol.name, detail: container || relativeSearchPath(workspace, uri) || '' });
                  if (symbol.children) visit(symbol.children, symbol.name);
                }
              };
              visit(outline.getTopLevelSymbols());
            } finally { reference.dispose(); }
          });
        }
      }));
    };
    await Promise.all(jobs);
    await enrich();
    if (tab !== 'Files' && rows.size < limit && !token.isCancellationRequested) await attempt('Text', async token => {
      const collect = (file: IFileMatch) => {
        if (relativeSearchPath(workspace, file.resource) === undefined || token.isCancellationRequested) return;
        candidates.set(file.resource.toString(), file.resource);
        for (const match of file.results ?? []) {
          if (!resultIsMatch(match)) continue;
          for (const { source, preview } of match.rangeLocations) {
            // Search ranges are zero-based; editor selections are one-based.
            const range = { startLineNumber: source.startLineNumber + 1, startColumn: source.startColumn + 1,
              endLineNumber: source.endLineNumber + 1, endColumn: source.endColumn + 1 };
            add({ uri: file.resource, range, kind: 'text', name: (match.previewText.split('\n')[preview.startLineNumber] ?? match.previewText).trim(),
              detail: `${fileName(file.resource.path)}:${range.startLineNumber}` });
          }
        }
        emit();
      };
      // The native progress stream substitutes dirty buffers and suppresses stale disk hits.
      const result = await search.textSearch(builder.text({ pattern: query, isRegExp: false, isCaseSensitive: false }, [workspace],
        { maxResults: limit - rows.size, ...(tab === 'Text' ? {} : { includePattern: sourcePattern }), previewOptions: { matchLines: 1, charsPerLine: 500 } }), token,
        item => { if (isFileMatch(item)) collect(item); });
      limited ||= !!result.limitHit;
      result.messages.forEach(message => messages.add(message.text));
    });
    await enrich();
    emit();
  } finally { clearTimeout(timeout); source.dispose(true); }
}
