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
/* eslint-disable import/no-unresolved -- Exercise the same native outline and tabs as the workbench. */
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { getService, IEditorService, ITextModelService } from '@codingame/monaco-vscode-api';
import { attachPart, Parts } from '@codingame/monaco-vscode-views-service-override';
import { IWorkbenchThemeService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/themes/common/workbenchThemeService.service';
import { editor, languages, Uri } from 'monaco-editor';
import TreeItems from '../../pulls/code-tests/TreeItems';
import { buildFileTree } from '../../pulls/fileTree';
import JavaFileIcon from '../JavaFileIcon';
import { installTestTabColors } from '../testTabColors';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/code-tests.css';
import '../workbench.css';

export async function verifyFileAppearance() {
  const host = document.createElement('div'); host.className = 'ide-workbench';
  host.style.cssText = 'position:fixed;inset:0;z-index:100'; document.body.append(host);
  const tree = document.createElement('nav'); tree.className = 'ct ct-files ide-files'; host.append(tree);
  const native = document.createElement('div'); native.className = 'ide-editor'; native.style.cssText = 'flex:1;min-height:0'; host.append(native);
  const attached = attachPart(Parts.EDITOR_PART, native);
  const editors = await getService(IEditorService);
  const models = await getService(ITextModelService);
  const theme = await getService(IWorkbenchThemeService);
  const paths = ['src/main/java/Example.java', 'src/test/java/Example.java', 'src/main/java/Contract.java', 'src/main/java/Mode.java', 'package_test.go', 'tests/example.rs',
    'src/main/resources/config.xml', 'src/test/resources/config.xml', 'target/build.log', 'pom.xml', 'module/pom.xml',
    'module/src/main/java/Main.java', 'src/test/resources/target/fixture.xml', 'docs/java/notes.txt'];
  const uri = (path: string) => Uri.parse(`appearance:/project/${path}`);
  const contents = models.registerTextModelContentProvider('appearance', { provideTextContent: async resource => {
    const name = resource.path.split('/').pop()?.split('.')[0];
    return editor.getModel(resource) ?? editor.createModel(`public ${name === 'Contract' ? 'interface' : name === 'Mode' ? 'enum' : 'class'} ${name} {}`, 'java', resource);
  } });
  const symbols = languages.registerDocumentSymbolProvider('java', { provideDocumentSymbols: model => [{
    name: model.uri.path.split('/').pop()?.replace(/\.java$/, '') ?? '', detail: '', tags: [],
    kind: model.getValue().includes('interface') ? languages.SymbolKind.Interface : model.getValue().includes('enum') ? languages.SymbolKind.Enum : languages.SymbolKind.Class,
    range: model.getFullModelRange(), selectionRange: model.getFullModelRange(),
  }] });
  const stop = installTestTabColors(editors);
  const root = createRoot(tree);
  let opened = '';
  flushSync(() => root.render(<TreeItems items={buildFileTree(paths.map(path => ({ path, changedInPr: false, addedInPr: false, onlyInWorkingCopy: false, uncommitted: false })), file => file.path, { layout: 'ide' })}
    depth={0} selected={paths[1]} isOpen={() => true} onToggle={() => undefined} onOpen={path => { opened = path; }}
    renderFileIcon={path => path.endsWith('.java') ? <JavaFileIcon uri={uri(path).toString()} /> : undefined} />));
  const until = async (test: () => boolean, message: string) => {
    for (let attempt = 0; attempt < 100; attempt++) { if (test()) return; await new Promise(resolve => setTimeout(resolve, 50)); }
    throw new Error(message);
  };
  try {
    await until(() => tree.querySelector('[aria-label="Java interface"]') !== null && tree.querySelector('[aria-label="Java enum"]') !== null, 'Tree uses language-service class/interface/enum icons');
    const row = (path: string) => {
      const button = tree.querySelector<HTMLButtonElement>(`button[title="${path}"]`);
      assert(button !== null, `Missing tree row: ${path}`); return button;
    };
    assert(row(paths[0]).querySelector('[aria-label="Java class"]') !== null, 'Class has its own icon');
    assert(row(paths[0]).querySelector('.ct-tree__name')?.textContent === 'Example', 'Java suffix is hidden only in the label');
    assert(row(paths[4]).querySelector('.ct-tree__name')?.textContent === 'package_test.go', 'Other languages retain their suffix');
    assert(row('src/main/java').querySelector('[aria-label="Production Java sources"]') !== null, 'Production source root is identified');
    assert(row('module/src/main/java').querySelector('[aria-label="Production Java sources"]') !== null, 'Nested modules have source roots');
    assert(row('src/test/java').querySelector('[aria-label="Test Java sources"]') !== null, 'Test source root is identified');
    for (const scope of ['main', 'test']) assert(row(`src/${scope}/resources`).querySelector('.ct-tree__resource-badge') !== null, 'Resource roots have a distinct badge');
    assert(row('module').querySelector('[aria-label="Maven module"] .ct-tree__module-badge') !== null, 'Maven module retains its badge');
    assert(row('target').querySelector('[aria-label="Build output"]') !== null, 'Build output has its own icon');
    assert(row('src/test/resources/target').querySelector('[aria-label="Build output"]') === null, 'A target directory in resources is not build output');
    assert(row('docs/java').querySelector('[aria-label="Directory"]') !== null, 'An unrelated java directory remains generic');
    assert(row('pom.xml').querySelector('[aria-label="Maven POM"]') !== null, 'Maven POM has its own icon');
    row(paths[0]).click(); assert(opened === paths[0], 'Opening uses the full unchanged source path');
    for (const path of [paths[0], paths[1], paths[4], paths[5]]) await editors.openEditor({ resource: uri(path), options: { pinned: true } });
    const group = editors.activeEditorPane?.group;
    assert(group !== undefined, 'Native editor group opens');
    const tabs = () => [...native.querySelectorAll<HTMLElement>('.tabs-container .tab')];
    await until(() => tabs().length === 4 && tabs().filter(tab => tab.classList.contains('ide-test-tab')).length === 3, 'Only test tabs are green, including Go and Rust tests');
    const more = native.querySelector<HTMLElement>('.editor-actions .codicon-toolbar-more');
    assert(more !== null && more.getClientRects().length === 0, 'The native editor overflow menu is hidden');
    const split = native.querySelector<HTMLElement>('[aria-label^="Split Editor"]');
    assert(split !== null && split.getClientRects().length > 0, 'The split-editor action remains available');
    assert(!tabs()[0].classList.contains('ide-test-tab') && tabs()[1].classList.contains('ide-test-tab'), 'Same-named main/test files have different colors');
    for (const [name, dark] of [['ByteQuay IDEA Light', false], ['ByteQuay Darcula', true], ['GitHub Light', false], ['GitHub Dark', true]] as const) {
      host.classList.toggle('is-dark', dark); host.style.colorScheme = dark ? 'dark' : 'light';
      host.dataset.theme = name;
      const palette = (await theme.getColorThemes()).find(item => item.settingsId === name);
      assert(palette !== undefined, `Missing theme: ${name}`);
      await theme.setColorTheme(palette.id, undefined); await settle();
      if (name === 'GitHub Light' || name === 'GitHub Dark') {
        const background = getComputedStyle(tree).backgroundColor, foreground = getComputedStyle(host).color;
        assert(background === (dark ? 'rgb(36, 39, 46)' : 'rgb(246, 248, 250)') && foreground === (dark ? 'rgb(171, 178, 191)' : 'rgb(31, 35, 40)'), `GitHub colors apply to the file tree and shell: ${background}, ${foreground}`);
        assert(getComputedStyle(host).backgroundColor === (dark ? 'rgb(30, 32, 37)' : 'rgb(255, 255, 255)'), 'GitHub canvas uses the selected palette');
      }
      const tint = getComputedStyle(tabs()[1]).backgroundColor;
      assert(tint === (dark ? 'rgb(41, 56, 43)' : 'rgb(231, 242, 218)'), `${name} test tab has the green tint: ${tint}`);
      assert(getComputedStyle(row(paths[1])).backgroundColor === (dark ? 'rgb(52, 75, 54)' : 'rgb(214, 233, 196)'), `${name} selected test row keeps its green tint`);
      const iconColor = (path: string) => getComputedStyle(row(path).querySelector('.ct-tree__icon')!).color;
      assert(iconColor('src/main/java') === (dark ? 'rgb(100, 149, 237)' : 'rgb(53, 116, 232)'), `${name} production sources are blue`);
      assert(iconColor('src/test/java') === (dark ? 'rgb(128, 184, 116)' : 'rgb(57, 131, 73)'), `${name} test sources are green`);
      assert(iconColor('pom.xml') === iconColor('src/main/java'), `${name} Maven icon uses source blue`);
      assert(iconColor('target') !== iconColor('docs/java'), `${name} build output is distinct from a generic directory`);
    }
    const moved = group.getEditorByIndex(0);
    assert(moved !== undefined, 'Production file is open');
    group.moveEditor(moved, group, { index: 2 }); await settle();
    assert(!tabs()[2].classList.contains('ide-test-tab') && tabs()[0].classList.contains('ide-test-tab'), 'Color follows the resource after tab reorder');
    await group.closeEditor(moved); await settle();
    assert(tabs().every(tab => tab.classList.contains('ide-test-tab')), 'Closing a source tab preserves remaining test colors');
    const contract = editor.getModel(uri(paths[2]));
    assert(contract !== null, 'Visible interface shares an editor model');
    contract.setValue('public class Contract {}');
    await until(() => row(paths[2]).querySelector('[aria-label="Java class"]') !== null, 'Unsaved type edits update the icon');
  } finally {
    await editors.activeEditorPane?.group.closeAllEditors();
    flushSync(() => root.unmount()); stop(); symbols.dispose(); contents.dispose(); attached.dispose(); host.remove();
  }
}
