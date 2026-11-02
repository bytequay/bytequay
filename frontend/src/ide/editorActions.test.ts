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
/* eslint-disable import/no-unresolved -- Pinned VS Code registry types. */
import { expect, it, vi } from 'vitest';
import { MenuRegistry, type IMenuItem, type ISubmenuItem } from '@codingame/monaco-vscode-api/vscode/vs/platform/actions/common/actions';
import { KeybindingsRegistry } from '@codingame/monaco-vscode-api/vscode/vs/platform/keybinding/common/keybindingsRegistry';
import { installEditorActions } from './editorActions';

vi.mock('@codingame/monaco-vscode-api/vscode/vs/platform/actions/common/actions', () => ({ MenuRegistry: { getMenuItems: vi.fn() } }));
vi.mock('@codingame/monaco-vscode-api/vscode/vs/platform/keybinding/common/keybindingsRegistry', () => ({ KeybindingsRegistry: { getDefaultKeybindings: vi.fn() } }));

it('keeps Run and navigation while hiding unsupported native and late extension actions', () => {
  const item = (id: string): IMenuItem => ({ command: { id, title: id } });
  const menus: (IMenuItem | ISubmenuItem)[] = [item('testing.runAtCursor'), item('editor.action.goToReferences'),
    item('editor.debug.action.toggleBreakpoint'), item('editor.action.rename'), item('editor.action.refactor'),
    item('editor.action.triggerSuggest'), { ...item('java.test.editor.run'), alt: item('java.test.editor.debug').command }];
  vi.mocked(MenuRegistry.getMenuItems).mockImplementation(() => menus);
  const bindings = [item('testing.runAtCursor'), item('workbench.action.debug.start'), item('editor.action.rename'), item('editor.action.triggerSuggest')]
    .map(value => ({ keybinding: null, command: value.command.id, when: undefined, weight1: 0, weight2: 0, extensionId: null, isBuiltinExtension: true }));
  vi.mocked(KeybindingsRegistry.getDefaultKeybindings).mockImplementation(() => bindings);
  installEditorActions();
  menus.push(item('rust-analyzer.debug'), item('go.test.cursor'));
  menus.push({ title: 'Debug', submenu: { id: 'debug' } });
  bindings.push({ ...bindings[0], command: 'go.debug.cursor' });
  const visible = MenuRegistry.getMenuItems({ id: 'editor/context' });
  expect(visible.map(value => 'command' in value ? value.command.id : value.submenu.id))
    .toEqual(['testing.runAtCursor', 'editor.action.goToReferences', 'java.test.editor.run', 'go.test.cursor']);
  expect((visible[2] as IMenuItem).alt).toBeUndefined();
  expect(KeybindingsRegistry.getDefaultKeybindings().map(value => value.command)).toEqual(['testing.runAtCursor']);
  expect(menus[6]).toHaveProperty('alt.id', 'java.test.editor.debug');
});
