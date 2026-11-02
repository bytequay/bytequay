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
/* eslint-disable import/no-unresolved -- This adapter targets the pinned VS Code registries. */
import { MenuRegistry, type IMenuItem, type ISubmenuItem } from '@codingame/monaco-vscode-api/vscode/vs/platform/actions/common/actions';
import { KeybindingsRegistry } from '@codingame/monaco-vscode-api/vscode/vs/platform/keybinding/common/keybindingsRegistry';

const hidden = (command: string) => /debug|breakpoint|rename|refactor/i.test(command)
  || ['editor.action.triggerSuggest', 'editor.action.inlineSuggest.trigger', 'editor.action.triggerParameterHints'].includes(command);

/** Filter presentation, leaving commands available to Java's noDebug test adapter. Install before creating the workbench. */
export function installEditorActions() {
  const getMenuItems = MenuRegistry.getMenuItems.bind(MenuRegistry);
  MenuRegistry.getMenuItems = id => getMenuItems(id).flatMap<IMenuItem | ISubmenuItem>(item => {
    if ('submenu' in item) return hidden(item.submenu.id) ? [] : [item];
    if (hidden(item.command.id)) return [];
    return [{ ...item, alt: item.alt && hidden(item.alt.id) ? undefined : item.alt }];
  });
  // Reading the registry also catches contributions registered after remote extensions load.
  const getKeybindings = KeybindingsRegistry.getDefaultKeybindings.bind(KeybindingsRegistry);
  KeybindingsRegistry.getDefaultKeybindings = () => getKeybindings().filter(item => !item.command || !hidden(item.command));
}
