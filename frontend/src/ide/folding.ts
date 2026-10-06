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
/* eslint-disable import/no-unresolved -- Pinned native folding contribution. */
import type { ICodeEditorService } from '@codingame/monaco-vscode-api';
import type { ICodeEditor } from '@codingame/monaco-vscode-api/vscode/vs/editor/browser/editorBrowser';
import type { ITextModel } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/model';
import { FoldingController } from '@codingame/monaco-vscode-api/vscode/vs/editor/contrib/folding/browser/folding';

/** Wait for native folding ranges, then fold only the leading license once per open model. */
export function installHeaderFolding(editors: ICodeEditorService) {
  const handled = new WeakSet<ITextModel>();
  const attach = (editor: ICodeEditor) => {
    let foldingChange: { dispose(): void } | undefined;
    let disposed = false;
    const bind = () => {
      foldingChange?.dispose(); foldingChange = undefined;
      const model = editor.getModel();
      if (!model || model.uri.scheme !== 'vscode-remote' || handled.has(model)) return;
      void FoldingController.get(editor)?.getFoldingModel()?.then(folding => {
        if (!folding || disposed || editor.getModel() !== model) return;
        const fold = () => {
          if (handled.has(model)) return;
          let line = 1;
          while (line < model.getLineCount() && !model.getLineContent(line).trim()) line++;
          if (!/^\s*(?:\/\*|\/\/)/.test(model.getLineContent(line))) { handled.add(model); return; }
          const region = folding.getRegionAtLine(line);
          if (!region || region.startLineNumber !== line) return;
          const header = model.getValueInRange({ startLineNumber: line, startColumn: 1, endLineNumber: region.endLineNumber, endColumn: model.getLineMaxColumn(region.endLineNumber) });
          handled.add(model);
          if (/copyright|\blicen[cs]e\b|SPDX-License-Identifier/i.test(header) && !region.isCollapsed) {
            folding.toggleCollapseState([region]);
          }
        };
        foldingChange = folding.onDidChange(fold);
        fold();
      });
    };
    const changed = editor.onDidChangeModel(bind);
    editor.onDidDispose(() => { disposed = true; changed.dispose(); foldingChange?.dispose(); });
    bind();
  };
  editors.listCodeEditors().forEach(attach);
  editors.onCodeEditorAdd(attach);
}
