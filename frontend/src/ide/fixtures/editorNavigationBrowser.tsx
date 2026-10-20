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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { TextEditor } from 'vscode';
import type { ICodeEditor } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
import type { ReviewCodeFileDto } from '../../pulls/code-tests/contracts';
import { assert, settle } from '../../fixtures/harness';
import EditorNavigation from '../EditorNavigation';
import type { CodeBlame, IdeContext } from '../contracts';
import type { IdeRuntime } from '../runtime';

export async function run() {
  const { host, root, dispose: disposeRoot } = createFixtureRoot();
  const originalBridge = window.ideWorkbench;
  const uri = { toString: () => 'vscode-remote://fixture/main.go' };
  const documentModel = { uri, isDirty: false, getText: () => 'package main' };
  const active = { document: documentModel } as unknown as TextEditor;
  let options: { lineNumbers: string | ((line: number) => string); lineNumbersMinChars: number } = { lineNumbers: 'on', lineNumbersMinChars: 5 };
  const currentOptions = () => options;
  let optionChanges = 0;
  const listen = () => ({ dispose() { return; } });
  const editor = {
    getModel: () => ({ uri }), getRawOptions: () => options,
    updateOptions: (value: typeof options) => { options = value; optionChanges++; },
    onMouseDown: listen, onMouseMove: listen, onMouseLeave: listen,
  } as unknown as ICodeEditor;
  const runtime = { editors: { getActiveCodeEditor: () => editor, listCodeEditors: () => [editor] } } as unknown as IdeRuntime;
  let context: IdeContext = { host: { kind: 'review', reviewId: 'one' }, review: null, task: null, detail: null };
  let file: ReviewCodeFileDto = { path: 'main.go', content: 'package main', contentHash: 'hash-one', editable: true,
    readOnlyReason: null, unavailableReason: null, prPatch: null, lineChanges: [] };
  let revision = 'revision-one';
  const requests: { resolve: (value: CodeBlame) => void }[] = [];
  const readCount = () => requests.length;
  Object.assign(window, { ideWorkbench: { request: (method: string) => {
    assert(method === 'readCodeBlame', 'Only blame should be requested');
    return new Promise<CodeBlame>(resolve => requests.push({ resolve }));
  } } });
  const render = () => flushSync(() => root.render(<EditorNavigation runtime={runtime} active={active} context={context} file={file} revision={revision} onHistory={() => {}} />));
  const answer = async (index: number, author = 'Author') => {
    requests[index].resolve({ path: file.path, contentHash: file.contentHash ?? '', revision, truncated: false, unavailableReason: null,
      spans: [{ line: 1, count: 1, originalLine: 1, commit: 'a'.repeat(40), author, authoredAt: '2024-01-01T00:00:00Z', summary: 'Saved line', originalPath: file.path }] });
    await settle();
  };
  try {
    render();
    const button = [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === 'Blame');
    assert(button !== undefined, 'Blame toggle should exist');
    flushSync(() => button.click());
    await answer(0);
    assert(currentOptions().lineNumbersMinChars === 29, 'Blame should widen the gutter');
    const installed = currentOptions().lineNumbers;
    const changes = optionChanges;
    for (let i = 0; i < 20; i++) {
      // IPC recreates these objects during ordinary shell updates without changing their identity.
      context = { ...context, host: { ...context.host } };
      file = { ...file };
      render(); await settle();
      assert(readCount() === 1, 'Unchanged context must not reread blame');
      assert(currentOptions().lineNumbers === installed && optionChanges === changes, 'Blame must remain installed without a narrow-gutter frame');
    }
    documentModel.isDirty = true;
    render(); await settle();
    assert(currentOptions().lineNumbers === 'on', 'Unsaved source must hide stale attribution');
    documentModel.isDirty = false;
    file = { ...file, contentHash: 'hash-two' };
    render(); await settle();
    assert(readCount() === 2, 'Saved source changes must reload blame');
    await answer(1);
    revision = 'revision-two';
    render(); await settle();
    assert(readCount() === 3, 'A new revision must reload blame');
    context = { ...context, host: { kind: 'review', reviewId: 'two' } };
    render(); await settle();
    assert(readCount() === 4, 'A different host must reload blame');
    await answer(2, 'Stale author');
    assert(currentOptions().lineNumbers === 'on', 'A late response from the previous host must not install blame');
    await answer(3, 'Current author');
    const lineNumbers = currentOptions().lineNumbers;
    assert(typeof lineNumbers === 'function' && lineNumbers(1).includes('Current author'), 'Only the current host response should appear');
    flushSync(() => button.click());
    assert(currentOptions().lineNumbers === 'on' && currentOptions().lineNumbersMinChars === 5, 'Turning off blame restores the original gutter');
    return { contextUpdates: 20, reads: requests.length };
  }
  finally { disposeRoot(); window.ideWorkbench = originalBridge; }
}
