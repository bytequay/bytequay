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
import { describe, expect, it } from 'vitest';
import { buildFileTree, type FileTreeDirectory, type FileTreeItem } from './fileTree';

function directory(items: FileTreeItem<string>[], name: string): FileTreeDirectory<string> {
  const found = items.find(item => item.kind === 'directory' && item.name === name);
  if (found?.kind !== 'directory') throw new Error(`${name} should be a directory`);
  return found;
}

const IDE_PATHS = [
  'README.md',
  'backend/app/pom.xml',
  'backend/app/src/main/java/com/bytequay/app/git/Git.java',
  'backend/app/src/main/java/com/bytequay/app/agent/Agent.java',
  'backend/app/src/main/resources/static/css/app.css',
  'assets/logo.png',
  '.claude/settings.json',
  'Assets/icon.png',
  '.gitignore',
  'pom.xml',
  'docs/design/notes/draft.md',
  'src/test/kotlin/org/example/FooTest.kt',
];

describe('buildFileTree', () => {
  it('sorts directories before files by IntelliJ file-name order in the ide layout', () => {
    const tree = buildFileTree(IDE_PATHS, path => path, { layout: 'ide' });

    expect(tree.map(item => `${item.kind}:${item.name}`)).toEqual([
      'directory:.claude', 'directory:Assets', 'directory:assets', 'directory:backend', 'directory:docs', 'directory:src',
      'file:.gitignore', 'file:pom.xml', 'file:README.md',
    ]);
    const app = directory(directory(tree, 'backend').children, 'app');
    expect(app.children.map(item => item.name)).toEqual(['src', 'pom.xml']);
  });

  it('compares digit runs as numbers and dashes as underscores in the ide layout', () => {
    const tree = buildFileTree([
      'V10__c.sql', 'V2__b.sql', 'V01__a.sql', 'V1__a.sql', 'pom-test.xml', 'pom.xml', 'foo-bar.txt', 'foo1.txt', 'Test10.java', 'Test2.java',
    ], path => path, { layout: 'ide' });

    expect(tree.map(item => item.name)).toEqual([
      'foo1.txt', 'foo-bar.txt', 'pom.xml', 'pom-test.xml', 'Test2.java', 'Test10.java', 'V1__a.sql', 'V01__a.sql', 'V2__b.sql', 'V10__c.sql',
    ]);
  });

  it('merges single-child packages only below a source or resource root in the ide layout', () => {
    const tree = buildFileTree(IDE_PATHS, path => path, { layout: 'ide' });

    const main = directory(directory(directory(directory(tree, 'backend').children, 'app').children, 'src').children, 'main');
    expect(main.children.map(item => item.name)).toEqual(['java', 'resources']);
    const java = directory(main.children, 'java');
    expect(java.children.map(item => item.name)).toEqual(['com.bytequay.app']);
    const app = directory(java.children, 'com.bytequay.app');
    expect(app.path).toBe('backend/app/src/main/java/com/bytequay/app');
    expect(app.children.map(item => item.name)).toEqual(['agent', 'git']);
    const resources = directory(main.children, 'resources');
    expect(resources.children.map(item => item.name)).toEqual(['static.css']);
    expect(directory(resources.children, 'static.css').children.map(item => item.name)).toEqual(['app.css']);
    expect(directory(directory(tree, 'docs').children, 'design').children.map(item => item.name)).toEqual(['notes']);

    const kotlin = directory(directory(tree, 'src').children, 'test').children;
    expect(kotlin.map(item => item.name)).toEqual(['kotlin']);
    expect(directory(kotlin, 'kotlin').children.map(item => item.name)).toEqual(['org.example']);
  });

  it('flags directories that directly hold a pom.xml in the ide layout', () => {
    const tree = buildFileTree(IDE_PATHS, path => path, { layout: 'ide' });

    const backend = directory(tree, 'backend');
    expect(backend.module).toBe(false);
    expect(directory(backend.children, 'app').module).toBe(true);
    expect(directory(tree, 'docs').module).toBe(false);
  });

  it('does not flag a pom.xml under src as a module in the ide layout', () => {
    const tree = buildFileTree([
      'core/main/pom.xml',
      'core/main/src/test/resources/fixture/pom.xml',
    ], path => path, { layout: 'ide' });

    const repositoryMain = directory(directory(tree, 'core').children, 'main');
    expect(repositoryMain.module).toBe(true);
    const resources = directory(directory(directory(repositoryMain.children, 'src').children, 'test').children, 'resources');
    expect(directory(resources.children, 'fixture').module).toBe(false);
  });

  it('keeps insertion order and slash compaction without a layout', () => {
    const tree = buildFileTree(IDE_PATHS, path => path);

    expect(tree.map(item => item.name)).toEqual([
      'README.md', 'backend/app', 'assets', '.claude', 'Assets', '.gitignore', 'pom.xml', 'docs/design/notes', 'src/test/kotlin/org/example',
    ]);
    const app = directory(tree, 'backend/app');
    expect(app).not.toHaveProperty('module');
    expect(app.children.map(item => item.name)).toEqual(['pom.xml', 'src/main']);
  });

  it('compacts directory chains without crossing branches or direct files', () => {
    const tree = buildFileTree([
      'core/main/src/main/java/com/example/connector/Connector.java',
      'core/main/src/main/java/com/example/connector/system/SystemPageSource.java',
      'core/main/src/main/java/com/example/operator/TableScanOperator.java',
      'core/main/src/test/java/com/example/execution/TestPageSource.java',
      'core/spi/pom.xml',
      'LICENSE',
      'legacy',
      'legacy/new.txt',
    ], path => path);

    expect(tree.map(item => item.name)).toEqual(['core', 'LICENSE', 'legacy', 'legacy']);
    const core = tree[0];
    if (core.kind !== 'directory') throw new Error('core should be a directory');
    expect(core.children.map(item => item.name)).toEqual(['main/src', 'spi']);
    const repositoryMain = core.children[0];
    if (repositoryMain.kind !== 'directory') throw new Error('main/src should be a directory');
    expect(repositoryMain.children.map(item => item.name)).toEqual(['main/java/com/example', 'test/java/com/example/execution']);
    const main = repositoryMain.children[0];
    if (main.kind !== 'directory') throw new Error('main/java/com/example should be a directory');
    expect(main.children.map(item => item.name)).toEqual(['connector', 'operator']);
    const connector = main.children[0];
    if (connector.kind !== 'directory') throw new Error('connector should be a directory');
    expect(connector.children.map(item => item.name)).toEqual(['Connector.java', 'system']);
    expect(tree.filter(item => item.path === 'legacy').map(item => item.kind)).toEqual(['file', 'directory']);
  });
});
