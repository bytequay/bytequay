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
import { getService, ICodeEditorService } from '@codingame/monaco-vscode-api';
// eslint-disable-next-line import/no-unresolved -- Exercise the actual workbench themes.
import { IWorkbenchThemeService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/themes/common/workbenchThemeService.service';
import { editor, Uri } from 'monaco-editor';
import type { ReviewCodeFileDto } from '../../pulls/code-tests/contracts';
import { assert, settle } from '../../fixtures/harness';
import type { IdeRuntime } from '../runtime';
import LineChanges from '../LineChanges';
import '../../css/base.css';
import '../../css/code-tests.css';
import '../workbench.css';

export async function verifyLineChanges() {
  const host = document.createElement('div'); host.className = 'ide-workbench';
  host.style.cssText = 'position:fixed;inset:0;z-index:100'; document.body.append(host);
  const native = document.createElement('div'); native.style.cssText = 'height:100%;width:100%'; host.append(native);
  const shell = document.createElement('div'); host.append(shell);
  const root = createRoot(shell);
  const code = editor.create(native, { automaticLayout: true, minimap: { enabled: false }, lineNumbers: 'on', folding: true, fontSize: 16 });
  const text = 'class Example {\n    void added() {\n        System.out.println("Added");\n    }\n\n    void updated() {\n        System.out.println("Updated");\n    }\n}';
  const model = editor.createModel(text, 'java', Uri.parse('vscode-remote://fixture/changes.java'));
  const other = editor.createModel(text, 'java', Uri.parse('vscode-remote://fixture/unchanged.java'));
  const runtime = { editors: await getService(ICodeEditorService) } as IdeRuntime;
  let file: ReviewCodeFileDto = { path: 'changes.java', content: '\uFEFF' + text, contentHash: 'saved', editable: true,
    readOnlyReason: null, unavailableReason: null, prPatch: null, lineChanges: [
      { kind: 'DELETED', line: 0, count: 2 }, { kind: 'ADDED', line: 2, count: 3 },
      { kind: 'MODIFIED', line: 6, count: 2 }, { kind: 'DELETED', line: 9, count: 1 },
    ] };
  code.setModel(model);
  const render = (current: ReviewCodeFileDto | null = file) => flushSync(() => root.render(<LineChanges runtime={runtime} uri={code.getModel()?.uri.toString() ?? ''} file={current} />));
  const changes = () => model.getAllDecorations().filter(item => item.options.linesDecorationsClassName?.includes('ct-line-change'));
  try {
    render(); code.render(true); await settle();
    assert(changes().length === 4, 'All changes must render even when the saved file has a BOM');
    assert(changes().some(item => item.range.startLineNumber === 2 && item.range.endLineNumber === 4), 'Added blocks cover every added line');
    assert(changes().some(item => item.range.startLineNumber === 6 && item.range.endLineNumber === 7), 'Updated blocks cover every updated line');
    assert(changes().some(item => item.range.startLineNumber === 1 && item.options.linesDecorationsClassName?.includes('is-before')), 'Top deletions anchor before the first line');
    assert(changes().some(item => item.range.startLineNumber === 9 && item.options.linesDecorationsTooltip?.includes('after line 9')), 'End deletions anchor after the last line');
    const theme = await getService(IWorkbenchThemeService);
    for (const mode of ['dark', 'light']) {
      const target = (await theme.getColorThemes()).find(item => item.settingsId === (mode === 'dark' ? 'ByteQuay Darcula' : 'ByteQuay IDEA Light'));
      assert(target !== undefined, 'The workbench theme must exist'); await theme.setColorTheme(target.id, undefined);
      host.classList.toggle('is-dark', mode === 'dark'); host.style.colorScheme = mode;
      code.render(true); await settle();
      const colors = new Set<string>();
      for (const kind of ['added', 'modified', 'deleted']) {
        const marker = native.querySelector(`.ct-line-change.is-${kind}`);
        assert(marker !== null, `${kind} markers must be visible in ${mode}`);
        const style = getComputedStyle(marker, '::before'); colors.add(style.backgroundColor);
        assert(style.width === (kind === 'deleted' ? '8px' : '4px') && style.backgroundColor !== 'rgba(0, 0, 0, 0)', 'Gutter bars must have visible dimensions and colors');
      }
      assert(colors.size === 3, 'Added, updated, and deleted markers have distinct colors');
      Object.assign(window, { lineChangesCapture: mode });
      for (let attempt = 0; attempt < 200 && Reflect.get(window, 'lineChangesCaptured') !== mode; attempt++) await new Promise(resolve => setTimeout(resolve, 50));
      assert(Reflect.get(window, 'lineChangesCaptured') === mode, 'The native gutter must reach the capture checkpoint');
    }
    model.setValue('unsaved\n' + text);
    assert(changes().length === 0, 'Saved line positions must not decorate an unsaved draft');
    model.setValue(text);
    assert(changes().length === 4, 'Undoing the draft restores the saved markers');
    file = { ...file, content: 'saved\n' + text, lineChanges: [{ kind: 'ADDED', line: 1, count: 1 }] };
    model.setValue(file.content ?? ''); render();
    assert(changes().length === 1 && changes()[0].range.startLineNumber === 1, 'Saving and refreshing replaces the previous ranges');
    code.setModel(other); render(null);
    assert(changes().length === 0 && !other.getAllDecorations().some(item => item.options.linesDecorationsClassName?.includes('ct-line-change')), 'Switching to another file clears stale markers');
    code.setModel(model); render();
    flushSync(() => root.unmount());
    assert(changes().length === 0, 'Closing the surface removes its markers');
  } finally { code.dispose(); model.dispose(); other.dispose(); host.remove(); }
}
