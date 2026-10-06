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
import { useEffect, useMemo, useState } from 'react';
import { FilePlus, FolderPlus, PanelLeft, RefreshCw } from 'lucide-react';
import { buildFileTree } from '../pulls/fileTree';
import TreeItems from '../pulls/code-tests/TreeItems';
import type { ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import type { IdeRuntime } from './runtime';
import JavaFileIcon from './JavaFileIcon';

/** The same compact tree as today's Code & Tests, opening files through the shared IDE. */
export default function FileExplorer({ files, selected, runtime, onRefresh, onError, onHide }: {
  files: ReviewCodeFilesDto | null; selected: string | null; runtime: IdeRuntime;
  onRefresh: () => void; onError: (error: string) => void; onHide: () => void;
}) {
  const [changed, setChanged] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const entries = useMemo(() => files?.files.filter(file => !changed || file.changedInPr || file.uncommitted || file.onlyInWorkingCopy) ?? [], [files, changed]);
  const tree = useMemo(() => buildFileTree(entries, file => file.path, { layout: 'ide' }), [entries]);
  useEffect(() => {
    if (!selected) return;
    setExpanded(current => new Set([...current, ...selected.split('/').slice(0, -1).map((_part, index, parts) => parts.slice(0, index + 1).join('/'))]));
  }, [selected]);
  const open = (path: string) => {
    void runtime.api.window.showTextDocument(runtime.workspace.with({ path: `${runtime.workspace.path}/${path}` }), { preview: false }).then(undefined, error => onError(String(error)));
  };
  const create = async (folder: boolean) => {
    const path = await runtime.api.window.showInputBox({ title: folder ? 'New folder' : 'New file', prompt: 'Path relative to this working copy',
      validateInput: value => !value || value.startsWith('/') || value.split('/').some(part => part === '..' || part === '.' || !part) ? 'Enter a relative path inside this working copy' : undefined });
    if (!path) return;
    try {
      const uri = runtime.workspace.with({ path: `${runtime.workspace.path}/${path}` });
      if (folder) await runtime.api.workspace.fs.createDirectory(uri);
      else {
        const edit = new runtime.api.WorkspaceEdit(); edit.createFile(uri, { overwrite: false });
        if (!(await runtime.api.workspace.applyEdit(edit))) throw new Error('Could not create the file');
        open(path);
      }
      onRefresh();
    }
    catch (error) { onError(String(error)); }
  };
  return <nav className="ct ct-files ide-files" aria-label="Files">
    <div className="ct-files__head"><div className="ct-scope" role="group" aria-label="File scope">
      <button aria-pressed={!changed} onClick={() => setChanged(false)}>All files</button>
      <button aria-pressed={changed} onClick={() => setChanged(true)}>Changed files <span className="ct-scope__count">{files?.files.filter(file => file.changedInPr || file.uncommitted || file.onlyInWorkingCopy).length ?? 0}</span></button>
    </div><button className="ct-icon-button" title="Hide files" aria-label="Hide files" onClick={onHide}><PanelLeft size={14} /></button></div>
    <div className="ide-file-actions">
      <button onClick={() => { void create(false); }} title="New file" aria-label="New file"><FilePlus size={14} /></button>
      <button onClick={() => { void create(true); }} title="New folder" aria-label="New folder"><FolderPlus size={14} /></button>
      <button onClick={onRefresh} title="Refresh files" aria-label="Refresh files"><RefreshCw size={13} /></button>
    </div>
    <div className="ct-files__list">
      {files === null && <div className="ct-files__note" role="status">Loading files…</div>}
      <TreeItems items={tree} depth={0} selected={selected} isOpen={path => changed ? !expanded.has(`closed:${path}`) : expanded.has(path)}
        renderFileIcon={path => path.endsWith('.java') ? <JavaFileIcon uri={runtime.workspace.with({ path: `${runtime.workspace.path}/${path}` }).toString()} /> : undefined}
        onToggle={path => setExpanded(current => { const next = new Set(current); const key = changed ? `closed:${path}` : path; if (next.has(key)) next.delete(key); else next.add(key); return next; })} onOpen={open} />
      {files?.truncated && <div className="ct-files__note">More files are available through Search.</div>}
      {files && entries.length === 0 && <div className="ct-files__note">{changed ? 'Nothing touched' : 'No files'}</div>}
    </div>
  </nav>;
}
