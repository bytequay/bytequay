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

export type FileTreeNode<T> = DirNode<T> | FileNode<T>;

export type DirNode<T> = {
  kind: 'dir';
  /** display name; may contain '/' after compaction (e.g. "src/main/java") */
  name: string;
  /** full path from root, used as a stable key for collapse state */
  path: string;
  children: FileTreeNode<T>[];
};

export type FileNode<T> = {
  kind: 'file';
  name: string;
  path: string;
  data: T;
};

export function buildFileTree<T>(items: T[], pathOf: (item: T) => string): FileTreeNode<T>[] {
  const root: FileTreeNode<T>[] = [];
  for (const item of items) {
    const parts = pathOf(item).split('/');
    insert(root, parts, 0, item, '');
  }
  sortTree(root);
  return compactChildren(root);
}

function insert<T>(
  children: FileTreeNode<T>[],
  parts: string[],
  idx: number,
  item: T,
  pathPrefix: string,
): void {
  const name = parts[idx];
  const fullPath = pathPrefix ? `${pathPrefix}/${name}` : name;
  if (idx === parts.length - 1) {
    children.push({ kind: 'file', name, path: fullPath, data: item });
    return;
  }
  let dir = children.find((c): c is DirNode<T> => c.kind === 'dir' && c.name === name);
  if (!dir) {
    dir = { kind: 'dir', name, path: fullPath, children: [] };
    children.push(dir);
  }
  insert(dir.children, parts, idx + 1, item, fullPath);
}

function sortTree<T>(children: FileTreeNode<T>[]): void {
  children.sort((a, b) => {
    if (a.kind !== b.kind) return a.kind === 'dir' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
  for (const c of children) {
    if (c.kind === 'dir') sortTree(c.children);
  }
}

function compactChildren<T>(children: FileTreeNode<T>[]): FileTreeNode<T>[] {
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
export type TreeRow<T> =
  | { kind: 'dir'; name: string; path: string; depth: number; collapsed: boolean }
  | { kind: 'file'; name: string; path: string; data: T; depth: number };

export function flattenFileTree<T>(
  nodes: FileTreeNode<T>[],
  collapsed: ReadonlySet<string>,
  depth = 0,
): TreeRow<T>[] {
  const rows: TreeRow<T>[] = [];
  for (const node of nodes) {
    if (node.kind === 'file') {
      rows.push({ kind: 'file', name: node.name, path: node.path, data: node.data, depth });
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
