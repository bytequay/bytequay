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
export type FileTreeDirectory<T> = {
  kind: 'directory';
  name: string;
  path: string;
  children: FileTreeItem<T>[];
  // Set only by the ide layout: the directory directly holds a pom.xml.
  module?: boolean;
};

export type FileTreeFile<T> = {
  kind: 'file';
  name: string;
  path: string;
  entry: T;
};

export type FileTreeItem<T> = FileTreeDirectory<T> | FileTreeFile<T>;

export function buildFileTree<T>(
  entries: readonly T[],
  pathOf: (entry: T) => string,
  { layout }: { layout?: 'ide' } = {},
): FileTreeItem<T>[] {
  const root: FileTreeItem<T>[] = [];

  entries.forEach(entry => {
    const filePath = pathOf(entry);
    const segments = filePath.split('/');
    const name = segments.pop() ?? filePath;
    let path = '';
    let children = root;

    segments.forEach(segment => {
      path = path ? `${path}/${segment}` : segment;
      let directory = children.find((item): item is FileTreeDirectory<T> =>
        item.kind === 'directory' && item.name === segment);
      if (directory === undefined) {
        directory = { kind: 'directory', name: segment, path, children: [] };
        children.push(directory);
      }
      children = directory.children;
    });

    children.push({ kind: 'file', name, path: filePath, entry });
  });

  return layout === 'ide' ? ideFileTree(root) : compactFileTree(root);
}

// IntelliJ gives resource roots a package prefix too, so their directory chains merge like packages.
const BELOW_SOURCE_ROOT = /(^|\/)src\/[^/]+\/(java|kotlin|groovy|scala|resources)\//;
// A pom.xml under src is a test fixture or resource, not a Maven module.
const UNDER_SRC = /(^|\/)src\//;

function ideFileTree<T>(items: FileTreeItem<T>[]): FileTreeItem<T>[] {
  return items.map((item): FileTreeItem<T> => {
    if (item.kind === 'file') return item;

    let { name, path, children } = item;
    if (BELOW_SOURCE_ROOT.test(path)) {
      while (children.length === 1 && children[0].kind === 'directory') {
        name = `${name}.${children[0].name}`;
        path = children[0].path;
        children = children[0].children;
      }
    }
    const module = !UNDER_SRC.test(path) && children.some(child => child.kind === 'file' && child.name === 'pom.xml');
    return { kind: 'directory', name, path, module, children: ideFileTree(children) };
  }).sort(compareIdeItems);
}

function compareIdeItems<T>(left: FileTreeItem<T>, right: FileTreeItem<T>): number {
  if (left.kind !== right.kind) return left.kind === 'directory' ? -1 : 1;
  return compareFileNames(left.name, right.name, true);
}

// Port of IntelliJ's FileNameComparator (NaturalComparator.naturalCompare with likeFileNames).
// ponytail: IntelliJ's space-before-number rules are left out; add them if spaced names sort visibly wrong.
function compareFileNames(left: string, right: string, ignoreCase: boolean): number {
  let i = 0;
  let j = 0;
  for (; i < left.length && j < right.length; i++, j++) {
    const leftEnd = digitRunEnd(left, i);
    const rightEnd = leftEnd > i ? digitRunEnd(right, j) : j;
    if (leftEnd > i && rightEnd > j) {
      const leftNumber = left.slice(i, leftEnd).replace(/^0+/, '');
      const rightNumber = right.slice(j, rightEnd).replace(/^0+/, '');
      const diff = leftNumber.length - rightNumber.length
        || compareCodeUnits(leftNumber, rightNumber)
        || (leftEnd - i) - (rightEnd - j);
      if (diff !== 0) return diff;
      i = leftEnd - 1;
      j = rightEnd - 1;
    } else if (left[i] !== right[j]) {
      const leftChar = left[i] === '-' && right[j] !== '_' ? '_' : left[i];
      const rightChar = right[j] === '-' && left[i] !== '_' ? '_' : right[j];
      const diff = ignoreCase ? compareIgnoringCase(leftChar, rightChar) : compareCodeUnits(leftChar, rightChar);
      if (diff !== 0) return diff;
    }
  }
  return (left.length - i) - (right.length - j) || (ignoreCase ? compareFileNames(left, right, false) : 0);
}

function digitRunEnd(name: string, start: number): number {
  let end = start;
  while (end < name.length && name[end] >= '0' && name[end] <= '9') end++;
  return end;
}

// Mirrors IntelliJ's Strings.compare(char, char, true): upper-case first, then lower-case the upper-cased chars.
function compareIgnoringCase(left: string, right: string): number {
  const leftUpper = left.toUpperCase();
  const rightUpper = right.toUpperCase();
  return leftUpper === rightUpper ? 0 : compareCodeUnits(leftUpper.toLowerCase(), rightUpper.toLowerCase());
}

function compareCodeUnits(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function compactFileTree<T>(items: FileTreeItem<T>[]): FileTreeItem<T>[] {
  return items.map(item => {
    if (item.kind === 'file') return item;

    let name = item.name;
    let path = item.path;
    let children = compactFileTree(item.children);
    while (children.length === 1) {
      const child = children[0];
      if (child.kind !== 'directory') break;
      name = `${name}/${child.name}`;
      path = child.path;
      children = child.children;
    }
    return { kind: 'directory', name, path, children };
  });
}
