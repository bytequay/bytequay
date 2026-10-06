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
/* eslint-disable import/no-unresolved -- The symbol service is shared with the pinned native editor. */
import { useEffect, useRef, useState } from 'react';
import { File } from 'lucide-react';
import { getService, ITextModelService, ILanguageFeaturesService, IOutlineModelService } from '@codingame/monaco-vscode-api';
import { CancellationTokenSource } from '@codingame/monaco-vscode-api/vscode/vs/base/common/cancellation';
import { DisposableStore } from '@codingame/monaco-vscode-api/vscode/vs/base/common/lifecycle';
import { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import { SymbolKind } from '@codingame/monaco-vscode-api/vscode/vs/editor/common/languages';

const kinds = new Map([
  [SymbolKind.Class, ['class', 'C']], [SymbolKind.Interface, ['interface', 'I']],
  [SymbolKind.Enum, ['enum', 'E']], [SymbolKind.Struct, ['record', 'R']],
]);

/** Reuse the editor's outline; release its model when the row leaves the viewport. */
export default function JavaFileIcon({ uri }: { uri: string }) {
  const host = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(false);
  const [kind, setKind] = useState<string[] | undefined>();
  useEffect(() => {
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting));
    if (host.current) observer.observe(host.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!visible) return;
    const disposables = new DisposableStore();
    const lifetime = new CancellationTokenSource();
    let query: CancellationTokenSource | undefined;
    let timer: ReturnType<typeof setTimeout>;
    void (async () => {
      const [models, features, outlines] = await Promise.all([
        getService(ITextModelService), getService(ILanguageFeaturesService), getService(IOutlineModelService),
      ]);
      if (lifetime.token.isCancellationRequested) return;
      const reference = await models.createModelReference(URI.parse(uri));
      if (lifetime.token.isCancellationRequested) { reference.dispose(); return; }
      disposables.add(reference);
      const model = reference.object.textEditorModel;
      const refresh = () => {
        clearTimeout(timer); query?.dispose(true);
        query = new CancellationTokenSource(lifetime.token);
        const token = query.token;
        timer = setTimeout(() => {
          void outlines.getOrCreate(model, token).then(outline => {
            if (token.isCancellationRequested) return;
            const name = model.uri.path.split('/').pop()?.replace(/\.java$/, '');
            const symbol = outline.asListOfDocumentSymbols().find(item => item.name === name && kinds.has(item.kind));
            setKind(symbol ? kinds.get(symbol.kind) : undefined);
          }).catch(() => { /* A generic file icon remains available during import or a failed read. */ });
        }, 150);
      };
      disposables.add(model.onDidChangeContent(refresh));
      disposables.add(features.documentSymbolProvider.onDidChange(refresh));
      refresh();
    })().catch(() => { /* Missing/deleted files keep the generic icon until the tree refreshes. */ });
    return () => { clearTimeout(timer); lifetime.dispose(true); query?.dispose(true); disposables.dispose(); };
  }, [uri, visible]);
  return <span ref={host} className={`ct-tree__icon ide-java-icon${kind ? ` is-${kind[0]}` : ''}`} title={kind ? `Java ${kind[0]}` : 'Java source'}>
    {kind ? <span role="img" aria-label={`Java ${kind[0]}`}>{kind[1]}</span> : <File size={14} strokeWidth={1.7} aria-hidden="true" />}
  </span>;
}
