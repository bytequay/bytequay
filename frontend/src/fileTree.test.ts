import { describe, expect, it } from 'vitest';
import { buildFileTree, flattenFileTree, type FileTreeNode } from './fileTree';
import type { DiffFileDto } from './types';

function f(filename: string): DiffFileDto {
  return { filename, status: 'modified', additions: 1, deletions: 0, patch: null };
}

/** Abbreviated dump of the tree, for concise assertions. */
function dump(nodes: FileTreeNode[]): unknown {
  return nodes.map((n) =>
    n.kind === 'dir' ? { dir: n.name, children: dump(n.children) } : { file: n.path },
  );
}

describe('buildFileTree', () => {
  it('returns an empty array for no files', () => {
    expect(buildFileTree([])).toEqual([]);
  });

  it('groups files into directories', () => {
    const tree = buildFileTree([f('src/a.ts'), f('src/b.ts'), f('README.md')]);
    expect(dump(tree)).toEqual([
      { dir: 'src', children: [{ file: 'src/a.ts' }, { file: 'src/b.ts' }] },
      { file: 'README.md' },
    ]);
  });

  it('compacts a single-child directory chain into one node', () => {
    const tree = buildFileTree([f('src/main/java/com/foo/A.java'), f('src/main/java/com/foo/B.java')]);
    expect(dump(tree)).toEqual([
      {
        dir: 'src/main/java/com/foo',
        children: [{ file: 'src/main/java/com/foo/A.java' }, { file: 'src/main/java/com/foo/B.java' }],
      },
    ]);
  });

  it('stops compacting at a directory that has more than one child', () => {
    const tree = buildFileTree([
      f('src/main/java/com/foo/A.java'),
      f('src/main/java/com/foo/B.java'),
      f('src/main/resources/C.xml'),
    ]);
    expect(dump(tree)).toEqual([
      {
        dir: 'src/main',
        children: [
          {
            dir: 'java/com/foo',
            children: [{ file: 'src/main/java/com/foo/A.java' }, { file: 'src/main/java/com/foo/B.java' }],
          },
          { dir: 'resources', children: [{ file: 'src/main/resources/C.xml' }] },
        ],
      },
    ]);
  });

  it('does not compact a directory whose only child is a file', () => {
    const tree = buildFileTree([f('docs/README.md')]);
    expect(dump(tree)).toEqual([{ dir: 'docs', children: [{ file: 'docs/README.md' }] }]);
  });

  it('puts directories before files at the same level, alphabetically within', () => {
    const tree = buildFileTree([f('z-top.ts'), f('src/b.ts'), f('src/a.ts'), f('a-top.ts')]);
    expect(dump(tree)).toEqual([
      { dir: 'src', children: [{ file: 'src/a.ts' }, { file: 'src/b.ts' }] },
      { file: 'a-top.ts' },
      { file: 'z-top.ts' },
    ]);
  });
});

describe('flattenFileTree', () => {
  const tree = buildFileTree([
    f('src/a.ts'),
    f('src/b.ts'),
    f('docs/README.md'),
  ]);

  it('produces one row per node in depth-first order when nothing is collapsed', () => {
    const rows = flattenFileTree(tree, new Set());
    expect(rows.map((r) => [r.kind, r.path, r.depth])).toEqual([
      ['dir', 'docs', 0],
      ['file', 'docs/README.md', 1],
      ['dir', 'src', 0],
      ['file', 'src/a.ts', 1],
      ['file', 'src/b.ts', 1],
    ]);
  });

  it('hides children of collapsed directories', () => {
    const rows = flattenFileTree(tree, new Set(['src']));
    expect(rows.map((r) => r.path)).toEqual(['docs', 'docs/README.md', 'src']);
  });
});
