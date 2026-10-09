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
/* eslint-disable import/no-unresolved -- This fixture uses the real native editor and reference registry. */
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { getService, ICodeEditorService, IEditorService, ITextModelService } from '@codingame/monaco-vscode-api';
import { attachPart, Parts } from '@codingame/monaco-vscode-views-service-override';
import { editor, languages, Uri } from 'monaco-editor';
import Usages from '../Usages';
import { assert, settle } from '../../fixtures/harness';

export async function verifyUsages() {
  const host = document.createElement('div'); host.className = 'ide-workbench is-dark';
  host.style.cssText = 'position:fixed;inset:0;z-index:100'; document.body.append(host);
  const native = document.createElement('div'); native.style.cssText = 'height:100%;width:100%'; host.append(native);
  const shell = document.createElement('div'); host.append(shell);
  const attached = attachPart(Parts.EDITOR_PART, native);
  const editors = await getService(IEditorService);
  const codeEditors = await getService(ICodeEditorService);
  const models = await getService(ITextModelService);
  const workspace = Uri.parse('usages:/workspace');
  const uri = (path: string) => Uri.joinPath(workspace, path);
  const source = uri('Service.java');
  const sourceText = 'class Service {\n    void request() {}\n    void unused() {}\n}';
  const contents = models.registerTextModelContentProvider('usages', { provideTextContent: async resource => editor.getModel(resource)
    ?? editor.createModel(resource.toString() === source.toString() ? sourceText : 'class Caller {\n    service.request();\n    service.request();\n}', 'java', resource) });
  const links: languages.Location[] = [
    { uri: uri('src/main/java/Caller.java'), range: { startLineNumber: 2, endLineNumber: 2, startColumn: 13, endColumn: 20 } },
    { uri: uri('src/test/java/Caller.java'), range: { startLineNumber: 2, endLineNumber: 2, startColumn: 13, endColumn: 20 } },
    { uri: uri('src/test/java/Caller.java'), range: { startLineNumber: 3, endLineNumber: 3, startColumn: 13, endColumn: 20 } },
  ];
  let mode: 'ready' | 'slow' | 'empty' | 'error' = 'ready';
  let finish: ((locations: languages.Location[]) => void) | undefined;
  let cancelled = false;
  let searches = 0;
  const registerReferences = () => languages.registerReferenceProvider('java', { provideReferences: (_model, _position, context, token) => {
    searches++;
    assert(!context.includeDeclaration, 'Usages exclude the method declaration');
    if (mode === 'error') throw new Error('Fixture reference failure');
    if (mode === 'empty') return [];
    if (mode === 'slow') { token.onCancellationRequested(() => { cancelled = true; }); return new Promise<languages.Location[]>(resolve => { finish = resolve; }); }
    return [links[2], links[0], links[1], links[0]];
  } });
  let references = registerReferences();
  const definitions = languages.registerDefinitionProvider('java', { provideDefinition: () => links[0] });
  const root = createRoot(shell);
  flushSync(() => root.render(<Usages editors={codeEditors} workspace={workspace} />));
  const until = async (test: () => boolean, message: string) => {
    for (let attempt = 0; attempt < 100; attempt++) { if (test()) return; await new Promise(resolve => setTimeout(resolve, 50)); }
    throw new Error(`${message}: ${host.querySelector('.ide-usages')?.textContent ?? 'no popup'}; active: ${editors.activeEditor?.resource?.toString()}`);
  };
  const popup = () => host.querySelector<HTMLElement>('.ide-usages');
  const rows = () => [...host.querySelectorAll<HTMLButtonElement>('.ide-usages__row')];
  const key = (key: string) => { (host.querySelector('.ide-usages__list') ?? popup())?.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true })); };
  await editors.openEditor({ resource: source, options: { pinned: true } });
  const code = codeEditors.listCodeEditors().find(item => item.getModel()?.uri.toString() === source.toString());
  assert(code !== undefined, 'Open a native method declaration');
  const position = { lineNumber: 2, column: 11 };
  const point = () => {
    code.revealPositionInCenter(position); code.render();
    const bounds = code.getDomNode()?.getBoundingClientRect();
    const text = code.getScrolledVisiblePosition(position);
    assert(bounds !== undefined && text !== null, 'Method is visible');
    return { x: Math.round(bounds.left + text.left + 2), y: Math.round(bounds.top + text.top + text.height / 2) };
  };
  const click = (modified = true) => {
    const { x, y } = point();
    const element = document.elementFromPoint(x, y);
    assert(element !== null, 'Method can receive a click');
    for (const type of ['mousedown', 'mouseup']) element.dispatchEvent(new MouseEvent(type, { bubbles: true, button: 0, clientX: x, clientY: y, metaKey: modified, ctrlKey: modified }));
  };
  try {
    code.focus(); await settle();
    // The Electron half sends an actual Command-click at this point.
    Object.assign(window, { usageClickPoint: point() });
    await until(() => rows().length === 3 && rows().every(row => row.querySelector('code')?.textContent === 'service.request();'), 'Command-click lists all deduplicated usages with source lines');
    assert(editors.activeEditor?.resource?.toString() === source.toString(), 'Command-click must not also navigate to the definition');
    assert(rows()[0].title === 'src/main/java/Caller.java:2' && rows()[2].title === 'src/test/java/Caller.java:3', 'Results show path and exact line');
    assert(rows()[0].querySelector('code strong')?.textContent === 'request', 'Highlight the referenced method within its source line');
    for (const dark of [false, true]) {
      host.classList.toggle('is-dark', dark); await settle();
      assert(getComputedStyle(rows()[1]).backgroundColor === (dark ? 'rgb(41, 56, 43)' : 'rgb(231, 242, 218)'), 'Test usages use the light/dark green palette');
      assert(!rows()[0].classList.contains('is-test'), 'Same-named production class is not marked as a test');
    }
    key('ArrowDown'); await settle(); key('Enter');
    await until(() => editors.activeEditor?.resource?.toString() === links[1].uri.toString() && !popup(), 'Keyboard selection opens a test usage');
    const target = codeEditors.listCodeEditors().find(item => item.getModel()?.uri.toString() === links[1].uri.toString());
    assert(target?.getSelection()?.startLineNumber === 2 && target.getSelection()?.startColumn === 13, 'Navigation selects the exact call site');
    await editors.openEditor({ resource: source, options: { pinned: true } }); await settle();
    click(false); await settle(); assert(!popup(), 'Plain click retains normal editing');
    click(); await until(() => rows().length === 3, 'Usages can reopen');
    key('Escape'); await settle(); assert(!popup() && code.hasTextFocus(), 'Escape closes and restores editor focus');
    mode = 'slow'; click(); await until(() => finish !== undefined, 'Start a cancellable reference lookup');
    key('Escape'); await settle(); assert(cancelled, 'Closing cancels the language-service request');
    finish?.(links); await settle(); assert(!popup(), 'Late results cannot reopen the popup');
    mode = 'empty'; click(); await until(() => popup()?.textContent?.includes('No usages found.') === true, 'An empty search is explicit');
    key('Escape'); await settle();
    mode = 'error'; click(); await until(() => popup()?.textContent?.includes('Fixture reference failure') === true, 'A provider failure is not presented as zero usages');
    assert(popup()?.querySelector('[role="status"]')?.textContent === 'Search unavailable', 'A failed search must not report zero usages');
    key('Escape'); await settle();
    mode = 'ready'; click(); await until(() => rows().length === 3, 'Usages recover after a provider failure');
    code.getModel()?.applyEdits([{ range: { startLineNumber: 3, endLineNumber: 3, startColumn: 1, endColumn: 1 }, text: ' ' }]);
    await settle(); assert(!popup(), 'Editing the queried document dismisses stale locations');
    references.dispose(); click();
    await until(() => popup()?.textContent?.includes('Preparing reference search…') === true, 'An importing project waits for a reference provider');
    assert(!popup()?.querySelector('[role="alert"]') && !popup()?.textContent?.includes('0 usages'), 'Project import is pending, not a failed or empty search');
    const otherLanguage = languages.registerReferenceProvider('go', { provideReferences: () => [] });
    await settle(); assert(popup()?.textContent?.includes('Preparing reference search…'), 'Another language becoming ready must not finish this search');
    otherLanguage.dispose();
    const transient = registerReferences(); transient.dispose();
    await settle(); assert(popup()?.textContent?.includes('Preparing reference search…'), 'A provider removed during startup must not produce an empty search');
    references = registerReferences();
    await until(() => rows().length === 3, 'Provider registration automatically runs the pending search without refreshing');
    key('Escape'); await settle();
    references.dispose(); click();
    await until(() => popup()?.textContent?.includes('Preparing reference search…') === true, 'A second import wait can start');
    key('Escape'); await settle();
    const before = searches;
    references = registerReferences(); await settle();
    assert(!popup() && searches === before, 'Closing during import cancels the wait without launching a late search');
    references.dispose();
    const model = code.getModel(); assert(model !== null, 'The queried document remains open');
    editor.setModelLanguage(model, 'plaintext'); click();
    await until(() => popup()?.textContent?.includes('Reference search is not available for this file.') === true, 'Unsupported files do not wait indefinitely for project import');
    key('Escape');
  } finally {
    flushSync(() => root.unmount()); references.dispose(); definitions.dispose();
    await editors.activeEditorPane?.group.closeAllEditors(); contents.dispose(); attached.dispose(); host.remove();
  }
}
