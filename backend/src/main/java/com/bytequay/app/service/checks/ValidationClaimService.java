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
package com.bytequay.app.service.checks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static java.util.Objects.requireNonNull;

/**
 * Claimed validation: one durable, fingerprinted claim row owns one
 * validation of one exact code state, and checks run with no database
 * transaction active. The flow is the design's three steps — insert the
 * STARTED claim in a short task command and commit; admit at most one
 * executor (in-JVM single flight + cross-restart owner/lease CAS); and
 * finish through a terminal CAS that only the owner of the exact
 * fingerprint can win. Completion publishes the same
 * {@link ValidationPassFinishedEvent} the legacy path publishes, so the
 * phase machine's existing listener keeps working unchanged.
 *
 * <p>Legacy {@link ValidationPassService#run} remains for callers inside
 * ambient transactions (the brain-review paths) until the round machine
 * rewires them.
 */
@Service
public class ValidationClaimService
{
    private static final Logger log = LoggerFactory.getLogger(ValidationClaimService.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final long RENEW_PERIOD_MS = 30_000;

    static final String CONTEXT_DEV_ROUND = "dev-round";

    private final ValidationPassStore store;
    private final TaskStore taskStore;
    private final ValidationPassService validation;
    private final CodeFingerprints fingerprints;
    private final ValidationExecutorRegistry registry;
    private final TaskCommandExecutor commands;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String executorIdentity;

    public ValidationClaimService(
            ValidationPassStore store,
            TaskStore taskStore,
            ValidationPassService validation,
            CodeFingerprints fingerprints,
            ValidationExecutorRegistry registry,
            TaskCommandExecutor commands,
            ApplicationEventPublisher events,
            ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.validation = requireNonNull(validation, "validation is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.events = requireNonNull(events, "events is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = Clock.systemUTC();
        this.executorIdentity = ProcessHandle.current().pid() + "@" + hostName();
    }

    /**
     * Claim and run the normal dev-round validation for the task's
     * current code fingerprint. Idempotent per fingerprint: an existing
     * terminal claim replays its finished event; a live leased claim is
     * left to its owner.
     */
    public void claimAndRunDevRound(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null || task.worktreePath() == null || task.worktreePath().isBlank()) {
            // No worktree to fingerprint — legacy semantics (checks decide).
            validation.run(taskId);
            return;
        }
        String fingerprint = fingerprints.fingerprint(Path.of(task.worktreePath()));
        String claimKey = CONTEXT_DEV_ROUND + ":" + taskId + ":" + fingerprint;

        ValidationClaim claim = commands.execute(taskId, () -> {
            Optional<Long> inserted = store.insertClaim(
                    claimKey, taskId, CONTEXT_DEV_ROUND, /* roundId */ null,
                    fingerprint, /* throughSequence */ null, /* rootSetDigest */ null,
                    clock.instant());
            if (inserted.isEmpty()) {
                log.debug("validation claim {} already exists", claimKey);
            }
            return store.findByClaimKey(claimKey).orElseThrow();
        });

        if (claim.endedAt() != null) {
            // Terminal already — replay the finished event so a dropped
            // consumer advances; the phase-machine listener is idempotent.
            publishFinished(taskId, claim);
            return;
        }
        if (!claim.isLive()) {
            return; // cancelled or superseded — nothing to run
        }
        registry.submitIfAbsent(claimKey, () -> runOwned(taskId, claimKey, fingerprint));
    }

    private void runOwned(String taskId, String claimKey, String fingerprint)
    {
        String ownerId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!store.acquireOwner(claimKey, ownerId, executorIdentity, now.plus(LEASE), now)) {
            return; // someone else owns a live lease
        }
        ScheduledFuture<?> renewal = registry.scheduleLeaseRenewal(
                () -> store.renewLease(
                        claimKey, ownerId, clock.instant().plus(LEASE), clock.instant()),
                RENEW_PERIOD_MS);
        boolean passed;
        List<ValidationFailure> failures;
        try {
            failures = validation.runChecks(taskId);
            passed = failures.isEmpty();
        }
        catch (RuntimeException e) {
            log.warn("claimed validation {} failed to execute: {}", claimKey, e.getMessage());
            failures = List.of(new ValidationFailure("validation", String.valueOf(e.getMessage())));
            passed = false;
        }
        finally {
            renewal.cancel(false);
        }
        if (!store.completeOwned(claimKey, ownerId, fingerprint, clock.instant(), passed, toJson(failures))) {
            log.warn("claimed validation {} lost ownership before completion; result discarded", claimKey);
            return;
        }
        events.publishEvent(new ValidationPassFinishedEvent(taskId, passed, failures));
    }

    private void publishFinished(String taskId, ValidationClaim claim)
    {
        boolean passed = Boolean.TRUE.equals(claim.passed());
        events.publishEvent(new ValidationPassFinishedEvent(
                taskId, passed, passed ? List.of() : fromJson(claim.failuresJson())));
    }

    private String toJson(List<ValidationFailure> failures)
    {
        try {
            return mapper.writeValueAsString(failures);
        }
        catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<ValidationFailure> fromJson(String json)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(mapper.readValue(json, ValidationFailure[].class));
        }
        catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String hostName()
    {
        String name = System.getenv("HOSTNAME");
        return name == null || name.isBlank() ? "local" : name;
    }
}
