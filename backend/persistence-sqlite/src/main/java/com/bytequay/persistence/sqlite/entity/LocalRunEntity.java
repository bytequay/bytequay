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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.review.LocalRun;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.REVIEW;

@Entity
@Table(name = "local_run", indexes = @Index(name = "idx_local_run_task", columnList = "task_id, ordinal"))
public class LocalRunEntity
        extends AssignedIdEntity<String>
{
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<LocalRun.TestClass>> RESULTS = new TypeReference<>() {};

    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private LocalRun.Initiator initiator;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private LocalRun.Scope scope;

    @Column(columnDefinition = "TEXT")
    private String path;

    @Column(name = "test_class", columnDefinition = "TEXT")
    private String testClass;

    @Column(name = "test_method", columnDefinition = "TEXT")
    private String testMethod;

    @Column(columnDefinition = "TEXT")
    private String module;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String command;

    @Column(name = "source_head_sha")
    private String sourceHeadSha;

    @Column(name = "local_paths_json", nullable = false, columnDefinition = "TEXT")
    private String localPathsJson;

    @Column(name = "source_fingerprint")
    private String sourceFingerprint;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private LocalRun.Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    private Integer tests;

    private Integer failures;

    private Integer errors;

    private Integer skipped;

    @Column(name = "output_truncated", nullable = false)
    private boolean outputTruncated;

    @Column(name = "omitted_bytes")
    private Long omittedBytes;

    @Column(name = "results_json", nullable = false, columnDefinition = "TEXT")
    private String resultsJson;

    @Column(name = "agent_event_id", unique = true)
    private String agentEventId;

    @Column(name = "launch_id")
    private String launchId;

    @Column(name = "tree_unverified", nullable = false)
    private boolean treeUnverified;

    @Version
    @Getter
    private long version;

    public LocalRunEntity() {}

    public static LocalRunEntity fromDomain(LocalRun run)
    {
        LocalRunEntity entity = new LocalRunEntity();
        entity.id = run.id();
        entity.version = run.version();
        entity.updateFrom(run);
        return entity;
    }

    public void updateFrom(LocalRun run)
    {
        taskId = run.taskId();
        workspaceId = run.workspaceId();
        ordinal = run.ordinal();
        initiator = run.initiator();
        scope = run.scope();
        path = run.path().orElse(null);
        testClass = run.testClass().orElse(null);
        testMethod = run.testMethod().orElse(null);
        module = run.module().orElse(null);
        command = run.command();
        sourceHeadSha = run.sourceHeadSha().orElse(null);
        localPathsJson = REVIEW.write(run.localPaths(), "local run paths");
        sourceFingerprint = run.sourceFingerprint().orElse(null);
        status = run.status();
        startedAt = run.startedAt();
        endedAt = run.endedAt().orElse(null);
        exitCode = run.exitCode().isPresent() ? run.exitCode().getAsInt() : null;
        tests = run.counts().map(LocalRun.Counts::tests).orElse(null);
        failures = run.counts().map(LocalRun.Counts::failures).orElse(null);
        errors = run.counts().map(LocalRun.Counts::errors).orElse(null);
        skipped = run.counts().map(LocalRun.Counts::skipped).orElse(null);
        outputTruncated = run.outputTruncated();
        omittedBytes = run.omittedBytes().isPresent() ? run.omittedBytes().getAsLong() : null;
        resultsJson = REVIEW.write(run.results(), "local run results");
        agentEventId = run.agentEventId().orElse(null);
        launchId = run.launchId().orElse(null);
        treeUnverified = run.treeUnverified();
    }

    public LocalRun toDomain()
    {
        return new LocalRun(
                id, taskId, workspaceId, ordinal, initiator, scope, Optional.ofNullable(path), Optional.ofNullable(testClass), Optional.ofNullable(testMethod),
                Optional.ofNullable(module), command, Optional.ofNullable(sourceHeadSha), REVIEW.read(localPathsJson, STRINGS, "local run paths"),
                Optional.ofNullable(sourceFingerprint), status, startedAt, Optional.ofNullable(endedAt), exitCode == null ? OptionalInt.empty() : OptionalInt.of(exitCode),
                tests == null ? Optional.empty() : Optional.of(new LocalRun.Counts(tests, failures, errors, skipped)), outputTruncated,
                omittedBytes == null ? OptionalLong.empty() : OptionalLong.of(omittedBytes), REVIEW.read(resultsJson, RESULTS, "local run results"),
                Optional.ofNullable(agentEventId), Optional.ofNullable(launchId), treeUnverified, version);
    }
}
