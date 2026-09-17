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
import type { LineChangeDto, ReviewCodeFileEntryDto, ReviewCodeSymbolDto } from './contracts';

export type CodeLocation = { path: string; line: number };

export type FileScope = 'all' | 'changed';

export type CodeNavigation = {
  tabs: string[];
  history: CodeLocation[];
  index: number;
  /** Bumped on every navigation so the view re-scrolls even to the line it already shows. */
  token: number;
};

export type CodeNavigationAction =
  | { type: 'open'; location: CodeLocation }
  | { type: 'back' }
  | { type: 'forward' }
  | { type: 'close'; path: string }
  | { type: 'retain'; paths: ReadonlySet<string> };

export const EMPTY_NAVIGATION: CodeNavigation = { tabs: [], history: [], index: -1, token: 0 };

export function currentLocation(navigation: CodeNavigation): CodeLocation | null {
  return navigation.history[navigation.index] ?? null;
}

export function canGoBack(navigation: CodeNavigation) {
  return navigation.index > 0;
}

export function canGoForward(navigation: CodeNavigation) {
  return navigation.index >= 0 && navigation.index < navigation.history.length - 1;
}

export function navigate(state: CodeNavigation, action: CodeNavigationAction): CodeNavigation {
  switch (action.type) {
    case 'open': {
      const tabs = state.tabs.includes(action.location.path) ? state.tabs : [...state.tabs, action.location.path];
      const history = [...state.history.slice(0, state.index + 1), action.location];
      return { tabs, history, index: history.length - 1, token: state.token + 1 };
    }
    case 'back':
      return canGoBack(state) ? reopen(state, state.index - 1) : state;
    case 'forward':
      return canGoForward(state) ? reopen(state, state.index + 1) : state;
    case 'close': {
      if (!state.tabs.includes(action.path)) return state;
      const tabs = state.tabs.filter(path => path !== action.path);
      const current = currentLocation(state);
      if (current?.path !== action.path) return { ...state, tabs };
      const neighbour = tabs[Math.min(state.tabs.indexOf(action.path), tabs.length - 1)];
      if (neighbour === undefined) return { ...state, tabs };
      const history = [...state.history.slice(0, state.index + 1), { path: neighbour, line: 0 }];
      return { tabs, history, index: history.length - 1, token: state.token + 1 };
    }
    case 'retain': {
      const tabs = state.tabs.filter(path => action.paths.has(path));
      const history = state.history.filter(location => action.paths.has(location.path));
      if (tabs.length === state.tabs.length && history.length === state.history.length) return state;
      const current = currentLocation(state);
      const index = current !== null && action.paths.has(current.path)
        ? history.lastIndexOf(current)
        : history.length - 1;
      return { tabs, history, index, token: state.token + 1 };
    }
  }
}

function reopen(state: CodeNavigation, index: number): CodeNavigation {
  const location = state.history[index];
  const tabs = state.tabs.includes(location.path) ? state.tabs : [...state.tabs, location.path];
  return { tabs, history: state.history, index, token: state.token + 1 };
}

export function visibleFiles(files: readonly ReviewCodeFileEntryDto[], scope: FileScope, query: string, testsOnly = false): ReviewCodeFileEntryDto[] {
  const needle = query.trim().toLowerCase();
  return files.filter(file => (scope === 'all' || file.changedInPr || file.onlyInWorkingCopy)
    && (!testsOnly || isTestSource(file.path))
    && (needle === '' || file.path.toLowerCase().includes(needle)));
}

export function touchedCount(files: readonly ReviewCodeFileEntryDto[]) {
  return files.filter(file => file.changedInPr || file.onlyInWorkingCopy).length;
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

export type CodeLanguage = 'java' | 'xml' | 'properties' | 'yaml' | 'shell' | 'sql' | 'javascript' | 'python' | 'text';

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
};

export function languageFor(path: string): CodeLanguage {
  const name = fileName(path);
  if (name === 'mvnw') return 'shell';
  const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '';
  return LANGUAGE_BY_EXTENSION[extension] ?? 'text';
}

/** The Java package a source file declares, for the breadcrumb; null when it declares none. */
export function javaPackage(content: string | null): string | null {
  const match = content?.match(/^\s*package\s+([\w.]+)\s*;/m);
  return match?.[1] ?? null;
}

export function shortSha(sha: string) {
  return sha.slice(0, 7);
}

/** Files for the palette: a name match ranks above a path match, and shorter paths first within each rank. */
export function paletteFiles(files: readonly ReviewCodeFileEntryDto[], query: string, limit: number): ReviewCodeFileEntryDto[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') return [];
  const rank = (file: ReviewCodeFileEntryDto) => {
    const name = fileName(file.path).toLowerCase();
    if (name.startsWith(needle)) return 0;
    if (name.includes(needle)) return 1;
    return file.path.toLowerCase().includes(needle) ? 2 : -1;
  };
  return files
    .map(file => ({ file, rank: rank(file) }))
    .filter(item => item.rank >= 0)
    .sort((a, b) => a.rank - b.rank || a.file.path.length - b.file.path.length)
    .slice(0, limit)
    .map(item => item.file);
}

const TYPE_KINDS: ReadonlySet<string> = new Set(['class', 'interface', 'enum', 'struct', 'trait', 'protocol', 'type_alias', 'record']);
const METHOD_KINDS: ReadonlySet<string> = new Set(['method', 'function', 'constructor']);
const MEMBER_KINDS: ReadonlySet<string> = new Set([...TYPE_KINDS, ...METHOD_KINDS, 'field', 'property', 'constant', 'enum_member']);

export function symbolGroup(symbol: Pick<ReviewCodeSymbolDto, 'kind'>): 'type' | 'method' | 'member' | 'other' {
  if (TYPE_KINDS.has(symbol.kind)) return 'type';
  if (METHOD_KINDS.has(symbol.kind)) return 'method';
  return MEMBER_KINDS.has(symbol.kind) ? 'member' : 'other';
}

/** Types and methods a palette or outline offers as destinations. */
export function navigableSymbols(symbols: readonly ReviewCodeSymbolDto[]): ReviewCodeSymbolDto[] {
  return symbols.filter(symbol => {
    const group = symbolGroup(symbol);
    return group === 'type' || group === 'method';
  });
}

/** Definitions a ⌘-click on `name` can go to: exact-name declarations, never imports or parameters. */
export function definitionsNamed(symbols: readonly ReviewCodeSymbolDto[], name: string): ReviewCodeSymbolDto[] {
  const seen = new Set<string>();
  return symbols.filter(symbol => {
    if (symbol.name !== name || symbolGroup(symbol) === 'other') return false;
    const key = `${symbol.path}:${symbol.startLine}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function symbolLabel(symbol: Pick<ReviewCodeSymbolDto, 'kind' | 'name'>) {
  return symbolGroup(symbol) === 'method' ? `${symbol.name}()` : symbol.name;
}

/**
 * Whether an Agent review finding can sit on these lines: Changes shows it at its end line only while it is current,
 * and here the text on screen must also be the revision it was validated at, holding its excerpt.
 */
export function findingOnLines(finding: ReviewFindingDto, clean: boolean, reviewedHead: string | null, lines: readonly string[]) {
  return clean && finding.side === 'RIGHT' && finding.validatedHeadSha === reviewedHead
    && lines.slice(finding.startLine - 1, finding.endLine).join('\n') === finding.excerpt.join('\n');
}

export type GutterChanges = {
  lines: ReadonlyMap<number, 'added' | 'modified'>;
  /** Lines that removed lines followed; 0 is the top of the file. */
  deletedAfter: ReadonlySet<number>;
};

export function gutterChanges(changes: readonly LineChangeDto[]): GutterChanges {
  const lines = new Map<number, 'added' | 'modified'>();
  const deletedAfter = new Set<number>();
  for (const change of changes) {
    if (change.kind === 'DELETED') {
      deletedAfter.add(change.line);
      continue;
    }
    for (let line = change.line; line < change.line + change.count; line++) lines.set(line, change.kind === 'ADDED' ? 'added' : 'modified');
  }
  return { lines, deletedAfter };
}

export function testClassName(path: string) {
  return fileName(path).replace(/\.java$/, '');
}

export type CommentAnchorFacts = {
  prPatch: string | null;
  start: number;
  end: number;
  /** The range, or the file, has unsaved text. */
  draft: boolean;
  /** Saved changes to this file that the reviewed revision does not have. */
  uncommitted: boolean;
  /** The working copy's HEAD is the review run's head (always true when reading the revision itself). */
  atReviewedRevision: boolean;
  /** The review run's head is the pull request head GitHub comments land on. */
  reviewedIsHead: boolean;
};

/**
 * Why a GitHub line comment cannot anchor to this range, or null when it can. GitHub accepts line comments only on
 * lines of the pull request diff, so a range must be new-side lines of one hunk in the file's patch.
 */
export function githubCommentBlocker(facts: CommentAnchorFacts): string | null {
  if (facts.draft || facts.uncommitted) return 'Save or revert your edits to comment on the reviewed revision';
  if (!facts.atReviewedRevision) return 'The working copy is not at the reviewed revision';
  if (!facts.reviewedIsHead) return 'Update the review to comment on its latest revision';
  const inOneHunk = parseUnifiedDiff(facts.prPatch).some(hunk => {
    const lines = new Set(hunk.rows.filter(row => row.kind !== 'del').map(row => row.newLine));
    for (let line = facts.start; line <= facts.end; line++) {
      if (!lines.has(line)) return false;
    }
    return true;
  });
  return inOneHunk ? null : "GitHub accepts line comments only on lines in this pull request's diff";
}
