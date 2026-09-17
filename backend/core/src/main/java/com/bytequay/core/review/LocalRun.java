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
package com.bytequay.core.review;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.core.domain.Checks.checkOptionalSha;
import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One test run in a task's working copy: its selector, the source it ran against, and the runner's facts at finish.
 * It is not a {@link ReviewRun}, which is an agent review pass.
 *
 * @param path the test source the run was started from
 * @param module the Maven module directory; empty for a root-module project
 * @param command worktree-relative display argv, never executed from this text
 * @param localPaths uncommitted or working-copy-only paths when the run started
 * @param counts from the runner's reports only; empty when the run has no aggregate
 * @param outputTruncated the stored output, read through {@link LocalRunStore#findOutput}, lost part of what the run printed
 * @param omittedBytes characters dropped from the middle of the output; empty when the amount is unknown
 * @param treeUnverified the run's processes could not be verified stopped, so it still holds the working copy
 */
public record LocalRun(
        String id,
        String taskId,
        String workspaceId,
        int ordinal,
        Initiator initiator,
        Scope scope,
        Optional<String> path,
        Optional<String> testClass,
        Optional<String> testMethod,
        Optional<String> module,
        String command,
        Optional<String> sourceHeadSha,
        List<String> localPaths,
        Optional<String> sourceFingerprint,
        Status status,
        Instant startedAt,
        Optional<Instant> endedAt,
        OptionalInt exitCode,
        Optional<Counts> counts,
        boolean outputTruncated,
        OptionalLong omittedBytes,
        List<TestClass> results,
        Optional<String> agentEventId,
        Optional<String> launchId,
        boolean treeUnverified,
        long version)
{
    public LocalRun
    {
        checkText(id, "id", 64);
        checkText(taskId, "taskId", 64);
        checkText(workspaceId, "workspaceId", 128);
        checkArgument(ordinal > 0, "ordinal is not positive");
        requireNonNull(initiator, "initiator is null");
        requireNonNull(scope, "scope is null");
        checkOptionalText(path, "path", 4096);
        checkOptionalText(testClass, "testClass", 1024);
        checkOptionalText(testMethod, "testMethod", 1024);
        checkOptionalText(module, "module", 4096);
        checkArgument(scope == Scope.COMMAND || testClass.isPresent(), "a class or method run has no test class");
        checkArgument((scope == Scope.METHOD) == testMethod.isPresent(), "only a method run names a test method");
        checkText(command, "command", 64 * 1024);
        checkOptionalSha(sourceHeadSha, "sourceHeadSha");
        localPaths = ImmutableList.copyOf(localPaths);
        checkOptionalText(sourceFingerprint, "sourceFingerprint", 128);
        requireNonNull(status, "status is null");
        requireNonNull(startedAt, "startedAt is null");
        requireNonNull(endedAt, "endedAt is null");
        checkArgument((status == Status.RUNNING) == endedAt.isEmpty(), "only a running run has no end");
        requireNonNull(exitCode, "exitCode is null");
        requireNonNull(counts, "counts is null");
        requireNonNull(omittedBytes, "omittedBytes is null");
        omittedBytes.ifPresent(omitted -> checkArgument(omitted >= 0, "omittedBytes is negative"));
        results = ImmutableList.copyOf(results);
        checkOptionalText(agentEventId, "agentEventId", 128);
        checkOptionalText(launchId, "launchId", 128);
        checkArgument(version >= 0, "version is negative");
    }

    /** A new user run of one test class or method, running from {@code startedAt}. */
    public static LocalRun started(
            String id,
            String taskId,
            String workspaceId,
            int ordinal,
            String path,
            String testClass,
            Optional<String> testMethod,
            Optional<String> module,
            String command,
            String sourceHeadSha,
            List<String> localPaths,
            String sourceFingerprint,
            String launchId,
            Instant startedAt)
    {
        return new LocalRun(id, taskId, workspaceId, ordinal, Initiator.USER, testMethod.isPresent() ? Scope.METHOD : Scope.CLASS,
                Optional.of(path), Optional.of(testClass), testMethod, module, command, Optional.of(sourceHeadSha), localPaths, Optional.of(sourceFingerprint),
                Status.RUNNING, startedAt, Optional.empty(), OptionalInt.empty(), Optional.empty(), false, OptionalLong.empty(), ImmutableList.of(),
                Optional.empty(), Optional.of(launchId), false, 0);
    }

    /** A finished Maven command the review agent ran itself; a single class or method selector gives it that scope. */
    public static LocalRun mirrored(
            String id,
            String taskId,
            String workspaceId,
            int ordinal,
            Optional<String> testClass,
            Optional<String> testMethod,
            String command,
            Optional<String> sourceHeadSha,
            List<String> localPaths,
            Optional<String> sourceFingerprint,
            Status status,
            Instant startedAt,
            Instant endedAt,
            OptionalInt exitCode,
            Optional<Counts> counts,
            List<TestClass> results,
            boolean outputTruncated,
            OptionalLong omittedBytes,
            String agentEventId)
    {
        Scope scope = testClass.isEmpty() ? Scope.COMMAND : testMethod.isPresent() ? Scope.METHOD : Scope.CLASS;
        return new LocalRun(id, taskId, workspaceId, ordinal, Initiator.AGENT, scope, Optional.empty(), testClass, testMethod, Optional.empty(), command,
                sourceHeadSha, localPaths, sourceFingerprint, status, startedAt, Optional.of(endedAt), exitCode, counts, outputTruncated, omittedBytes, results,
                Optional.of(agentEventId), Optional.empty(), false, 0);
    }

    public LocalRun finished(
            Status status,
            Instant endedAt,
            OptionalInt exitCode,
            Optional<Counts> counts,
            List<TestClass> results,
            boolean outputTruncated,
            OptionalLong omittedBytes)
    {
        checkArgument(status != Status.RUNNING, "a finished run cannot be running");
        return new LocalRun(id, taskId, workspaceId, ordinal, initiator, scope, path, testClass, testMethod, module, command, sourceHeadSha, localPaths,
                sourceFingerprint, status, startedAt, Optional.of(endedAt), exitCode, counts, outputTruncated, omittedBytes, results, agentEventId, launchId,
                treeUnverified, version);
    }

    public LocalRun withTreeUnverified(boolean unverified)
    {
        return new LocalRun(id, taskId, workspaceId, ordinal, initiator, scope, path, testClass, testMethod, module, command, sourceHeadSha, localPaths,
                sourceFingerprint, status, startedAt, endedAt, exitCode, counts, outputTruncated, omittedBytes, results, agentEventId, launchId, unverified,
                version);
    }

    public enum Initiator
    {
        USER,
        AGENT
    }

    public enum Scope
    {
        CLASS,
        METHOD,
        COMMAND
    }

    public enum Status
    {
        RUNNING,
        PASSED,
        FAILED_ASSERTION,
        BUILD_FAILED,
        NO_TESTS_RAN,
        STOPPED,
        TIMED_OUT,
        RESULTS_UNAVAILABLE,
        INTERRUPTED
    }

    public record Counts(int tests, int failures, int errors, int skipped)
    {
        public Counts
        {
            checkArgument(tests >= 0 && failures >= 0 && errors >= 0 && skipped >= 0, "counts are negative");
        }
    }

    public record TestClass(String className, OptionalLong durationMs, List<TestMethod> methods)
    {
        public TestClass
        {
            checkText(className, "className");
            requireNonNull(durationMs, "durationMs is null");
            methods = ImmutableList.copyOf(methods);
        }
    }

    public record TestMethod(String name, MethodStatus status, OptionalLong durationMs, Optional<String> message, Optional<String> trace)
    {
        public TestMethod
        {
            requireNonNull(name, "name is null");
            requireNonNull(status, "status is null");
            requireNonNull(durationMs, "durationMs is null");
            requireNonNull(message, "message is null");
            requireNonNull(trace, "trace is null");
        }
    }

    public enum MethodStatus
    {
        PASSED,
        FAILED,
        ERROR,
        SKIPPED
    }
}
