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

export type DiffReviewTabIcon = 'files' | 'commits' | 'review';

export type DiffReviewExtraTab = {
  key: string;
  label: string;
  icon?: DiffReviewTabIcon;
  count?: number;
  content: ReactNode;
};

export function DiffColumnTabIcon({ icon }: { icon: DiffReviewTabIcon }) {
  if (icon === 'files') {
    return (
      <svg className="diff-viewer__col-tab-svg" viewBox="0 0 16 16" aria-hidden="true">
        <rect x="2.5" y="2.5" width="4" height="4" rx="0.8" />
        <path d="M6.5 4.5h1.4a1 1 0 0 1 1 1v5" />
        <rect x="9.5" y="3" width="4" height="3.5" rx="0.8" />
        <path d="M8.9 10.5h0.6" />
        <rect x="9.5" y="9.5" width="4" height="3.5" rx="0.8" />
      </svg>
    );
  }
  if (icon === 'commits') {
    return (
      <svg className="diff-viewer__col-tab-svg" viewBox="0 0 16 16" aria-hidden="true">
        <path className="diff-viewer__col-tab-fill" d="M11.93 8.5a4.002 4.002 0 0 1-7.86 0H.75a.75.75 0 0 1 0-1.5h3.32a4.002 4.002 0 0 1 7.86 0h3.32a.75.75 0 0 1 0 1.5h-3.32ZM8 10.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z" />
      </svg>
    );
  }
  return (
    <svg className="diff-viewer__col-tab-svg" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M2.75 2.75h10.5a1.5 1.5 0 0 1 1.5 1.5v5.5a1.5 1.5 0 0 1-1.5 1.5H8.1L4.5 13.75v-2.5H2.75a1.5 1.5 0 0 1-1.5-1.5v-5.5a1.5 1.5 0 0 1 1.5-1.5Z" />
      <path d="m5.25 7.3 1.55 1.55 3.7-3.7" />
    </svg>
  );
}

function tabLabel(label: string, count?: number): string {
  return count === undefined ? label : `${label}, ${count}`;
}

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
                  aria-label={tabLabel('Files', files?.length)}
                  title="Files"
                >
                  <span className="diff-viewer__col-tab-icon"><DiffColumnTabIcon icon="files" /></span>
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
                    aria-label={tabLabel(t.label, t.count)}
                    title={t.label}
                  >
                    <span className="diff-viewer__col-tab-icon">
                      <DiffColumnTabIcon icon={t.icon ?? (t.key === 'review' ? 'review' : 'commits')} />
                    </span>
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
