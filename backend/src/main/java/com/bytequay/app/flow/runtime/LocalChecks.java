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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckEvidence;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckPolicyRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckProfile;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.Inspection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Program-owned local-check policy, execution, and immutable evidence. */
public final class LocalChecks
{
    static final String PROCESS_BOUNDARY_UNPROVEN =
            "PROCESS_BOUNDARY_UNPROVEN";
    private static final String POLICY_MISSING = "LOCAL_CHECK_POLICY_MISSING";
    private static final String EVIDENCE_MISSING =
            "LOCAL_CHECK_EVIDENCE_MISSING";
    private static final String EVIDENCE_STALE =
            "LOCAL_CHECK_EVIDENCE_STALE";
    private static final int MAX_PROFILES = 16;
    private static final int MAX_ARGUMENTS = 128;
    private static final int MAX_TEXT = 4_096;
    private static final int MAX_OUTPUT_BYTES = 256 * 1_024;
    private static final Duration PROCESS_GRACE = Duration.ofSeconds(2);
    private static final Duration INSPECTION_BOUND = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FlowRuntime runtime;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FlowWorktreeInspector worktreeInspector =
            new FlowWorktreeInspector();

    static final class ProcessBoundaryUnprovenException
            extends IllegalStateException
    {
        private ProcessBoundaryUnprovenException(Throwable cause)
        {
            super(PROCESS_BOUNDARY_UNPROVEN, cause);
        }

        private ProcessBoundaryUnprovenException()
        {
            super(PROCESS_BOUNDARY_UNPROVEN);
        }
    }

    /** Unforgeable complete/latest evidence set for one initial reservation. */
    public static final class ReviewerEvidence
    {
        private final LocalChecks owner;
        private final LocalCheckEvidence evidence;

        private ReviewerEvidence(
                LocalChecks owner, LocalCheckEvidence evidence)
        {
            this.owner = requireNonNull(owner, "owner is null");
            this.evidence = requireNonNull(evidence, "evidence is null");
        }

        String taskId()
        {
            return evidence.taskId();
        }

        String changeSetRevisionId()
        {
            return evidence.changeSetRevisionId();
        }

        String policyRevisionId()
        {
            return requireNonNull(
                    evidence.policyRevisionId(),
                    "policyRevisionId is null");
        }

        GateIntent gateKind()
        {
            return evidence.gateKind();
        }

        List<String> checkRunRefs()
        {
            return evidence.checkRunRefs();
        }

        public LocalCheckEvidence evidence()
        {
            return evidence;
        }

        public void assertCurrentForReservation()
        {
            owner.assertCurrentForReservation(evidence);
        }
    }

    static final class PreparedLocalCheckBatch
    {
        private final AgentRun run;
        private final Task task;
        private final LocalCheckPolicyRevision policy;
        private final List<LocalCheckProfile> profiles;
        private final ValidationRequest validationRequest;
        private final Duration requiredAuthorityDuration;

        private PreparedLocalCheckBatch(
                AgentRun run,
                Task task,
                LocalCheckPolicyRevision policy,
                List<LocalCheckProfile> profiles,
                ValidationRequest validationRequest,
                Duration requiredAuthorityDuration)
        {
            this.run = run;
            this.task = task;
            this.policy = policy;
            this.profiles = List.copyOf(profiles);
            this.validationRequest = validationRequest;
            this.requiredAuthorityDuration = requiredAuthorityDuration;
        }

        Duration requiredAuthorityDuration()
        {
            return requiredAuthorityDuration;
        }
    }

    /** Exact local validation invocation supplied by the active Task Agent. */
    public record ValidationRequest(
            List<String> command,
            String workingDirectory)
    {
        public ValidationRequest
        {
            command = boundedUniqueList(
                    command, "command", false, MAX_ARGUMENTS);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
            requireSafeRelativePath(workingDirectory, true);
        }
    }

    /** Program input, never an agent-authored command. */
    public record ProfileDefinition(
            String name,
            List<String> command,
            String workingDirectory,
            List<String> environmentAllowlist,
            Duration timeout,
            List<GateIntent> requiredForGateKinds)
    {
        public ProfileDefinition
        {
            requireText(name, "name");
            command = boundedUniqueList(
                    command, "command", false, MAX_ARGUMENTS);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
            requireSafeRelativePath(workingDirectory, true);
            environmentAllowlist = boundedUniqueList(
                    environmentAllowlist,
                    "environmentAllowlist",
                    true,
                    64).stream().sorted().toList();
            for (String variable : environmentAllowlist) {
                if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException(
                            "invalid environment variable name");
                }
            }
            requireNonNull(timeout, "timeout is null");
            // The ceiling exists to catch a nonsense value, not to ration a
            // real build: repository verifies routinely run past an hour.
            if (timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofHours(2)) > 0
                    || timeout.toSeconds() == 0
                    || !timeout.equals(Duration.ofSeconds(
                            timeout.toSeconds()))) {
                throw new IllegalArgumentException(
                        "timeout must be 1-7200 whole seconds");
            }
            requiredForGateKinds = List.copyOf(requiredForGateKinds).stream()
                    .sorted().toList();
            if (new LinkedHashSet<>(requiredForGateKinds).size()
                    != requiredForGateKinds.size()) {
                throw new IllegalArgumentException(
                        "requiredForGateKinds contains duplicates");
            }
        }
    }

    public LocalChecks(DataSource dataSource, FlowRuntime runtime, Clock clock)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Appends or exactly replays one immutable program-owned policy. */
    public synchronized LocalCheckPolicyRevision recordPolicy(
            String repositoryId,
            String expectedCurrentPolicyRevisionId,
            String sourceRevision,
            String sourceDigest,
            List<ProfileDefinition> definitions)
    {
        requireText(repositoryId, "repositoryId");
        requireText(sourceRevision, "sourceRevision");
        requireText(sourceDigest, "sourceDigest");
        requireNonNull(definitions, "definitions is null");
        if (definitions.size() > MAX_PROFILES
                || definitions.stream().map(ProfileDefinition::name)
                        .distinct().count() != definitions.size()) {
            throw new IllegalArgumentException(
                    "policy profiles must have unique bounded names");
        }
        List<ProfileDefinition> profiles = List.copyOf(definitions);
        return inTransaction(() -> {
            Optional<LocalCheckPolicyRevision> existing = policyBySource(
                    repositoryId, sourceRevision);
            if (existing.isPresent()) {
                if (!existing.get().sourceDigest().equals(sourceDigest)
                        || !profiles(existing.get().policyRevisionId())
                                .equals(storedProfiles(
                                        existing.get().policyRevisionId(),
                                        profiles))) {
                    throw new IllegalStateException(
                            "sourceRevision already owns another local-check policy");
                }
                return existing.get();
            }
            LocalCheckPolicyRevision current = currentPolicy(repositoryId)
                    .orElse(null);
            if (!Objects.equals(
                    expectedCurrentPolicyRevisionId,
                    current == null ? null : current.policyRevisionId())) {
                throw new IllegalStateException(
                        "local-check policy current revision changed");
            }
            long sequence = current == null ? 1 : current.sequence() + 1;
            String policyId = stableId(
                    "local-check-policy",
                    repositoryId,
                    Long.toString(sequence),
                    sourceRevision,
                    sourceDigest,
                    policyDefinitionDigest(profiles));
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_local_check_policy_revision (
                        policy_revision_id, repository_id, sequence,
                        source_revision, source_digest, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    policyId,
                    repositoryId,
                    sequence,
                    sourceRevision,
                    sourceDigest,
                    now.toEpochMilli());
            for (ProfileDefinition definition : profiles) {
                insertProfile(policyId, definition);
            }
            int advanced;
            if (current == null) {
                advanced = jdbc.update(
                        """
                        INSERT INTO flow_runtime_local_check_policy_current (
                            repository_id, policy_revision_id
                        ) VALUES (?, ?)
                        """,
                        repositoryId,
                        policyId);
            }
            else {
                advanced = jdbc.update(
                        """
                        UPDATE flow_runtime_local_check_policy_current
                        SET policy_revision_id = ?
                        WHERE repository_id = ? AND policy_revision_id = ?
                        """,
                        policyId,
                        repositoryId,
                        current.policyRevisionId());
            }
            if (advanced != 1) {
                throw new IllegalStateException(
                        "local-check policy pointer changed during append");
            }
            return currentPolicy(repositoryId).orElseThrow();
        });
    }

    public Optional<LocalCheckPolicyRevision> currentPolicy(
            String repositoryId)
    {
        requireText(repositoryId, "repositoryId");
        return jdbc.query(
                """
                SELECT p.*
                FROM flow_runtime_local_check_policy_current c
                JOIN flow_runtime_local_check_policy_revision p
                  ON p.policy_revision_id = c.policy_revision_id
                WHERE c.repository_id = ?
                """,
                (result, row) -> readPolicy(result),
                repositoryId).stream().findFirst();
    }

    public List<LocalCheckProfile> profiles(String policyRevisionId)
    {
        requireText(policyRevisionId, "policyRevisionId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_local_check_profile
                WHERE policy_revision_id = ? ORDER BY name
                """,
                (result, row) -> readProfile(result),
                policyRevisionId);
    }

    /** Freezes one policy/profile selection before authority is renewed. */
    synchronized PreparedLocalCheckBatch prepareBatch(
            String runId, String profileName)
    {
        return prepareBatch(runId, profileName, null);
    }

    /** Freezes one agent-selected invocation against one policy profile. */
    synchronized PreparedLocalCheckBatch prepareBatch(
            String runId,
            List<String> command,
            String workingDirectory)
    {
        return prepareBatch(
                runId, null,
                new ValidationRequest(command, workingDirectory));
    }

    private PreparedLocalCheckBatch prepareBatch(
            String runId,
            String profileName,
            ValidationRequest validationRequest)
    {
        AgentRun run = requireTaskRun(runId);
        Task task = runtime.task(runForTask(run).taskId()).orElseThrow();
        LocalCheckPolicyRevision policy = currentPolicy(task.repositoryId())
                .orElseThrow(() -> new IllegalStateException(POLICY_MISSING));
        List<LocalCheckProfile> selected = selectProfiles(
                policy, run.intendedGateKind(), profileName);
        if (validationRequest != null && selected.size() != 1) {
            throw new IllegalStateException(
                    "agent validation requires exactly one local-check profile");
        }
        if (validationRequest != null
                && !validationRequest.command().get(0).equals(
                        selected.get(0).command().get(0))) {
            throw new IllegalArgumentException(
                    "validation executable is not allowed by the local-check policy");
        }
        Duration duration = INSPECTION_BOUND.multipliedBy(
                        selected.size() * 2L + 3L)
                .plus(PROCESS_GRACE.multipliedBy(selected.size() * 4L))
                .plusSeconds(10);
        for (LocalCheckProfile profile : selected) {
            duration = duration.plus(profile.timeout());
        }
        return new PreparedLocalCheckBatch(
                run, task, policy, selected, validationRequest, duration);
    }

    /**
     * Runs required or one focused profile inside the already claimed writer
     * turn. Command execution is at-least-once; every completed call appends a
     * new immutable attempt.
     */
    List<LocalCheckRun> runAndRecord(
            PreparedLocalCheckBatch batch,
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            Runnable onBoundaryUnproven)
    {
        requireNonNull(batch, "batch is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireNonNull(onBoundaryUnproven,
                "onBoundaryUnproven is null");
        AgentRun run = requireTaskRun(batch.run.runId());
        Task task = runtime.task(runForTask(run).taskId()).orElseThrow();
        if (!run.equals(batch.run) || !task.equals(batch.task)) {
            throw new IllegalStateException(
                    "local-check batch owner changed before execution");
        }
        ChangeSetRevision current = runtime.currentChangeSet(task.taskId())
                .orElse(null);
        ChangeSetRevision adopted;
        if (current == null) {
            adopted = runtime.adoptChangeSet(
                    claim, fence, programOwnedRepositoryRoot, null);
        }
        else {
            Inspection observed = worktreeInspector.inspect(
                    programOwnedRepositoryRoot,
                    Path.of(task.worktreePath()),
                    task.branchName(),
                    current.baseSha(),
                    current.headSha());
            if (inspectionMatches(observed, current)) {
                adopted = current;
            }
            else {
                adopted = runtime.adoptChangeSet(
                        claim,
                        fence,
                        programOwnedRepositoryRoot,
                        current.changeSetRevisionId());
                if (!inspectionMatches(observed, adopted)) {
                    throw new IllegalStateException(
                            "adopted local-check subject moved after inspection");
                }
            }
        }
        assertPreparedPolicyCurrent(batch);
        List<LocalCheckRun> results = new ArrayList<>();
        for (LocalCheckProfile profile : batch.profiles) {
            assertPreparedPolicyCurrent(batch);
            results.add(runOne(
                    run,
                    claim,
                    fence,
                    task,
                    adopted,
                    programOwnedRepositoryRoot,
                    profile,
                    batch.validationRequest,
                    onBoundaryUnproven));
        }
        return List.copyOf(results);
    }

    /** Exact-current evidence; failed/genuine unavailable attempts remain refs. */
    public synchronized LocalCheckEvidence requiredEvidence(
            String taskId,
            String changeSetRevisionId,
            GateIntent gateKind)
    {
        return inTransaction(() -> requiredEvidenceInTransaction(
                taskId, changeSetRevisionId, gateKind));
    }

    public ReviewerEvidence reviewerEvidence(
            String taskId,
            String changeSetRevisionId,
            GateIntent gateKind)
    {
        LocalCheckEvidence evidence = requiredEvidence(
                taskId, changeSetRevisionId, gateKind);
        if (!evidence.blockerCodes().isEmpty()) {
            throw new IllegalStateException(String.join(
                    ",", evidence.blockerCodes()));
        }
        return new ReviewerEvidence(this, evidence);
    }

    private void assertCurrentForReservation(LocalCheckEvidence expected)
    {
        int locked = jdbc.update(
                """
                UPDATE flow_runtime_local_check_policy_current
                SET policy_revision_id = policy_revision_id
                WHERE policy_revision_id = ?
                """,
                expected.policyRevisionId());
        LocalCheckEvidence current = requiredEvidenceInTransaction(
                expected.taskId(),
                expected.changeSetRevisionId(),
                expected.gateKind());
        if (locked != 1 || !current.equals(expected)
                || !current.blockerCodes().isEmpty()) {
            throw new FlowRuntime.StaleOwnerRevisionException(
                    "review local-check evidence is no longer complete/latest");
        }
    }

    private LocalCheckEvidence requiredEvidenceInTransaction(
            String taskId,
            String changeSetRevisionId,
            GateIntent gateKind)
    {
        requireText(taskId, "taskId");
        requireText(changeSetRevisionId, "changeSetRevisionId");
        requireNonNull(gateKind, "gateKind is null");
        Task task = runtime.task(taskId).orElseThrow(() ->
                new IllegalArgumentException("unknown Task"));
        List<String> blockers = new ArrayList<>();
        ChangeSetRevision changeSet = runtime.currentChangeSet(taskId)
                .orElse(null);
        if (changeSet == null
                || !changeSet.changeSetRevisionId().equals(
                        changeSetRevisionId)
                || !changeSet.headSha().equals(task.currentHeadSha())) {
            blockers.add(EVIDENCE_STALE);
        }
        LocalCheckPolicyRevision policy = currentPolicy(task.repositoryId())
                .orElse(null);
        if (policy == null) {
            blockers.add(POLICY_MISSING);
            return new LocalCheckEvidence(
                    taskId,
                    changeSetRevisionId,
                    null,
                    gateKind,
                    List.of(),
                    blockers);
        }
        List<LocalCheckRun> runs = new ArrayList<>();
        for (LocalCheckProfile profile : profiles(policy.policyRevisionId())) {
            if (!profile.requiredForGateKinds().contains(gateKind)) {
                continue;
            }
            Optional<LocalCheckRun> latest = latestRun(
                    taskId,
                    changeSetRevisionId,
                    policy.policyRevisionId(),
                    profile.profileId());
            if (latest.isEmpty()) {
                blockers.add(EVIDENCE_MISSING + ":" + profile.name());
                continue;
            }
            LocalCheckRun check = latest.get();
            runs.add(check);
            if (!check.observedStartHead().equals(task.currentHeadSha())
                    || !Objects.equals(
                            check.observedEndHead(), task.currentHeadSha())
                    || !check.trackedTreeCleanBefore()
                    || !check.trackedTreeCleanAfter()) {
                blockers.add(EVIDENCE_STALE + ":" + profile.name());
            }
            if (PROCESS_BOUNDARY_UNPROVEN.equals(
                    check.unavailableReasonCode())) {
                blockers.add(PROCESS_BOUNDARY_UNPROVEN + ":" + profile.name());
            }
        }
        return new LocalCheckEvidence(
                taskId,
                changeSetRevisionId,
                policy.policyRevisionId(),
                gateKind,
                runs,
                blockers.stream().distinct().toList());
    }

    public Optional<LocalCheckRun> run(String checkRunId)
    {
        requireText(checkRunId, "checkRunId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_local_check_run
                WHERE check_run_id = ?
                """,
                (result, row) -> readRun(result),
                checkRunId).stream().findFirst();
    }

    private LocalCheckRun runOne(
            AgentRun run,
            Claim claim,
            WriterFence fence,
            Task task,
            ChangeSetRevision changeSet,
            Path programOwnedRepositoryRoot,
            LocalCheckProfile profile,
            ValidationRequest validationRequest,
            Runnable onBoundaryUnproven)
    {
        runtime.assertWriterFence(claim, fence);
        Inspection before = worktreeInspector.inspect(
                programOwnedRepositoryRoot,
                Path.of(task.worktreePath()),
                task.branchName(),
                changeSet.baseSha(),
                changeSet.headSha());
        if (!before.headSha().equals(changeSet.headSha())
                || !before.headTreeDigest().equals(
                        changeSet.headTreeDigest())
                || !before.baseToHeadDiffDigest().equals(
                        changeSet.diffDigest())) {
            throw new IllegalStateException(
                    "run_checks requires the exact clean adopted head");
        }
        Path worktree = realDirectory(Path.of(task.worktreePath()), "worktree");
        List<String> actualCommand = validationRequest == null
                ? profile.command() : validationRequest.command();
        String actualWorkingDirectory = validationRequest == null
                ? profile.workingDirectory()
                : validationRequest.workingDirectory();
        Path workingDirectory = resolveWorkingDirectory(
                worktree, actualWorkingDirectory);
        Instant startedAt = clock.instant();
        CommandOutcome command = execute(
                profile, actualCommand, worktree, workingDirectory);
        if (PROCESS_BOUNDARY_UNPROVEN.equals(
                command.unavailableReasonCode())) {
            onBoundaryUnproven.run();
        }
        Inspection after = null;
        try {
            after = worktreeInspector.inspect(
                    programOwnedRepositoryRoot,
                    worktree,
                    task.branchName(),
                    changeSet.baseSha(),
                    changeSet.headSha());
        }
        catch (RuntimeException inspectionFailure) {
            if (!PROCESS_BOUNDARY_UNPROVEN.equals(
                    command.unavailableReasonCode())) {
                command = command.asUnavailable(
                        "END_INSPECTION_UNAVAILABLE",
                        inspectionFailure.getMessage());
            }
        }
        boolean cleanAfter = after != null;
        LocalCheckConclusion conclusion = command.conclusion();
        if (conclusion == LocalCheckConclusion.PASSED
                && (!cleanAfter
                    || !after.headSha().equals(changeSet.headSha()))) {
            conclusion = LocalCheckConclusion.FAILED;
        }
        CommandOutcome finalCommand = command.withConclusion(conclusion);
        if (finalCommand.interrupted()) {
            Thread.currentThread().interrupt();
        }
        runtime.assertWriterFence(claim, fence);
        boolean boundaryUnproven = PROCESS_BOUNDARY_UNPROVEN.equals(
                finalCommand.unavailableReasonCode());
        LocalCheckRun stored;
        try {
            stored = appendRun(
                    run,
                    claim,
                    fence,
                    task,
                    changeSet,
                    profile,
                    actualCommand,
                    actualWorkingDirectory,
                    before,
                    after,
                    cleanAfter,
                    startedAt,
                    finalCommand);
        }
        catch (RuntimeException failure) {
            if (boundaryUnproven) {
                throw new ProcessBoundaryUnprovenException(failure);
            }
            throw failure;
        }
        if (boundaryUnproven) {
            throw new ProcessBoundaryUnprovenException();
        }
        return stored;
    }

    private CommandOutcome execute(
            LocalCheckProfile profile,
            List<String> requestedCommand,
            Path worktree,
            Path workingDirectory)
    {
        Map<String, String> environment = new LinkedHashMap<>();
        boolean sensitiveEnvironment = false;
        environment.put("LANG", "C");
        environment.put("LC_ALL", "C");
        for (String name : profile.environmentAllowlist()) {
            String value = System.getenv(name);
            if (value == null) {
                return CommandOutcome.unavailable(
                        "ENVIRONMENT_UNAVAILABLE",
                        "required environment variable is unavailable: "
                                + name);
            }
            environment.put(name, value);
            if (!value.isEmpty()) {
                sensitiveEnvironment = true;
            }
        }
        Path executable;
        try {
            executable = resolveExecutable(
                    requestedCommand.get(0), worktree, workingDirectory);
        }
        catch (RuntimeException unavailable) {
            return CommandOutcome.unavailable(
                    "EXECUTABLE_UNAVAILABLE", unavailable.getMessage());
        }
        List<String> command = new ArrayList<>(requestedCommand);
        command.set(0, executable.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        }
        catch (IOException unavailable) {
            return CommandOutcome.unavailable(
                    "EXECUTABLE_UNAVAILABLE", unavailable.getMessage());
        }
        OutputDrain drain = new OutputDrain(process.getInputStream());
        Thread drainThread = Thread.ofVirtual()
                .name("local-check-output")
                .start(drain);
        boolean timedOut = false;
        boolean interrupted = Thread.interrupted();
        boolean directExited;
        if (interrupted) {
            directExited = false;
        }
        else {
            try {
                directExited = process.waitFor(
                        profile.timeout().toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException ignored) {
                directExited = false;
                interrupted = true;
            }
        }
        if (!directExited) {
            timedOut = !interrupted;
            process.destroy();
            directExited = waitFor(process, PROCESS_GRACE);
            if (!directExited) {
                process.destroyForcibly();
                directExited = waitFor(process, PROCESS_GRACE);
            }
        }
        boolean joinedAtEof = join(drainThread, PROCESS_GRACE);
        boolean drainJoined = joinedAtEof;
        if (!joinedAtEof) {
            try {
                process.getInputStream().close();
            }
            catch (IOException ignored) {
                // The missing EOF is already retained as unavailable evidence.
            }
            drainJoined = join(drainThread, PROCESS_GRACE);
        }
        String output = drainJoined
                ? sensitiveEnvironment
                        ? "local check output omitted because allowlisted "
                                + "environment values were present\n"
                        : drain.truncated()
                        ? "[earlier output dropped; the tail follows]\n"
                                + drain.output()
                        : drain.output()
                : "";
        if (interrupted) {
            output = appendDiagnostic(output, "local check was interrupted");
        }
        if (timedOut || interrupted || !directExited || !joinedAtEof
                || !drainJoined
                || !drain.sawEof()) {
            BoundedOutput bounded = boundOutput(
                    appendDiagnostic(
                            output,
                            timedOut
                                    ? "local check timed out"
                                    : "local check process boundary was not proven"));
            return new CommandOutcome(
                    LocalCheckConclusion.UNAVAILABLE,
                    directExited ? process.exitValue() : null,
                    PROCESS_BOUNDARY_UNPROVEN,
                    bounded.output(),
                    sensitiveEnvironment || drain.truncated()
                            || bounded.truncated(),
                    interrupted);
        }
        int exitCode = process.exitValue();
        BoundedOutput bounded = boundOutput(output);
        return new CommandOutcome(
                exitCode == 0
                        ? LocalCheckConclusion.PASSED
                        : LocalCheckConclusion.FAILED,
                exitCode,
                null,
                bounded.output(),
                sensitiveEnvironment || drain.truncated()
                        || bounded.truncated(),
                false);
    }

    private LocalCheckRun appendRun(
            AgentRun run,
            Claim claim,
            WriterFence fence,
            Task task,
            ChangeSetRevision changeSet,
            LocalCheckProfile profile,
            List<String> actualCommand,
            String actualWorkingDirectory,
            Inspection before,
            Inspection after,
            boolean cleanAfter,
            Instant startedAt,
            CommandOutcome command)
    {
        return inTransaction(() -> {
            Integer authority = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_runtime_task t
                    JOIN flow_runtime_change_set_revision c
                      ON c.change_set_revision_id =
                         t.current_change_set_revision_id
                    JOIN flow_runtime_operation o
                      ON o.operation_id = t.selected_writer_operation_id
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_runtime_writer_lease l
                      ON l.operation_id = o.operation_id
                    JOIN flow_runtime_agent_run r
                      ON r.operation_id = o.operation_id
                    JOIN flow_runtime_local_check_policy_current cp
                      ON cp.repository_id = t.repository_id
                    JOIN flow_runtime_local_check_profile p
                      ON p.policy_revision_id = cp.policy_revision_id
                    WHERE t.task_id = ? AND t.status = 'ACTIVE'
                      AND t.waiting_mutation_state_ref IS NULL
                      AND c.change_set_revision_id = ? AND c.head_sha = ?
                      AND o.operation_id = ? AND o.state = 'CLAIMED'
                      AND d.claim_generation = ? AND d.claim_token = ?
                      AND d.claim_owner = ? AND d.delivery_state = 'CLAIMED'
                      AND d.claim_expires_at > ?
                      AND l.task_id = ? AND l.task_epoch = ?
                      AND l.holder_kind = 'TASK_AGENT'
                      AND l.fencing_token = ?
                      AND l.claim_generation = ?
                      AND l.claim_token_digest = ? AND l.expires_at = ?
                      AND l.expires_at > ?
                      AND r.run_id = ? AND r.role = 'TASK_AGENT'
                      AND r.state = 'RUNNING'
                      AND cp.policy_revision_id = ? AND p.profile_id = ?
                    """,
                    Integer.class,
                    task.taskId(),
                    changeSet.changeSetRevisionId(),
                    changeSet.headSha(),
                    claim.operationId(),
                    claim.generation(),
                    claim.claimToken(),
                    claim.workerId(),
                    clock.instant().toEpochMilli(),
                    fence.taskId(),
                    fence.taskEpoch(),
                    fence.fencingToken(),
                    fence.claimGeneration(),
                    fence.claimTokenDigest(),
                    fence.expiresAt().toEpochMilli(),
                    clock.instant().toEpochMilli(),
                    run.runId(),
                    profile.policyRevisionId(),
                    profile.profileId());
            if (requireNonNull(authority, "authority count is null") != 1) {
                throw new FlowRuntime.StaleWriterFenceException(
                        "local-check authority changed before finalization");
            }
            long sequence = jdbc.queryForObject(
                    """
                    SELECT COALESCE(MAX(attempt_sequence), 0) + 1
                    FROM flow_runtime_local_check_run
                    WHERE task_id = ? AND change_set_revision_id = ?
                      AND policy_revision_id = ? AND profile_id = ?
                    """,
                    Long.class,
                    task.taskId(),
                    changeSet.changeSetRevisionId(),
                    profile.policyRevisionId(),
                    profile.profileId());
            String runId = stableId(
                    "local-check-run",
                    task.taskId(),
                    changeSet.changeSetRevisionId(),
                    profile.policyRevisionId(),
                    profile.profileId(),
                    Long.toString(sequence));
            String outputRef = "local-check-output:" + runId;
            Instant completedAt = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_local_check_run (
                        check_run_id, task_id, change_set_revision_id,
                        policy_revision_id, profile_id, operation_id,
                        agent_run_id, command_json, working_directory,
                        attempt_sequence, observed_start_head,
                        observed_end_head, started_at,
                        completed_at, conclusion, exit_code,
                        unavailable_reason_code, output_ref, output_text,
                        output_truncated, tracked_tree_clean_before,
                        tracked_tree_clean_after
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              ?, ?, ?, ?, ?, ?, ?)
                    """,
                    runId,
                    task.taskId(),
                    changeSet.changeSetRevisionId(),
                    profile.policyRevisionId(),
                    profile.profileId(),
                    claim.operationId(),
                    run.runId(),
                    writeJson(actualCommand),
                    actualWorkingDirectory,
                    sequence,
                    before.headSha(),
                    after == null ? null : after.headSha(),
                    startedAt.toEpochMilli(),
                    completedAt.toEpochMilli(),
                    command.conclusion().name(),
                    command.exitCode(),
                    command.unavailableReasonCode(),
                    outputRef,
                    command.output(),
                    command.outputTruncated() ? 1 : 0,
                    1,
                    cleanAfter ? 1 : 0);
            return requireRun(runId);
        });
    }

    private List<LocalCheckProfile> selectProfiles(
            LocalCheckPolicyRevision policy,
            GateIntent gateKind,
            String profileName)
    {
        List<LocalCheckProfile> available = profiles(
                policy.policyRevisionId());
        if (profileName != null) {
            requireText(profileName, "profileName");
            return List.of(available.stream()
                    .filter(profile -> profile.name().equals(profileName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown local-check profile")));
        }
        return available.stream()
                .filter(profile -> profile.requiredForGateKinds()
                        .contains(gateKind))
                .toList();
    }

    private void assertPreparedPolicyCurrent(PreparedLocalCheckBatch batch)
    {
        LocalCheckPolicyRevision current = currentPolicy(
                batch.task.repositoryId()).orElse(null);
        if (!Objects.equals(current, batch.policy)) {
            throw new FlowRuntime.StaleWriterFenceException(
                    "local-check policy changed before execution");
        }
    }

    private void insertProfile(
            String policyId, ProfileDefinition definition)
    {
        String profileId = stableId(
                "local-check-profile",
                policyId,
                definition.name());
        jdbc.update(
                """
                INSERT INTO flow_runtime_local_check_profile (
                    profile_id, policy_revision_id, name,
                    command_json, working_directory,
                    environment_allowlist_json, required_gate_kinds_json,
                    timeout_seconds
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                profileId,
                policyId,
                definition.name(),
                writeJson(definition.command()),
                definition.workingDirectory(),
                writeJson(definition.environmentAllowlist()),
                writeJson(definition.requiredForGateKinds().stream()
                        .map(Enum::name).toList()),
                definition.timeout().toSeconds());
    }

    private LocalCheckProfile readProfile(ResultSet result)
            throws SQLException
    {
        String profileId = result.getString("profile_id");
        return new LocalCheckProfile(
                profileId,
                result.getString("policy_revision_id"),
                result.getString("name"),
                readStringList(result.getString("command_json")),
                result.getString("working_directory"),
                readStringList(result.getString(
                        "environment_allowlist_json")),
                Duration.ofSeconds(result.getLong("timeout_seconds")),
                readStringList(result.getString(
                        "required_gate_kinds_json")).stream()
                        .map(GateIntent::valueOf).toList());
    }

    private Optional<LocalCheckPolicyRevision> policyBySource(
            String repositoryId, String sourceRevision)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_local_check_policy_revision
                WHERE repository_id = ? AND source_revision = ?
                """,
                (result, row) -> readPolicy(result),
                repositoryId,
                sourceRevision).stream().findFirst();
    }

    private Optional<LocalCheckRun> latestRun(
            String taskId,
            String changeSetRevisionId,
            String policyRevisionId,
            String profileId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_local_check_run
                WHERE task_id = ? AND change_set_revision_id = ?
                  AND policy_revision_id = ? AND profile_id = ?
                ORDER BY attempt_sequence DESC, check_run_id DESC LIMIT 1
                """,
                (result, row) -> readRun(result),
                taskId,
                changeSetRevisionId,
                policyRevisionId,
                profileId).stream().findFirst();
    }

    private LocalCheckRun requireRun(String checkRunId)
    {
        return run(checkRunId).orElseThrow();
    }

    private AgentRun requireTaskRun(String runId)
    {
        AgentRun run = runtime.run(runId).orElseThrow(() ->
                new IllegalArgumentException("unknown AgentRun"));
        if (run.role() != AgentRole.TASK_AGENT
                || run.state() != RunState.RUNNING
                || run.intendedGateKind() == null) {
            throw new IllegalStateException(
                    "run_checks requires the active Task Agent run");
        }
        return run;
    }

    private Operation runForTask(AgentRun run)
    {
        return runtime.operation(run.operationId()).orElseThrow();
    }

    private static LocalCheckPolicyRevision readPolicy(ResultSet result)
            throws SQLException
    {
        return new LocalCheckPolicyRevision(
                result.getString("policy_revision_id"),
                result.getString("repository_id"),
                result.getLong("sequence"),
                result.getString("source_revision"),
                result.getString("source_digest"),
                Instant.ofEpochMilli(result.getLong("recorded_at")));
    }

    private LocalCheckRun readRun(ResultSet result)
            throws SQLException
    {
        int exitCode = result.getInt("exit_code");
        boolean exitCodeWasNull = result.wasNull();
        return new LocalCheckRun(
                result.getString("check_run_id"),
                result.getString("task_id"),
                result.getString("change_set_revision_id"),
                result.getString("policy_revision_id"),
                result.getString("profile_id"),
                result.getString("operation_id"),
                result.getString("agent_run_id"),
                readStringList(result.getString("command_json")),
                result.getString("working_directory"),
                result.getLong("attempt_sequence"),
                result.getString("observed_start_head"),
                result.getString("observed_end_head"),
                Instant.ofEpochMilli(result.getLong("started_at")),
                Instant.ofEpochMilli(result.getLong("completed_at")),
                LocalCheckConclusion.valueOf(
                        result.getString("conclusion")),
                exitCodeWasNull ? null : exitCode,
                result.getString("unavailable_reason_code"),
                result.getString("output_ref"),
                result.getString("output_text"),
                result.getInt("output_truncated") != 0,
                result.getInt("tracked_tree_clean_before") != 0,
                result.getInt("tracked_tree_clean_after") != 0);
    }

    private static Path resolveWorkingDirectory(
            Path worktree, String relative)
    {
        Path resolved = worktree.resolve(relative).normalize();
        Path real = realDirectory(resolved, "workingDirectory");
        if (!real.startsWith(worktree)) {
            throw new IllegalArgumentException(
                    "workingDirectory escapes the Task worktree");
        }
        return real;
    }

    private static boolean inspectionMatches(
            Inspection inspection, ChangeSetRevision changeSet)
    {
        return inspection.headSha().equals(changeSet.headSha())
                && inspection.headTreeDigest().equals(
                        changeSet.headTreeDigest())
                && inspection.baseToHeadDiffDigest().equals(
                        changeSet.diffDigest());
    }

    private static Path resolveExecutable(
            String value, Path worktree, Path workingDirectory)
    {
        Path candidate = Path.of(value);
        if (!candidate.isAbsolute()) {
            if (!value.contains("/")) {
                throw new IllegalArgumentException(
                        "executable must be absolute or worktree-relative");
            }
            candidate = workingDirectory.resolve(candidate).normalize();
            if (!candidate.startsWith(worktree)) {
                throw new IllegalArgumentException(
                        "relative executable escapes the Task worktree");
            }
        }
        try {
            Path real = candidate.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
                throw new IllegalArgumentException(
                        "local-check executable is unavailable");
            }
            return real;
        }
        catch (IOException e) {
            throw new IllegalArgumentException(
                    "local-check executable is unavailable", e);
        }
    }

    private static Path realDirectory(Path value, String name)
    {
        try {
            Path real = value.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException(name + " is not a directory");
            }
            return real;
        }
        catch (IOException e) {
            throw new IllegalArgumentException(name + " is unavailable", e);
        }
    }

    private static void requireSafeRelativePath(
            String value, boolean allowDot)
    {
        requireText(value, "relativePath");
        if (allowDot && value.equals(".")) {
            return;
        }
        Path path = Path.of(value);
        if (path.isAbsolute()
                || !path.normalize().equals(path)
                || (!allowDot && path.toString().equals("."))
                || (path.getNameCount() > 0
                    && path.getName(0).toString().equals(".."))) {
            throw new IllegalArgumentException("unsafe relative path");
        }
    }

    private static List<String> boundedUniqueList(
            List<String> values,
            String name,
            boolean unique,
            int maxValues)
    {
        requireNonNull(values, name + " is null");
        if (values.size() > maxValues) {
            throw new IllegalArgumentException(name + " is too large");
        }
        List<String> result = List.copyOf(values);
        if (result.stream().anyMatch(value -> value == null
                || value.isEmpty()
                || value.length() > MAX_TEXT
                || value.indexOf('\0') >= 0)
                || (unique && new LinkedHashSet<>(result).size()
                        != result.size())) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }

    private static String policyDefinitionDigest(
            List<ProfileDefinition> profiles)
    {
        List<String> values = new ArrayList<>();
        values.add("local-check-policy-definition:v2");
        values.add("profiles");
        values.add(Integer.toString(profiles.size()));
        profiles.stream().sorted(Comparator.comparing(ProfileDefinition::name))
                .forEach(profile -> {
                    values.add("profile");
                    values.add(profile.name());
                    values.add("working-directory");
                    values.add(profile.workingDirectory());
                    values.add("timeout");
                    values.add(profile.timeout().toString());
                    values.add("command");
                    values.add(Integer.toString(profile.command().size()));
                    values.addAll(profile.command());
                    values.add("environment-allowlist");
                    values.add(Integer.toString(
                            profile.environmentAllowlist().size()));
                    values.addAll(profile.environmentAllowlist());
                    values.add("required-gates");
                    values.add(Integer.toString(
                            profile.requiredForGateKinds().size()));
                    values.addAll(profile.requiredForGateKinds().stream()
                            .map(Enum::name).sorted().toList());
                });
        return stableId(values.toArray(String[]::new));
    }

    private static List<LocalCheckProfile> storedProfiles(
            String policyId, List<ProfileDefinition> definitions)
    {
        return definitions.stream()
                .sorted(Comparator.comparing(ProfileDefinition::name))
                .map(definition -> new LocalCheckProfile(
                        stableId(
                                "local-check-profile",
                                policyId,
                                definition.name()),
                        policyId,
                        definition.name(),
                        definition.command(),
                        definition.workingDirectory(),
                        definition.environmentAllowlist().stream()
                                .sorted().toList(),
                        definition.timeout(),
                        definition.requiredForGateKinds().stream()
                                .sorted().toList()))
                .toList();
    }

    private String writeJson(List<String> values)
    {
        try {
            return objectMapper.writeValueAsString(values);
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException(
                    "bounded local-check list cannot be encoded", impossible);
        }
    }

    private List<String> readStringList(String value)
    {
        try {
            List<String> result = objectMapper.readValue(
                    value, new TypeReference<List<String>>() {});
            return boundedUniqueList(result, "stored local-check list", false,
                    MAX_ARGUMENTS);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException(
                    "invalid stored local-check list", invalid);
        }
    }

    private static String stableId(String... values)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean waitFor(Process process, Duration timeout)
    {
        try {
            return process.waitFor(
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean join(Thread thread, Duration timeout)
    {
        try {
            thread.join(timeout);
            return !thread.isAlive();
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String appendDiagnostic(String output, String diagnostic)
    {
        String separator = output.isEmpty() || output.endsWith("\n")
                ? "" : "\n";
        return output + separator + diagnostic + "\n";
    }

    private static BoundedOutput boundOutput(String output)
    {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) {
            return new BoundedOutput(output, false);
        }
        // The tail, for the same reason the drain keeps it: that is where a
        // build says what failed.
        return new BoundedOutput(new String(
                bytes,
                bytes.length - (MAX_OUTPUT_BYTES - 3),
                MAX_OUTPUT_BYTES - 3,
                StandardCharsets.UTF_8), true);
    }

    private <T> T inTransaction(Supplier<T> action)
    {
        return requireNonNull(
                transactions.execute(status -> action.get()),
                "transaction returned null");
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new IllegalArgumentException(name + " is blank or too large");
        }
    }

    private record CommandOutcome(
            LocalCheckConclusion conclusion,
            Integer exitCode,
            String unavailableReasonCode,
            String output,
            boolean outputTruncated,
            boolean interrupted)
    {
        private static CommandOutcome unavailable(
                String reason, String output)
        {
            return new CommandOutcome(
                    LocalCheckConclusion.UNAVAILABLE,
                    null,
                    reason,
                    output == null ? "" : output,
                    false,
                    false);
        }

        private CommandOutcome asUnavailable(
                String reason, String diagnostic)
        {
            return new CommandOutcome(
                    LocalCheckConclusion.UNAVAILABLE,
                    exitCode,
                    reason,
                    appendDiagnostic(output, diagnostic),
                    outputTruncated,
                    interrupted);
        }

        private CommandOutcome withConclusion(
                LocalCheckConclusion replacement)
        {
            return new CommandOutcome(
                    replacement,
                    exitCode,
                    replacement == LocalCheckConclusion.UNAVAILABLE
                            ? unavailableReasonCode : null,
                    output,
                    outputTruncated,
                    interrupted);
        }
    }

    private record BoundedOutput(String output, boolean truncated) {}

    private static final class OutputDrain implements Runnable
    {
        private final InputStream input;
        private final ByteArrayOutputStream captured =
                new ByteArrayOutputStream();
        private volatile boolean truncated;
        private volatile boolean sawEof;

        private OutputDrain(InputStream input)
        {
            this.input = input;
        }

        @Override
        public void run()
        {
            byte[] buffer = new byte[8_192];
            try {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    append(buffer, read);
                }
                sawEof = true;
            }
            catch (IOException ignored) {
                // Missing EOF is detected by the runner's bounded join.
            }
        }

        private synchronized String output()
        {
            compact();
            return captured.toString(StandardCharsets.UTF_8);
        }

        /**
         * Keeps the tail rather than the head: a build log's failure summary
         * is at the end, and a head-kept excerpt of a long red build would
         * show the agent everything except why it failed.
         */
        private synchronized void append(byte[] value, int length)
        {
            captured.write(value, 0, length);
            if (captured.size() > 2 * MAX_OUTPUT_BYTES) {
                compact();
            }
        }

        private void compact()
        {
            if (captured.size() <= MAX_OUTPUT_BYTES) {
                return;
            }
            byte[] all = captured.toByteArray();
            captured.reset();
            captured.write(
                    all, all.length - MAX_OUTPUT_BYTES, MAX_OUTPUT_BYTES);
            truncated = true;
        }

        private boolean truncated()
        {
            return truncated;
        }

        private boolean sawEof()
        {
            return sawEof;
        }
    }
}
