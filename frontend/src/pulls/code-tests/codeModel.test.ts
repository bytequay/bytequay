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
import {
  canGoBack,
  canGoForward,
  currentLocation,
  definitionsNamed,
  EMPTY_NAVIGATION,
  findingOnLines,
  githubCommentBlocker,
  gutterChanges,
  inTestRoot,
  isTestSource,
  javaPackage,
  languageFor,
  navigate,
  navigableSymbols,
  paletteFiles,
  touchedCount,
  visibleFiles,
  type CodeNavigation,
  type CodeNavigationAction,
} from './codeModel';
import { reviewFixture } from '../agent-review/fixtures/review';
import { parseReviewCodeFile, parseReviewCodeFiles, parseReviewCodeSymbols, type ReviewCodeSymbolDto } from './contracts';

function run(...actions: CodeNavigationAction[]): CodeNavigation {
  return actions.reduce(navigate, EMPTY_NAVIGATION);
}

const open = (path: string, line = 0): CodeNavigationAction => ({ type: 'open', location: { path, line } });

describe('code navigation', () => {
  it('opens tabs once and walks the visited locations back and forward', () => {
    const state = run(open('A.java'), open('B.java', 12), open('A.java', 40));
    expect(state.tabs).toEqual(['A.java', 'B.java']);
    expect(currentLocation(state)).toEqual({ path: 'A.java', line: 40 });

    const back = navigate(state, { type: 'back' });
    expect(currentLocation(back)).toEqual({ path: 'B.java', line: 12 });
    expect(canGoForward(back)).toBe(true);
    expect(currentLocation(navigate(back, { type: 'forward' }))).toEqual({ path: 'A.java', line: 40 });
  });

  it('drops the forward entries when a new location is opened from the middle of the history', () => {
    const state = run(open('A.java'), open('B.java'), open('C.java'), { type: 'back' }, { type: 'back' }, open('D.java'));
    expect(state.history.map(location => location.path)).toEqual(['A.java', 'D.java']);
    expect(canGoForward(state)).toBe(false);
    expect(canGoBack(state)).toBe(true);
  });

  it('bumps the token on every navigation so the same line scrolls again', () => {
    const first = run(open('A.java', 5));
    const again = navigate(first, open('A.java', 5));
    expect(again.token).toBeGreaterThan(first.token);
  });

  it('closing the shown tab opens its neighbour, and reopening from history restores the tab', () => {
    const state = run(open('A.java'), open('B.java'), open('C.java'));
    const closed = navigate(state, { type: 'close', path: 'C.java' });
    expect(closed.tabs).toEqual(['A.java', 'B.java']);
    expect(currentLocation(closed)?.path).toBe('B.java');

    const back = navigate(closed, { type: 'back' });
    expect(currentLocation(back)?.path).toBe('C.java');
    expect(back.tabs).toContain('C.java');
  });

  it('closing a background tab keeps the shown file and closing the last tab leaves no tab', () => {
    const state = run(open('A.java'), open('B.java'));
    expect(currentLocation(navigate(state, { type: 'close', path: 'A.java' }))?.path).toBe('B.java');
    const single = navigate(run(open('A.java')), { type: 'close', path: 'A.java' });
    expect(single.tabs).toEqual([]);
  });

  it('forgets tabs and history of files that left the working copy', () => {
    const state = run(open('A.java'), open('Gone.java'), open('B.java'));
    const retained = navigate(state, { type: 'retain', paths: new Set(['A.java', 'B.java']) });
    expect(retained.tabs).toEqual(['A.java', 'B.java']);
    expect(retained.history.map(location => location.path)).toEqual(['A.java', 'B.java']);
    expect(currentLocation(retained)?.path).toBe('B.java');
    expect(navigate(retained, { type: 'retain', paths: new Set(['A.java', 'B.java']) })).toBe(retained);
  });

  it('forgets history of a gone file even when its tab was already closed', () => {
    const state = run(open('Gone.java'), open('B.java'), { type: 'close', path: 'Gone.java' });
    const retained = navigate(state, { type: 'retain', paths: new Set(['B.java']) });
    expect(retained.history.map(location => location.path)).toEqual(['B.java']);
    expect(canGoBack(retained)).toBe(false);
  });
});

describe('file scope', () => {
  const files = [
    { path: 'core/src/main/java/Access.java', changedInPr: true, addedInPr: false, onlyInWorkingCopy: false, uncommitted: false },
    { path: 'core/src/test/java/TestAccess.java', changedInPr: false, addedInPr: false, onlyInWorkingCopy: false, uncommitted: true },
    { path: 'core/src/test/java/TestRepro.java', changedInPr: false, addedInPr: false, onlyInWorkingCopy: true, uncommitted: true },
    { path: 'pom.xml', changedInPr: false, addedInPr: false, onlyInWorkingCopy: false, uncommitted: false },
  ];

  it('limits Changed in PR to files the pull request or the review touched, and filters by path', () => {
    expect(visibleFiles(files, 'changed', '').map(file => file.path)).toEqual([
      'core/src/main/java/Access.java',
      'core/src/test/java/TestRepro.java',
    ]);
    expect(touchedCount(files)).toBe(2);
    expect(visibleFiles(files, 'all', 'TEST').map(file => file.path)).toEqual([
      'core/src/test/java/TestAccess.java',
      'core/src/test/java/TestRepro.java',
    ]);
  });

  it('narrows either scope to test sources', () => {
    expect(visibleFiles(files, 'all', '', true).map(file => file.path)).toEqual([
      'core/src/test/java/TestAccess.java',
      'core/src/test/java/TestRepro.java',
    ]);
    expect(visibleFiles(files, 'changed', '', true).map(file => file.path)).toEqual(['core/src/test/java/TestRepro.java']);
  });
});

describe('file kinds', () => {
  it('recognizes only Surefire-named sources under src/test as tests', () => {
    expect(isTestSource('core/src/test/java/io/TestAccess.java')).toBe(true);
    expect(isTestSource('src/test/java/AccessTest.java')).toBe(true);
    expect(isTestSource('core/src/test/java/io/AccessTests.java')).toBe(true);
    expect(isTestSource('core/src/test/java/io/TestingHelper.java')).toBe(true);
    expect(isTestSource('core/src/test/java/io/Helper.java')).toBe(false);
    expect(isTestSource('core/src/main/java/io/TestAccess.java')).toBe(false);
    expect(isTestSource('core/src/test/resources/TestData.json')).toBe(false);
  });

  it('puts a test source root and everything inside it in the test scope', () => {
    expect(inTestRoot('core/src/test')).toBe(false);
    expect(inTestRoot('core/src/test/java')).toBe(true);
    expect(inTestRoot('src/test/java/io/Helper.java')).toBe(true);
    expect(inTestRoot('core/src/test/resources/data.json')).toBe(true);
    expect(inTestRoot('core/src/main/java/io/TestAccess.java')).toBe(false);
  });

  it('picks a highlighting language from the file name', () => {
    expect(languageFor('a/B.java')).toBe('java');
    expect(languageFor('pom.xml')).toBe('xml');
    expect(languageFor('mvnw')).toBe('shell');
    expect(languageFor('README')).toBe('text');
    expect(languageFor('config.YML')).toBe('yaml');
  });

  it('reads the declared Java package for the breadcrumb', () => {
    expect(javaPackage('/* header */\npackage io.trino.plugin;\n\nclass A {}')).toBe('io.trino.plugin');
    expect(javaPackage('class A {}')).toBeNull();
    expect(javaPackage(null)).toBeNull();
  });
});

describe('review code contracts', () => {
  it('parses the file list and rejects an unknown source', () => {
    expect(parseReviewCodeFiles({
      source: 'WORKING_COPY',
      revision: 'abc',
      truncated: false,
      files: [{ path: 'A.java', changedInPr: true, addedInPr: false, onlyInWorkingCopy: false, uncommitted: false }],
    }).files).toEqual([{ path: 'A.java', changedInPr: true, addedInPr: false, onlyInWorkingCopy: false, uncommitted: false }]);
    expect(() => parseReviewCodeFiles({ source: 'DISK', revision: 'abc', files: [] })).toThrow('Malformed file list source');
  });

  it('keeps an unavailable file readable as a reason instead of content', () => {
    expect(parseReviewCodeFile({ path: 'big.bin', content: null, editable: false, unavailableReason: 'Binary file' })).toEqual({
      path: 'big.bin',
      content: null,
      contentHash: null,
      editable: false,
      readOnlyReason: null,
      unavailableReason: 'Binary file',
      prPatch: null,
      lineChanges: [],
    });
  });

  it('reads line changes, dropping entries the gutter cannot place', () => {
    expect(parseReviewCodeFile({
      path: 'A.java',
      content: 'a\n',
      lineChanges: [
        { kind: 'MODIFIED', line: 2, count: 1 },
        { kind: 'DELETED', line: 0, count: 3 },
        { kind: 'RENAMED', line: 1, count: 1 },
        { kind: 'ADDED', line: 1.5, count: 1 },
        { kind: 'ADDED', line: 4, count: 0 },
        null,
      ],
    }).lineChanges).toEqual([{ kind: 'MODIFIED', line: 2, count: 1 }, { kind: 'DELETED', line: 0, count: 3 }]);
  });

  it('places a finding only on the clean reviewed revision that still holds its excerpt', () => {
    const lines = ['a', 'b', 'c', 'd'];
    const finding = { ...reviewFixture().findings[0], side: 'RIGHT' as const, startLine: 2, endLine: 3, excerpt: ['b', 'c'], validatedHeadSha: 'head' };
    expect(findingOnLines(finding, true, 'head', lines)).toBe(true);
    expect(findingOnLines(finding, false, 'head', lines)).toBe(false);
    expect(findingOnLines(finding, true, 'other', lines)).toBe(false);
    expect(findingOnLines({ ...finding, side: 'LEFT' }, true, 'head', lines)).toBe(false);
    expect(findingOnLines(finding, true, 'head', ['a', 'b', 'x', 'd'])).toBe(false);
  });

  it('marks each added or modified line and where removed lines were', () => {
    const marks = gutterChanges([
      { kind: 'MODIFIED', line: 2, count: 1 },
      { kind: 'ADDED', line: 4, count: 2 },
      { kind: 'DELETED', line: 6, count: 1 },
      { kind: 'DELETED', line: 0, count: 2 },
    ]);
    expect([...marks.lines]).toEqual([[2, 'modified'], [4, 'added'], [5, 'added']]);
    expect([...marks.deletedAfter]).toEqual([6, 0]);
  });
});

function symbol(overrides: Partial<ReviewCodeSymbolDto>): ReviewCodeSymbolDto {
  return { name: 'x', kind: 'method', qualifiedName: null, path: 'A.java', startLine: 1, startColumn: 0, endLine: 1, signature: null, test: false, added: false, ...overrides };
}

describe('navigation sources', () => {
  const entry = (path: string, changedInPr = false, onlyInWorkingCopy = false) => ({ path, changedInPr, addedInPr: false, onlyInWorkingCopy, uncommitted: onlyInWorkingCopy });

  it('ranks file-name matches above path matches, shorter paths first', () => {
    const files = [
      entry('core/src/main/java/access/Rules.java'),
      entry('core/src/main/java/access/AccessControl.java'),
      entry('plugin/src/main/java/deep/nested/access/AccessControl.java'),
      entry('core/src/main/java/other/UsesAccessControl.java'),
    ];
    expect(paletteFiles(files, 'access', 10).map(file => file.path)).toEqual([
      'core/src/main/java/access/AccessControl.java',
      'plugin/src/main/java/deep/nested/access/AccessControl.java',
      'core/src/main/java/other/UsesAccessControl.java',
      'core/src/main/java/access/Rules.java',
    ]);
    expect(paletteFiles(files, '  ', 10)).toEqual([]);
  });

  it('follows only exact-name declarations and drops duplicates of one location', () => {
    const symbols = [
      symbol({ name: 'match', kind: 'method', path: 'Rule.java', startLine: 20 }),
      symbol({ name: 'match', kind: 'method', path: 'Rule.java', startLine: 20 }),
      symbol({ name: 'match', kind: 'method', path: 'OtherRule.java', startLine: 8 }),
      symbol({ name: 'matchAll', kind: 'method', path: 'Rule.java', startLine: 30 }),
      symbol({ name: 'match', kind: 'import', path: 'Uses.java', startLine: 3 }),
      symbol({ name: 'match', kind: 'parameter', path: 'Uses.java', startLine: 9 }),
    ];
    expect(definitionsNamed(symbols, 'match').map(item => `${item.path}:${item.startLine}`)).toEqual(['Rule.java:20', 'OtherRule.java:8']);
    expect(navigableSymbols([symbol({ kind: 'class' }), symbol({ kind: 'field' }), symbol({ kind: 'method' })]).map(item => item.kind))
      .toEqual(['class', 'method']);
  });

  it('parses symbol results and rejects an unknown index status', () => {
    expect(parseReviewCodeSymbols({ status: 'BUSY', symbols: [] })).toEqual({ status: 'BUSY', symbols: [] });
    expect(parseReviewCodeSymbols({
      status: 'READY',
      symbols: [{ name: 'Rule', kind: 'class', qualifiedName: 'a.Rule', path: 'Rule.java', startLine: 3, startColumn: 0, endLine: 40, signature: null, test: false }],
    }).symbols[0]).toMatchObject({ name: 'Rule', qualifiedName: 'a.Rule', startLine: 3, test: false });
    expect(() => parseReviewCodeSymbols({ status: 'STALE', symbols: [] })).toThrow('Malformed symbol status');
  });
});

describe('GitHub comment anchoring', () => {
  const patch = '@@ -10,3 +10,4 @@\n context\n-old\n+new one\n+new two\n context\n@@ -40,2 +41,2 @@\n context\n+later';
  const facts = { prPatch: patch, start: 11, end: 12, draft: false, uncommitted: false, atReviewedRevision: true, reviewedIsHead: true };

  it('accepts new-side lines inside one hunk only', () => {
    expect(githubCommentBlocker(facts)).toBeNull();
    expect(githubCommentBlocker({ ...facts, start: 10, end: 13 })).toBeNull();
    expect(githubCommentBlocker({ ...facts, start: 13, end: 41 })).toBe("GitHub accepts line comments only on lines in this pull request's diff");
    expect(githubCommentBlocker({ ...facts, start: 20, end: 20 })).toBe("GitHub accepts line comments only on lines in this pull request's diff");
    expect(githubCommentBlocker({ ...facts, prPatch: null })).toBe("GitHub accepts line comments only on lines in this pull request's diff");
  });

  it('refuses text that is not the reviewed revision before checking the diff', () => {
    expect(githubCommentBlocker({ ...facts, draft: true })).toBe('Save or revert your edits to comment on the reviewed revision');
    expect(githubCommentBlocker({ ...facts, atReviewedRevision: false })).toBe('The working copy is not at the reviewed revision');
    expect(githubCommentBlocker({ ...facts, reviewedIsHead: false })).toBe('Update the review to comment on its latest revision');
  });
});
