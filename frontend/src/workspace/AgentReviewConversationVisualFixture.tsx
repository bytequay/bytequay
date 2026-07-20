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
import type {
  AgentReviewData, FindingRow, InvestigationStepRow, ReviewAssignmentRow,
  ReviewObjectiveRow, ReviewRoundRow,
} from '../review/agentReviewTypes';
import type { LocalPRBundle } from '../types/localPr';
import { AgentReviewConversation } from '../pulls/AgentColumn';

type FixtureState = 'running' | 'finished';

const PR_ID = 'visual-review-pr-30267';
const REVIEW_ID = 'visual-review-30267';
const TITLE = 'Resolve hive/delta/iceberg metadata using virtual threads';
const COMMITS = [
  ['058b8b2d53', 'Allow Iceberg metadata enumeration on virtual threads'],
  ['ce903ef13b', 'Allow Delta Lake metadata enumeration on virtual threads'],
  ['613592d92e', 'Allow Hive metadata enumeration on virtual threads'],
] as const;
const LAST_COMMIT = COMMITS[2];

const bundle: LocalPRBundle = {
  pr: {
    id: PR_ID,
    taskId: null,
    branchName: 'Rnn checks',
    baseBranch: 'master',
    title: TITLE,
    description: 'Use virtual-thread-per-task executors for connector metadata resolution.',
    status: 'remote-open',
    createdAt: Date.parse('2026-07-10T08:00:00Z'),
    pushedAt: Date.parse('2026-07-10T08:00:00Z'),
    remotePrNumber: 30267,
    remotePrUrl: 'https://github.com/trinodb/trino/pull/30267',
    mergedAt: null,
    closedAt: null,
    origin: 'external',
    repo: 'trinodb/trino',
    author: 'wendigo',
    syncedAt: Date.parse('2026-07-12T12:00:00Z'),
    syncedAdditions: 0,
    syncedDeletions: 0,
    syncedMergeable: true,
    syncedMergeableState: 'clean',
    syncedMergeQueueEnabled: false,
    syncedMergeQueueState: null,
    branchDeletedAt: null,
  },
  commits: COMMITS.map(([sha, message], index) => ({
    id: `visual-commit-${index + 1}`,
    localPrId: PR_ID,
    sha,
    message,
    additions: 0,
    deletions: 0,
    authoredAt: Date.parse('2026-07-10T08:00:00Z') + index * 60 * 60_000,
    pushedAt: Date.parse('2026-07-10T08:05:00Z') + index * 60 * 60_000,
  })),
  timeline: [],
  checks: [],
  comments: [],
  pendingStripCount: 0,
};

function round(
  id: string,
  agentRunId: string,
  status: ReviewRoundRow['status'],
  scope: string,
  costCents: number,
): ReviewRoundRow {
  return {
    id,
    review_id: REVIEW_ID,
    agent_run_id: agentRunId,
    trigger: 'manual',
    scope,
    start_commit: COMMITS[0][0],
    end_commit: LAST_COMMIT[0],
    status,
    budget_json: { cost_cap_cents: 150, wall_clock_minutes: 20 },
    cost_cents: costCents,
    capabilities_json: {
      source_mode: 'local-source',
      available: ['repository-read', 'git-diff'],
      unavailable: [],
    },
    trigger_stage_id: null,
    message_gate_open: status === 'RUNNING',
  };
}

function objective(id: string, roundId: string, statement: string, resolution = 'pending'): ReviewObjectiveRow {
  return {
    id,
    round_id: roundId,
    criterion_id: `criterion-${id}`,
    statement,
    source: 'planner',
    applicability_status: 'applicable',
    resolution_status: resolution,
  };
}

function assignment(
  id: string,
  roundId: string,
  reviewer: string,
  runner: ReviewAssignmentRow['runner'],
  status: string,
  summary: string,
): ReviewAssignmentRow {
  return {
    id,
    round_id: roundId,
    reviewer_def_id: reviewer,
    runner,
    status,
    understanding_summary: summary,
    assumptions_json: [],
    unknowns_json: [],
    budget_json: { hypotheses: 1, active_hypotheses: 1, steps: 24, findings: 8 },
  };
}

function step(
  id: string,
  assignmentId: string,
  actionType: string,
  target: string | string[],
  costCents = 0,
  status = 'completed',
  reason = '',
  post = '',
): InvestigationStepRow {
  return {
    id,
    assignment_id: assignmentId,
    action_type: actionType,
    arguments_json: { target, post },
    reason,
    planned: true,
    cost_cents: costCents,
    status,
  };
}

function fillSteps(
  assignmentId: string,
  count: number,
  seed: InvestigationStepRow[],
): InvestigationStepRow[] {
  const extra = Array.from({ length: Math.max(0, count - seed.length) }, (_, index) =>
    step(`${assignmentId}-extra-${index + 1}`, assignmentId, 'inspect_hunk', `changed hunk ${index + 1}`));
  return [
    ...seed.slice(0, -1),
    ...extra,
    ...seed.slice(-1),
  ];
}

function finding(
  id: string,
  roundId: string,
  objectiveId: string,
  hypothesisId: string,
  verification: FindingRow['verification_status'] = 'verified',
  lifecycle = 'included',
): FindingRow {
  return {
    id,
    review_id: REVIEW_ID,
    round_id: roundId,
    objective_id: objectiveId,
    hypothesis_id: hypothesisId,
    criterion_kind: 'hard-invariant',
    claim: `Review finding ${id}`,
    severity: 3,
    confidence_class: verification === 'rejected' ? 'REJECTED' : 'VERIFIED',
    verification_status: verification,
    requested_action: '',
    lifecycle_status: lifecycle,
    last_checked_commit: LAST_COMMIT[0],
  };
}

function baseData(rounds: ReviewRoundRow[]): AgentReviewData {
  return {
    review: {
      id: REVIEW_ID,
      repo_id: 'trinodb/trino',
      pr_id: PR_ID,
      base_commit: COMMITS[0][0],
      reviewed_head_commit: LAST_COMMIT[0],
      status: 'active',
      workspace_id: 'workspace-bytequay',
      owner_thread_id: null,
      owner_task_id: null,
    },
    rounds,
    runs: [],
    criteria: [],
    objectives: [],
    assignments: [],
    hypotheses: [],
    steps: [],
    observations: [],
    findings: [],
    evidence: [],
    verifications: [],
    relations: [],
    outcomes: [],
    knowledge_items: [],
    knowledge_provenance: [],
    activity_facts: [],
    round_messages: [],
    reviewed_commits: [],
    round_message_targets: {},
    pr_comments: [],
    pr_timeline_events: [],
  };
}

function runningFixture(): { data: AgentReviewData; round: ReviewRoundRow; roundNumber: number } {
  const firstRound = round('visual-round-1', 'visual-run-1', 'COMPLETED_WITH_QUESTIONS', 'full', 53);
  const activeRound = round('visual-round-2', 'visual-run-2', 'RUNNING', 're-verification', 31);
  const data = baseData([firstRound, activeRound]);
  data.runs = [{
    id: 'visual-run-2', taskId: null, kind: 'panel_review', source: 'local',
    parentStageId: null, reviewRoundId: activeRound.id, stageId: null,
    status: 'running', iterations: 1, budget: 150,
    headline: 'Re-verifying author fixes',
    startedAt: new Date(Date.now() - 12 * 60_000).toISOString(), finishedAt: null,
    metricsJson: null,
  }];
  data.objectives = [
    objective('run-o1', activeRound.id, 'Re-verify round-1 fixes F1 F2', 'resolved'),
    objective('run-o2', activeRound.id, 'Feature-flag removal & config surface'),
    objective('run-o3', activeRound.id, 'Enumeration bounds under concurrent catalogs'),
  ];
  data.criteria = data.objectives.map(row => ({
    id: row.criterion_id,
    kind: 'hard-invariant',
    statement: row.statement,
    source_type: 'planner',
  }));
  data.assignments = [
    assignment('run-a1', activeRound.id, 'general-api', 'api', 'completed',
      'Both round-1 fixes verified: executors now shut down via close(), and enumeration fan-out is capped. No regressions introduced.'),
    assignment('run-a2', activeRound.id, 'general-cli', 'cli', 'running',
      'Verifying the removed feature flag leaves no orphaned config keys, session properties, or docs references…'),
    assignment('run-a3', activeRound.id, 'general-api', 'api', 'queued',
      'Starts when objective 2 completes — bounded enumeration behavior under 50 concurrent catalogs.'),
  ];
  data.hypotheses = data.assignments.map((row, index) => ({
    id: `run-h${index + 1}`,
    assignment_id: row.id,
    objective_id: data.objectives[index].id,
    claim: data.objectives[index].statement,
    origin: 'planner',
    status: index === 0 ? 'resolved' : 'active',
    confidence_class: index === 0 ? 'VERIFIED' : 'SUPPORTED',
  }));
  data.steps = [
    ...fillSteps('run-a1', 14, [
      step('run-a1-s1', 'run-a1', 'diff_commits', 'F1, F2', 11),
      step('run-a1-s2', 'run-a1', 'read_file', ['HiveMetadataFactory.java', 'IcebergMetadata.java']),
      step('run-a1-s3', 'run-a1', 'check_shutdown', 'executor close() on connector shutdown'),
    ]),
    ...fillSteps('run-a2', 8, [
      step('run-a2-s1', 'run-a2', 'read_file', ['cli/Trino.java', 'ClientOptions.java'], 14),
      step('run-a2-s2', 'run-a2', 'search_config', 'metadata.* properties', 0, 'completed', 'Searched config surface for', ' — 2 hits'),
      step('run-a2-s3', 'run-a2', 'trace_symbol', 'HiveMetadataFactory', 0, 'running', 'Tracing', ' executor config → session properties…'),
    ]),
  ];
  data.findings = [
    finding('run-f1', activeRound.id, 'run-o1', 'run-h1'),
    finding('run-f2', activeRound.id, 'run-o1', 'run-h1'),
    finding('run-f3', activeRound.id, 'run-o2', 'run-h2', 'unknown', 'NEEDS_AUTHOR_INPUT'),
  ];
  data.outcomes = ['run-f1', 'run-f2'].map(findingId => ({
    finding_id: findingId,
    user_disposition: 'published',
    author_response: 'fixed',
    epistemic_resolution: 'confirmed',
    utility_assessment: 'useful',
    style_edit_magnitude: 0,
  }));
  data.reviewed_commits = COMMITS.map(([sha, message], position) => ({
    round_id: activeRound.id, sha, message, position,
  }));
  return { data, round: activeRound, roundNumber: 2 };
}

function finishedFixture(): { data: AgentReviewData; round: ReviewRoundRow; roundNumber: number } {
  const finishedRound = round('visual-round-1', 'visual-run-1', 'COMPLETED_WITH_QUESTIONS', 'full', 53);
  const data = baseData([finishedRound]);
  data.runs = [{
    id: 'visual-run-1', taskId: null, kind: 'panel_review', source: 'local',
    parentStageId: null, reviewRoundId: finishedRound.id, stageId: null,
    status: 'succeeded', iterations: 1, budget: 150,
    headline: 'Review complete with author questions',
    startedAt: '2026-07-12T10:58:48Z', finishedAt: '2026-07-12T11:00:00Z',
    metricsJson: null,
  }];
  data.objectives = [
    objective('fin-o1', finishedRound.id, 'Self-refutation pass over surviving findings', 'resolved'),
    objective('fin-o2', finishedRound.id, 'CLI surface & docs impact', 'resolved'),
    objective('fin-o3', finishedRound.id, 'Executor swap correctness & bounds', 'needs-author'),
    objective('fin-o4', finishedRound.id, 'Connector lifecycle cleanup', 'resolved'),
    objective('fin-o5', finishedRound.id, 'Changed-test coverage', 'resolved'),
  ];
  data.criteria = data.objectives.map(row => ({
    id: row.criterion_id,
    kind: 'hard-invariant',
    statement: row.statement,
    source_type: 'planner',
  }));
  data.assignments = [
    assignment('fin-a1', finishedRound.id, 'general-api', 'api', 'completed',
      'Re-tested all 7 surviving findings. Refuted 3 whose behavior was removed by the latest commits; kept 4 with line-level evidence attached.'),
    assignment('fin-a2', finishedRound.id, 'general-cli', 'cli', 'completed',
      'Confirmed the PR leaves the CLI surface untouched — no new flags, no config properties, no docs impact from the executor swap.'),
    assignment('fin-a3', finishedRound.id, 'general-api', 'api', 'completed',
      'Verified the virtual-thread-per-task swap is behavior-preserving and shut down correctly. Raised 2 questions: is a feature flag needed, and should enumeration fan-out stay bounded per catalog?'),
  ];
  data.hypotheses = data.assignments.map((row, index) => ({
    id: `fin-h${index + 1}`,
    assignment_id: row.id,
    objective_id: data.objectives[index].id,
    claim: data.objectives[index].statement,
    origin: 'planner',
    status: 'resolved',
    confidence_class: 'VERIFIED',
  }));
  data.steps = [
    ...fillSteps('fin-a1', 16, [
      step('fin-a1-s1', 'fin-a1', 'line_scan', '7 surviving findings against the diff', 8),
      step('fin-a1-s2', 'fin-a1', 'read_file', ['IcebergMetadata.java', 'DeltaLakeMetadata.java']),
      step('fin-a1-s3', 'fin-a1', 'check_removed_behavior', 'F3, F4, F6'),
    ]),
    ...fillSteps('fin-a2', 7, [
      step('fin-a2-s1', 'fin-a2', 'search_cli', 'flags touching metadata resolution'),
      step('fin-a2-s2', 'fin-a2', 'read_file', ['cli/Trino.java', 'docs/properties.rst']),
    ]),
    ...fillSteps('fin-a3', 11, [
      step('fin-a3-s1', 'fin-a3', 'read_file', 'HiveMetadataFactory.java +4'),
      step('fin-a3-s2', 'fin-a3', 'trace_lifecycle', 'newVirtualThreadPerTaskExecutor()'),
      step('fin-a3-s3', 'fin-a3', 'check_bounds', 'enumeration fan-out bounds per connector'),
    ]),
  ];
  data.findings = [
    finding('fin-f1', finishedRound.id, 'fin-o1', 'fin-h1'),
    finding('fin-f2', finishedRound.id, 'fin-o1', 'fin-h1'),
    finding('fin-f3', finishedRound.id, 'fin-o1', 'fin-h1', 'rejected', 'dropped'),
    finding('fin-f4', finishedRound.id, 'fin-o1', 'fin-h1', 'rejected', 'dropped'),
    finding('fin-f5', finishedRound.id, 'fin-o1', 'fin-h1'),
    finding('fin-f6', finishedRound.id, 'fin-o1', 'fin-h1'),
    finding('fin-f7', finishedRound.id, 'fin-o3', 'fin-h3', 'unknown', 'NEEDS_AUTHOR_INPUT'),
    finding('fin-f8', finishedRound.id, 'fin-o3', 'fin-h3', 'unknown', 'NEEDS_AUTHOR_INPUT'),
  ];
  data.reviewed_commits = COMMITS.map(([sha, message], position) => ({
    round_id: finishedRound.id, sha, message, position,
  }));
  return { data, round: finishedRound, roundNumber: 1 };
}

export default function AgentReviewConversationVisualFixture({ state }: { state: FixtureState }) {
  const fixture = state === 'running' ? runningFixture() : finishedFixture();
  return (
    <div
      data-agent-review-fixture={state}
      style={{
        width: 1600,
        height: 980,
        display: 'grid',
        gridTemplateColumns: '216px minmax(0,1fr) 430px',
        overflow: 'hidden',
        border: '1px solid rgba(0,0,0,0.13)',
        borderRadius: 12,
        background: '#fff',
      }}
    >
      <div aria-hidden="true" style={{ background: '#fafafa', borderRight: '1px solid rgba(0,0,0,0.08)' }} />
      <main style={{ minWidth: 0, minHeight: 0, display: 'flex' }}>
        <AgentReviewConversation
          bundle={bundle}
          data={fixture.data}
          round={fixture.round}
          roundNumber={fixture.roundNumber}
          onBack={() => {}}
          onTogglePanel={() => {}}
          onStopRound={() => {}}
          onStartRound={() => true}
          onSendMessage={() => true}
        />
      </main>
      <div aria-hidden="true" style={{ background: '#fff', borderLeft: '1px solid #e7e9ec' }} />
    </div>
  );
}
