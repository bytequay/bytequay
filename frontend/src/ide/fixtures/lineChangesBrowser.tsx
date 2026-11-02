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
import { getService, ICodeEditorService, ICommandService } from '@codingame/monaco-vscode-api';
// eslint-disable-next-line import/no-unresolved -- Exercise the actual workbench themes.
import { IWorkbenchThemeService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/themes/common/workbenchThemeService.service';
// eslint-disable-next-line import/no-unresolved -- Verify discovery through the native command palette.
import { MenuId, MenuRegistry } from '@codingame/monaco-vscode-api/vscode/vs/platform/actions/common/actions';
import { editor, Uri } from 'monaco-editor';
import type { ReviewCodeFileDto } from '../../pulls/code-tests/contracts';
import { assert, settle, element, until } from '../../fixtures/harness';
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
  const code = editor.create(native, { automaticLayout: true, minimap: { enabled: false }, lineNumbers: 'on', folding: true,
    foldingStrategy: 'indentation', showFoldingControls: 'always', fontSize: 16 });
  const text = 'class Example {\n    void added() {\n        System.out.println("Added");\n    }\n\n    void updated() {\n        System.out.println("Updated");\n    }\n}';
  const model = editor.createModel(text, 'java', Uri.parse('vscode-remote://fixture/changes.java'));
  const other = editor.createModel(text, 'java', Uri.parse('vscode-remote://fixture/unchanged.java'));
  const initialModels = new Set(editor.getModels()), initialDiffs = new Set(editor.getDiffEditors());
  const runtime = { editors: await getService(ICodeEditorService) } as IdeRuntime;
  const commands = await getService(ICommandService);
  let file: ReviewCodeFileDto = { path: 'changes.java', content: '\uFEFF' + text, contentHash: 'saved', editable: true,
    readOnlyReason: null, unavailableReason: null, prPatch: null, lineChanges: [
      { kind: 'DELETED', line: 0, count: 2, originalLines: ['// Removed heading', '// Previous description'] },
      { kind: 'ADDED', line: 2, count: 3, originalLines: [] },
      { kind: 'MODIFIED', line: 6, count: 2, originalLines: ['    void previous() {', '        System.out.println("Before");'] },
      { kind: 'DELETED', line: 9, count: 1, originalLines: ['// Removed footer'] },
    ] };
  code.setModel(model);
  const render = (current: ReviewCodeFileDto | null = file) => flushSync(() => root.render(<LineChanges runtime={runtime} uri={code.getModel()?.uri.toString() ?? ''} file={current} />));
  const changes = () => model.getAllDecorations().filter(item => item.options.linesDecorationsClassName?.includes('ct-line-change'));
  const preview = () => host.querySelector<HTMLElement>('.ide-change-preview');
  const clickAt = async (clientX: number, clientY: number) => {
    const target = document.elementFromPoint(clientX, clientY);
    assert(target !== null, 'The editor must have a mouse target');
    for (const type of ['mousedown', 'mouseup']) {
      flushSync(() => target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY, button: 0 })));
    }
    await settle();
  };
  const clickMarker = async (selector: string) => {
    code.render(true); await settle();
    const marker = element(selector, native), bounds = marker.getBoundingClientRect();
    const y = marker.classList.contains('is-deleted') ? marker.classList.contains('is-before') ? bounds.top + 1 : bounds.bottom - 1 : bounds.top + bounds.height / 2;
    await clickAt(bounds.left + 2, y);
  };
  const checkPreview = async (original: string, modified: string) => {
    await until(() => preview() !== null, 'Clicking a change marker opens its inline preview');
    const section = preview();
    assert(section !== null && section.getAttribute('aria-label') === 'Saved change', 'The preview is accessible and labeled');
    assert(host.querySelectorAll('.ide-change-preview').length === 1, 'Only one change preview opens at a time');
    const diff = editor.getDiffEditors().find(item => section.contains(item.getContainerDomNode()));
    assert(diff !== undefined, 'The preview uses the native diff editor');
    assert(diff.getOriginalEditor().getValue() === original, 'The preview contains the exact previous lines');
    assert(diff.getModifiedEditor().getValue() === modified, 'The preview contains the exact saved replacement');
    assert(diff.getOriginalEditor().getOption(editor.EditorOption.readOnly) && diff.getModifiedEditor().getOption(editor.EditorOption.readOnly), 'Preview code is read-only');
    for (const inner of [diff.getOriginalEditor(), diff.getModifiedEditor()]) {
      const native = runtime.editors.listCodeEditors().find(item => item.getId() === inner.getId());
      assert(native && Reflect.get(native, 'isSimpleWidget') === true, 'Preview editors must not become the workbench active text editor');
    }
    assert(!section.closest('[aria-hidden="true"]'), 'The preview stays in the accessibility tree');
  };
  const closePreview = async () => {
    flushSync(() => element<HTMLButtonElement>('[aria-label="Close change preview"]', host).click()); await settle();
    assert(preview() === null, 'The close button dismisses the preview');
  };
  const modifiedBefore = file.lineChanges[2].originalLines.join('\n') + '\n';
  const modifiedAfter = text.split('\n').slice(5, 7).join('\n') + '\n';
  try {
    render(); code.render(true); await settle();
    assert(changes().length === 4, 'All changes must render even when the saved file has a BOM');
    assert(changes().some(item => item.range.startLineNumber === 2 && item.range.endLineNumber === 4), 'Added blocks cover every added line');
    assert(changes().some(item => item.range.startLineNumber === 6 && item.range.endLineNumber === 7), 'Updated blocks cover every updated line');
    assert(changes().some(item => item.range.startLineNumber === 1 && item.options.linesDecorationsClassName?.includes('is-before')), 'Top deletions anchor before the first line');
    assert(changes().some(item => item.range.startLineNumber === 9 && item.options.linesDecorationsTooltip?.includes('after line 9')), 'End deletions anchor after the last line');
    const bounds = native.getBoundingClientRect(), layout = code.getLayoutInfo();
    const sourceLine = code.getScrolledVisiblePosition({ lineNumber: 6, column: 1 });
    assert(sourceLine !== null, 'The source line must be visible');
    await clickAt(bounds.left + layout.contentLeft + 30, bounds.top + sourceLine.top + sourceLine.height / 2);
    assert(preview() === null, 'Source clicks retain ordinary editing');
    await until(() => native.querySelector('.codicon-folding-expanded') !== null, 'The native editor exposes its folding control');
    const foldBounds = element('.codicon-folding-expanded', native).getBoundingClientRect();
    await clickAt(foldBounds.left + foldBounds.width / 2, foldBounds.top + foldBounds.height / 2);
    assert(preview() === null, 'Folding controls do not open change previews');
    await code.getAction('editor.unfoldAll')?.run(); await settle();
    const theme = await getService(IWorkbenchThemeService);
    for (const mode of ['dark', 'light']) {
      const target = (await theme.getColorThemes()).find(item => item.settingsId === (mode === 'dark' ? 'ByteQuay Darcula' : 'ByteQuay IDEA Light'));
      assert(target !== undefined, 'The workbench theme must exist'); await theme.setColorTheme(target.id, undefined);
      host.classList.toggle('is-dark', mode === 'dark'); host.style.colorScheme = mode;
      code.render(true); await settle();
      const colors = new Set<string>();
      for (const kind of ['added', 'modified', 'deleted']) {
        const marker = element(`.ct-line-change.is-${kind}`, native, `${kind} markers must be visible in ${mode}`);
        const style = getComputedStyle(marker, '::before'); colors.add(style.backgroundColor);
        assert(style.width === (kind === 'deleted' ? '8px' : '4px') && style.backgroundColor !== 'rgba(0, 0, 0, 0)', 'Gutter bars must have visible dimensions and colors');
      }
      assert(colors.size === 3, 'Added, updated, and deleted markers have distinct colors');
      await clickMarker('.ct-line-change.is-modified'); await checkPreview(modifiedBefore, modifiedAfter);
      Object.assign(window, { lineChangesCapture: mode });
      for (let attempt = 0; attempt < 200 && Reflect.get(window, 'lineChangesCaptured') !== mode; attempt++) await new Promise(resolve => setTimeout(resolve, 50));
      assert(Reflect.get(window, 'lineChangesCaptured') === mode, 'The native gutter must reach the capture checkpoint');
      await closePreview();
    }
    await clickMarker('.ct-line-change.is-deleted.is-before'); await checkPreview('// Removed heading\n// Previous description\n', '');
    await clickMarker('.ct-line-change.is-deleted:not(.is-before)'); await checkPreview('// Removed footer\n', '');
    await clickMarker('.ct-line-change.is-deleted:not(.is-before)');
    assert(preview() === null, 'Clicking the selected marker toggles its preview closed');
    await clickMarker('.ct-line-change.is-added'); await checkPreview('', text.split('\n').slice(1, 4).join('\n') + '\n');
    flushSync(() => element('[aria-label="Close change preview"]', host).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })));
    await settle(); assert(preview() === null && code.hasTextFocus(), 'Escape closes the preview and restores editor focus');
    code.setPosition({ lineNumber: 6, column: 1 });
    assert(MenuRegistry.getMenuItems(MenuId.CommandPalette).some(item => 'command' in item && item.command.id === 'bytequay.showLineChange'), 'The show-change action is discoverable in the command palette');
    await commands.executeCommand('bytequay.showLineChange'); await checkPreview(modifiedBefore, modifiedAfter);
    model.pushEditOperations([], [{ range: { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1 }, text: 'unsaved\n' }], () => null);
    await settle(); assert(preview() === null, 'Editing closes an open preview');
    assert(changes().some(item => item.range.startLineNumber === 3 && item.range.endLineNumber === 5), 'Unsaved edits move the saved markers with their lines');
    await clickMarker('.ct-line-change.is-modified'); await checkPreview(modifiedBefore, modifiedAfter);
    assert(preview()?.textContent?.includes('Unsaved edits are not included.'), 'A shifted marker identifies the saved comparison');
    model.pushEditOperations([], [3, 4, 5].map(line => ({ range: { startLineNumber: line, startColumn: 1, endLineNumber: line, endColumn: 1 }, text: '\t' })), () => null);
    assert(changes().length === 4 && changes().some(item => item.range.startLineNumber === 3 && item.range.endLineNumber === 5), 'Indenting a changed block keeps its markers');
    model.setValue(text);
    await settle(); assert(preview() === null, 'Replacing document content closes the preview');
    assert(changes().length === 4 && changes().some(item => item.range.startLineNumber === 2 && item.range.endLineNumber === 4), 'Returning to the saved text places the saved markers again');
    await clickMarker('.ct-line-change.is-modified'); await checkPreview(modifiedBefore, modifiedAfter);
    file = { ...file, contentHash: 'refreshed' }; render(); await settle();
    assert(preview() === null, 'Refreshing the file closes the old comparison');
    file = { ...file, content: text, lineChanges: [{ kind: 'DELETED', line: 0, count: 1, originalLines: [''] }] };
    model.setValue(text); render();
    await clickMarker('.ct-line-change.is-deleted'); await checkPreview('\n', '');
    file = { ...file, content: '\n' + text, lineChanges: [{ kind: 'ADDED', line: 1, count: 1, originalLines: [] }] };
    model.setValue(file.content ?? ''); render();
    await clickMarker('.ct-line-change.is-added'); await checkPreview('', '\n');
    file = { ...file, content: text.replace(/\n/g, '\r\n'), lineChanges: [{ kind: 'MODIFIED', line: 6, count: 2,
      originalLines: ['    void previous() {\r', '        System.out.println("Before");\r'] }] };
    model.setValue(file.content ?? ''); render();
    await clickMarker('.ct-line-change.is-modified'); await checkPreview(modifiedBefore, modifiedAfter);
    const longOriginal = Array.from({ length: 100 }, (_, index) => `int value${index} = -1;`);
    const longModified = Array.from({ length: 100 }, (_, index) => `int value${index} = ${index};`).join('\n');
    file = { ...file, content: longModified, lineChanges: [{ kind: 'MODIFIED', line: 1, count: 100, originalLines: longOriginal }] };
    model.setValue(longModified); render(); code.setScrollTop(0); code.render(true); await settle();
    const nearBottom = code.getScrolledVisiblePosition({ lineNumber: 25, column: 1 });
    assert(nearBottom !== null, 'The long changed block has a visible line near the bottom of the editor');
    const viewport = native.getBoundingClientRect();
    const clickX = viewport.left + code.getLayoutInfo().decorationsLeft + 2;
    const clickY = viewport.top + nearBottom.top + nearBottom.height / 2;
    assert(code.getTargetAtClientPoint(clickX, clickY)?.position?.lineNumber === 25, 'The marker gesture targets line 25 of the long block');
    await clickAt(clickX, clickY); await checkPreview(longOriginal.join('\n') + '\n', longModified + '\n');
    await until(() => {
      const bounds = preview()?.getBoundingClientRect();
      return bounds !== undefined && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom;
    }, 'Opening a large change scrolls its preview fully into the viewport');
    assert(code.getTopForLineNumber(26) - code.getTopForLineNumber(25) > 320, 'The preview opens after the clicked line rather than the end of the block');
    const longPreview = preview();
    assert(longPreview !== null, 'The large change preview remains open');
    const longDiff = editor.getDiffEditors().find(item => longPreview.contains(item.getContainerDomNode()));
    assert(longDiff?.getContainerDomNode().getBoundingClientRect().height === 320, 'Large changes keep the diff viewport capped at 320 pixels');
    await closePreview(); code.setScrollTop(0);
    file = { ...file, content: 'saved\n' + text, lineChanges: [{ kind: 'ADDED', line: 1, count: 1, originalLines: [] }] };
    model.setValue(file.content ?? ''); render();
    assert(changes().length === 1 && changes()[0].range.startLineNumber === 1, 'Saving and refreshing replaces the previous ranges');
    await clickMarker('.ct-line-change.is-added'); await checkPreview('', 'saved\n');
    code.setModel(other); render(null);
    await settle(); assert(preview() === null, 'Switching files closes the old preview');
    assert(changes().length === 0 && !other.getAllDecorations().some(item => item.options.linesDecorationsClassName?.includes('ct-line-change')), 'Switching to another file clears stale markers');
    code.setModel(model); render();
    await clickMarker('.ct-line-change.is-added'); await checkPreview('', 'saved\n');
    flushSync(() => root.unmount());
    assert(changes().length === 0 && preview() === null, 'Closing the surface removes its markers and preview');
    assert(editor.getDiffEditors().every(item => initialDiffs.has(item)) && editor.getModels().every(item => initialModels.has(item)), 'Closing previews releases their diff editors and models');
  } finally { code.dispose(); model.dispose(); other.dispose(); host.remove(); }
}
