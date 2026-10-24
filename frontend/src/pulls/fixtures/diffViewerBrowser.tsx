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
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { assert, settle } from '../../fixtures/harness';
import type { DiffFileDto } from '../../types';
import { DiffViewer } from '../PullChanges';

export async function run(): Promise<{ name: string; error?: string }[]> {
  const name = 'Portal controls and layout-specific file-tree choices survive refresh and hidden tabs';
  const host = document.createElement('div');
  const header = document.createElement('header');
  document.body.append(header, host);
  const root = createRoot(host);
  let files: DiffFileDto[] = ['first.ts', 'second.ts'].map(filename => ({
    filename, status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new',
  }));
  let expanded = false;
  let toolbarTarget: HTMLElement | null = null;
  let toolbarVisible = true;
  let sourceClicks = 0;
  const render = () => flushSync(() => root.render(<DiffViewer files={files}
    defaultTreeOpen={expanded} toolbarTarget={toolbarTarget} toolbarVisible={toolbarVisible}
    toolbarStart={<button aria-label="Choose source" onClick={() => { sourceClicks++; }}>Choose source</button>} />));
  const treeOpen = () => host.querySelector('nav[aria-label="Changed files"]') !== null;
  const click = async (selector: string) => {
    const button = (toolbarTarget ?? host).querySelector<HTMLButtonElement>(selector);
    assert(button !== null, `Missing ${selector}`);
    button.click();
    await settle();
  };
  const refresh = async () => { files = files.map(file => ({ ...file })); render(); await settle(); };
  try {
    render();
    assert(!treeOpen() && host.querySelector('.pull-changes__toolbar') !== null,
      'The compact view must start folded and render inline without a header target');
    assert(host.querySelectorAll('.pull-diff-code').length === 2, 'Small diffs must retain their expanded file bodies');
    toolbarTarget = header;
    render();
    assert(header.querySelectorAll('.pull-changes__toolbar').length === 1
      && host.querySelector('.pull-changes__toolbar') === null,
    'Providing a target must move the toolbar into that header without duplicating it');
    await click('button[aria-label="Choose source"]');
    assert(sourceClicks === 1, 'The supplied toolbar control must remain interactive in the portal');

    expanded = true;
    render();
    assert(treeOpen(), 'Entering an expanded view must open its file tree by default');
    expanded = false;
    render();
    assert(!treeOpen(), 'Returning to compact must restore its folded default');
    await click('.pull-changes__files-toggle');
    await refresh();
    assert(treeOpen(), 'Refreshing files must retain the compact view manual open choice');
    expanded = true;
    render();
    assert(treeOpen(), 'An untouched expanded view must still use its open default');
    await click('.pull-changes__files-toggle');
    await refresh();
    assert(!treeOpen(), 'Refreshing files must retain the expanded view manual closed choice');
    expanded = false;
    render();
    assert(treeOpen(), 'Leaving expanded view must restore the independent compact choice');
    expanded = true;
    render();
    assert(!treeOpen(), 'Re-entering expanded view must restore its independent manual choice');

    await click('.pull-changes__bulk-toggle');
    assert(host.querySelector('.pull-diff-code') === null, 'The portaled collapse control must still operate on the diff');
    toolbarVisible = false;
    render();
    assert(header.childElementCount === 0 && host.querySelector('.pull-changes__toolbar') === null,
      'Hiding a kept tab must remove its toolbar from the shared header and the body');
    toolbarVisible = true;
    render();
    assert(header.querySelectorAll('.pull-changes__toolbar').length === 1
      && host.querySelector('.pull-diff-code') === null && !treeOpen(),
    'Showing the tab must restore one toolbar while preserving file and tree choices');
    toolbarTarget = null;
    render();
    assert(header.childElementCount === 0 && host.querySelector('.pull-changes__toolbar') !== null,
      'Removing the target must clean the old header and restore inline controls');

    const patch = `@@ -0,0 +1,300 @@\n${Array.from({ length: 300 }, (_, index) => `+line ${index}`).join('\n')}`;
    files = Array.from({ length: 13 }, (_, index) => ({ filename: `File${index}.ts`, status: 'added', additions: 300, deletions: 0, patch }));
    render(); await settle();
    assert(host.querySelectorAll('[data-diff-row]').length === 300, 'A large patch workload must initially render only the selected file');
    await click('.pull-changes__files-toggle');
    await click('button[aria-label^="File12.ts,"]');
    assert(host.querySelectorAll('.pull-diff-code').length === 1
      && host.querySelector('[data-file-card="File12.ts"] .pull-diff-code') !== null,
    'Picking a collapsed file must mount its patch and release the previous patch');
    await click('button[aria-label="Expand all file diffs"]');
    assert(host.querySelectorAll('[data-diff-row]').length === 3900, 'Explicitly expanding all files must still reveal every changed line');
    await click('button[aria-label="Collapse all file diffs"]');
    assert(host.querySelector('.pull-diff-code') === null, 'All patches remain collapsible after expansion');
    return [{ name }];
  }
  catch (error) { return [{ name, error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); header.remove(); }
}
