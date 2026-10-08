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
import { explanationRunFixture, reviewFixture } from '../agent-review/fixtures/review';
import { parseLocalRun, parseLocalRunOutput, type LocalRunDto, type ReviewCodeFilesDto } from './contracts';
import { commandLabel, runGate, runIsStale, runLabel, runSummary, shownResults } from './runModel';

function run(overrides: Partial<LocalRunDto> = {}): LocalRunDto {
  return {
    id: 'run-1', ordinal: 1, initiator: 'USER', scope: 'METHOD', path: 'core/src/test/java/io/x/TestAccess.java',
    testClass: 'io.x.TestAccess', testMethod: 'testBuiltin', module: 'core', command: './mvnw -B -pl core -Dtest=io.x.TestAccess#testBuiltin test',
    sourceHeadSha: 'abc', localPaths: [], sourceFingerprint: 'f1', status: 'PASSED', startedAt: '2026-09-15T10:00:00Z',
    endedAt: '2026-09-15T10:00:03Z', exitCode: 0, tests: 1, failures: 0, errors: 0, skipped: 0, outputTruncated: false,
    omittedBytes: null, treeUnverified: false,
    results: [{ className: 'io.x.TestAccess', durationMs: 40, methods: [{ name: 'testBuiltin', status: 'PASSED', durationMs: 40, message: null, trace: null }] }],
    ...overrides,
  };
}

const files: ReviewCodeFilesDto = { source: 'WORKING_COPY', revision: 'abc', truncated: false, sourceFingerprint: 'f1', savedExperiment: false, files: [] };

describe('result tree', () => {
  it('keeps only failed and errored methods, and only the classes that have them, when asked', () => {
    const mixed = run({ results: [
      { className: 'io.x.TestAccess', durationMs: 40, methods: [
        { name: 'passes', status: 'PASSED', durationMs: 1, message: null, trace: null },
        { name: 'fails', status: 'FAILED', durationMs: 2, message: 'expected', trace: null },
        { name: 'breaks', status: 'ERROR', durationMs: 3, message: 'boom', trace: null },
        { name: 'skips', status: 'SKIPPED', durationMs: 0, message: null, trace: null },
      ] },
      { className: 'io.x.TestGreen', durationMs: 5, methods: [{ name: 'green', status: 'PASSED', durationMs: 5, message: null, trace: null }] },
    ] });
    expect(shownResults(mixed, true)).toEqual([{ className: 'io.x.TestAccess', durationMs: 40, methods: [
      { name: 'fails', status: 'FAILED', durationMs: 2, message: 'expected', trace: null },
      { name: 'breaks', status: 'ERROR', durationMs: 3, message: 'boom', trace: null },
    ] }]);
    expect(shownResults(mixed, false)).toBe(mixed.results);
    expect(shownResults(run(), true)).toEqual([]);
  });
});

describe('run gate', () => {
  it('allows a run only on an idle open review with a working copy', () => {
    expect(runGate(reviewFixture(), files)).toEqual({ hidden: false, blocked: null });
    expect(runGate(reviewFixture({ state: 'CLOSED' }), files).hidden).toBe(true);
    expect(runGate(reviewFixture(), { ...files, source: 'REVISION' }).hidden).toBe(true);
  });

  it('names what holds the working copy', () => {
    const task = reviewFixture().task;
    expect(runGate(reviewFixture({ task: { ...task, mutationClaim: 'LOCAL_RUN' } }), files).blocked).toBe('A run is using this working copy');
    expect(runGate(reviewFixture({ task: { ...task, mutationClaim: 'COMPLETION' } }), files).blocked).toBe('The working copy is being updated');
    expect(runGate(reviewFixture({ activeTurn: { id: 't', state: 'RUNNING', startedAt: null, cancellationRequestedAt: null } as never }), files).blocked)
      .toBe('The review agent is working');
    expect(runGate(reviewFixture({ queuedInputIds: ['q'] }), files).blocked).toBe('Remove or resume the queued question');
  });

  it('treats a paused review whose pass is still RUNNING as idle', () => {
    const review = reviewFixture();
    const running = { ...review.runs[0], state: 'RUNNING' as const };
    expect(runGate({ ...review, runs: [running], followUpPaused: false }, files).blocked).toBe('The review agent is working');
    expect(runGate({ ...review, runs: [running], followUpPaused: true }, files).blocked).toBeNull();
  });

  it('waits for explanation refresh completion even after its provider turn settles', () => {
    const review = reviewFixture();
    const explaining = explanationRunFixture({ state: 'RUNNING' });
    expect(runGate({ ...review, runs: [...review.runs, explaining] }, files).blocked).toBe('The review agent is working');
    expect(runGate({ ...review, runs: [...review.runs, { ...explaining, state: 'ACCEPTED' }] }, files).blocked).toBeNull();
  });
});

describe('run presentation', () => {
  it('never shows a successful aggregate for an unfinished or rowless run', () => {
    expect(runSummary(run({ status: 'RUNNING', endedAt: null, results: [] }), Date.parse('2026-09-15T10:00:05Z'))).toBe('Running testBuiltin() · 5s');
    expect(runSummary(run({ status: 'RESULTS_UNAVAILABLE', scope: 'COMMAND', results: [] }), 0)).toBe('Command result');
    expect(runSummary(run({ status: 'BUILD_FAILED', results: [] }), 0)).toBe('Build failed');
    expect(runSummary(run(), 0)).toBe('1 passed');
    expect(runLabel(run({ ordinal: 3, testMethod: null, scope: 'CLASS' }))).toBe('TestAccess #3');
  });

  it('names an agent command by its test selectors, or its module and goals', () => {
    expect(commandLabel('./mvnw -B --no-transfer-progress -pl plugin/trino-snowflake -am -Dtest=TestSnowflakeConfig,TestSnowflakePlugin '
      + '-Dsurefire.failIfNoSpecifiedTests=false test > /tmp/bytequay-26519-unit.log 2>&1; result=$?; tail -n 70 /tmp/bytequay-26519-unit.log; exit "$result"'))
      .toBe('TestSnowflakeConfig, TestSnowflakePlugin');
    expect(commandLabel("./mvnw -o -pl plugin/trino-iceberg '-Dtest=io.trino.plugin.iceberg.TestTrinoRestCatalog#testReview*' -DsurefireEnablePreview= compiler:testCompile surefire:test"))
      .toBe('TestTrinoRestCatalog#testReview* · compiler:testCompile surefire:test');
    expect(commandLabel('./mvnw -o -pl plugin/trino-iceberg compiler:testCompile')).toBe('trino-iceberg · compiler:testCompile');
    expect(commandLabel('export JAVA_HOME=/jdk; cd core && ../mvnw -q -T 4 verify | tail -40')).toBe('verify');
    expect(commandLabel('./mvnw')).toBe('mvnw');
    expect(runLabel(run({ scope: 'COMMAND', testClass: null, testMethod: null, ordinal: 5, command: './mvnw -pl lib/trino-cache test' }))).toBe('trino-cache #5');
  });

  it('marks a run stale only when both fingerprints are known and differ', () => {
    expect(runIsStale(run(), files)).toBe(false);
    expect(runIsStale(run({ sourceFingerprint: 'f0' }), files)).toBe(true);
    expect(runIsStale(run({ sourceFingerprint: 'f0' }), { ...files, sourceFingerprint: null })).toBe(false);
  });

});

describe('run contracts', () => {
  it('parses a run and rejects an unknown status', () => {
    const wire: LocalRunDto = { ...run(), results: [{ className: 'io.x.TestAccess', durationMs: null, methods: [] }] };
    expect(parseLocalRun(wire)).toEqual(wire);
    expect(() => parseLocalRun({ ...wire, status: 'MAYBE' })).toThrow('Malformed run status');
    expect(parseLocalRunOutput({ text: 'x', nextOffset: 1, complete: true })).toEqual({ text: 'x', nextOffset: 1, skippedBytes: 0, complete: true });
  });
});
