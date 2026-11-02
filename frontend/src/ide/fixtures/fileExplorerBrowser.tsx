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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { Uri } from 'monaco-editor';
import { assert, element, settle, until } from '../../fixtures/harness';
import type { ReviewCodeFilesDto } from '../../pulls/code-tests/contracts';
import type { IdeRuntime } from '../runtime';
import FileExplorer from '../FileExplorer';

export async function verifyFileExplorer() {
  const host = document.createElement('div'); host.className = 'ide-workbench';
  host.style.cssText = 'position:fixed;inset:0;z-index:100'; document.body.append(host);
  const pane = document.createElement('div'); pane.className = 'ide-files-pane';
  pane.style.cssText = 'width:285px;height:340px'; host.append(pane);
  const root = createRoot(pane);
  const packagePath = 'src/main/resources/com/example/deeply/nested/configuration';
  const currentPath = `${packagePath}/ZCurrent.xml`;
  const longPath = `${packagePath}/VeryLongConfigurationFileNameThatMustRemainReadableWhenHorizontallyScrolled.xml`;
  const deepPath = `nested/${Array.from({ length: 16 }, (_, index) => `level${index + 1}`).join('/')}/Current.xml`;
  const paths = [currentPath, longPath, deepPath, 'changed.txt',
    ...Array.from({ length: 35 }, (_, index) => `${packagePath}/Entry${index + 1}.xml`)];
  let files: ReviewCodeFilesDto | null = { source: 'WORKING_COPY', revision: 'head', sourceFingerprint: 'files', savedExperiment: false, truncated: false,
    files: paths.map(path => ({ path, changedInPr: path === 'changed.txt', addedInPr: false, onlyInWorkingCopy: false, uncommitted: false })) };
  let selected: string | null = currentPath;
  const opened: string[] = [];
  const runtime = { workspace: Uri.parse('file-explorer:/project'), api: { window: {
    showTextDocument: async (uri: { path: string }) => { opened.push(uri.path); },
  } } } as unknown as IdeRuntime;
  const render = () => flushSync(() => root.render(<FileExplorer files={files} selected={selected} runtime={runtime}
    onRefresh={() => undefined} onHide={() => undefined} onError={message => { throw new Error(message); }} />));
  const row = (path: string) => pane.querySelector<HTMLButtonElement>(`.ct-tree__row[title="${path}"]`);
  const locate = () => element<HTMLButtonElement>('[aria-label="Locate current file"]', pane);
  const nameVisible = (path: string) => {
    const name = row(path)?.querySelector('.ct-tree__name')?.getBoundingClientRect();
    const viewport = element('.ct-files__list', pane).getBoundingClientRect();
    return name !== undefined && name.top >= viewport.top && name.bottom <= viewport.bottom
      && name.left >= viewport.left && name.right <= viewport.right;
  };
  const locateCurrent = async (path: string) => {
    flushSync(() => locate().click());
    await until(() => document.activeElement === row(path) && nameVisible(path), 'Locate expands and reveals the current file label and focuses its row');
    assert(opened.length === 0, 'Locating the current file does not reopen it');
  };
  try {
    render();
    await until(() => row(currentPath) !== null, 'Selecting a file automatically expands all of its ancestors');
    assert(!locate().disabled, 'A file in the full list can be located');
    assert(row(packagePath)?.querySelector('.ct-tree__name')?.textContent === 'com.example.deeply.nested.configuration', 'The fixture exercises a compacted package path');
    const list = element('.ct-files__list', pane);
    const label = element('.ct-tree__name', row(longPath) ?? pane);
    assert(list.scrollWidth > list.clientWidth + 100, 'Long file labels extend the tree horizontally');
    assert(label.clientWidth >= label.scrollWidth && label.getBoundingClientRect().width > list.clientWidth, 'Long labels retain their full width without ellipsis clipping');
    const actions = element('.ide-file-actions', pane), header = element('.ct-files__head', pane);
    const actionBounds = actions.getBoundingClientRect(), headerBounds = header.getBoundingClientRect();
    const labelLeft = label.getBoundingClientRect().left;
    list.scrollLeft = list.scrollWidth; list.scrollTop = list.scrollHeight; await settle();
    assert(list.scrollLeft > 100 && label.getBoundingClientRect().left < labelLeft - 100, 'Horizontal scrolling moves the tree content');
    assert(label.getBoundingClientRect().right <= list.getBoundingClientRect().right, 'Scrolling to the end reveals the full file-name suffix');
    assert(actions.getBoundingClientRect().left === actionBounds.left && actions.getBoundingClientRect().top === actionBounds.top
      && header.getBoundingClientRect().left === headerBounds.left && header.getBoundingClientRect().top === headerBounds.top, 'File actions and scope controls stay fixed while the tree scrolls');

    flushSync(() => row('src')?.click()); await settle();
    assert(row(currentPath) === null, 'Collapsing a source ancestor hides the current file');
    await locateCurrent(currentPath);
    for (const path of ['src', 'src/main', 'src/main/resources', packagePath]) {
      assert(row(path)?.getAttribute('aria-expanded') === 'true', 'Locate opens ordinary and compacted ancestors');
    }
    flushSync(() => element<HTMLButtonElement>('.ct-scope button:nth-child(2)', pane).click()); await settle();
    assert(row(currentPath) === null && !locate().disabled, 'Changed files can hide the current file without disabling Locate');
    await locateCurrent(currentPath);
    assert(element('.ct-scope button:first-child', pane).getAttribute('aria-pressed') === 'true', 'Locate clears the Changed files filter');

    selected = deepPath; render();
    await until(() => row(deepPath) !== null, 'A newly selected deeply nested file still expands automatically');
    list.scrollLeft = 0; list.scrollTop = 0; await settle();
    assert(!nameVisible(deepPath), 'The deeply indented current file begins outside the visible tree area');
    await locateCurrent(deepPath);
    assert(list.scrollLeft > 0, 'Locate scrolls horizontally to reveal a deeply indented file name');

    selected = null; render(); assert(locate().disabled, 'Locate is disabled with no current file');
    selected = 'missing.xml'; render(); assert(locate().disabled, 'Locate is disabled when the current file is absent from the list');
    selected = currentPath; files = null; render(); assert(locate().disabled, 'Locate is disabled while the file list is unavailable');
  } finally { flushSync(() => root.unmount()); host.remove(); }
}
