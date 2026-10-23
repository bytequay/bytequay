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
import { useLayoutEffect } from 'react';
import type { ReviewCodeFileDto } from '../pulls/code-tests/contracts';
import { lineChangeMarkers } from '../pulls/code-tests/lineChangeMarkers';
import type { IdeRuntime } from './runtime';

export default function LineChanges({ runtime, uri, file }: { runtime: IdeRuntime; uri: string; file: ReviewCodeFileDto | null }): null {
  const editor = runtime.editors.listCodeEditors().find(editor => editor.getModel()?.uri.toString() === uri);
  useLayoutEffect(() => {
    const model = editor?.getModel();
    if (!model || !file || file.content === null) return;
    let decorations: string[] = [];
    const update = () => {
      // Monaco carries the saved ranges through unsaved edits, so only the saved contents place them again.
      // ponytail: lines first changed in the unsaved draft get no marker until it is saved; a live diff needs the base text here.
      if (model.getValue() !== file.content?.replace(/^\uFEFF/, '')) return;
      decorations = model.deltaDecorations(decorations, lineChangeMarkers(file.lineChanges, model.getLineCount()).map(marker => ({
        range: { startLineNumber: marker.start, endLineNumber: marker.end, startColumn: 1, endColumn: 1 },
        options: { description: 'ByteQuay line change', isWholeLine: true,
          linesDecorationsClassName: marker.className, linesDecorationsTooltip: marker.title },
      })));
    };
    update();
    const changed = model.onDidChangeContent(update);
    return () => { changed.dispose(); if (!model.isDisposed()) model.deltaDecorations(decorations, []); };
  }, [editor, uri, file]);
  return null;
}
