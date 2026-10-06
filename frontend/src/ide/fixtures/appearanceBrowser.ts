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
/* eslint-disable import/no-unresolved -- Exercise the pinned native editor and real grammar resources. */
import { initialize, getService, ICodeEditorService } from '@codingame/monaco-vscode-api';
import { registerExtension, ExtensionHostKind } from '@codingame/monaco-vscode-api/extensions';
import { IWorkbenchThemeService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/themes/common/workbenchThemeService.service';
import { FoldingController } from '@codingame/monaco-vscode-api/vscode/vs/editor/contrib/folding/browser/folding';
import getConfiguration, { initUserConfiguration } from '@codingame/monaco-vscode-configuration-service-override';
import getModel from '@codingame/monaco-vscode-model-service-override';
import getTheme from '@codingame/monaco-vscode-theme-service-override';
import getTextmate from '@codingame/monaco-vscode-textmate-service-override';
import getLanguages from '@codingame/monaco-vscode-languages-service-override';
import getSearch from '@codingame/monaco-vscode-search-service-override';
import getViews from '@codingame/monaco-vscode-views-service-override';
import EditorWorker from '@codingame/monaco-vscode-api/workers/editor.worker?worker';
import TextmateWorker from '@codingame/monaco-vscode-textmate-service-override/worker?worker';
import '@codingame/monaco-vscode-java-default-extension';
import '@codingame/monaco-vscode-go-default-extension';
import '@codingame/monaco-vscode-rust-default-extension';
import { editor, languages, Uri } from 'monaco-editor';
import { installHeaderFolding } from '../folding';
import light from '../ideaLight.json';
import dark from '../darcula.json';
import { assert } from '../../fixtures/harness';
import { verifyFileAppearance } from './fileAppearance';
import { verifyUsages } from './usagesBrowser';
import { verifySearch } from './searchBrowser';

export async function run() {
  window.MonacoEnvironment = { getWorker: (_id, label) => label === 'TextMateWorker' ? new TextmateWorker() : new EditorWorker() };
  await initUserConfiguration(JSON.stringify({ 'workbench.colorTheme': light.name, 'editor.foldingImportsByDefault': true, 'editor.foldingHighlight': false, 'editor.bracketPairColorization.enabled': false }));
  const extension = registerExtension({ name: 'appearance', publisher: 'bytequay', version: '1.0.0', engines: { vscode: '*' },
    contributes: { themes: [
      { id: light.name, label: light.name, uiTheme: 'vs', path: './light.json' },
      { id: dark.name, label: dark.name, uiTheme: 'vs-dark', path: './dark.json' },
    ] } }, ExtensionHostKind.LocalProcess);
  extension.registerFileUrl('light.json', `data:application/json,${encodeURIComponent(JSON.stringify(light))}`);
  extension.registerFileUrl('dark.json', `data:application/json,${encodeURIComponent(JSON.stringify(dark))}`);
  const container = document.createElement('div');
  container.style.cssText = 'width:1050px;height:680px'; document.body.append(container);
  await initialize({ ...getConfiguration(), ...getModel(), ...getViews(), ...getTheme(), ...getTextmate(), ...getLanguages(), ...getSearch() });
  installHeaderFolding(await getService(ICodeEditorService));
  const code = editor.create(container, { automaticLayout: true, fontSize: 16, fontFamily: 'Menlo, monospace', minimap: { enabled: false } });
  const source = `/*
 * Copyright ByteQuay
 * Licensed under the Apache License, Version 2.0
 */
package example;

import java.util.List;
import java.util.Set;

public class Example {
    private static final String NAME = "hello";
    public String message() {
        return NAME;
    }
}`;
  const model = editor.createModel(source, 'java', Uri.parse('vscode-remote://fixture/Example.java'));
  code.setModel(model);
  // Simulate ranges arriving after project import; the real native folding controller applies them.
  const provider = languages.registerFoldingRangeProvider('java', { provideFoldingRanges: () => [
    { start: 1, end: 4, kind: languages.FoldingRangeKind.Comment },
    { start: 7, end: 8, kind: languages.FoldingRangeKind.Imports },
    { start: 10, end: 15 }, { start: 12, end: 14 },
  ] });
  const theme = await getService(IWorkbenchThemeService);
  const until = async (predicate: () => boolean, message: string) => {
    for (let attempt = 0; attempt < 100; attempt++) {
      if (predicate()) return;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    throw new Error(message);
  };
  const folding = await FoldingController.get(code)?.getFoldingModel();
  assert(folding != null, 'Native folding must initialize');
  await until(() => folding.getRegionAtLine(1)?.isCollapsed === true && folding.getRegionAtLine(7)?.isCollapsed === true, 'License and imports must fold on first open');
  assert(!folding.getRegionAtLine(10)?.isCollapsed && !folding.getRegionAtLine(12)?.isCollapsed, 'Class and method bodies remain expanded');
  const header = folding.getRegionAtLine(1);
  assert(header != null, 'License region must exist'); folding.toggleCollapseState([header]);
  model.applyEdits([{ range: { startLineNumber: 11, endLineNumber: 11, startColumn: 1, endColumn: 1 }, text: ' ' }]);
  await new Promise(resolve => setTimeout(resolve, 500));
  assert(!folding.getRegionAtLine(1)?.isCollapsed, 'Editing must not refold a manually expanded license');
  folding.toggleCollapseState([header]);
  code.setPosition({ lineNumber: 10, column: 1 });
  const results: Record<string, number> = {};
  for (const palette of [light, dark]) {
    const nativeTheme = (await theme.getColorThemes()).find(item => item.settingsId === palette.name);
    assert(nativeTheme !== undefined, `Missing theme ${palette.name}`);
    await theme.setColorTheme(nativeTheme.id, undefined);
    for (const [language, text] of [
      ['java', source], ['go', 'package main\nfunc main() { println("hello") }'], ['rust', 'fn main() { let message = "hello"; println!("{}", message); }'],
    ]) {
      const html = await editor.colorize(text, language, {});
      const sample = document.createElement('div'); sample.innerHTML = html; document.body.append(sample);
      const colors = new Set([...sample.querySelectorAll('span')].map(span => getComputedStyle(span).color));
      for (const expected of [palette.semanticTokenColors.keyword.foreground, palette.semanticTokenColors.string, palette.semanticTokenColors.method]) {
        sample.style.color = expected;
        assert(colors.has(sample.style.color), `${palette.name}: ${language} must use ${expected}, got ${[...colors]}`);
      }
      if (language === 'java') {
        const type = [...sample.querySelectorAll('span')].find(span => span.textContent === 'String');
        sample.style.color = palette.semanticTokenColors.class;
        assert(type !== undefined && getComputedStyle(type).color === sample.style.color && getComputedStyle(type).fontStyle !== 'italic', 'Java object types use the type color, not the keyword color');
      }
      sample.remove();
      assert(colors.size >= 4, `${palette.name}: ${language} must have TextMate syntax colors without a language server: ${[...colors]}`);
      results[`${palette.type}.${language}`] = colors.size;
    }
  }
  provider.dispose();
  await verifyFileAppearance();
  await verifyUsages();
  await verifySearch();
  return results;
}
