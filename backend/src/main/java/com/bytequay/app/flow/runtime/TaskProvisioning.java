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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.Inspection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** One exact, local-only {@code PROVISION_TASK} owner. */
public final class TaskProvisioning
        implements NewFlowDispatcher.Handler
{
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int OUTPUT_LIMIT = 8 * 1024 * 1024;
    private static final String BRANCH_PREFIX = "bytequay/";

    /** Read-only product configuration copied only before Task acceptance. */
    public interface RepositoryCatalog
    {
        RepositoryConfig repository(String repositoryId);
    }

    public record RepositoryConfig(
            String repositoryId,
            String repositoryOwner,
            String repositoryName,
            Path repositoryRoot,
            Path gitCommonDir,
            String remoteName,
            String baseRef,
            Path worktreeRoot)
    {
        public RepositoryConfig
        {
            requireText(repositoryId, "repositoryId");
            requireGitHubRepositoryLocator(
                    repositoryOwner, repositoryName);
            repositoryRoot = canonicalDirectory(
                    repositoryRoot, "repositoryRoot");
            gitCommonDir = canonicalDirectory(gitCommonDir, "gitCommonDir");
            requireRefPart(remoteName, "remoteName");
            requireFullRef(baseRef, "baseRef");
            if (!baseRef.startsWith("refs/remotes/" + remoteName + "/")) {
                throw new IllegalArgumentException(
                        "baseRef is not owned by remoteName");
            }
            worktreeRoot = canonicalDirectory(worktreeRoot, "worktreeRoot");
            if (worktreeRoot.startsWith(repositoryRoot)
                    || repositoryRoot.startsWith(worktreeRoot)
                    || worktreeRoot.startsWith(gitCommonDir)
                    || gitCommonDir.startsWith(worktreeRoot)) {
                throw new IllegalArgumentException(
                        "worktreeRoot is not disjoint from repository/common");
            }
        }
    }

    interface GitProcess
    {
        ProcessResult run(Path repositoryRoot, List<String> arguments);
    }

    record ProcessResult(
            boolean complete, int exitCode, String stdout, boolean overflow)
    {
        ProcessResult(boolean complete, int exitCode, String stdout)
        {
            this(complete, exitCode, stdout, false);
        }

        ProcessResult
        {
            requireNonNull(stdout, "stdout is null");
        }
    }

    enum ObservedState
    {
        EXACT,
        ABSENT,
        PARTIAL,
        MISMATCHED,
        OUTPUT_LIMIT,
        UNKNOWN
    }

    record ResolvedSubject(
            String operationId,
            String taskId,
            String launchDigest,
            String baseSha,
            String mutationDigest,
            Instant boundAt)
    {
        ResolvedSubject
        {
            requireText(operationId, "operationId");
            requireText(taskId, "taskId");
            requireText(launchDigest, "launchDigest");
            requireObjectId(baseSha, "baseSha");
            requireText(mutationDigest, "mutationDigest");
            requireNonNull(boundAt, "boundAt is null");
        }
    }

    static final class FrozenLaunch
    {
        private final String taskId;
        private final String requestKey;
        private final String repositoryId;
        private final String repositoryOwner;
        private final String repositoryName;
        private final String goalText;
        private final String repositoryRoot;
        private final String gitCommonDir;
        private final String remoteName;
        private final String baseRef;
        private final String branchName;
        private final String worktreePath;
        private final String launchDigest;

        private FrozenLaunch(
                String taskId,
                String requestKey,
                String repositoryId,
                String repositoryOwner,
                String repositoryName,
                String goalText,
                String repositoryRoot,
                String gitCommonDir,
                String remoteName,
                String baseRef,
                String branchName,
                String worktreePath,
                String launchDigest)
        {
            this.taskId = taskId;
            this.requestKey = requestKey;
            this.repositoryId = repositoryId;
            this.repositoryOwner = repositoryOwner;
            this.repositoryName = repositoryName;
            this.goalText = goalText;
            this.repositoryRoot = repositoryRoot;
            this.gitCommonDir = gitCommonDir;
            this.remoteName = remoteName;
            this.baseRef = baseRef;
            this.branchName = branchName;
            this.worktreePath = worktreePath;
            this.launchDigest = launchDigest;
        }

        String taskId() { return taskId; }
        String requestKey() { return requestKey; }
        String repositoryId() { return repositoryId; }
        String repositoryOwner() { return repositoryOwner; }
        String repositoryName() { return repositoryName; }
        String goalText() { return goalText; }
        String repositoryRoot() { return repositoryRoot; }
        String gitCommonDir() { return gitCommonDir; }
        String remoteName() { return remoteName; }
        String baseRef() { return baseRef; }
        String branchName() { return branchName; }
        String worktreePath() { return worktreePath; }
        String launchDigest() { return launchDigest; }
    }

    static final class ProvisionedWorktree
    {
        private final String operationId;
        private final String taskId;
        private final String launchDigest;
        private final String baseSha;
        private final Inspection inspection;

        private ProvisionedWorktree(
                ResolvedSubject subject, Inspection inspection)
        {
            this.operationId = subject.operationId();
            this.taskId = subject.taskId();
            this.launchDigest = subject.launchDigest();
            this.baseSha = subject.baseSha();
            this.inspection = inspection;
        }

        String baseSha() { return baseSha; }
        String headSha() { return inspection.headSha(); }

        void assertMatches(Claim claim, Task task, Operation operation)
        {
            if (!operation.operationId().equals(operationId)
                    || !task.taskId().equals(taskId)
                    || !task.launchDigest().equals(launchDigest)
                    || !claim.operationId().equals(operationId)
                    || operation.kind() != OperationKind.PROVISION_TASK
                    || !inspection.headSha().equals(baseSha)
                    || inspection.differsFromBase()) {
                throw new IllegalStateException(
                        "provisioning proof does not match its exact owner graph");
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FlowRuntime runtime;
    private final RepositoryCatalog catalog;
    private final GitProcess git;
    private final FlowWorktreeInspector inspector;
    private final Clock clock;

    public TaskProvisioning(
            DataSource dataSource,
            FlowRuntime runtime,
            RepositoryCatalog catalog,
            Clock clock)
    {
        this(dataSource, runtime, catalog, new DirectGitProcess(),
                new FlowWorktreeInspector(), clock);
    }

    TaskProvisioning(
            DataSource dataSource,
            FlowRuntime runtime,
            RepositoryCatalog catalog,
            GitProcess git,
            FlowWorktreeInspector inspector,
            Clock clock)
    {
        this.jdbc = new JdbcTemplate(requireNonNull(
                dataSource, "dataSource is null"));
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.catalog = requireNonNull(catalog, "catalog is null");
        this.git = requireNonNull(git, "git is null");
        this.inspector = requireNonNull(inspector, "inspector is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Git-free acceptance; every launch field comes from program configuration. */
    public Task startTask(
            String requestKey, String repositoryId, String goalText)
    {
        requireText(requestKey, "requestKey");
        requireText(repositoryId, "repositoryId");
        requireText(goalText, "goalText");
        Optional<Task> replay = runtime.taskForRequestKey(requestKey);
        if (replay.isPresent()) {
            Task existing = replay.orElseThrow();
            assertStoredLaunch(existing);
            if (!existing.repositoryId().equals(repositoryId)
                    || !existing.goalText().equals(goalText)) {
                throw new IllegalStateException(
                        "requestKey already owns a different Task command");
            }
            return existing;
        }
        RepositoryConfig config = requireNonNull(
                catalog.repository(repositoryId),
                "repository catalog returned null");
        if (!config.repositoryId().equals(repositoryId)) {
            throw new IllegalStateException(
                    "repository catalog returned a different repository");
        }
        String taskId = stableId("task", requestKey);
        String taskKey = taskId.substring(taskId.indexOf(':') + 1);
        String branchName = BRANCH_PREFIX + taskKey;
        Path worktree = config.worktreeRoot().resolve(taskKey).normalize();
        if (!worktree.startsWith(config.worktreeRoot())
                || worktree.getParent() == null
                || !worktree.getParent().equals(config.worktreeRoot())) {
            throw new IllegalStateException("derived worktree escaped its root");
        }
        String launchDigest = stableId(
                "task-launch-v1",
                taskId,
                repositoryId,
                config.repositoryOwner(),
                config.repositoryName(),
                config.repositoryRoot().toString(),
                config.gitCommonDir().toString(),
                config.remoteName(),
                config.baseRef(),
                branchName,
                worktree.toString());
        return runtime.startTask(new FrozenLaunch(
                taskId,
                requestKey,
                repositoryId,
                config.repositoryOwner(),
                config.repositoryName(),
                goalText,
                config.repositoryRoot().toString(),
                config.gitCommonDir().toString(),
                config.remoteName(),
                config.baseRef(),
                branchName,
                worktree.toString(),
                launchDigest));
    }

    @Override
    public OperationKind kind()
    {
        return OperationKind.PROVISION_TASK;
    }

    @Override
    public void execute(Claim claim)
    {
        requireOutsideTransaction();
        requireProvisionClaim(claim);
        Operation operation = runtime.operation(claim.operationId()).orElseThrow();
        Task task = runtime.task(operation.taskId()).orElseThrow();
        assertStoredLaunch(task);
        if (operation.state() == OperationState.SUCCEEDED) {
            assertCompletedReplay(
                    claim.operationId(), task,
                    requireSubject(operation.operationId()));
            assertCompletedClaimReplay(claim);
            return;
        }
        assertClaimedGraph(claim, task);
        ResolvedSubject subject;
        try {
            subject = subject(operation.operationId())
                    .orElseGet(() -> resolveAndBind(claim, task));
        }
        catch (TerminalProvisioningDisposition ignored) {
            return;
        }
        assertSubject(task, operation, subject);
        if (!revalidateClaimed(claim, task, subject)) {
            return;
        }
        ObservedState before = observe(task, subject.baseSha());
        if (before == ObservedState.EXACT) {
            complete(claim, task, subject);
            return;
        }
        if (before == ObservedState.PARTIAL
                || before == ObservedState.MISMATCHED
                || before == ObservedState.OUTPUT_LIMIT) {
            attentionClaimed(claim, task, before);
            return;
        }
        if (before == ObservedState.UNKNOWN) {
            rearmClaimed(claim, "PROVISION_UNAVAILABLE");
            return;
        }
        ProcessResult mutation = git.run(
                Path.of(task.repositoryRoot()),
                mutationArguments(task, subject.baseSha()));
        ObservedState after = observe(task, subject.baseSha());
        switch (after) {
            case EXACT -> complete(claim, task, subject);
            case ABSENT, UNKNOWN -> {
                if (mutation.overflow()) {
                    attentionClaimed(
                            claim, task, "PROVISION_COMMAND_OUTPUT_LIMIT");
                }
                else if (mutation.complete()
                        && (mutation.exitCode() != 0
                            || after == ObservedState.ABSENT)) {
                    attentionClaimed(
                            claim, task, "PROVISION_COMMAND_FAILED");
                }
                else {
                    rearmClaimed(claim, "PROVISION_ABSENT");
                }
            }
            case PARTIAL, MISMATCHED, OUTPUT_LIMIT -> attentionClaimed(
                    claim, task, after);
        }
    }

    @Override
    public boolean recover(ExpiredClaim expired)
    {
        requireOutsideTransaction();
        if (expired.kind() != OperationKind.PROVISION_TASK) {
            throw new IllegalArgumentException(
                    "expired claim is not Task provisioning");
        }
        Operation operation = runtime.operation(expired.operationId())
                .orElseThrow();
        Task task = runtime.task(expired.taskId()).orElseThrow();
        assertStoredLaunch(task);
        if (operation.state() == OperationState.SUCCEEDED) {
            assertCompletedReplay(
                    expired.operationId(), task,
                    requireSubject(expired.operationId()));
            assertCompletedRecoveryReplay(expired);
            return false;
        }
        if (operation.state() == OperationState.READY
                && recoveryReplay(expired)) {
            return true;
        }
        if (operation.state() == OperationState.FAILED) {
            assertAttentionReplay(expired, task);
            return false;
        }
        assertExpiredClaim(expired);
        Optional<ResolvedSubject> subject = subject(expired.operationId());
        if (subject.isEmpty()) {
            rearmExpired(expired, "PROVISION_UNRESOLVED");
            return true;
        }
        ResolvedSubject resolved = subject.orElseThrow();
        assertSubject(task, operation, resolved);
        if (!revalidateExpired(expired, task, resolved)) {
            return true;
        }
        ObservedState observed = observe(task, resolved.baseSha());
        switch (observed) {
            case EXACT -> {
                rearmExpired(expired, "PROVISION_EXACT");
                return true;
            }
            case ABSENT, UNKNOWN -> {
                rearmExpired(expired, "PROVISION_ABSENT");
                return true;
            }
            case PARTIAL, MISMATCHED, OUTPUT_LIMIT -> {
                attentionExpired(expired, task, observed);
                return false;
            }
        }
        throw new IllegalStateException("unreachable provisioning observation");
    }

    private ResolvedSubject resolveAndBind(Claim claim, Task task)
    {
        String baseSha;
        try {
            baseSha = preflight(task);
        }
        catch (TransientProvisioningFailure unavailable) {
            rearmClaimed(claim, "PROVISION_UNAVAILABLE");
            throw new TerminalProvisioningDisposition();
        }
        catch (StableProvisioningFailure failure) {
            failBeforeSubject(claim, task, failure.code);
            throw new TerminalProvisioningDisposition();
        }
        String mutationDigest = stableId(
                "provision-mutation-v1",
                task.repositoryRoot(),
                task.gitCommonDir(),
                task.remoteName(),
                task.branchName(),
                branchRef(task),
                task.worktreePath(),
                baseSha);
        return requireNonNull(transactions.execute(ignored -> {
            assertClaimedGraph(claim, task);
            Optional<ResolvedSubject> replay = subject(claim.operationId());
            if (replay.isPresent()) {
                ResolvedSubject existing = replay.orElseThrow();
                if (!existing.baseSha().equals(baseSha)
                        || !existing.mutationDigest().equals(mutationDigest)) {
                    throw new IllegalStateException(
                            "provisioning subject changed during binding");
                }
                return existing;
            }
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_provision_subject (
                        operation_id, task_id, launch_digest, base_sha,
                        mutation_digest, bound_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    claim.operationId(),
                    task.taskId(),
                    task.launchDigest(),
                    baseSha,
                    mutationDigest,
                    clock.instant().toEpochMilli());
            return requireSubject(claim.operationId());
        }), "subject transaction returned null");
    }

    private String preflight(Task task)
    {
        validateRepository(task, true);
        Path root = Path.of(task.repositoryRoot());
        ProcessResult base = command(root,
                "rev-parse", "--verify", "--quiet", task.baseRef());
        requireComplete(base);
        if (base.exitCode() != 0) {
            throw new StableProvisioningFailure("BASE_REF_UNAVAILABLE");
        }
        String baseSha = base.stdout().strip();
        try {
            requireObjectId(baseSha, "resolved base");
        }
        catch (IllegalArgumentException malformed) {
            throw new StableProvisioningFailure("BASE_REF_INVALID");
        }
        validateBoundBase(task, baseSha);
        return baseSha;
    }

    private void validateRepository(Task task, boolean worktreeMustBeAbsent)
    {
        Path root = Path.of(task.repositoryRoot());
        Path common = Path.of(task.gitCommonDir());
        Path worktree = Path.of(task.worktreePath());
        Path worktreeRoot = worktree.getParent();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(common, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)
                || Files.isSymbolicLink(common)
                || !Files.isDirectory(worktreeRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(worktreeRoot)
                || !worktree.getParent().equals(worktreeRoot)
                || (worktreeMustBeAbsent
                    && Files.exists(worktree, LinkOption.NOFOLLOW_LINKS))
                || (Files.exists(worktree, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(
                            worktree, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(worktree)))) {
            throw new StableProvisioningFailure("INVALID_REPOSITORY_PATH");
        }
        rejectUnsafeState(task, common);
        requireScalar(root, "true",
                "rev-parse", "--is-inside-work-tree");
        requireScalar(root, task.repositoryRoot(),
                "rev-parse", "--show-toplevel");
        String actualCommon = scalar(root,
                "rev-parse", "--path-format=absolute", "--git-common-dir");
        if (!Path.of(actualCommon).normalize().equals(common)) {
            throw new StableProvisioningFailure("COMMON_DIR_CHANGED");
        }
        requireValidRef(root, branchRef(task));
        requireValidRef(root, task.baseRef());
        ProcessResult remote = command(
                root, "remote", "get-url", "--all", task.remoteName());
        List<String> remoteUrls = remote.stdout().lines()
                .filter(line -> !line.isBlank()).toList();
        requireComplete(remote);
        if (remote.exitCode() != 0
                || remoteUrls.size() != 1
                || !githubRepository(remoteUrls.getFirst()).equals(
                        task.repositoryOwner() + "/" + task.repositoryName())) {
            throw new StableProvisioningFailure("REMOTE_IDENTITY_CHANGED");
        }
    }

    private void validateBoundBase(Task task, String baseSha)
    {
        Path root = Path.of(task.repositoryRoot());
        ProcessResult object = command(root,
                "cat-file", "-e", baseSha + "^{commit}");
        requireComplete(object);
        if (object.exitCode() != 0) {
            throw new StableProvisioningFailure("BASE_OBJECT_UNAVAILABLE");
        }
        ProcessResult gitlinks = command(
                root, "ls-tree", "-r", "--format=%(objectmode)", baseSha);
        requireComplete(gitlinks);
        if (gitlinks.exitCode() != 0) {
            throw new StableProvisioningFailure("BASE_TREE_UNAVAILABLE");
        }
        if (gitlinks.stdout().lines().anyMatch(line -> line.equals("160000"))) {
            throw new StableProvisioningFailure("GITLINK_UNSUPPORTED");
        }
    }

    private void rejectUnsafeState(Task task, Path common)
    {
        if (List.of(
                    common.resolve("info/grafts"),
                    common.resolve("objects/info/alternates"),
                    common.resolve("shallow"),
                    common.resolve("refs/replace")).stream()
                .anyMatch(path -> Files.exists(
                        path, LinkOption.NOFOLLOW_LINKS))) {
            throw new StableProvisioningFailure("UNSAFE_GIT_STATE");
        }
        Path packDirectory = common.resolve("objects/pack");
        try {
            if (Files.exists(packDirectory, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(
                        packDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(packDirectory)) {
                    throw new StableProvisioningFailure("UNSAFE_GIT_STATE");
                }
                try (var packs = Files.newDirectoryStream(
                        packDirectory, "*.promisor")) {
                    if (packs.iterator().hasNext()) {
                        throw new StableProvisioningFailure("UNSAFE_GIT_STATE");
                    }
                }
            }
        }
        catch (IOException unavailable) {
            throw new TransientProvisioningFailure();
        }
        ProcessResult config = command(
                Path.of(task.repositoryRoot()),
                "config", "--includes", "--null", "--name-only", "--list");
        requireComplete(config);
        if (config.exitCode() != 0) {
            throw new StableProvisioningFailure("INVALID_GIT_CONFIG");
        }
        String nul = String.valueOf((char) 0);
        boolean unsafe = List.of(config.stdout().split(
                        Pattern.quote(nul), -1)).stream()
                .filter(key -> !key.isBlank())
                .map(key -> key.toLowerCase(Locale.ROOT))
                .anyMatch(TaskProvisioning::unsafeConfigKey);
        if (unsafe) {
            throw new StableProvisioningFailure("UNSAFE_GIT_CONFIG");
        }
    }

    private ObservedState observe(Task task, String baseSha)
    {
        Path root = Path.of(task.repositoryRoot());
        Path worktree = Path.of(task.worktreePath());
        ProcessResult branch = command(root,
                "rev-parse", "--verify", "--quiet", branchRef(task));
        if (branch.overflow()) {
            return ObservedState.OUTPUT_LIMIT;
        }
        if (!branch.complete()
                || (branch.exitCode() != 0 && branch.exitCode() != 1)) {
            return ObservedState.UNKNOWN;
        }
        boolean branchPresent = branch.exitCode() == 0;
        boolean branchExact = branchPresent
                && branch.stdout().strip().equals(baseSha);
        boolean pathPresent = Files.exists(worktree, LinkOption.NOFOLLOW_LINKS);
        ProcessResult list = command(root, "worktree", "list", "--porcelain");
        if (list.overflow()) {
            return ObservedState.OUTPUT_LIMIT;
        }
        if (!list.complete() || list.exitCode() != 0) {
            return ObservedState.UNKNOWN;
        }
        boolean registered = list.stdout().lines()
                .anyMatch(line -> line.equals("worktree " + task.worktreePath()));
        if (!branchPresent && !pathPresent && !registered) {
            return ObservedState.ABSENT;
        }
        if (!branchPresent || !pathPresent || !registered) {
            return ObservedState.PARTIAL;
        }
        if (!branchExact) {
            return ObservedState.MISMATCHED;
        }
        try {
            Inspection observed = inspect(task, baseSha);
            return observed.headSha().equals(baseSha)
                    && !observed.differsFromBase()
                    ? ObservedState.EXACT : ObservedState.MISMATCHED;
        }
        catch (FlowWorktreeInspector.InspectionFailure failure) {
            return switch (failure.code()) {
                case GIT_UNAVAILABLE, MOVED_DURING_INSPECTION, TIMEOUT,
                        COMMAND_FAILED, INTERRUPTED -> ObservedState.UNKNOWN;
                case NOT_WORKTREE, WRONG_REPOSITORY, DETACHED_HEAD,
                        WRONG_BRANCH, BRANCH_HEAD_MISMATCH, DIRTY,
                        GIT_OPERATION_IN_PROGRESS, BASE_NOT_FOUND,
                        PREDECESSOR_NOT_FOUND, BASE_NOT_ANCESTOR,
                        PREDECESSOR_NOT_ANCESTOR, CLEAN,
                        UNTRUSTED_REPOSITORY_STATE, OUTPUT_LIMIT ->
                        ObservedState.MISMATCHED;
                case INVALID_INPUT -> throw failure;
            };
        }
    }

    private void complete(
            Claim claim, Task task, ResolvedSubject subject)
    {
        Inspection inspection = inspect(task, subject.baseSha());
        ProvisionedWorktree proof = new ProvisionedWorktree(
                subject, inspection);
        transactions.executeWithoutResult(ignored -> {
            assertCompletionOwner(claim, task, subject);
            runtime.provisionTask(claim, proof);
        });
    }

    private Inspection inspect(Task task, String baseSha)
    {
        return inspector.inspect(
                Path.of(task.repositoryRoot()),
                Path.of(task.worktreePath()),
                task.branchName(),
                baseSha,
                baseSha);
    }

    private boolean revalidateClaimed(
            Claim claim, Task task, ResolvedSubject subject)
    {
        try {
            validateRepository(task, false);
            validateBoundBase(task, subject.baseSha());
            return true;
        }
        catch (TransientProvisioningFailure unavailable) {
            rearmClaimed(claim, "PROVISION_UNAVAILABLE");
            return false;
        }
        catch (StableProvisioningFailure invalid) {
            attentionClaimed(
                    claim, task, "PROVISION_INVALID:" + invalid.code);
            return false;
        }
    }

    private boolean revalidateExpired(
            ExpiredClaim expired, Task task, ResolvedSubject subject)
    {
        try {
            validateRepository(task, false);
            validateBoundBase(task, subject.baseSha());
            return true;
        }
        catch (TransientProvisioningFailure unavailable) {
            rearmExpired(expired, "PROVISION_UNAVAILABLE");
            return false;
        }
        catch (StableProvisioningFailure invalid) {
            attentionExpired(
                    expired, task, "PROVISION_INVALID:" + invalid.code);
            return false;
        }
    }

    private void rearmClaimed(Claim claim, String result)
    {
        transactions.executeWithoutResult(ignored -> {
            Operation owner = runtime.operation(claim.operationId())
                    .orElseThrow();
            assertClaimedGraph(
                    claim, runtime.task(owner.taskId()).orElseThrow());
            int operation = jdbc.update(
                    """
                    UPDATE flow_runtime_operation SET state = 'READY',
                        result_ref = ?
                    WHERE operation_id = ? AND state = 'CLAIMED'
                    """,
                    result,
                    claim.operationId());
            int ticket = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'AVAILABLE', claim_owner = NULL,
                        claim_expires_at = NULL, claim_token = NULL,
                        not_before = ?
                    WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                      AND claim_generation = ? AND claim_token = ?
                    """,
                    clock.instant().plusSeconds(1).toEpochMilli(),
                    claim.operationId(),
                    claim.generation(),
                    claim.claimToken());
            if (operation != 1 || ticket != 1) {
                throw new IllegalStateException(
                        "provisioning redrive changed concurrently");
            }
        });
    }

    private void rearmExpired(ExpiredClaim expired, String result)
    {
        transactions.executeWithoutResult(ignored -> {
            assertExpiredClaim(expired);
            int operation = jdbc.update(
                    """
                    UPDATE flow_runtime_operation SET state = 'READY',
                        result_ref = ?
                    WHERE operation_id = ? AND state = 'CLAIMED'
                    """,
                    result,
                    expired.operationId());
            int ticket = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'AVAILABLE', claim_owner = NULL,
                        claim_expires_at = NULL, claim_token = NULL,
                        not_before = ?
                    WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                      AND claim_generation = ?
                    """,
                    clock.instant().toEpochMilli(),
                    expired.operationId(),
                    expired.generation());
            if (operation != 1 || ticket != 1) {
                throw new IllegalStateException(
                        "expired provisioning redrive changed concurrently");
            }
        });
    }

    private void failBeforeSubject(
            Claim claim, Task task, String reason)
    {
        transactions.executeWithoutResult(ignored -> {
            assertClaimedGraph(claim, task);
            attentionRows(claim.operationId(), task,
                    "PROVISION_INVALID:" + reason);
        });
    }

    private void attentionClaimed(
            Claim claim, Task task, ObservedState observed)
    {
        attentionClaimed(claim, task, "PROVISION_" + observed.name());
    }

    private void attentionClaimed(
            Claim claim, Task task, String reason)
    {
        transactions.executeWithoutResult(ignored -> {
            assertClaimedGraph(claim, task);
            attentionRows(claim.operationId(), task, reason);
        });
    }

    private void attentionExpired(
            ExpiredClaim expired, Task task, ObservedState observed)
    {
        attentionExpired(expired, task, "PROVISION_" + observed.name());
    }

    private void attentionExpired(
            ExpiredClaim expired, Task task, String reason)
    {
        transactions.executeWithoutResult(ignored -> {
            assertExpiredClaim(expired);
            attentionRows(expired.operationId(), task, reason);
        });
    }

    private void attentionRows(
            String operationId, Task task, String reason)
    {
        Instant now = clock.instant();
        String lifecycleId = stableId(
                "task-lifecycle", task.taskId(), "2", reason);
        int lifecycle = jdbc.update(
                """
                INSERT INTO flow_runtime_task_lifecycle_revision (
                    lifecycle_revision_id, task_id, sequence, from_status,
                    to_status, reason_code, evidence_ref, operation_id,
                    recorded_at
                ) SELECT ?, task_id, 2, 'CREATED', 'NEEDS_ATTENTION',
                    ?, ?, ?, ? FROM flow_runtime_task
                WHERE task_id = ? AND status = 'CREATED'
                  AND current_lifecycle_revision_id = ?
                """,
                lifecycleId,
                reason,
                "provision-operation:" + operationId,
                operationId,
                now.toEpochMilli(),
                task.taskId(),
                task.currentLifecycleRevisionId());
        int taskUpdated = jdbc.update(
                """
                UPDATE flow_runtime_task SET status = 'NEEDS_ATTENTION',
                    current_lifecycle_revision_id = ?
                WHERE task_id = ? AND status = 'CREATED'
                  AND current_lifecycle_revision_id = ?
                """,
                lifecycleId,
                task.taskId(),
                task.currentLifecycleRevisionId());
        int operation = jdbc.update(
                """
                UPDATE flow_runtime_operation SET state = 'FAILED',
                    result_ref = ?
                WHERE operation_id = ? AND state = 'CLAIMED'
                """,
                reason,
                operationId);
        int ticket = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket SET delivery_state = 'DONE'
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                """,
                operationId);
        if (lifecycle != 1 || taskUpdated != 1
                || operation != 1 || ticket != 1) {
            throw new IllegalStateException(
                    "provisioning attention lost an owner row");
        }
    }

    private void assertClaimedGraph(Claim claim, Task task)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                JOIN flow_runtime_task t ON t.task_id = o.task_id
                WHERE o.operation_id = ? AND o.kind = 'PROVISION_TASK'
                  AND o.state = 'CLAIMED' AND o.task_id = ?
                  AND o.subject_digest = ?
                  AND d.delivery_state = 'CLAIMED'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                  AND d.claim_expires_at > ?
                  AND t.status = 'CREATED' AND t.launch_digest = ?
                  AND t.launch_base_sha IS NULL
                  AND t.current_base_sha IS NULL
                  AND t.current_head_sha IS NULL
                  AND t.task_session_id IS NULL
                """,
                Integer.class,
                claim.operationId(),
                task.taskId(),
                provisionSubjectDigest(task.taskId(), task.launchDigest()),
                claim.generation(),
                claim.claimToken(),
                claim.workerId(),
                clock.instant().toEpochMilli(),
                task.launchDigest());
        if (requireNonNull(exact, "claim graph count is null") != 1) {
            throw new IllegalStateException(
                    "provisioning claim graph is stale or corrupt");
        }
    }

    private void assertCompletionOwner(
            Claim claim, Task task, ResolvedSubject subject)
    {
        assertSubject(task,
                runtime.operation(claim.operationId()).orElseThrow(), subject);
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_provision_subject s
                JOIN flow_runtime_operation o
                  ON o.operation_id = s.operation_id
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE s.operation_id = ? AND s.task_id = ?
                  AND s.launch_digest = ? AND s.base_sha = ?
                  AND s.mutation_digest = ? AND o.state = 'CLAIMED'
                  AND d.delivery_state = 'CLAIMED'
                  AND d.claim_generation = ? AND d.claim_token = ?
                """,
                Integer.class,
                subject.operationId(),
                task.taskId(),
                task.launchDigest(),
                subject.baseSha(),
                subject.mutationDigest(),
                claim.generation(),
                claim.claimToken());
        if (requireNonNull(exact, "completion owner count is null") != 1) {
            throw new IllegalStateException(
                    "provision completion lost its exact claim/subject");
        }
    }

    private void assertExpiredClaim(ExpiredClaim expired)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PROVISION_TASK' AND o.state = 'CLAIMED'
                  AND d.delivery_state = 'CLAIMED'
                  AND d.claim_generation = ? AND d.claim_expires_at <= ?
                """,
                Integer.class,
                expired.operationId(),
                expired.taskId(),
                expired.generation(),
                clock.instant().toEpochMilli());
        if (requireNonNull(exact, "expired claim count is null") != 1) {
            throw new IllegalStateException(
                    "provisioning generation is not expired/current");
        }
    }

    private boolean recoveryReplay(ExpiredClaim expired)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PROVISION_TASK' AND o.state = 'READY'
                  AND o.result_ref IN (
                    'PROVISION_UNRESOLVED', 'PROVISION_ABSENT',
                    'PROVISION_UNAVAILABLE',
                    'PROVISION_EXACT')
                  AND d.delivery_state = 'AVAILABLE'
                  AND d.claim_generation = ?
                """,
                Integer.class,
                expired.operationId(),
                expired.taskId(),
                expired.generation());
        return requireNonNull(exact, "recovery replay count is null") == 1;
    }

    private void assertAttentionReplay(ExpiredClaim expired, Task task)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                JOIN flow_runtime_task t ON t.task_id = o.task_id
                JOIN flow_runtime_task_lifecycle_revision l
                  ON l.lifecycle_revision_id = t.current_lifecycle_revision_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PROVISION_TASK' AND o.state = 'FAILED'
                  AND o.result_ref GLOB 'PROVISION_*'
                  AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ?
                  AND t.status = 'NEEDS_ATTENTION'
                  AND t.current_lifecycle_revision_id = ?
                  AND l.task_id = t.task_id AND l.sequence = 2
                  AND l.from_status = 'CREATED'
                  AND l.to_status = 'NEEDS_ATTENTION'
                  AND l.operation_id = o.operation_id
                  AND l.reason_code = o.result_ref
                  AND l.evidence_ref = 'provision-operation:' || o.operation_id
                """,
                Integer.class,
                expired.operationId(),
                task.taskId(),
                expired.generation(),
                task.currentLifecycleRevisionId());
        if (requireNonNull(exact, "attention replay count is null") != 1) {
            throw new IllegalStateException(
                    "provisioning attention replay is inconsistent");
        }
    }

    private void assertSubject(
            Task task, Operation operation, ResolvedSubject subject)
    {
        String expectedMutation = stableId(
                "provision-mutation-v1",
                task.repositoryRoot(),
                task.gitCommonDir(),
                task.remoteName(),
                task.branchName(),
                branchRef(task),
                task.worktreePath(),
                subject.baseSha());
        if (!operation.operationId().equals(subject.operationId())
                || !operation.taskId().equals(task.taskId())
                || !operation.subjectDigest().equals(provisionSubjectDigest(
                        task.taskId(), task.launchDigest()))
                || !task.taskId().equals(subject.taskId())
                || !task.launchDigest().equals(subject.launchDigest())
                || !expectedMutation.equals(subject.mutationDigest())) {
            throw new IllegalStateException(
                    "resolved provisioning subject graph is corrupt");
        }
    }

    private void assertCompletedReplay(
            String operationId, Task task, ResolvedSubject subject)
    {
        Operation operation = runtime.operation(operationId).orElseThrow();
        assertSubject(task, operation, subject);
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                JOIN flow_runtime_task t ON t.task_id = o.task_id
                JOIN flow_runtime_task_base_revision b
                  ON b.task_id = t.task_id AND b.sequence = 1
                JOIN flow_runtime_task_lifecycle_revision l
                  ON l.task_id = t.task_id AND l.sequence = 2
                JOIN flow_runtime_agent_session s
                  ON s.session_id = t.task_session_id
                JOIN flow_runtime_inbox i
                  ON i.task_id = t.task_id AND i.kind = 'INITIAL_TASK'
                WHERE o.operation_id = ? AND o.state = 'SUCCEEDED'
                  AND o.result_ref = ? AND d.delivery_state = 'DONE'
                  AND t.launch_base_sha = ?
                  AND b.base_sha = ? AND b.reason_code = 'INITIAL'
                  AND b.source_operation_id = o.operation_id
                  AND b.evidence_ref = 'provision-operation:' || o.operation_id
                  AND l.from_status = 'CREATED' AND l.to_status = 'ACTIVE'
                  AND l.reason_code = 'PROVISIONED'
                  AND l.evidence_ref = 'base:' || b.base_sha
                  AND l.operation_id = o.operation_id
                  AND s.task_id = t.task_id AND s.role = 'TASK_AGENT'
                  AND i.subject_head = ? AND i.source = 'TASK'
                  AND i.external_key = t.task_id AND i.revision = '1'
                  AND i.payload_ref = 'task-goal:' || t.task_id
                  AND i.intended_gate_kind = 'INITIAL_PUBLISH'
                """,
                Integer.class,
                operationId,
                "provisioned:" + task.taskId(),
                subject.baseSha(),
                subject.baseSha(),
                subject.baseSha());
        if (requireNonNull(exact, "completion replay count is null") != 1) {
            throw new IllegalStateException(
                    "completed provisioning replay is inconsistent");
        }
    }

    private void assertCompletedClaimReplay(Claim claim)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PROVISION_TASK' AND o.state = 'SUCCEEDED'
                  AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                """,
                Integer.class,
                claim.operationId(),
                claim.taskId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId());
        if (requireNonNull(exact, "claim replay count is null") != 1) {
            throw new IllegalStateException(
                    "completed provisioning claim replay is not exact");
        }
    }

    private void assertCompletedRecoveryReplay(ExpiredClaim expired)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PROVISION_TASK' AND o.state = 'SUCCEEDED'
                  AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ?
                """,
                Integer.class,
                expired.operationId(),
                expired.taskId(),
                expired.generation());
        if (requireNonNull(exact, "recovery replay count is null") != 1) {
            throw new IllegalStateException(
                    "completed provisioning recovery replay is not exact");
        }
    }

    private Optional<ResolvedSubject> subject(String operationId)
    {
        return jdbc.query(
                "SELECT * FROM flow_runtime_provision_subject "
                        + "WHERE operation_id = ?",
                (result, row) -> new ResolvedSubject(
                        result.getString("operation_id"),
                        result.getString("task_id"),
                        result.getString("launch_digest"),
                        result.getString("base_sha"),
                        result.getString("mutation_digest"),
                        Instant.ofEpochMilli(result.getLong("bound_at"))),
                operationId).stream().findFirst();
    }

    private ResolvedSubject requireSubject(String operationId)
    {
        return subject(operationId).orElseThrow();
    }

    private static List<String> mutationArguments(Task task, String baseSha)
    {
        return List.of(
                "-c", "core.hooksPath=/dev/null",
                "-c", "protocol.allow=never",
                "worktree", "add", "-b",
                task.branchName(), task.worktreePath(), baseSha);
    }

    private ProcessResult command(Path root, String... arguments)
    {
        return git.run(root, List.of(arguments));
    }

    private String scalar(Path root, String... arguments)
    {
        ProcessResult result = command(root, arguments);
        if (!result.complete() || result.exitCode() != 0) {
            throw new TransientProvisioningFailure();
        }
        return result.stdout().strip();
    }

    private void requireScalar(
            Path root, String expected, String... arguments)
    {
        if (!scalar(root, arguments).equals(expected)) {
            throw new StableProvisioningFailure(
                    "REPOSITORY_IDENTITY_CHANGED");
        }
    }

    private void requireValidRef(Path root, String ref)
    {
        ProcessResult result = command(root, "check-ref-format", ref);
        requireComplete(result);
        if (result.exitCode() != 0) {
            throw new StableProvisioningFailure("INVALID_GIT_REF");
        }
    }

    private static void requireComplete(ProcessResult result)
    {
        if (result.overflow()) {
            throw new StableProvisioningFailure("GIT_OUTPUT_LIMIT");
        }
        if (!result.complete()) {
            throw new TransientProvisioningFailure();
        }
    }

    private static void requireProvisionClaim(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        if (claim.kind() != OperationKind.PROVISION_TASK) {
            throw new IllegalArgumentException(
                    "claim is not Task provisioning");
        }
    }

    private static void requireOutsideTransaction()
    {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Task provisioning must run outside owner transactions");
        }
    }

    private static final class StableProvisioningFailure
            extends RuntimeException
    {
        private final String code;

        private StableProvisioningFailure(String code)
        {
            super(code);
            this.code = code;
        }
    }

    private static final class TerminalProvisioningDisposition
            extends RuntimeException {}

    private static final class TransientProvisioningFailure
            extends RuntimeException {}

    static final class DirectGitProcess
            implements GitProcess
    {
        @Override
        public ProcessResult run(
                Path repositoryRoot, List<String> arguments)
        {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git");
            command.addAll(arguments);
            Process process;
            try {
                ProcessBuilder builder = new ProcessBuilder(command)
                        .directory(repositoryRoot.toFile());
                builder.environment().clear();
                builder.environment().putAll(safeEnvironment());
                process = builder.start();
            }
            catch (IOException failure) {
                return new ProcessResult(false, -1, "");
            }
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            AtomicBoolean stdoutEof = new AtomicBoolean();
            AtomicBoolean stderrEof = new AtomicBoolean();
            AtomicBoolean overflow = new AtomicBoolean();
            Thread out = drain(
                    process.getInputStream(), stdout, stdoutEof, overflow, true);
            Thread err = drain(
                    process.getErrorStream(), null, stderrEof, overflow, false);
            boolean exited = false;
            boolean interrupted = Thread.interrupted();
            try {
                if (!interrupted) {
                    exited = process.waitFor(
                            COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }
                if (!exited) {
                    interrupted |= stop(process);
                }
                join(out, err);
            }
            catch (InterruptedException canceled) {
                interrupted = true;
                interrupted |= stop(process);
            }
            if (out.isAlive() || err.isAlive()) {
                close(process.getInputStream());
                close(process.getErrorStream());
                try {
                    join(out, err);
                }
                catch (InterruptedException canceled) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            boolean complete = exited
                    && !out.isAlive() && !err.isAlive()
                    && stdoutEof.get() && stderrEof.get()
                    && !overflow.get();
            return new ProcessResult(
                    complete,
                    complete ? process.exitValue() : -1,
                    complete ? stdout.toString(StandardCharsets.UTF_8) : "",
                    overflow.get());
        }

        private static Thread drain(
                InputStream input,
                ByteArrayOutputStream output,
                AtomicBoolean eof,
                AtomicBoolean overflow,
                boolean retain)
        {
            return Thread.ofPlatform().daemon().start(() -> {
                try (input) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (retain) {
                            int accepted = Math.min(
                                    read,
                                    Math.max(0, OUTPUT_LIMIT - output.size()));
                            output.write(buffer, 0, accepted);
                            if (accepted != read) {
                                overflow.set(true);
                            }
                        }
                    }
                    eof.set(true);
                }
                catch (IOException ignored) {
                    // Missing EOF is represented by incomplete output.
                }
            });
        }

        private static void join(Thread first, Thread second)
                throws InterruptedException
        {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(2).toNanos();
            joinUntil(first, deadline);
            joinUntil(second, deadline);
        }

        private static void joinUntil(Thread thread, long deadline)
                throws InterruptedException
        {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                thread.join(Duration.ofNanos(remaining));
            }
        }

        private static boolean stop(Process process)
        {
            boolean interrupted = false;
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            catch (InterruptedException canceled) {
                interrupted = true;
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                catch (InterruptedException canceledAgain) {
                    interrupted = true;
                }
            }
            return interrupted;
        }

        private static void close(AutoCloseable stream)
        {
            try {
                stream.close();
            }
            catch (Exception ignored) {
                // Incomplete remains fail closed.
            }
        }
    }

    private static Map<String, String> safeEnvironment()
    {
        return Map.of(
                "PATH", "/usr/bin:/bin",
                "HOME", "/dev/null",
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_SYSTEM", "/dev/null",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_TERMINAL_PROMPT", "0",
                "GIT_ASKPASS", "/usr/bin/false",
                "SSH_ASKPASS", "/usr/bin/false",
                "GIT_NO_LAZY_FETCH", "1",
                "GIT_NO_REPLACE_OBJECTS", "1");
    }

    private static Path canonicalDirectory(Path path, String name)
    {
        requireNonNull(path, name + " is null");
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(absolute)) {
            throw new IllegalArgumentException(name + " is not a real directory");
        }
        try {
            return absolute.toRealPath();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot canonicalize " + name, failure);
        }
    }

    private static String stableId(String domain, String... values)
    {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        frame(digest, domain);
        for (String value : values) {
            frame(digest, requireNonNull(value, "stable ID value is null"));
        }
        return domain + ":" + HexFormat.of().formatHex(digest.digest());
    }

    static String provisionSubjectDigest(String taskId, String launchDigest)
    {
        return stableId("provision-subject", taskId, launchDigest);
    }

    private static void frame(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static void requireObjectId(String value, String name)
    {
        requireText(value, name);
        if (!value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException(name + " is not a full object ID");
        }
    }

    private static void requireRefPart(String value, String name)
    {
        requireText(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(name + " is unsafe");
        }
    }

    private static void requireFullRef(String value, String name)
    {
        requireText(value, name);
        if (!value.startsWith("refs/")
                || value.contains("..")
                || value.contains("@{")
                || value.endsWith("/")
                || value.contains("\\")
                || value.chars().anyMatch(character ->
                        Character.isWhitespace(character)
                                || Character.isISOControl(character))
                || value.matches(".*[~^:?*\\[].*")) {
            throw new IllegalArgumentException(name + " is not a safe full ref");
        }
    }

    static void assertStoredLaunch(Task task)
    {
        requireNonNull(task, "task is null");
        String taskKey = task.taskId().substring(
                task.taskId().indexOf(':') + 1);
        String expectedBranch = BRANCH_PREFIX + taskKey;
        Path expectedPath = Path.of(task.worktreePath()).normalize();
        String expected = stableId(
                "task-launch-v1",
                task.taskId(),
                task.repositoryId(),
                task.repositoryOwner(),
                task.repositoryName(),
                task.repositoryRoot(),
                task.gitCommonDir(),
                task.remoteName(),
                task.baseRef(),
                expectedBranch,
                expectedPath.toString());
        if (!task.taskId().equals(stableId("task", task.requestKey()))
                || !task.branchName().equals(expectedBranch)
                || expectedPath.getFileName() == null
                || !expectedPath.getFileName().toString().equals(taskKey)
                || !task.launchDigest().equals(expected)) {
            throw new IllegalStateException("stored Task launch graph is corrupt");
        }
    }

    private static void requireGitHubRepositoryLocator(
            String owner, String name)
    {
        requireText(owner, "repositoryOwner");
        requireText(name, "repositoryName");
        if (!owner.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
                || !name.matches("[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException(
                    "repository locator is not canonical GitHub owner/name");
        }
    }

    private static String githubRepository(String value)
    {
        requireText(value, "remote URL");
        String path;
        if (value.startsWith("https://github.com/")) {
            path = value.substring("https://github.com/".length());
        }
        else if (value.startsWith("git@github.com:")) {
            path = value.substring("git@github.com:".length());
        }
        else if (value.startsWith("ssh://git@github.com/")) {
            path = value.substring("ssh://git@github.com/".length());
        }
        else {
            throw new StableProvisioningFailure("REMOTE_IDENTITY_CHANGED");
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        try {
            int slash = path.indexOf('/');
            if (slash < 1 || slash == path.length() - 1
                    || path.indexOf('/', slash + 1) >= 0) {
                throw new IllegalArgumentException("remote has extra path");
            }
            requireGitHubRepositoryLocator(
                    path.substring(0, slash), path.substring(slash + 1));
        }
        catch (IllegalArgumentException malformed) {
            throw new StableProvisioningFailure("REMOTE_IDENTITY_CHANGED");
        }
        return path;
    }

    private static boolean unsafeConfigKey(String key)
    {
        return key.startsWith("alias.")
                || key.startsWith("credential.")
                || key.startsWith("filter.")
                || key.startsWith("http.")
                || key.startsWith("url.")
                || key.startsWith("protocol.")
                || key.startsWith("submodule.")
                || key.startsWith("include.")
                || key.equals("extensions.worktreeconfig")
                || key.equals("extensions.partialclone")
                || key.equals("core.sparsecheckout")
                || key.equals("core.sparsecheckoutcone")
                || key.equals("core.hookspath")
                || key.equals("core.fsmonitor")
                || key.equals("core.attributesfile")
                || key.equals("core.sshcommand")
                || key.matches("remote\\..*\\.(proxy|promisor|partialclonefilter)");
    }

    private static String branchRef(Task task)
    {
        return "refs/heads/" + task.branchName();
    }
}
