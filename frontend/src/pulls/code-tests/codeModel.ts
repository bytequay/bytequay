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
import { parseUnifiedDiff } from '../../diffParse';
import type { ReviewFindingDto } from '../agent-review/contracts';
import type { ReviewCodeFileEntryDto } from './contracts';

export type FileScope = 'all' | 'changed';

export function visibleFiles(files: readonly ReviewCodeFileEntryDto[], scope: FileScope, query: string, testsOnly = false): ReviewCodeFileEntryDto[] {
  const needle = query.trim().toLowerCase();
  return files.filter(file => (scope === 'all' || file.changedInPr || file.onlyInWorkingCopy)
    && (!testsOnly || isTestSource(file.path))
    && (needle === '' || file.path.toLowerCase().includes(needle)));
}

export function fileName(path: string) {
  return path.slice(path.lastIndexOf('/') + 1);
}

const SUREFIRE_TEST_NAME = /^(Test.*|.*Test|.*Tests|.*TestCase)\.java$/;

/** A Java source Maven Surefire runs by default: under `src/test/` and named like a test class. */
export function isTestSource(path: string) {
  return (path.startsWith('src/test/') || path.includes('/src/test/')) && SUREFIRE_TEST_NAME.test(fileName(path));
}

const IN_TEST_ROOT = /(^|\/)src\/test\/[^/]+/;

/** IntelliJ's test scope, which it paints green: a source root under `src/test/` and everything inside it. */
export function inTestRoot(path: string) {
  return IN_TEST_ROOT.test(path);
}

/** File colors, independent of which test framework can execute the file. */
export function isTestFile(path: string) {
  return inTestRoot(path) || /(^|\/)(tests|__tests__)\//.test(path)
    || /_test\.go$|\.(test|spec)\.[cm]?[jt]sx?$/.test(path);
}

export type CodeLanguage = 'java' | 'xml' | 'properties' | 'yaml' | 'shell' | 'sql' | 'javascript' | 'python' | 'go' | 'rust' | 'text';

const LANGUAGE_BY_EXTENSION: Record<string, CodeLanguage> = {
  java: 'java',
  xml: 'xml',
  pom: 'xml',
  properties: 'properties',
  yaml: 'yaml',
  yml: 'yaml',
  sh: 'shell',
  bash: 'shell',
  sql: 'sql',
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  ts: 'javascript',
  tsx: 'javascript',
  jsx: 'javascript',
  py: 'python',
  go: 'go',
  rs: 'rust',
};

export function languageFor(path: string): CodeLanguage {
  const name = fileName(path);
  if (name === 'mvnw') return 'shell';
  const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '';
  return LANGUAGE_BY_EXTENSION[extension] ?? 'text';
}



/**
 * Whether an Agent review finding can sit on these lines: Changes shows it at its end line only while it is current,
 * and here the text on screen must also be the revision it was validated at, holding its excerpt.
 */
export function findingOnLines(finding: ReviewFindingDto, clean: boolean, sourceHead: string | null, lines: readonly string[]) {
  return clean && finding.side === 'RIGHT' && finding.validatedHeadSha === sourceHead
    && lines.slice(finding.startLine - 1, finding.endLine).join('\n') === finding.excerpt.join('\n');
}

export type CommentAnchorFacts = {
  prPatch: string | null;
  start: number;
  end: number;
  /** The range, or the file, has unsaved text. */
  draft: boolean;
  /** Saved changes to this file that its committed revision does not have. */
  uncommitted: boolean;
  /** The displayed revision is the pull request head GitHub comments land on. */
  atPrHead: boolean;
};

/**
 * Why a GitHub line comment cannot anchor to this range, or null when it can. GitHub accepts line comments only on
 * lines of the pull request diff, so a range must be new-side lines of one hunk in the file's patch.
 */
export function githubCommentBlocker(facts: CommentAnchorFacts): string | null {
  if (facts.draft || facts.uncommitted) return 'The displayed file has local edits that are not in the pull request';
  if (!facts.atPrHead) return 'The displayed code is not at the latest pull request revision';
  const inOneHunk = parseUnifiedDiff(facts.prPatch).some(hunk => {
    const lines = new Set(hunk.rows.filter(row => row.kind !== 'del').map(row => row.newLine));
    for (let line = facts.start; line <= facts.end; line++) {
      if (!lines.has(line)) return false;
    }
    return true;
  });
  return inOneHunk ? null : "GitHub accepts line comments only on lines in this pull request's diff";
}

export { shortSha } from '../../displayText';
