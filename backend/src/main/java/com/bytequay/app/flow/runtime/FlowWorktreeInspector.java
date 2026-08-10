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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Stateless, fail-closed inspection of one program-owned Task worktree.
 *
 * <p>This class observes Git only. It does not adopt a head, mutate a worktree,
 * or grant writer authority.
 */
public final class FlowWorktreeInspector
{
    private static final Path GIT = Path.of("/usr/bin/git");
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);
    private static final int SCALAR_OUTPUT_LIMIT = 4 * 1024;
    private static final int STDERR_LIMIT = 64 * 1024;
    private static final String HEAD_TREE_DOMAIN = "bytequay-head-tree-v1\0";
    private static final String DIFF_DOMAIN = "bytequay-base-head-trees-v1\0";

    public Inspection inspect(
            Path repositoryRoot,
            Path worktree,
            String expectedBranch,
            String baseSha,
            String predecessorSha)
    {
        Inputs inputs = validateInputs(
                repositoryRoot,
                worktree,
                expectedBranch,
                baseSha,
                predecessorSha);
        Deadline deadline = new Deadline(TOTAL_TIMEOUT);

        CommandResult branchValidation = runGit(
                inputs.repositoryRoot(),
                deadline,
                Command.CHECK_BRANCH,
                expectedBranch,
                null,
                OutputMode.SCALAR);
        if (branchValidation.exitCode() != 0
                || !branchValidation.stdout().equals(expectedBranch)) {
            throw failure(FailureCode.INVALID_INPUT);
        }

        Observation first = observe(inputs, deadline);
        Observation second = observe(inputs, deadline);
        if (!first.equals(second)) {
            throw failure(FailureCode.MOVED_DURING_INSPECTION);
        }

        ValidatedObservation validated = validateObservation(inputs, second);
        String headTreeDigest = digest(HEAD_TREE_DOMAIN + validated.headTree());
        String diffDigest = digest(
                DIFF_DOMAIN + validated.baseTree() + "\0" + validated.headTree());
        return new Inspection(
                validated.head(),
                headTreeDigest,
                diffDigest,
                !validated.baseTree().equals(validated.headTree()));
    }

    private static Inputs validateInputs(
            Path repositoryRoot,
            Path worktree,
            String expectedBranch,
            String baseSha,
            String predecessorSha)
    {
        if (repositoryRoot == null
                || worktree == null
                || expectedBranch == null
                || baseSha == null
                || predecessorSha == null
                || !repositoryRoot.isAbsolute()
                || !worktree.isAbsolute()
                || hasLineBreak(repositoryRoot.toString())
                || hasLineBreak(worktree.toString())
                || expectedBranch.isBlank()
                || expectedBranch.length() > 1024
                || !isFullLowercaseSha(baseSha)
                || !isFullLowercaseSha(predecessorSha)) {
            throw failure(FailureCode.INVALID_INPUT);
        }
        if (!Files.isExecutable(GIT)) {
            throw failure(FailureCode.GIT_UNAVAILABLE);
        }
        try {
            Path realRepositoryRoot = repositoryRoot.toRealPath();
            Path realWorktree = worktree.toRealPath();
            if (!Files.isDirectory(realRepositoryRoot) || !Files.isDirectory(realWorktree)) {
                throw failure(FailureCode.NOT_WORKTREE);
            }
            if (realRepositoryRoot.equals(realWorktree)) {
                throw failure(FailureCode.NOT_WORKTREE);
            }
            return new Inputs(
                    realRepositoryRoot,
                    realWorktree,
                    expectedBranch,
                    baseSha,
                    predecessorSha);
        }
        catch (IOException e) {
            throw failure(FailureCode.NOT_WORKTREE);
        }
    }

    private static Observation observe(Inputs inputs, Deadline deadline)
    {
        CommandResult repositoryTop = scalar(
                inputs.repositoryRoot(), deadline, Command.SHOW_TOPLEVEL, null, null);
        CommandResult repositoryCommon = scalar(
                inputs.repositoryRoot(), deadline, Command.COMMON_DIR, null, null);
        CommandResult worktreeTop = scalar(
                inputs.worktree(), deadline, Command.SHOW_TOPLEVEL, null, null);
        CommandResult worktreeCommon = scalar(
                inputs.worktree(), deadline, Command.COMMON_DIR, null, null);
        CommandResult gitDirectory = scalar(
                inputs.worktree(), deadline, Command.GIT_DIR, null, null);
        validatePathIdentity(
                inputs,
                repositoryTop,
                repositoryCommon,
                worktreeTop,
                worktreeCommon,
                gitDirectory);
        ControlState controlState = readControlState(
                inputs.worktree(), worktreeCommon, gitDirectory);
        requireSafeControlState(controlState);
        CommandResult safetyConfig = runGit(
                inputs.worktree(),
                deadline,
                Command.CONFIG_ENTRIES,
                null,
                null,
                OutputMode.CONFIG_ENTRIES);
        requireSafeProbe(safetyConfig);

        CommandResult branch = scalar(
                inputs.worktree(), deadline, Command.SYMBOLIC_BRANCH, null, null);
        CommandResult head = scalar(
                inputs.worktree(), deadline, Command.HEAD, null, null);
        CommandResult branchHead = scalar(
                inputs.worktree(),
                deadline,
                Command.BRANCH_HEAD,
                inputs.expectedBranch(),
                null);
        CommandResult gitlinks = runGit(
                inputs.worktree(),
                deadline,
                Command.INDEX_STAGES,
                null,
                null,
                OutputMode.GITLINKS);
        requireSafeProbe(gitlinks);
        CommandResult hiddenFlags = runGit(
                inputs.worktree(),
                deadline,
                Command.INDEX_FLAGS,
                null,
                null,
                OutputMode.HIDDEN_FLAGS);
        requireSafeProbe(hiddenFlags);
        CommandResult status = runGit(
                inputs.worktree(),
                deadline,
                Command.STATUS,
                null,
                null,
                OutputMode.PRESENCE);
        CommandResult baseType = scalar(
                inputs.worktree(), deadline, Command.OBJECT_TYPE, inputs.baseSha(), null);
        CommandResult predecessorType = scalar(
                inputs.worktree(),
                deadline,
                Command.OBJECT_TYPE,
                inputs.predecessorSha(),
                null);
        CommandResult baseAncestor = scalar(
                inputs.worktree(),
                deadline,
                Command.IS_ANCESTOR,
                inputs.baseSha(),
                head.stdout());
        CommandResult predecessorAncestor = scalar(
                inputs.worktree(),
                deadline,
                Command.IS_ANCESTOR,
                inputs.predecessorSha(),
                head.stdout());
        CommandResult headTree = scalar(
                inputs.worktree(), deadline, Command.TREE, head.stdout(), null);
        CommandResult baseTree = scalar(
                inputs.worktree(), deadline, Command.TREE, inputs.baseSha(), null);

        return new Observation(
                repositoryTop,
                repositoryCommon,
                worktreeTop,
                worktreeCommon,
                gitDirectory,
                controlState,
                branch,
                head,
                branchHead,
                safetyConfig,
                gitlinks,
                hiddenFlags,
                status,
                baseType,
                predecessorType,
                baseAncestor,
                predecessorAncestor,
                headTree,
                baseTree);
    }

    private static void requireSafeProbe(CommandResult result)
    {
        requireSuccess(result, FailureCode.COMMAND_FAILED);
        if (result.unsafeOutput()) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
    }

    private static void requireSafeControlState(ControlState state)
    {
        if (state.unavailable()) {
            throw failure(FailureCode.COMMAND_FAILED);
        }
        if (!state.registeredLinkedWorktree()) {
            throw failure(FailureCode.NOT_WORKTREE);
        }
        if (state.legacyGrafts() || state.partialObjectState()) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
        if (state.operationInProgress()) {
            throw failure(FailureCode.GIT_OPERATION_IN_PROGRESS);
        }
    }

    private static CommandResult scalar(
            Path workingDirectory,
            Deadline deadline,
            Command command,
            String first,
            String second)
    {
        return runGit(
                workingDirectory,
                deadline,
                command,
                first,
                second,
                OutputMode.SCALAR);
    }

    private static ControlState readControlState(
            Path worktree,
            CommandResult commonDirectory,
            CommandResult gitDirectory)
    {
        if (commonDirectory.exitCode() != 0 || gitDirectory.exitCode() != 0) {
            return ControlState.unavailableState();
        }
        try {
            Path common = reportedPath(worktree, commonDirectory.stdout());
            Path gitDir = reportedPath(worktree, gitDirectory.stdout());
            boolean registeredLinkedWorktree = isRegisteredLinkedWorktree(
                    worktree, common, gitDir);
            boolean operationInProgress = List.of(
                            "MERGE_HEAD",
                            "MERGE_AUTOSTASH",
                            "AUTO_MERGE",
                            "CHERRY_PICK_HEAD",
                            "REVERT_HEAD",
                            "REBASE_HEAD",
                            "rebase-merge",
                            "rebase-apply",
                            "sequencer",
                            "BISECT_START",
                            "BISECT_LOG",
                            "BISECT_HEAD",
                            "BISECT_NAMES",
                            "BISECT_TERMS")
                    .stream()
                    .anyMatch(marker -> Files.exists(gitDir.resolve(marker)));
            operationInProgress = operationInProgress
                    || Files.exists(common.resolve("refs/bisect"));
            Path grafts = common.resolve("info/grafts");
            boolean legacyGrafts = Files.exists(grafts)
                    && (!Files.isRegularFile(grafts) || Files.size(grafts) > 0);
            boolean partialObjectState = hasPartialObjectState(common);
            return new ControlState(
                    false,
                    registeredLinkedWorktree,
                    operationInProgress,
                    legacyGrafts,
                    partialObjectState);
        }
        catch (IOException | RuntimeException e) {
            return ControlState.unavailableState();
        }
    }

    private static boolean hasPartialObjectState(Path common)
            throws IOException
    {
        Path alternates = common.resolve("objects/info/alternates");
        if (Files.exists(alternates, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(alternates, LinkOption.NOFOLLOW_LINKS)
                || Files.size(alternates) > 0)) {
            return true;
        }
        Path packDirectory = common.resolve("objects/pack");
        if (!Files.isDirectory(packDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (var entries = Files.newDirectoryStream(packDirectory, "*.promisor")) {
            return entries.iterator().hasNext();
        }
    }

    private static boolean isRegisteredLinkedWorktree(
            Path worktree,
            Path common,
            Path gitDirectory)
            throws IOException
    {
        if (gitDirectory.equals(common)) {
            return false;
        }
        Path registrations = common.resolve("worktrees").toRealPath();
        if (!gitDirectory.getParent().equals(registrations)) {
            return false;
        }

        Path dotGit = worktree.resolve(".git");
        if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(
                        gitDirectory.resolve("gitdir"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(
                        gitDirectory.resolve("commondir"), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path dotGitTarget = readPointer(dotGit, worktree, "gitdir: ");
        Path adminBackpointer = readPointer(
                gitDirectory.resolve("gitdir"), gitDirectory, "");
        Path commonBackpointer = readPointer(
                gitDirectory.resolve("commondir"), gitDirectory, "");
        return dotGitTarget.equals(gitDirectory)
                && adminBackpointer.equals(dotGit.toRealPath())
                && commonBackpointer.equals(common);
    }

    private static Path readPointer(Path file, Path relativeTo, String prefix)
            throws IOException
    {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(SCALAR_OUTPUT_LIMIT + 1);
        }
        if (bytes.length == 0 || bytes.length > SCALAR_OUTPUT_LIMIT) {
            throw new IOException("invalid bounded Git pointer");
        }
        String value = new String(bytes, StandardCharsets.UTF_8).strip();
        if (!value.startsWith(prefix)) {
            throw new IOException("invalid Git pointer prefix");
        }
        value = value.substring(prefix.length());
        if (value.isBlank() || hasLineBreak(value)) {
            throw new IOException("invalid Git pointer value");
        }
        Path target = Path.of(value);
        if (!target.isAbsolute()) {
            target = relativeTo.resolve(target);
        }
        return target.toRealPath();
    }

    private static ValidatedObservation validateObservation(
            Inputs inputs,
            Observation observation)
    {
        validatePathIdentity(
                inputs,
                observation.repositoryTop(),
                observation.repositoryCommon(),
                observation.worktreeTop(),
                observation.worktreeCommon(),
                observation.gitDirectory());

        if (observation.branch().exitCode() == 1) {
            throw failure(FailureCode.DETACHED_HEAD);
        }
        requireSuccess(observation.branch(), FailureCode.COMMAND_FAILED);
        if (!observation.branch().stdout().equals(inputs.expectedBranch())) {
            throw failure(FailureCode.WRONG_BRANCH);
        }

        requireSuccess(observation.head(), FailureCode.COMMAND_FAILED);
        String head = requireObservedSha(observation.head().stdout());
        requireSuccess(observation.branchHead(), FailureCode.BRANCH_HEAD_MISMATCH);
        String branchHead = requireObservedSha(observation.branchHead().stdout());
        if (!head.equals(branchHead)) {
            throw failure(FailureCode.BRANCH_HEAD_MISMATCH);
        }

        requireSuccess(observation.status(), FailureCode.COMMAND_FAILED);
        if (observation.status().hadOutput()) {
            throw failure(FailureCode.DIRTY);
        }

        requireCommit(observation.baseType(), FailureCode.BASE_NOT_FOUND);
        requireCommit(observation.predecessorType(), FailureCode.PREDECESSOR_NOT_FOUND);
        requireAncestor(observation.baseAncestor(), FailureCode.BASE_NOT_ANCESTOR);
        requireAncestor(
                observation.predecessorAncestor(), FailureCode.PREDECESSOR_NOT_ANCESTOR);

        requireSuccess(observation.headTree(), FailureCode.COMMAND_FAILED);
        requireSuccess(observation.baseTree(), FailureCode.COMMAND_FAILED);
        String headTree = requireObservedSha(observation.headTree().stdout());
        String baseTree = requireObservedSha(observation.baseTree().stdout());
        return new ValidatedObservation(head, headTree, baseTree);
    }

    private static void validatePathIdentity(
            Inputs inputs,
            CommandResult repositoryTopResult,
            CommandResult repositoryCommonResult,
            CommandResult worktreeTopResult,
            CommandResult worktreeCommonResult,
            CommandResult gitDirectoryResult)
    {
        requireSuccess(repositoryTopResult, FailureCode.NOT_WORKTREE);
        requireSuccess(repositoryCommonResult, FailureCode.NOT_WORKTREE);
        requireSuccess(worktreeTopResult, FailureCode.NOT_WORKTREE);
        requireSuccess(worktreeCommonResult, FailureCode.NOT_WORKTREE);
        requireSuccess(gitDirectoryResult, FailureCode.NOT_WORKTREE);
        try {
            Path repositoryTop = reportedPath(
                    inputs.repositoryRoot(), repositoryTopResult.stdout());
            Path worktreeTop = reportedPath(
                    inputs.worktree(), worktreeTopResult.stdout());
            if (!repositoryTop.equals(inputs.repositoryRoot())
                    || !worktreeTop.equals(inputs.worktree())) {
                throw failure(FailureCode.NOT_WORKTREE);
            }
            Path repositoryCommon = reportedPath(
                    inputs.repositoryRoot(), repositoryCommonResult.stdout());
            Path worktreeCommon = reportedPath(
                    inputs.worktree(), worktreeCommonResult.stdout());
            if (!repositoryCommon.equals(worktreeCommon)) {
                throw failure(FailureCode.WRONG_REPOSITORY);
            }
        }
        catch (IOException | IllegalArgumentException e) {
            throw failure(FailureCode.NOT_WORKTREE);
        }
    }

    private static void requireCommit(CommandResult result, FailureCode missingCode)
    {
        if (result.exitCode() != 0 || !result.stdout().equals("commit")) {
            throw failure(missingCode);
        }
    }

    private static void requireAncestor(CommandResult result, FailureCode failureCode)
    {
        if (result.exitCode() == 1) {
            throw failure(failureCode);
        }
        requireSuccess(result, FailureCode.COMMAND_FAILED);
    }

    private static void requireSuccess(CommandResult result, FailureCode failureCode)
    {
        if (result.exitCode() != 0) {
            throw failure(failureCode);
        }
    }

    private static String requireObservedSha(String value)
    {
        if (!isFullLowercaseSha(value)) {
            throw failure(FailureCode.COMMAND_FAILED);
        }
        return value;
    }

    private static Path reportedPath(Path workingDirectory, String value)
            throws IOException
    {
        if (value.isBlank() || hasLineBreak(value)) {
            throw new IllegalArgumentException("invalid Git path output");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = workingDirectory.resolve(path);
        }
        return path.toRealPath();
    }

    private static boolean hasLineBreak(String value)
    {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static boolean isFullLowercaseSha(String value)
    {
        return value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static CommandResult runGit(
            Path workingDirectory,
            Deadline deadline,
            Command command,
            String first,
            String second,
            OutputMode outputMode)
    {
        requireNonNull(workingDirectory, "workingDirectory is null");
        List<String> arguments = command.arguments(first, second);
        List<String> processArguments = new ArrayList<>(10 + arguments.size());
        processArguments.add(GIT.toString());
        processArguments.add("-c");
        processArguments.add("core.fsmonitor=false");
        processArguments.add("-c");
        processArguments.add("core.hooksPath=/dev/null");
        processArguments.add("-c");
        processArguments.add("diff.external=");
        processArguments.add("-c");
        processArguments.add("core.fileMode=true");
        processArguments.add("-c");
        processArguments.add("core.ignoreStat=false");
        processArguments.add("-c");
        processArguments.add("core.trustctime=true");
        processArguments.add("-c");
        processArguments.add("core.checkStat=default");
        processArguments.add("-c");
        processArguments.add("core.ignoreCase=false");
        processArguments.add("-c");
        processArguments.add("core.symlinks=true");
        processArguments.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(processArguments);
        builder.directory(workingDirectory.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("LC_ALL", "C");
        environment.put("LANG", "C");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_PAGER", "cat");
        environment.put("PAGER", "cat");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("GIT_NO_REPLACE_OBJECTS", "1");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");

        Process process;
        try {
            process = builder.start();
        }
        catch (IOException e) {
            throw failure(FailureCode.GIT_UNAVAILABLE);
        }
        close(process.getOutputStream());

        OutputDrain stdout = new OutputDrain(
                process.getInputStream(),
                outputMode == OutputMode.SCALAR ? SCALAR_OUTPUT_LIMIT : 0,
                outputMode);
        OutputDrain stderr = new OutputDrain(
                process.getErrorStream(), STDERR_LIMIT, OutputMode.DISCARD);
        Thread stdoutThread = Thread.ofVirtual().unstarted(stdout);
        Thread stderrThread = Thread.ofVirtual().unstarted(stderr);
        stdoutThread.start();
        stderrThread.start();

        try {
            long remainingNanos = deadline.remainingNanos();
            if (remainingNanos <= 0
                    || !process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                terminate(process, stdoutThread, stderrThread);
                throw failure(FailureCode.TIMEOUT);
            }
            if (!join(stdoutThread, deadline) || !join(stderrThread, deadline)) {
                terminate(process, stdoutThread, stderrThread);
                throw failure(FailureCode.TIMEOUT);
            }
        }
        catch (InterruptedException e) {
            terminate(process, stdoutThread, stderrThread);
            Thread.currentThread().interrupt();
            throw failure(FailureCode.INTERRUPTED);
        }

        if (stdout.failed() || stderr.failed()) {
            throw failure(FailureCode.COMMAND_FAILED);
        }
        if (stdout.overflow() || stderr.overflow()) {
            terminate(process, stdoutThread, stderrThread);
            throw failure(FailureCode.OUTPUT_LIMIT);
        }
        return new CommandResult(
                process.exitValue(),
                outputMode == OutputMode.SCALAR ? stdout.text().strip() : "",
                stdout.hadOutput(),
                stdout.unsafeOutput());
    }

    private static boolean join(Thread thread, Deadline deadline)
            throws InterruptedException
    {
        long remainingNanos = deadline.remainingNanos();
        if (remainingNanos <= 0) {
            return false;
        }
        thread.join(Duration.ofNanos(remainingNanos));
        return !thread.isAlive();
    }

    private static void terminate(Process process, Thread stdout, Thread stderr)
    {
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants().toList();
        }
        catch (RuntimeException ignored) {
            descendants = List.of();
        }
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        close(process.getOutputStream());
        close(process.getInputStream());
        close(process.getErrorStream());
        boolean interrupted = Thread.interrupted();
        interrupted |= joinAfterTermination(stdout);
        interrupted |= joinAfterTermination(stderr);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean joinAfterTermination(Thread thread)
    {
        try {
            thread.join(Duration.ofSeconds(1));
            return false;
        }
        catch (InterruptedException e) {
            return true;
        }
    }

    private static void close(AutoCloseable closeable)
    {
        try {
            closeable.close();
        }
        catch (Exception ignored) {
            // Best-effort process cleanup; the typed failure is already fixed.
        }
    }

    private static InspectionFailure failure(FailureCode code)
    {
        return new InspectionFailure(code);
    }

    public record Inspection(
            String headSha,
            String headTreeDigest,
            String baseToHeadDiffDigest,
            boolean differsFromBase) {}

    public enum FailureCode
    {
        INVALID_INPUT,
        GIT_UNAVAILABLE,
        NOT_WORKTREE,
        WRONG_REPOSITORY,
        DETACHED_HEAD,
        WRONG_BRANCH,
        BRANCH_HEAD_MISMATCH,
        DIRTY,
        GIT_OPERATION_IN_PROGRESS,
        BASE_NOT_FOUND,
        PREDECESSOR_NOT_FOUND,
        BASE_NOT_ANCESTOR,
        PREDECESSOR_NOT_ANCESTOR,
        UNTRUSTED_REPOSITORY_STATE,
        MOVED_DURING_INSPECTION,
        TIMEOUT,
        OUTPUT_LIMIT,
        COMMAND_FAILED,
        INTERRUPTED
    }

    public static final class InspectionFailure
            extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        private final FailureCode code;

        private InspectionFailure(FailureCode code)
        {
            super(code.name());
            this.code = requireNonNull(code, "code is null");
        }

        public FailureCode code()
        {
            return code;
        }
    }

    private enum OutputMode
    {
        SCALAR,
        PRESENCE,
        CONFIG_ENTRIES,
        GITLINKS,
        HIDDEN_FLAGS,
        DISCARD
    }

    private enum Command
    {
        CHECK_BRANCH,
        SHOW_TOPLEVEL,
        COMMON_DIR,
        GIT_DIR,
        SYMBOLIC_BRANCH,
        HEAD,
        BRANCH_HEAD,
        CONFIG_ENTRIES,
        INDEX_STAGES,
        INDEX_FLAGS,
        STATUS,
        OBJECT_TYPE,
        IS_ANCESTOR,
        TREE;

        private List<String> arguments(String first, String second)
        {
            return switch (this) {
                case CHECK_BRANCH -> List.of("check-ref-format", "--branch", first);
                case SHOW_TOPLEVEL -> List.of("rev-parse", "--show-toplevel");
                case COMMON_DIR -> List.of("rev-parse", "--git-common-dir");
                case GIT_DIR -> List.of("rev-parse", "--absolute-git-dir");
                case SYMBOLIC_BRANCH -> List.of(
                        "symbolic-ref", "--quiet", "--short", "HEAD");
                case HEAD -> List.of("rev-parse", "--verify", "HEAD^{commit}");
                case BRANCH_HEAD -> List.of(
                        "show-ref", "--verify", "--hash", "refs/heads/" + first);
                case CONFIG_ENTRIES -> List.of("config", "--null", "--list");
                case INDEX_STAGES -> List.of("ls-files", "--stage", "-z");
                case INDEX_FLAGS -> List.of("ls-files", "-v", "-z");
                case STATUS -> List.of(
                        "status",
                        "--porcelain=v1",
                        "-z",
                        "--untracked-files=all",
                        "--ignore-submodules=all");
                case OBJECT_TYPE -> List.of("cat-file", "-t", first);
                case IS_ANCESTOR -> List.of(
                        "merge-base", "--is-ancestor", first, second);
                case TREE -> List.of("rev-parse", "--verify", first + "^{tree}");
            };
        }
    }

    private record Inputs(
            Path repositoryRoot,
            Path worktree,
            String expectedBranch,
            String baseSha,
            String predecessorSha) {}

    private record CommandResult(
            int exitCode,
            String stdout,
            boolean hadOutput,
            boolean unsafeOutput) {}

    private record ControlState(
            boolean unavailable,
            boolean registeredLinkedWorktree,
            boolean operationInProgress,
            boolean legacyGrafts,
            boolean partialObjectState)
    {
        private static ControlState unavailableState()
        {
            return new ControlState(true, false, false, false, false);
        }
    }

    private record Observation(
            CommandResult repositoryTop,
            CommandResult repositoryCommon,
            CommandResult worktreeTop,
            CommandResult worktreeCommon,
            CommandResult gitDirectory,
            ControlState controlState,
            CommandResult branch,
            CommandResult head,
            CommandResult branchHead,
            CommandResult safetyConfig,
            CommandResult gitlinks,
            CommandResult hiddenFlags,
            CommandResult status,
            CommandResult baseType,
            CommandResult predecessorType,
            CommandResult baseAncestor,
            CommandResult predecessorAncestor,
            CommandResult headTree,
            CommandResult baseTree)
    {}

    private record ValidatedObservation(String head, String headTree, String baseTree) {}

    private static final class Deadline
    {
        private final long endNanos;

        private Deadline(Duration timeout)
        {
            endNanos = System.nanoTime() + timeout.toNanos();
        }

        private long remainingNanos()
        {
            return endNanos - System.nanoTime();
        }
    }

    private static final class OutputDrain
            implements Runnable
    {
        private final InputStream input;
        private final int limit;
        private final OutputMode mode;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final ByteArrayOutputStream record = new ByteArrayOutputStream();

        private boolean hadOutput;
        private boolean unsafeOutput;
        private boolean overflow;
        private boolean failed;

        private OutputDrain(InputStream input, int limit, OutputMode mode)
        {
            this.input = requireNonNull(input, "input is null");
            this.limit = limit;
            this.mode = requireNonNull(mode, "mode is null");
        }

        @Override
        public void run()
        {
            byte[] buffer = new byte[8192];
            int total = 0;
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    hadOutput = true;
                    if (limit > 0 && total > limit - read) {
                        overflow = true;
                    }
                    if (mode == OutputMode.SCALAR && !overflow) {
                        output.write(buffer, 0, read);
                    }
                    inspectRecords(buffer, read);
                    if (total <= limit) {
                        total += read;
                    }
                }
            }
            catch (IOException e) {
                failed = true;
            }
        }

        private void inspectRecords(byte[] buffer, int length)
        {
            if (mode != OutputMode.CONFIG_ENTRIES
                    && mode != OutputMode.GITLINKS
                    && mode != OutputMode.HIDDEN_FLAGS) {
                return;
            }
            for (int index = 0; index < length; index++) {
                byte value = buffer[index];
                if (value == 0) {
                    inspectRecord();
                    record.reset();
                }
                else if (record.size() <= SCALAR_OUTPUT_LIMIT) {
                    record.write(value);
                }
            }
        }

        private void inspectRecord()
        {
            byte[] bytes = record.toByteArray();
            if (bytes.length > SCALAR_OUTPUT_LIMIT) {
                unsafeOutput = true;
                return;
            }
            if (mode == OutputMode.CONFIG_ENTRIES) {
                String entry = new String(bytes, StandardCharsets.UTF_8);
                int separator = entry.indexOf('\n');
                if (separator < 0) {
                    unsafeOutput = true;
                    return;
                }
                String key = entry.substring(0, separator).toLowerCase(Locale.ROOT);
                String value = entry.substring(separator + 1);
                unsafeOutput = unsafeOutput
                        || key.isBlank()
                        || unsafeConfigEntry(key, value);
            }
            else if (mode == OutputMode.GITLINKS) {
                unsafeOutput = unsafeOutput
                        || new String(bytes, StandardCharsets.US_ASCII)
                        .startsWith("160000 ");
            }
            else if (mode == OutputMode.HIDDEN_FLAGS && bytes.length > 0) {
                int tag = Byte.toUnsignedInt(bytes[0]);
                unsafeOutput = unsafeOutput || tag == 'S' || (tag >= 'a' && tag <= 'z');
            }
        }

        private static boolean unsafeConfigEntry(String key, String value)
        {
            if (key.startsWith("filter.")
                    && (key.endsWith(".clean") || key.endsWith(".process"))) {
                return true;
            }
            if (key.equals("extensions.partialclone")
                    || key.equals("core.alternaterefscommand")) {
                return true;
            }
            if (!key.startsWith("remote.")) {
                return false;
            }
            if (key.endsWith(".promisor")) {
                return !List.of("false", "no", "off", "0")
                        .contains(value.toLowerCase(Locale.ROOT));
            }
            return key.endsWith(".partialclonefilter")
                    || key.endsWith(".uploadpack")
                    || key.endsWith(".vcs");
        }

        private boolean hadOutput()
        {
            return hadOutput;
        }

        private boolean overflow()
        {
            return overflow;
        }

        private boolean unsafeOutput()
        {
            return unsafeOutput;
        }

        private boolean failed()
        {
            return failed;
        }

        private String text()
        {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
