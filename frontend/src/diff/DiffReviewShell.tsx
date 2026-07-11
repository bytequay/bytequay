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

export type DiffReviewExtraTab = {
  key: string;
  label: string;
  count?: number;
  content: ReactNode;
};

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
  showToolbar = true,
  toolbarActions,
  extraTabs = [],
  activeTab = 'files',
  onTabChange,
  initialFilesWidth = 260,
  minFilesWidth = 180,
  maxFilesWidth = 480,
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
  showToolbar?: boolean;
  toolbarActions?: ReactNode;
  /** Additional tabs shown alongside "Changed files" in the left column
   *  header — e.g. Commits, Review. Empty (the default) renders the plain
   *  file-tree header exactly as before. */
  extraTabs?: DiffReviewExtraTab[];
  /** Controlled by the host when `extraTabs` is non-empty; ignored otherwise. */
  activeTab?: string;
  onTabChange?: (key: string) => void;
  initialFilesWidth?: number;
  minFilesWidth?: number;
  maxFilesWidth?: number;
}) {
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(new Set());
  const [filesWidth, setFilesWidth] = useState(initialFilesWidth);
  const bodyRef = useRef<HTMLDivElement>(null);
  const hasTabs = extraTabs.length > 0;

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
    setFilesWidth(Math.max(minFilesWidth, Math.min(maxFilesWidth, clientX - rect.left)));
  };

  return (
    <div className="diff-viewer">
      {showToolbar && (
        <div className="diff-viewer__toolbar">
          <button className="button button--secondary" onClick={onBack} type="button">{backLabel}</button>
          <div className="diff-viewer__title">
            <span className="diff-viewer__pr-title">{title}</span>
          </div>
          {toolbarActions}
        </div>
      )}
      <div
        className="diff-viewer__body"
        ref={bodyRef}
        style={{ gridTemplateColumns: `${filesWidth}px 5px minmax(0, 1fr)` }}
      >
        <aside className="diff-viewer__files">
          <div className="diff-viewer__files-header">
            {hasTabs ? (
              <div className="diff-viewer__col-tabs" role="tablist" aria-label="Files or other views">
                <button
                  type="button"
                  role="tab"
                  className={`diff-viewer__col-tab${activeTab === 'files' ? ' diff-viewer__col-tab--active' : ''}`}
                  onClick={() => onTabChange?.('files')}
                  aria-selected={activeTab === 'files'}
                >
                  Files
                  {files !== null && <span className="diff-viewer__files-count">{files.length}</span>}
                </button>
                {extraTabs.map(t => (
                  <button
                    key={t.key}
                    type="button"
                    role="tab"
                    className={'diff-viewer__col-tab'
                      + (t.key === 'review' ? ' diff-viewer__col-tab--review' : '')
                      + (activeTab === t.key ? ' diff-viewer__col-tab--active' : '')}
                    onClick={() => onTabChange?.(t.key)}
                    aria-selected={activeTab === t.key}
                  >
                    {t.label}
                    {t.count !== undefined && <span className="diff-viewer__files-count">{t.count}</span>}
                  </button>
                ))}
              </div>
            ) : (
              <>
                <span>Changed files</span>
                {files !== null && <span className="diff-viewer__files-count">{files.length}</span>}
              </>
            )}
          </div>
          {activeTab === 'files' || !hasTabs ? (
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
          ) : (
            extraTabs.find(t => t.key === activeTab)?.content
          )}
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
