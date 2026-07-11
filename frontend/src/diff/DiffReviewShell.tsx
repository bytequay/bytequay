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
import { useMemo, useRef, useState, type ReactNode } from 'react';
import ResizeHandle from '../ResizeHandle';
import type { DiffFileDto } from '../types';
import { statusBadge } from '../diffStatusBadge';
import { treeOrderedFiles } from '../fileTree';
import { ContinuousDiff } from './DiffFileList';
import { DiffFileTreePane, type FilesPaneMode } from './DiffFileTreePane';

export function DiffReviewShell({
  title,
  files,
  renderFileBody,
  onBack,
  error = null,
  mode = 'tree',
  emptyText = 'No files changed.',
  loadingText = 'Loading diff…',
  backLabel = '← Back',
  toolbarActions,
}: {
  title: ReactNode;
  /** Null = loading. Empty array = no changes. */
  files: DiffFileDto[] | null;
  renderFileBody: (file: DiffFileDto) => ReactNode;
  onBack: () => void;
  error?: string | null;
  mode?: FilesPaneMode;
  emptyText?: string;
  loadingText?: string;
  backLabel?: string;
  toolbarActions?: ReactNode;
}) {
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(new Set());
  const [filesWidth, setFilesWidth] = useState(260);
  const bodyRef = useRef<HTMLDivElement>(null);

  const orderedFiles = useMemo(
    () => (files ? treeOrderedFiles(files, f => f.filename) : []),
    [files],
  );

  const toggleDir = (path: string) => setCollapsedDirs(prev => {
    const next = new Set(prev);
    if (next.has(path)) next.delete(path);
    else next.add(path);
    return next;
  });

  const onFilesResize = (clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    setFilesWidth(Math.max(180, Math.min(480, clientX - rect.left)));
  };

  return (
    <div className="diff-viewer">
      <div className="diff-viewer__toolbar">
        <button className="button button--secondary" onClick={onBack} type="button">{backLabel}</button>
        <div className="diff-viewer__title">
          <span className="diff-viewer__pr-title">{title}</span>
        </div>
        {toolbarActions}
      </div>
      <div
        className="diff-viewer__body"
        ref={bodyRef}
        style={{ gridTemplateColumns: `${filesWidth}px 5px minmax(0, 1fr)` }}
      >
        <aside className="diff-viewer__files">
          <div className="diff-viewer__files-header">
            <span>Changed files</span>
            {files !== null && <span className="diff-viewer__files-count">{files.length}</span>}
          </div>
          <DiffFileTreePane
            files={files}
            error={error}
            mode={mode}
            pathOf={f => f.filename}
            statusBadgeOf={f => statusBadge(f.status)}
            selectedPath={selectedPath}
            onSelectPath={setSelectedPath}
            collapsedDirs={collapsedDirs}
            onToggleDir={toggleDir}
          />
        </aside>

        <ResizeHandle onResize={onFilesResize} ariaLabel="Resize changed-files panel" />

        <main className="diff-viewer__pane">
          {files !== null && files.length > 0 ? (
            <ContinuousDiff
              files={orderedFiles}
              selectedPath={selectedPath}
              onActiveFileChange={setSelectedPath}
              renderFileBody={renderFileBody}
            />
          ) : error !== null ? (
            <div className="diff-viewer__error">{error}</div>
          ) : files === null ? (
            <div className="diff-viewer__loading">{loadingText}</div>
          ) : (
            <div className="diff-viewer__empty">{emptyText}</div>
          )}
        </main>
      </div>
    </div>
  );
}
