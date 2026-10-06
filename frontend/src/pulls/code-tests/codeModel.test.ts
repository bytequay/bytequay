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
  findingOnLines,
  githubCommentBlocker,
  inTestRoot,
  isTestSource,
  languageFor,
  visibleFiles,
} from './codeModel';
import { reviewFixture } from '../agent-review/fixtures/review';
import { parseReviewCodeFile, parseReviewCodeFiles } from './contracts';


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
