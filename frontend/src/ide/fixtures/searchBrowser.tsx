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
/* eslint-disable import/no-unresolved -- Exercise native search services, models and command dispatch. */
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { getService, ICodeEditorService, IEditorService, IFileService, ICommandService } from '@codingame/monaco-vscode-api';
import { attachPart, Parts } from '@codingame/monaco-vscode-views-service-override';
import { IWorkbenchThemeService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/themes/common/workbenchThemeService.service';
import { InMemoryFileSystemProvider } from '@codingame/monaco-vscode-api/vscode/vs/platform/files/common/inMemoryFilesystemProvider';
import { VSBuffer } from '@codingame/monaco-vscode-api/vscode/vs/base/common/buffer';
import { match } from '@codingame/monaco-vscode-api/vscode/vs/base/common/glob';
import { matchesFuzzy } from '@codingame/monaco-vscode-api/vscode/vs/base/common/filters';
import { SymbolKind } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/languages';
import { WorkspaceSymbolProviderRegistry } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/search/common/search';
import { ISearchService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/search/common/search.service';
import { SearchProviderType, TextSearchMatch, SearchRange, type ISearchResultProvider } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/search/common/search';
import type { ITextModel } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/model';
import { languages, Uri } from 'monaco-editor';
import Search from '../Search';
import { assert, settle, changeInput } from '../../fixtures/harness';

export async function verifySearch() {
  const host = document.createElement('div'); host.className = 'ide-workbench is-dark';
  host.style.cssText = 'position:fixed;inset:0;z-index:100'; host.style.colorScheme = 'dark'; document.body.append(host);
  const native = document.createElement('div'); native.style.cssText = 'height:100%;width:100%'; host.append(native);
  const shell = document.createElement('div'); host.append(shell);
  const attached = attachPart(Parts.EDITOR_PART, native);
  const editors = await getService(IEditorService), codeEditors = await getService(ICodeEditorService), files = await getService(IFileService);
  const search = await getService(ISearchService), commands = await getService(ICommandService);
  const theme = await getService(IWorkbenchThemeService);
  const workspace = Uri.parse('search-fixture:/repository');
  const uri = (path: string) => Uri.joinPath(workspace, path);
  const sources = new Map([
    ['core/query-engine/src/main/java/QueryService.java', 'class QueryService {\n    int queryLimit;\n    void run() { queryLimit++; }\n}'],
    ['core/query-engine/src/test/java/TestQueryService.java', 'class TestQueryService {\n    int queryLimit;\n    void test() { queryLimit++; }\n}'],
    ['tools/query_test.go', 'package tests\n// query usage'],
    ['rust/tests/query.rs', '// query usage'],
    ['plugin/iceberg/src/test/resources/iceberg/metadata/00000-data.json', '{"name":"IcebergMetadata"}'],
    ['plugin/iceberg/src/test/java/ATestIcebergMetadata.java', 'class ATestIcebergMetadata {}'],
    ['plugin/iceberg/src/main/java/IcebergMetadataColumn.java', 'class IcebergMetadataColumn {}'],
    ['plugin/iceberg/src/main/java/IcebergMetadata.java', 'class IcebergMetadata {}'],
    ['src/main/java/Stalled.java', 'class Stalled {}'],
  ]);
  const mainPath = [...sources.keys()][0];
  const memory = new InMemoryFileSystemProvider();
  const disk = files.registerProvider(workspace.scheme, memory);
  await files.createFolder(workspace);
  for (const [path, value] of sources) {
    await files.createFolder(uri(path.slice(0, path.lastIndexOf('/'))));
    await files.writeFile(uri(path), VSBuffer.fromString(value));
  }
  let cancelled = false;
  let slowSymbols = false;
  let symbolsCancelled = false;
  let releaseSymbols: (() => void) | undefined;
  let textSearches = 0;
  let outlineCalls = 0;
  let outlineCancelled = false;
  let releaseOutline: (() => void) | undefined;
  let stalledModel: ITextModel | undefined;
  let release: (() => void) | undefined;
  const provider: ISearchResultProvider = {
    getAIName: async () => undefined, clearCache: async () => {},
    fileSearch: async query => {
      assert(query.maxResults === 30, 'File discovery requests at most 30 matches');
      return { messages: [], results: [...sources.keys()].filter(path => matchesFuzzy(query.filePattern ?? '', path, true)).map(path => ({ resource: uri(path) })) };
    },
    textSearch: async (query, onProgress, token) => {
      textSearches++;
      assert(query.maxResults !== undefined && query.maxResults <= 30, 'Content search requests only the remaining result slots');
      assert(!query.contentPattern.isRegExp && !query.contentPattern.isCaseSensitive, 'Text queries use native literal, case-insensitive search');
      if (query.contentPattern.pattern === 'failure') throw new Error('Fixture text search failure');
      if (query.contentPattern.pattern === 'slow') {
        token?.onCancellationRequested(() => { cancelled = true; });
        await new Promise<void>(resolve => { release = resolve; });
      }
      for (const [path, content] of sources) {
        if (query.includePattern && !match(query.includePattern, path)) continue;
        const results = content.split('\n').flatMap((line, i) => {
          const column = line.toLowerCase().indexOf(query.contentPattern.pattern.toLowerCase());
          return column < 0 ? [] : [new TextSearchMatch(line, new SearchRange(i, column, i, column + query.contentPattern.pattern.length))];
        });
        if (results.length) onProgress?.({ resource: uri(path), results });
      }
      return { results: [], messages: [], limitHit: query.contentPattern.pattern === 'query' };
    },
  };
  const searches = [search.registerSearchResultProvider(workspace.scheme, SearchProviderType.file, provider), search.registerSearchResultProvider(workspace.scheme, SearchProviderType.text, provider)];
  const classSymbols = [...sources.keys()].filter(path => path.endsWith('.java')).map(path => ({ name: path.split('/').at(-1)?.replace(/\.java$/, '') ?? '', kind: SymbolKind.Class,
    containerName: 'example.query', location: { uri: uri(path), range: { startLineNumber: 1, startColumn: 7, endLineNumber: 1, endColumn: 19 } } }));
  const symbols = WorkspaceSymbolProviderRegistry.register({ provideWorkspaceSymbols: async (query, token) => {
    if (slowSymbols) {
      slowSymbols = false;
      token.onCancellationRequested(() => { symbolsCancelled = true; });
      await new Promise<void>(resolve => { releaseSymbols = resolve; });
    }
    if (query === 'Bulk') return Array.from({ length: 1000 }, (_, i) => ({ ...classSymbols[0],
      name: `Bulk${i}`, location: { ...classSymbols[0].location, uri: uri(`${i < 500 ? 'src/test' : 'src/main'}/java/Bulk${i}.java`) },
    }));
    return [
      ...classSymbols.filter(symbol => symbol.name.toLowerCase().includes(query.toLowerCase())),
      { ...classSymbols[0], name: 'QueryExternal', location: { ...classSymbols[0].location, uri: Uri.parse('search-fixture:/external/QueryExternal.java') } },
    ];
  } });
  const outline = languages.registerDocumentSymbolProvider('java', { provideDocumentSymbols: async (model, token) => {
    outlineCalls++;
    const result: languages.DocumentSymbol[] = [{
      name: model.uri.path.split('/').at(-1)?.replace(/\.java$/, '') ?? '', detail: '', tags: [], kind: languages.SymbolKind.Class,
      range: model.getFullModelRange(), selectionRange: { startLineNumber: 1, startColumn: 7, endLineNumber: 1, endColumn: 19 },
      children: [{ name: 'queryLimit', detail: '', tags: [], kind: languages.SymbolKind.Field,
        range: { startLineNumber: 2, startColumn: 5, endLineNumber: 2, endColumn: 20 }, selectionRange: { startLineNumber: 2, startColumn: 9, endLineNumber: 2, endColumn: 19 } }],
    }];
    if (model.uri.path.endsWith('/Stalled.java')) {
      stalledModel = model;
      token.onCancellationRequested(() => { outlineCancelled = true; });
      await new Promise<void>(resolve => { releaseOutline = resolve; });
    }
    return result;
  } });
  const root = createRoot(shell);
  flushSync(() => root.render(<Search editors={codeEditors} workspace={workspace} files={{ source: 'WORKING_COPY', revision: 'fixture', truncated: false, sourceFingerprint: null, savedExperiment: false,
    files: [...sources.keys(), 'core/query-engine/pom.xml', 'tools/go.mod', 'rust/Cargo.toml'].map(path => ({ path, changedInPr: false, addedInPr: false, onlyInWorkingCopy: false, uncommitted: false })) }} />));
  const popup = () => host.querySelector<HTMLElement>('.ide-search-popup');
  const rows = () => [...host.querySelectorAll<HTMLButtonElement>('.ide-search-row')];
  const input = () => { const value = host.querySelector<HTMLInputElement>('[aria-label="Search query"]'); assert(value !== null, 'Search input is present'); return value; };
  const until = async (predicate: () => boolean, message: string) => {
    for (let attempt = 0; attempt < 160; attempt++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 50)); }
    throw new Error(`${message}: ${popup()?.textContent ?? 'No search popup'}`);
  };
  const key = (key: string) => input().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
  const category = (label: string) => { const button = [...host.querySelectorAll<HTMLButtonElement>('nav button')].find(button => button.textContent === label); assert(button !== undefined, `Missing ${label} filter`); flushSync(() => button.click()); };
  const idle = () => !host.querySelector('[aria-busy="true"]');
  try {
    await editors.openEditor({ resource: uri(mainPath), options: { pinned: true } });
    const code = codeEditors.getActiveCodeEditor(); assert(code !== null, 'Source editor is active');
    code.focus();
    await commands.executeCommand('workbench.action.findInFiles'); await settle(); changeInput(input(), 'query');
    await until(() => idle() && rows().some(row => row.dataset.kind === 'field'), 'Outlines supply fields omitted by the workspace symbol provider');
    const kinds = rows().map(row => row.dataset.kind);
    assert(kinds[0] === 'class' && kinds.indexOf('field') > kinds.lastIndexOf('class') && kinds.indexOf('text') > kinds.lastIndexOf('file'), 'Classes, fields, files and text are ranked in that order');
    assert(!popup()?.textContent?.includes('QueryExternal'), 'Exclude symbols outside the project');
    assert(rows().filter(row => row.dataset.kind === 'class').length === 2, 'Workspace and document symbols are deduplicated');
    assert(rows().some(row => row.querySelector('.ide-search-module')?.textContent === 'query-engine'), 'Java results show their Maven module');
    assert(rows().some(row => row.querySelector('.ide-search-module')?.textContent === 'tools') && rows().some(row => row.querySelector('.ide-search-module')?.textContent === 'rust'), 'Go and Rust results show their nearest module');
    assert(popup()?.textContent?.includes('limited set'), 'Search result limits are explicit');
    for (const [name, mode] of [['ByteQuay Darcula', 'dark'], ['ByteQuay IDEA Light', 'light'], ['GitHub Dark', 'github-dark']] as const) {
      const dark = mode === 'dark' || mode === 'github-dark';
      const palette = (await theme.getColorThemes()).find(item => item.settingsId === name);
      assert(palette !== undefined, 'Search palette is registered'); await theme.setColorTheme(palette.id, undefined);
      host.dataset.theme = name;
      host.classList.toggle('is-dark', dark); host.style.colorScheme = dark ? 'dark' : 'light'; await settle();
      const testRows = rows().filter(row => row.classList.contains('is-test'));
      assert(testRows.length >= 3 && testRows.every(row => getComputedStyle(row).backgroundColor === (dark ? 'rgb(41, 56, 43)' : 'rgb(231, 242, 218)')), 'Test results are green in both themes');
      Object.assign(window, { searchCapture: mode });
      await until(() => (window as unknown as { searchCaptured: string }).searchCaptured === mode, 'Capture the search popup');
    }
    changeInput(input(), 'IcebergMetadata');
    await until(() => idle() && rows().some(row => row.title.endsWith('/IcebergMetadata.java:1')), 'Exact class is available among fuzzy path matches');
    assert(rows()[0].querySelector('.ide-search-title')?.textContent === 'IcebergMetadata', 'Exact production class ranks first');
    const icebergClasses = rows().filter(row => row.dataset.kind === 'class');
    assert(icebergClasses.length === 3 && icebergClasses.at(-1)?.classList.contains('is-test'), 'Production classes precede an alphabetically earlier test class');
    assert(!rows().some(row => row.title.includes('00000-data.json')), 'All excludes path-only fixtures and data text matches');
    category('Files');
    await until(() => idle() && rows().length === 4, 'Files retains fuzzy path matches');
    assert(rows()[0].title.endsWith('/IcebergMetadata.java:1'), 'Exact filename ranks above UUID fixture paths');
    category('Text');
    await until(() => idle() && rows().some(row => row.title.includes('00000-data.json')), 'Text can still search test data');
    category('All'); changeInput(input(), 'iceberg/metadata');
    await until(() => idle() && rows().some(row => row.title.includes('00000-data.json')), 'Explicit paths still find resources in All');
    const textBefore = textSearches, outlinesBefore = outlineCalls;
    changeInput(input(), 'Bulk');
    await until(() => idle() && rows().length === 30, 'Large result sets stop at 30');
    assert(rows().every(row => row.dataset.kind === 'class' && !row.classList.contains('is-test')), 'The best production classes survive a thousand results with tests first');
    assert(textSearches === textBefore && outlineCalls === outlinesBefore, 'Enough name matches skip content scanning and outline loading');
    slowSymbols = true; changeInput(input(), 'queryLimit');
    await until(() => releaseSymbols !== undefined, 'A slow symbol request starts');
    await until(() => idle() && rows().some(row => row.dataset.kind === 'field'), 'A stuck workspace provider cannot prevent outline fallback or completion');
    assert(symbolsCancelled && popup()?.textContent?.includes('still loading'), 'Slow symbols are cancelled with an explicit partial result');
    changeInput(input(), 'query');
    await until(() => idle() && rows().some(row => row.dataset.kind === 'class'), 'A new search completes before the old symbol provider returns');
    const freshResults = popup()?.textContent;
    releaseSymbols?.(); await settle();
    assert(popup()?.textContent === freshResults, 'Late symbol results cannot overwrite a newer search');
    changeInput(input(), 'Stalled');
    await until(() => releaseOutline !== undefined, 'An outline provider starts a slow request');
    changeInput(input(), 'query');
    await until(() => idle() && rows().some(row => row.dataset.kind === 'class'), 'New searches are independent of a stuck outline');
    assert(outlineCancelled, 'Replacing the query cancels outline work');
    await until(() => stalledModel?.isDisposed() === true, 'Cancellation releases the temporary file model before the outline provider returns');
    releaseOutline?.(); await settle();
    assert(!rows().some(row => row.title.includes('Stalled.java')), 'Cancelled outlines cannot publish late results');
    category('Classes'); await until(() => idle() && rows().length === 2, 'Classes filter contains only types');
    category('Symbols'); await until(() => idle() && rows().some(row => row.dataset.kind === 'field'), 'Symbols filter includes fields');
    assert(rows().every(row => !['text', 'file'].includes(row.dataset.kind ?? '')), 'Symbols exclude text and file matches');
    category('Files'); await until(() => idle() && rows().length === 4, 'Files filter searches filenames');
    category('Text'); await until(() => idle() && rows().length > 0 && rows().every(row => row.dataset.kind === 'text'), 'Text filter has matching lines');
    const target = rows().find(row => row.title.endsWith('TestQueryService.java:2'));
    assert(target !== undefined, 'A text result shows file and line'); target.click();
    await until(() => !popup() && editors.activeEditor?.resource?.path.endsWith('TestQueryService.java') === true, 'Click opens the chosen source location');
    assert(codeEditors.getActiveCodeEditor()?.getSelection()?.startLineNumber === 2, 'Navigation selects the exact source line');
    const current = codeEditors.getActiveCodeEditor(); assert(current !== null, 'Test editor is active');
    current.getModel()?.setValue('class TestQueryService {\n    int unsaved;\n    void last() { unsaved++; }\n}');
    current.setPosition({ lineNumber: 1, column: 1 });
    await commands.executeCommand('actions.find'); await settle(); changeInput(input(), 'unsaved');
    await until(() => idle() && rows().length === 2, 'Current-file search includes unsaved text');
    assert(rows()[0].title.endsWith(':2') && rows()[1].title.endsWith(':3'), 'In-file results follow source order');
    key('ArrowDown'); await settle(); key('Enter');
    await until(() => !popup() && current.getSelection()?.startLineNumber === 3, 'Keyboard navigation opens the selected result');
    await commands.executeCommand('workbench.action.findInFiles'); await settle(); changeInput(input(), 'unsaved'); category('Text');
    await until(() => idle() && rows().length === 2, 'Project search includes unsaved editor matches too');
    changeInput(input(), 'query');
    await until(() => idle() && rows().some(row => row.title === `${mainPath}:2`), 'Project search completes after an unsaved edit');
    assert(!rows().some(row => row.title.endsWith('TestQueryService.java:2')), 'Project search suppresses stale disk matches in dirty files');
    changeInput(input(), 'slow');
    await until(() => release !== undefined, 'A slow search starts');
    changeInput(input(), 'unsaved');
    await until(() => idle() && rows().length === 2, 'Replacement text search completes while the previous provider is still pending');
    assert(cancelled, 'Typing a new query cancels the previous native search');
    release?.(); await settle();
    assert(rows().length === 2 && rows().every(row => row.textContent?.includes('unsaved')), 'Late text results cannot replace the current query');
    release = undefined; cancelled = false; changeInput(input(), 'slow');
    await until(() => release !== undefined, 'Another slow search starts'); key('Escape'); await settle();
    assert(cancelled && !popup(), 'Escape cancels pending native search and restores the editor'); release?.();
    await commands.executeCommand('workbench.action.findInFiles'); await settle(); changeInput(input(), 'failure');
    await until(() => idle() && popup()?.textContent?.includes('Fixture text search failure') === true, 'Search errors are visible');
    changeInput(input(), 'no-such-result'); await until(() => idle() && popup()?.textContent?.includes('No matches found.') === true, 'Query changes recover from a failed search');
    key('Escape'); await settle();
    // Shift used for selecting or typing must not open search.
    for (let attempt = 0; attempt < 2; attempt++) {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Shift', shiftKey: true }));
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', shiftKey: true }));
      window.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowDown', shiftKey: true }));
      window.dispatchEvent(new KeyboardEvent('keyup', { key: 'Shift' }));
    }
    assert(!popup(), 'Ordinary Shift combinations do not activate search');
    for (const gesture of ['doubleShift', 'file', 'project']) {
      current.setPosition({ lineNumber: 1, column: 1 }); current.focus();
      Object.assign(window, { searchShortcut: gesture });
      await until(() => popup()?.getAttribute('aria-label') === (gesture === 'file' ? 'Search current file' : 'Search everywhere'), `Native ${gesture} opens the shared popup`);
      assert(!document.querySelector('.find-widget.visible'), 'The old in-file find widget stays hidden');
      key('Escape'); await settle();
    }
  } finally {
    flushSync(() => root.unmount()); symbols.dispose(); outline.dispose(); searches.forEach(item => item.dispose());
    await editors.activeEditorPane?.group.closeAllEditors({ excludeSticky: false });
    disk.dispose(); memory.dispose(); attached.dispose(); host.remove();
  }
}
