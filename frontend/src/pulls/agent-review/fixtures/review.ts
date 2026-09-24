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
import type { AgentReviewDto, ReviewFindingDto, ReviewRunDto } from '../contracts';

/**
 * One illustrative review, used by the static render harness and the component tests.
 *
 * The findings and commits are invented for the fixture; they are not a review of a real pull
 * request.
 */
const COMMITS = [
  { sha: 'a41f2c0a1b2c3d4e5f60718293a4b5c6d7e8f900', message: 'Parse dotted paths in sorted_by', author: 'ezra-b' },
  { sha: '7be9d13a1b2c3d4e5f60718293a4b5c6d7e8f901', message: 'Resolve nested field IDs in IcebergUtil.getSortFields', author: 'ezra-b' },
  { sha: 'f03e8a7a1b2c3d4e5f60718293a4b5c6d7e8f902', message: 'Reject list/map paths in setTableProperties', author: 'ezra-b' },
  { sha: '2c6d9bea1b2c3d4e5f60718293a4b5c6d7e8f903', message: 'Add TestIcebergNestedSortOrder', author: 'ezra-b' },
  { sha: 'd9a0c41a1b2c3d4e5f60718293a4b5c6d7e8f904', message: 'Document nested sorted_by', author: 'ezra-b' },
];

const SUMMARY = {
  revision: 1,
  purpose: 'Lets `sorted_by` name a field inside a struct column (like `address.zip`) instead of only top-level columns. '
    + 'Iceberg sort orders are stored by field ID, so the change is mostly about finding the right ID for a dotted path '
    + 'and refusing paths that cannot be sorted on.',
  commits: [
    { sha: COMMITS[0].sha, explanation: 'Splits “address.zip” into a path instead of treating it as one column name.' },
    { sha: COMMITS[1].sha, explanation: 'Walks the struct schema to find the leaf field ID Iceberg stores in the sort order.' },
    { sha: COMMITS[2].sha, explanation: 'Blocks paths through repeated elements, which Iceberg cannot sort on.' },
    { sha: COMMITS[3].sha, explanation: 'Covers two-level struct paths end to end.' },
    { sha: COMMITS[4].sha, explanation: 'Adds the dotted-path syntax to the Iceberg connector docs.' },
  ],
  combinedResult: '`sorted_by` accepts dotted struct paths end to end: parse → resolve to a field ID → validate → write the '
    + 'Iceberg sort order. Top-level behaviour is unchanged. The result depends on `resolveNestedFieldId` returning the '
    + 'right ID, which the first finding questions.',
  criticalFindingIds: ['finding-1'],
};

const RUN: ReviewRunDto = {
  id: 'run-1',
  ordinal: 1,
  scope: 'FULL',
  baseRef: 'master',
  baseSha: '3f1e77a1b2c3d4e5f60718293a4b5c6d7e8f9012',
  headSha: 'd9a0c41a1b2c3d4e5f60718293a4b5c6d7e8f904',
  priorHeadSha: null,
  commits: COMMITS,
  changedFiles: 30,
  turnIds: ['turn-1'],
  state: 'ACCEPTED',
  summary: SUMMARY,
  coverage: { complete: true, omissions: [], recordedAt: '2026-09-06T16:15:00Z' },
  investigation: null,
  steps: [],
  evidence: [],
  context: null,
  contextDeliveryStates: {},
  contextPreparationActivities: [],
  contextNoteReads: [],
  contextNoteSearches: [],
  outcome: null,
  createdAt: '2026-09-06T16:03:00Z',
  endedAt: '2026-09-06T16:15:00Z',
};

const FINDINGS: ReviewFindingDto[] = [
  {
    id: 'finding-1',
    label: 'F-1',
    runId: 'run-1',
    title: 'Nested sort-field lookup matches leaf names across struct levels',
    body: '`resolveNestedFieldId` descends by the first path segment, then falls back to matching the remaining path by '
      + 'leaf name anywhere below. With two structs that both contain a `zip` field (`address.zip` and '
      + '`shipping.address.zip`), `sorted_by = ARRAY[\'address.zip\']` resolves to the shipping field ID. The table is '
      + 'then created with a sort order on the wrong column and no error is raised.',
    severity: 'Critical',
    path: 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSortField.java',
    side: 'RIGHT',
    startLine: 118,
    endLine: 131,
    range: 'R118–R131',
    excerpt: [
      '    private static int resolveNestedFieldId(Types.StructType struct, List<String> path)',
      '    {',
      '        Types.NestedField head = struct.field(path.get(0));',
    ],
    evidence: [
      {
        ref: 'E1',
        source: 'IcebergSortField.java L118–L131 (frozen head d9a0c41)',
        provenance: 'source read from the frozen head, not the working files',
        excerpt: '118  private static int resolveNestedFieldId(Types.StructType struct, List<String> path)\n'
          + '119  {\n'
          + '120      Types.NestedField head = struct.field(path.get(0));\n'
          + '124      // fall back to any descendant whose name matches the leaf\n'
          + '125      for (Types.NestedField field : struct.fields()) {',
        explanation: 'The fallback loop is the line that makes a leaf name from another struct eligible.',
      },
      {
        ref: 'E2',
        source: 'command output: ./mvnw -pl plugin/trino-iceberg test -Dtest=TestIcebergSortField',
        provenance: 'command run by the agent in the review worktree',
        excerpt: '$ ./mvnw -pl plugin/trino-iceberg test -Dtest=TestIcebergSortField#testDuplicateLeafNameAcrossStructs\n'
          + 'expected: 7 (address.zip)  but was: 12 (shipping.address.zip)  FAILED',
        explanation: 'The test was added by the agent and asserts against the original snapshot; the code under test is unchanged.',
      },
    ],
    revision: 1,
    state: 'OPEN',
    stateReason: null,
    outdated: false,
    validatedHeadSha: 'd9a0c41a1b2c3d4e5f60718293a4b5c6d7e8f904',
    version: 0,
    updatedAt: '2026-09-06T16:06:00Z',
  },
  {
    id: 'finding-2',
    label: 'F-2',
    runId: 'run-1',
    title: 'sorted_by accepts a path through a list element without an error',
    body: 'The new check rejects map paths but only inspects the first segment for lists. A path like `items.price` '
      + '(`items` is a `list<struct>`) passes validation and later fails deep inside Iceberg with an unrelated message.',
    severity: 'Medium',
    path: 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java',
    side: 'RIGHT',
    startLine: 2412,
    endLine: 2418,
    range: 'R2412–R2418',
    excerpt: ['        if (field.type().isMapType()) {'],
    evidence: [{
      ref: 'E3',
      source: 'IcebergMetadata.java L2412–L2418 (frozen head d9a0c41)',
      provenance: 'source read, no test run',
      excerpt: '2415  // lists are not checked below the first segment\n2417      field = ((Types.StructType) field.type()).field(segments.get(i));',
      explanation: 'The cast assumes every intermediate field is a struct; a list element would throw a ClassCastException.',
    }],
    revision: 1,
    state: 'OPEN',
    stateReason: null,
    outdated: false,
    validatedHeadSha: 'd9a0c41a1b2c3d4e5f60718293a4b5c6d7e8f904',
    version: 0,
    updatedAt: '2026-09-06T16:09:00Z',
  },
  {
    id: 'finding-3',
    label: 'F-3',
    runId: 'run-1',
    title: 'Only two-level nesting is tested',
    body: 'The new test creates a struct one level deep. The resolution loop is recursive, so a three-level path '
      + '(`a.b.c`) exercises a branch that currently has no coverage.',
    severity: 'Low',
    path: 'plugin/trino-iceberg/src/test/java/io/trino/plugin/iceberg/TestIcebergNestedSortOrder.java',
    side: 'RIGHT',
    startLine: 84,
    endLine: 84,
    range: 'R84',
    excerpt: ['        assertUpdate("CREATE TABLE ...");'],
    evidence: [{
      ref: 'E4',
      source: 'TestIcebergNestedSortOrder.java L84 (frozen head d9a0c41)',
      provenance: 'source read',
      excerpt: '84  assertUpdate("CREATE TABLE " + table + " (id INT, address ROW(zip VARCHAR)) WITH (sorted_by = ARRAY[\'address.zip\'])");',
      explanation: 'Single fixture in the file; no other test references a path with more than one dot.',
    }],
    revision: 1,
    state: 'OPEN',
    stateReason: null,
    outdated: false,
    validatedHeadSha: 'd9a0c41a1b2c3d4e5f60718293a4b5c6d7e8f904',
    version: 0,
    updatedAt: '2026-09-06T16:13:00Z',
  },
];

export function reviewFixture(overrides: Partial<AgentReviewDto> = {}): AgentReviewDto {
  return {
    id: 'review-1',
    workspaceId: 'workspace-1',
    repo: 'trinodb/trino',
    number: 30304,
    state: 'OPEN',
    followUpPaused: false,
    observedHeadSha: 'd9a0c41a1b2c3d4e5f60718293a4b5c6d7e8f904',
    latestCompletedRunId: 'run-1',
    version: 3,
    createdAt: '2026-09-06T16:03:00Z',
    closedAt: null,
    runs: [RUN],
    findings: FINDINGS,
    messages: [
      {
        id: 'message-1',
        findingId: null,
        author: 'USER',
        body: 'Why does the second commit change IcebergUtil rather than the parser in the first?',
        targetRevision: 1,
        turnId: 'turn-2',
        queuedInputId: null,
        createdAt: '2026-09-06T16:18:00Z',
      },
      {
        id: 'message-2',
        findingId: null,
        author: 'AGENT',
        body: 'The parser only splits the string. The field ID lookup needs the live table schema, which is only available '
          + 'where `getSortFields` already runs.',
        targetRevision: 1,
        turnId: 'turn-2',
        queuedInputId: null,
        createdAt: '2026-09-06T16:18:30Z',
      },
    ],
    task: {
      id: 'task-1',
      workspaceId: 'workspace-1',
      state: 'OPEN',
      version: 7,
      laneId: 'codex',
      laneLabel: 'Codex',
      model: 'gpt-5-codex',
      mutationClaim: null,
      steering: true,
      nestedAgents: false,
    },
    activeTurn: null,
    queuedInputIds: [],
    pendingApprovals: 0,
    byteQuayTurnIds: ['turn-1'],
    draft: null,
    pullRequest: null,
    ...overrides,
  };
}

export function runningReviewFixture(): AgentReviewDto {
  const review = reviewFixture();
  return {
    ...review,
    latestCompletedRunId: null,
    messages: [],
    findings: [review.findings[0]],
    runs: [{
      ...RUN,
      state: 'RUNNING',
      endedAt: null,
      coverage: null,
      summary: { ...SUMMARY, criticalFindingIds: [], commits: SUMMARY.commits.slice(0, 2) },
    }],
    activeTurn: { id: 'turn-1', state: 'RUNNING', startedAt: '2026-09-06T16:03:00Z', cancellationRequestedAt: null },
    task: { ...review.task, mutationClaim: 'TURN' },
  };
}
