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

import type { DiffFileDto } from './types';

export type FileTreeNode = DirNode | FileNode;

export type DirNode = {
  kind: 'dir';
  /** display name; may contain '/' after compaction (e.g. "src/main/java") */
  name: string;
  /** full path from root, used as a stable key for collapse state */
  path: string;
  children: FileTreeNode[];
};

export type FileNode = {
  kind: 'file';
  name: string;
  path: string;
  file: DiffFileDto;
};

export function buildFileTree(files: DiffFileDto[]): FileTreeNode[] {
  const root: FileTreeNode[] = [];
  for (const file of files) {
    const parts = file.filename.split('/');
    insert(root, parts, 0, file, '');
  }
  sortTree(root);
  return compactChildren(root);
}

function insert(
  children: FileTreeNode[],
  parts: string[],
  idx: number,
  file: DiffFileDto,
  pathPrefix: string,
): void {
  const name = parts[idx];
  const fullPath = pathPrefix ? `${pathPrefix}/${name}` : name;
  if (idx === parts.length - 1) {
    children.push({ kind: 'file', name, path: fullPath, file });
    return;
  }
  let dir = children.find((c): c is DirNode => c.kind === 'dir' && c.name === name);
  if (!dir) {
    dir = { kind: 'dir', name, path: fullPath, children: [] };
    children.push(dir);
  }
  insert(dir.children, parts, idx + 1, file, fullPath);
}

function sortTree(children: FileTreeNode[]): void {
  children.sort((a, b) => {
    if (a.kind !== b.kind) return a.kind === 'dir' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
  for (const c of children) {
    if (c.kind === 'dir') sortTree(c.children);
  }
}

function compactChildren(children: FileTreeNode[]): FileTreeNode[] {
  return children.map((c) => {
    if (c.kind !== 'dir') return c;
    let cur = c;
    while (cur.children.length === 1 && cur.children[0].kind === 'dir') {
      const only = cur.children[0];
      cur = {
        kind: 'dir',
        name: `${cur.name}/${only.name}`,
        path: only.path,
        children: only.children,
      };
    }
    return { ...cur, children: compactChildren(cur.children) };
  });
}

/** Flattened row used by the renderer. Depth drives indentation. */
export type TreeRow =
  | { kind: 'dir'; name: string; path: string; depth: number; collapsed: boolean }
  | { kind: 'file'; name: string; path: string; file: DiffFileDto; depth: number };

export function flattenFileTree(
  nodes: FileTreeNode[],
  collapsed: ReadonlySet<string>,
  depth = 0,
): TreeRow[] {
  const rows: TreeRow[] = [];
  for (const node of nodes) {
    if (node.kind === 'file') {
      rows.push({ kind: 'file', name: node.name, path: node.path, file: node.file, depth });
      continue;
    }
    const isCollapsed = collapsed.has(node.path);
    rows.push({ kind: 'dir', name: node.name, path: node.path, depth, collapsed: isCollapsed });
    if (!isCollapsed) {
      rows.push(...flattenFileTree(node.children, collapsed, depth + 1));
    }
  }
  return rows;
}

/**
 * Returns {@code files} in the order they would appear under a fully
 * expanded tree view — directory-grouped, depth-first. Used to sort the
 * flat list and the continuous-scroll diff sections so the order is the
 * same regardless of the file-list mode the user picks.
 */
export function treeOrderedFiles(files: DiffFileDto[]): DiffFileDto[] {
  const rows = flattenFileTree(buildFileTree(files), new Set());
  return rows
    .filter((r): r is Extract<TreeRow, { kind: 'file' }> => r.kind === 'file')
    .map(r => r.file);
}
