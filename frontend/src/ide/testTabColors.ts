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
/* eslint-disable import/no-unresolved -- Tab markup and editor services are pinned together. */
import type { IEditorService } from '@codingame/monaco-vscode-api';
import { EditorPane } from '@codingame/monaco-vscode-api/vscode/vs/workbench/browser/parts/editor/editorPane';
import { EditorResourceAccessor, SideBySideEditor } from '@codingame/monaco-vscode-api/vscode/vs/workbench/common/editor';
import { isTestFile } from '../pulls/code-tests/codeModel';

/** VS Code exposes file colors only for labels; ByteQuay adds the tab background. */
export function installTestTabColors(editors: IEditorService) {
  let disposed = false;
  const refresh = () => queueMicrotask(() => {
    if (disposed) return;
    for (const pane of editors.visibleEditorPanes) {
      const group = pane instanceof EditorPane ? pane.getContainer()?.closest('.editor-group-container') : undefined;
      group?.querySelectorAll('.tabs-container .tab').forEach((tab, index) => {
        const input = pane.group.getEditorByIndex(index);
        const uri = input && EditorResourceAccessor.getOriginalUri(input, { supportSideBySide: SideBySideEditor.PRIMARY });
        tab.classList.toggle('ide-test-tab', !!uri && isTestFile(uri.path));
      });
    }
  });
  const changes = editors.onDidEditorsChange(refresh);
  const visible = editors.onDidVisibleEditorsChange(refresh);
  refresh();
  return () => { disposed = true; changes.dispose(); visible.dispose(); };
}
