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
import { useMemo } from 'react';
import { Chevron, FolderIcon } from '../diffTreeIcons';
import { buildFileTree, flattenFileTree, treeOrderedFiles } from '../fileTree';
import type { StatusBadge } from '../diffStatusBadge';

export type FilesPaneMode = 'tree' | 'flat';

export type DiffFileTreePaneProps<T> = {
  /** Null = still loading. Empty array = no files changed. */
  files: T[] | null;
  /** Surfaces a parent-supplied error in the same scroll area. */
  error: string | null;
  mode: FilesPaneMode;
  pathOf: (item: T) => string;
  statusBadgeOf: (item: T) => StatusBadge;
  selectedPath: string | null;
  onSelectPath: (path: string) => void;
  collapsedDirs: ReadonlySet<string>;
  onToggleDir: (path: string) => void;
};

/**
 * Shared file-tree pane used by both the PR diff viewer and the local
 * Commits tab. The component knows about tree vs. flat mode, dir
 * collapse state, and selected-path highlighting; it does not know
 * what kind of file payload it is rendering — callers pass a
 * {@code pathOf} extractor and a {@code statusBadgeOf} mapper so a
 * GitHub DiffFileDto and a local LocalCommitFileDto can both render
 * through the same JSX without coupling the component to either type.
 */
export function DiffFileTreePane<T>(props: DiffFileTreePaneProps<T>) {
  const {
    files,
    error,
    mode,
    pathOf,
    statusBadgeOf,
    selectedPath,
    onSelectPath,
    collapsedDirs,
    onToggleDir,
  } = props;

  const tree = useMemo(() => (files ? buildFileTree(files, pathOf) : []), [files, pathOf]);
  const treeRows = useMemo(() => flattenFileTree(tree, collapsedDirs), [tree, collapsedDirs]);
  const orderedFiles = useMemo(() => (files ? treeOrderedFiles(files, pathOf) : []), [files, pathOf]);

  return (
    <div className={`diff-viewer__files-list diff-viewer__files-list--${mode}`}>
      {files === null && !error && <div className="diff-viewer__loading">Loading files…</div>}
      {error && <div className="diff-viewer__error">{error}</div>}
      {files !== null && files.length === 0 && (
        <div className="diff-viewer__empty">No files changed.</div>
      )}
      {files !== null && mode === 'tree' && treeRows.map((row) => {
        // Tighter indent than GitHub Desktop — the diff viewer's file
        // pane is narrow, every horizontal pixel back to the filename
        // helps readability.
        const indent = row.depth * 10;
        if (row.kind === 'dir') {
          return (
            <button
              key={`dir:${row.path}`}
              type="button"
              className="diff-file-row diff-file-row--dir"
              style={{ paddingLeft: indent }}
              onClick={() => onToggleDir(row.path)}
              title={row.path}
            >
              <Chevron open={!row.collapsed} />
              <FolderIcon open={!row.collapsed} />
              <span className="diff-tree-dir-name">{row.name}</span>
            </button>
          );
        }
        const badge = statusBadgeOf(row.data);
        return (
          <button
            key={`file:${row.path}`}
            type="button"
            className={`diff-file-row diff-file-row--file${selectedPath === row.path ? ' diff-file-row--selected' : ''}`}
            style={{ paddingLeft: indent }}
            onClick={() => onSelectPath(row.path)}
            title={row.path}
          >
            <span className="tree-chevron tree-chevron--placeholder" aria-hidden="true" />
            <span
              className={`diff-file-row__badge diff-file-row__badge--${badge.cls}`}
              title={badge.cls}
            >
              {badge.letter}
            </span>
            <span className="diff-file-row__name">{row.name}</span>
          </button>
        );
      })}
      {files !== null && mode === 'flat' && orderedFiles.map((f) => {
        const badge = statusBadgeOf(f);
        const path = pathOf(f);
        return (
          <button
            key={`flat:${path}`}
            type="button"
            className={`diff-file-row diff-file-row--flat${selectedPath === path ? ' diff-file-row--selected' : ''}`}
            onClick={() => onSelectPath(path)}
            title={path}
          >
            <span
              className={`diff-file-row__badge diff-file-row__badge--${badge.cls}`}
              title={badge.cls}
            >
              {badge.letter}
            </span>
            <span className="diff-file-row__name diff-file-row__name--path">{truncatePathMiddle(path)}</span>
          </button>
        );
      })}
    </div>
  );
}

/** Shortens "src/main/java/com/foo/bar/Baz.java" to "src/…/bar/Baz.java"
 *  for the flat-mode list, where there's only one row per file and the
 *  full path tends to overflow the narrow column. Tree mode shows just
 *  the leaf name, so it doesn't need this. */
export function truncatePathMiddle(path: string, headSegments = 1, tailSegments = 2): string {
  const segments = path.split('/');
  if (segments.length <= headSegments + tailSegments) return path;
  const head = segments.slice(0, headSegments).join('/');
  const tail = segments.slice(-tailSegments).join('/');
  return `${head}/…/${tail}`;
}
