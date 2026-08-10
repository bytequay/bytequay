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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Stateless, fail-closed inspection of one program-owned Task worktree.
 *
 * <p>This class observes Git only. It does not adopt a head, mutate a worktree,
 * or grant writer authority. The local-sidecar trust boundary excludes a
 * malicious same-UID process racing filesystem syscalls; such a process can
 * already alter the database and JVM. Inspection rejects stable symlink-parent
 * escapes, runs only after ByteQuay's writer is stopped, and uses two complete
 * observations to detect ordinary concurrent movement.
 */
public final class FlowWorktreeInspector
{
    private static final Path GIT = Path.of("/usr/bin/git");
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);
    private static final int SCALAR_OUTPUT_LIMIT = 4 * 1024;
    private static final int STDERR_LIMIT = 64 * 1024;
    private static final int PATH_OUTPUT_LIMIT = 8 * 1024 * 1024;
    private static final int STREAM_OUTPUT_LIMIT = 64 * 1024 * 1024;
    private static final int MAX_UNTRACKED_PATHS = 100_000;
    private static final long MAX_UNTRACKED_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UNTRACKED_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final int MAX_TRACKED_PATHS = 100_000;
    private static final long MAX_TRACKED_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TRACKED_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final int MAX_CONTROL_ENTRIES = 1_000;
    private static final long MAX_CONTROL_FILE_BYTES = 1024L * 1024;
    private static final long MAX_INDEX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_CONTROL_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final String HEAD_TREE_DOMAIN = "bytequay-head-tree-v1\0";
    private static final String DIFF_DOMAIN = "bytequay-base-head-trees-v1\0";
    private static final String NON_CLEAN_DOMAIN =
            "bytequay-non-clean-worktree-v1\0";

    private final Runnable betweenNonCleanObservations;

    public FlowWorktreeInspector()
    {
        this(() -> {});
    }

    FlowWorktreeInspector(Runnable betweenNonCleanObservations)
    {
        this.betweenNonCleanObservations = requireNonNull(
                betweenNonCleanObservations,
                "betweenNonCleanObservations is null");
    }

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

    /**
     * Seals one exact non-clean Task worktree without storing its contents.
     * Detached HEAD is accepted only while a recognized Git operation is
     * durably visible in the linked-worktree administration directory.
     */
    public NonCleanInspection inspectNonClean(
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

        NonCleanObservation first = observeNonClean(inputs, deadline);
        betweenNonCleanObservations.run();
        NonCleanObservation second = observeNonClean(inputs, deadline);
        if (!first.equals(second)) {
            throw failure(FailureCode.MOVED_DURING_INSPECTION);
        }
        return new NonCleanInspection(
                second.actualHeadSha(),
                second.branchHeadSha(),
                second.attachmentState(),
                second.kind(),
                second.operations(),
                second.stateDigest());
    }

    private static NonCleanObservation observeNonClean(
            Inputs inputs, Deadline deadline)
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
        ControlState basicControl = readControlState(
                inputs.worktree(), worktreeCommon, gitDirectory);
        requireSafeNonCleanControlState(basicControl);
        Path common = reportedPathUnchecked(
                inputs.worktree(), worktreeCommon.stdout());
        if (Files.exists(common.resolve("rr-cache"), LinkOption.NOFOLLOW_LINKS)) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
        Path gitDir = reportedPathUnchecked(
                inputs.worktree(), gitDirectory.stdout());
        if (Files.exists(
                gitDir.resolve("index.lock"), LinkOption.NOFOLLOW_LINKS)) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
        rejectSpecialWorktreeEntries(inputs.worktree(), deadline);
        ControlSnapshot control = captureControlSnapshot(gitDir, deadline);

        CommandResult safetyConfig = runGit(
                inputs.worktree(),
                deadline,
                Command.CONFIG_ENTRIES,
                null,
                null,
                OutputMode.CONFIG_ENTRIES);
        requireSafeProbe(safetyConfig);
        CommandResult sharedIndex = scalar(
                inputs.worktree(), deadline, Command.SHARED_INDEX, null, null);
        requireSuccess(sharedIndex, FailureCode.COMMAND_FAILED);
        if (!sharedIndex.stdout().isEmpty()) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
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
        requireSuccess(head, FailureCode.COMMAND_FAILED);
        requireSuccess(branchHead, FailureCode.BRANCH_HEAD_MISMATCH);
        String actualHead = requireObservedSha(head.stdout());
        String expectedBranchHead = requireObservedSha(branchHead.stdout());

        AttachmentState attachment;
        if (branch.exitCode() == 0) {
            if (!branch.stdout().equals(inputs.expectedBranch())) {
                throw failure(FailureCode.WRONG_BRANCH);
            }
            if (!actualHead.equals(expectedBranchHead)) {
                throw failure(FailureCode.BRANCH_HEAD_MISMATCH);
            }
            attachment = AttachmentState.ATTACHED;
        }
        else if (branch.exitCode() == 1
                && control.permitsDetachedHead()) {
            attachment = AttachmentState.DETACHED;
        }
        else if (branch.exitCode() == 1) {
            throw failure(FailureCode.DETACHED_HEAD);
        }
        else {
            throw failure(FailureCode.COMMAND_FAILED);
        }

        CommandResult indexStages = runGit(
                inputs.worktree(), deadline, Command.INDEX_STAGES,
                null, null, OutputMode.GITLINKS);
        requireSafeProbe(indexStages);
        CommandResult hiddenFlags = runGit(
                inputs.worktree(), deadline, Command.INDEX_FLAGS,
                null, null, OutputMode.HIDDEN_FLAGS);
        requireSafeProbe(hiddenFlags);
        CommandResult status = runGit(
                inputs.worktree(), deadline, Command.STATUS_V2,
                null, null, OutputMode.HASH);
        CommandResult untrackedPaths = runGit(
                inputs.worktree(), deadline, Command.UNTRACKED_PATHS,
                null, null, OutputMode.BYTES);
        CommandResult trackedPaths = runGit(
                inputs.worktree(), deadline, Command.TRACKED_PATHS,
                null, null, OutputMode.BYTES);
        CommandResult headGitlinks = runGit(
                inputs.worktree(), deadline, Command.HEAD_ENTRIES,
                inputs.predecessorSha(), null, OutputMode.GITLINKS);
        requireSafeProbe(headGitlinks);
        CommandResult headPaths = runGit(
                inputs.worktree(), deadline, Command.HEAD_PATHS,
                inputs.predecessorSha(), null, OutputMode.BYTES);
        CommandResult currentHeadGitlinks = runGit(
                inputs.worktree(), deadline, Command.HEAD_ENTRIES,
                actualHead, null, OutputMode.GITLINKS);
        requireSafeProbe(currentHeadGitlinks);
        CommandResult currentHeadPaths = runGit(
                inputs.worktree(), deadline, Command.HEAD_PATHS,
                actualHead, null, OutputMode.BYTES);
        requireSuccess(status, FailureCode.COMMAND_FAILED);
        requireSuccess(untrackedPaths, FailureCode.COMMAND_FAILED);
        requireSuccess(trackedPaths, FailureCode.COMMAND_FAILED);
        requireSuccess(headPaths, FailureCode.COMMAND_FAILED);
        requireSuccess(currentHeadPaths, FailureCode.COMMAND_FAILED);
        String untrackedDigest = hashUntracked(
                inputs.worktree(), untrackedPaths.bytes(), deadline);
        String trackedDigest = hashTracked(
                inputs.worktree(),
                trackedPaths.bytes(),
                headPaths.bytes(),
                currentHeadPaths.bytes(),
                deadline);

        CommandResult baseType = scalar(
                inputs.worktree(), deadline, Command.OBJECT_TYPE,
                inputs.baseSha(), null);
        CommandResult predecessorType = scalar(
                inputs.worktree(), deadline, Command.OBJECT_TYPE,
                inputs.predecessorSha(), null);
        CommandResult baseAncestor = scalar(
                inputs.worktree(), deadline, Command.IS_ANCESTOR,
                inputs.baseSha(), expectedBranchHead);
        CommandResult predecessorAncestor = scalar(
                inputs.worktree(), deadline, Command.IS_ANCESTOR,
                inputs.predecessorSha(), expectedBranchHead);
        requireCommit(baseType, FailureCode.BASE_NOT_FOUND);
        requireCommit(predecessorType, FailureCode.PREDECESSOR_NOT_FOUND);
        requireAncestor(baseAncestor, FailureCode.BASE_NOT_ANCESTOR);
        requireAncestor(
                predecessorAncestor, FailureCode.PREDECESSOR_NOT_ANCESTOR);

        NonCleanKind kind;
        if (!control.operations().isEmpty()) {
            kind = NonCleanKind.GIT_OPERATION_IN_PROGRESS;
        }
        else if (status.hadOutput()) {
            kind = NonCleanKind.DIRTY;
        }
        else {
            throw failure(FailureCode.CLEAN);
        }
        String stateDigest = nonCleanDigest(
                actualHead,
                expectedBranchHead,
                attachment,
                kind,
                control,
                safetyConfig.digest(),
                status.digest(),
                indexStages.digest(),
                trackedDigest,
                untrackedDigest);
        return new NonCleanObservation(
                actualHead,
                expectedBranchHead,
                attachment,
                kind,
                control.operations(),
                stateDigest);
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

    private static void requireSafeNonCleanControlState(ControlState state)
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
    }

    private static Path reportedPathUnchecked(Path workingDirectory, String value)
    {
        try {
            return reportedPath(workingDirectory, value);
        }
        catch (IOException | RuntimeException e) {
            throw failure(FailureCode.NOT_WORKTREE);
        }
    }

    private static ControlSnapshot captureControlSnapshot(
            Path gitDirectory, Deadline deadline)
    {
        List<ControlMarker> markers = List.of(
                new ControlMarker(gitDirectory.resolve("MERGE_HEAD"),
                        "gitdir/MERGE_HEAD", GitOperation.MERGE),
                new ControlMarker(gitDirectory.resolve("MERGE_AUTOSTASH"),
                        "gitdir/MERGE_AUTOSTASH", GitOperation.MERGE),
                new ControlMarker(gitDirectory.resolve("AUTO_MERGE"),
                        "gitdir/AUTO_MERGE", GitOperation.MERGE),
                new ControlMarker(gitDirectory.resolve("CHERRY_PICK_HEAD"),
                        "gitdir/CHERRY_PICK_HEAD", GitOperation.CHERRY_PICK),
                new ControlMarker(gitDirectory.resolve("REVERT_HEAD"),
                        "gitdir/REVERT_HEAD", GitOperation.REVERT),
                new ControlMarker(gitDirectory.resolve("REBASE_HEAD"),
                        "gitdir/REBASE_HEAD", GitOperation.REBASE),
                new ControlMarker(gitDirectory.resolve("rebase-merge"),
                        "gitdir/rebase-merge", GitOperation.REBASE),
                new ControlMarker(gitDirectory.resolve("rebase-apply"),
                        "gitdir/rebase-apply", GitOperation.REBASE),
                new ControlMarker(gitDirectory.resolve("sequencer"),
                        "gitdir/sequencer", GitOperation.SEQUENCER),
                new ControlMarker(gitDirectory.resolve("BISECT_START"),
                        "gitdir/BISECT_START", GitOperation.BISECT),
                new ControlMarker(gitDirectory.resolve("BISECT_LOG"),
                        "gitdir/BISECT_LOG", GitOperation.BISECT),
                new ControlMarker(gitDirectory.resolve("BISECT_HEAD"),
                        "gitdir/BISECT_HEAD", GitOperation.BISECT),
                new ControlMarker(gitDirectory.resolve("BISECT_NAMES"),
                        "gitdir/BISECT_NAMES", GitOperation.BISECT),
                new ControlMarker(gitDirectory.resolve("BISECT_TERMS"),
                        "gitdir/BISECT_TERMS", GitOperation.BISECT),
                new ControlMarker(gitDirectory.resolve("refs/bisect"),
                        "gitdir/refs/bisect", GitOperation.BISECT));
        MessageDigest digest = sha256();
        updateField(digest, "domain", "bytequay-git-control-v1");
        EnumSet<GitOperation> operations = EnumSet.noneOf(GitOperation.class);
        for (ControlMarker marker : markers) {
            if (!Files.exists(marker.path(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            operations.add(marker.operation());
        }
        hashControlTree(
                digest,
                gitDirectory,
                gitDirectory,
                "gitdir",
                new ContentBudget(
                        MAX_CONTROL_ENTRIES, MAX_CONTROL_TOTAL_BYTES),
                deadline);
        return new ControlSnapshot(
                List.copyOf(operations),
                HexFormat.of().formatHex(digest.digest()));
    }

    private static void rejectSpecialWorktreeEntries(
            Path worktree, Deadline deadline)
    {
        try {
            List<Path> paths;
            try (var stream = Files.walk(worktree)) {
                paths = stream
                        .peek(path -> requireTime(deadline))
                        .limit(MAX_TRACKED_PATHS + MAX_UNTRACKED_PATHS + 2L)
                        .toList();
            }
            if (paths.size() > MAX_TRACKED_PATHS + MAX_UNTRACKED_PATHS + 1) {
                throw failure(FailureCode.OUTPUT_LIMIT);
            }
            for (Path path : paths) {
                requireTime(deadline);
                requireRoundTrip(worktree.relativize(path));
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()
                        && !attributes.isRegularFile()
                        && !attributes.isSymbolicLink()) {
                    throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
                }
            }
        }
        catch (InspectionFailure failure) {
            throw failure;
        }
        catch (IOException | RuntimeException e) {
            throw failure(FailureCode.MOVED_DURING_INSPECTION);
        }
    }

    private static void hashControlTree(
            MessageDigest digest,
            Path gitDirectory,
            Path rootPath,
            String rootLabel,
            ContentBudget budget,
            Deadline deadline)
    {
        try {
            requireTime(deadline);
            BasicFileAttributes root = Files.readAttributes(
                    rootPath, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (root.isSymbolicLink() || (!root.isRegularFile()
                    && !root.isDirectory())) {
                throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
            }
            if (root.isRegularFile()) {
                hashBoundedPath(
                        digest,
                        gitDirectory,
                        rootPath,
                        rootLabel,
                        MAX_CONTROL_FILE_BYTES,
                        budget,
                        deadline);
                return;
            }
            List<Path> paths;
            try (var stream = Files.walk(rootPath)) {
                paths = stream
                        .peek(path -> requireTime(deadline))
                        .limit(MAX_CONTROL_ENTRIES + 2L)
                        .toList();
            }
            if (paths.size() > MAX_CONTROL_ENTRIES + 1) {
                throw failure(FailureCode.OUTPUT_LIMIT);
            }
            for (Path path : paths) {
                requireRoundTrip(rootPath.relativize(path));
            }
            paths = paths.stream()
                    .sorted(Comparator.comparing(path ->
                            rootPath.relativize(path).toString()))
                    .toList();
            for (Path path : paths) {
                String suffix = rootPath.equals(path)
                        ? ""
                        : "/" + rootPath.relativize(path)
                        .toString().replace(path.getFileSystem().getSeparator(), "/");
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) {
                    budget.entry();
                    updateField(digest, "directory", rootLabel + suffix);
                }
                else if (attributes.isRegularFile()) {
                    hashBoundedPath(
                            digest,
                            gitDirectory,
                            path,
                            rootLabel + suffix,
                            path.equals(gitDirectory.resolve("index"))
                                    ? MAX_INDEX_BYTES
                                    : MAX_CONTROL_FILE_BYTES,
                            budget,
                            deadline);
                }
                else {
                    throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
                }
            }
        }
        catch (InspectionFailure failure) {
            throw failure;
        }
        catch (IOException | RuntimeException e) {
            throw failure(FailureCode.MOVED_DURING_INSPECTION);
        }
    }

    private static String hashUntracked(
            Path worktree, byte[] encodedPaths, Deadline deadline)
    {
        return hashWorktreePaths(
                worktree,
                encodedPaths,
                deadline,
                "bytequay-untracked-v1",
                MAX_UNTRACKED_PATHS,
                MAX_UNTRACKED_FILE_BYTES,
                MAX_UNTRACKED_TOTAL_BYTES,
                false);
    }

    private static String hashTracked(
            Path worktree,
            byte[] indexPaths,
            byte[] predecessorPaths,
            byte[] currentHeadPaths,
            Deadline deadline)
    {
        long combinedLength = (long) indexPaths.length
                + predecessorPaths.length + currentHeadPaths.length;
        if (combinedLength > 3L * PATH_OUTPUT_LIMIT) {
            throw failure(FailureCode.OUTPUT_LIMIT);
        }
        byte[] combined = Arrays.copyOf(
                indexPaths, (int) combinedLength);
        System.arraycopy(
                predecessorPaths,
                0,
                combined,
                indexPaths.length,
                predecessorPaths.length);
        System.arraycopy(
                currentHeadPaths,
                0,
                combined,
                indexPaths.length + predecessorPaths.length,
                currentHeadPaths.length);
        return hashWorktreePaths(
                worktree,
                combined,
                deadline,
                "bytequay-tracked-worktree-v1",
                MAX_TRACKED_PATHS,
                MAX_TRACKED_FILE_BYTES,
                MAX_TRACKED_TOTAL_BYTES,
                true);
    }

    private static String hashWorktreePaths(
            Path worktree,
            byte[] encodedPaths,
            Deadline deadline,
            String domain,
            int maxPaths,
            long perFileLimit,
            long totalLimit,
            boolean permitMissing)
    {
        List<String> paths = decodeZeroTerminatedPaths(encodedPaths, maxPaths);
        MessageDigest digest = sha256();
        updateField(digest, "domain", domain);
        ContentBudget budget = new ContentBudget(maxPaths, totalLimit);
        for (String value : paths) {
            requireTime(deadline);
            Path relative;
            try {
                relative = Path.of(value);
            }
            catch (RuntimeException e) {
                throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
            }
            if (value.isEmpty()
                    || relative.isAbsolute()
                    || !relative.normalize().equals(relative)
                    || relative.startsWith("..")) {
                throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
            }
            Path path = worktree.resolve(relative).normalize();
            if (!path.startsWith(worktree)) {
                throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
            }
            try {
                ParentSnapshot parents = captureParents(
                        worktree, path, permitMissing);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!permitMissing) {
                        throw failure(FailureCode.MOVED_DURING_INSPECTION);
                    }
                    budget.entry();
                    updateField(digest, "missing-path", value);
                    parents.revalidate();
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) {
                    hashBoundedPath(
                            digest,
                            worktree,
                            path,
                            value,
                            perFileLimit,
                            budget,
                            deadline);
                }
                else if (attributes.isSymbolicLink()) {
                    budget.entry();
                    Path target = Files.readSymbolicLink(path);
                    try {
                        if (!Path.of(target.toString()).equals(target)) {
                            throw failure(
                                    FailureCode.UNTRUSTED_REPOSITORY_STATE);
                        }
                    }
                    catch (RuntimeException e) {
                        throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
                    }
                    int targetBytes = target.toString()
                            .getBytes(StandardCharsets.UTF_8).length;
                    if (targetBytes > perFileLimit) {
                        throw failure(FailureCode.OUTPUT_LIMIT);
                    }
                    budget.bytes(targetBytes);
                    updateField(digest, "symlink-path", value);
                    updateField(digest, "symlink-mode", Integer.toString(
                            unixMode(path)));
                    updateField(digest, "symlink-target", target.toString());
                    parents.revalidate();
                }
                else {
                    throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
                }
            }
            catch (InspectionFailure failure) {
                throw failure;
            }
            catch (IOException | RuntimeException e) {
                throw failure(FailureCode.MOVED_DURING_INSPECTION);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void hashBoundedPath(
            MessageDigest digest,
            Path trustedRoot,
            Path path,
            String label,
            long perFileLimit,
            ContentBudget budget,
            Deadline deadline)
            throws IOException
    {
        ParentSnapshot parents = captureParents(trustedRoot, path, false);
        BasicFileAttributes before = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > perFileLimit) {
            throw failure(before.isRegularFile()
                    ? FailureCode.OUTPUT_LIMIT
                    : FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
        int beforeMode = unixMode(path);
        budget.entry();
        budget.bytes(before.size());
        updateField(digest, "file-path", label);
        updateField(digest, "file-mode", Integer.toString(beforeMode));
        updateField(digest, "file-size", Long.toString(before.size()));
        MessageDigest contents = sha256();
        long read = 0;
        try (InputStream input = Files.newInputStream(
                path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                requireTime(deadline);
                if (count == 0) {
                    continue;
                }
                read += count;
                if (read > before.size()) {
                    throw failure(FailureCode.MOVED_DURING_INSPECTION);
                }
                if (read > perFileLimit) {
                    throw failure(FailureCode.OUTPUT_LIMIT);
                }
                contents.update(buffer, 0, count);
            }
        }
        BasicFileAttributes after = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameFileObservation(before, after)
                || beforeMode != unixMode(path)
                || read != before.size()) {
            throw failure(FailureCode.MOVED_DURING_INSPECTION);
        }
        parents.revalidate();
        updateField(digest, "file-content",
                HexFormat.of().formatHex(contents.digest()));
    }

    private static ParentSnapshot captureParents(
            Path root, Path leaf, boolean permitMissing)
            throws IOException
    {
        Path normalizedRoot = root.normalize();
        Path normalizedLeaf = leaf.normalize();
        if (!normalizedLeaf.startsWith(normalizedRoot)) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
        Path relative = normalizedRoot.relativize(normalizedLeaf);
        Path parent = relative.getParent();
        List<ParentEntry> entries = new ArrayList<>();
        Path current = normalizedRoot;
        entries.add(parentEntry(current));
        Path firstMissing = null;
        if (parent != null) {
            for (Path component : parent) {
                current = current.resolve(component);
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (!permitMissing) {
                        throw failure(FailureCode.MOVED_DURING_INSPECTION);
                    }
                    firstMissing = current;
                    break;
                }
                entries.add(parentEntry(current));
            }
        }
        return new ParentSnapshot(entries, firstMissing);
    }

    private static ParentEntry parentEntry(Path path)
            throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
        return new ParentEntry(
                path,
                attributes.fileKey(),
                attributes.lastModifiedTime().toMillis());
    }

    private static boolean sameFileObservation(
            BasicFileAttributes first, BasicFileAttributes second)
    {
        return first.isRegularFile() == second.isRegularFile()
                && first.isSymbolicLink() == second.isSymbolicLink()
                && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && Objects.equals(first.fileKey(), second.fileKey());
    }

    private static int unixMode(Path path)
            throws IOException
    {
        Object mode = Files.getAttribute(
                path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        if (!(mode instanceof Number number)) {
            throw new IOException("Unix mode unavailable");
        }
        return number.intValue() & 07777;
    }

    private static List<String> decodeZeroTerminatedPaths(
            byte[] bytes, int maxUniquePaths)
    {
        if (bytes.length == 0) {
            return List.of();
        }
        Set<String> paths = new HashSet<>();
        int start = 0;
        int records = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != 0) {
                continue;
            }
            records++;
            if (records > 4L * maxUniquePaths) {
                throw failure(FailureCode.OUTPUT_LIMIT);
            }
            paths.add(decodePath(bytes, start, index - start));
            if (paths.size() > maxUniquePaths) {
                throw failure(FailureCode.OUTPUT_LIMIT);
            }
            start = index + 1;
        }
        if (start != bytes.length) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
        return paths.stream().sorted().toList();
    }

    private static String decodePath(byte[] bytes, int offset, int length)
    {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        }
        catch (CharacterCodingException e) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
    }

    private static void requireRoundTrip(Path path)
    {
        try {
            if (!Path.of(path.toString()).equals(path)) {
                throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
            }
        }
        catch (RuntimeException e) {
            throw failure(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        }
    }

    private static String nonCleanDigest(
            String actualHead,
            String branchHead,
            AttachmentState attachment,
            NonCleanKind kind,
            ControlSnapshot control,
            String configDigest,
            String statusDigest,
            String indexDigest,
            String trackedDigest,
            String untrackedDigest)
    {
        MessageDigest digest = sha256();
        digest.update(NON_CLEAN_DOMAIN.getBytes(StandardCharsets.UTF_8));
        updateField(digest, "actual-head", actualHead);
        updateField(digest, "branch-head", branchHead);
        updateField(digest, "attachment", attachment.name());
        updateField(digest, "kind", kind.name());
        updateField(digest, "operations", control.operations().toString());
        updateField(digest, "control", control.digest());
        updateField(digest, "effective-config", configDigest);
        updateField(digest, "porcelain-v2", statusDigest);
        updateField(digest, "index-stages", indexDigest);
        updateField(digest, "tracked-worktree", trackedDigest);
        updateField(digest, "untracked", untrackedDigest);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateField(
            MessageDigest digest, String label, String value)
    {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(labelBytes.length).array());
        digest.update(labelBytes);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(valueBytes.length).array());
        digest.update(valueBytes);
    }

    private static void requireTime(Deadline deadline)
    {
        if (deadline.remainingNanos() <= 0) {
            throw failure(FailureCode.TIMEOUT);
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
                            "BISECT_TERMS",
                            "index.lock")
                    .stream()
                    .anyMatch(marker -> Files.exists(gitDir.resolve(marker)));
            operationInProgress = operationInProgress
                    || Files.exists(gitDir.resolve("refs/bisect"));
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
        Path objects = common.resolve("objects");
        BasicFileAttributes objectAttributes = Files.readAttributes(
                objects, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!objectAttributes.isDirectory()
                || objectAttributes.isSymbolicLink()) {
            return true;
        }
        Path infoDirectory = common.resolve("objects/info");
        if (Files.exists(infoDirectory, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes infoAttributes = Files.readAttributes(
                    infoDirectory,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!infoAttributes.isDirectory() || infoAttributes.isSymbolicLink()) {
                return true;
            }
            Path alternates = infoDirectory.resolve("alternates");
            if (Files.exists(alternates, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(
                            alternates, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(alternates) > 0)) {
                return true;
            }
        }
        Path packDirectory = objects.resolve("pack");
        if (!Files.exists(packDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        BasicFileAttributes packAttributes = Files.readAttributes(
                packDirectory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!packAttributes.isDirectory() || packAttributes.isSymbolicLink()) {
            return true;
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
        Path registrations = common.resolve("worktrees").normalize();
        BasicFileAttributes registrationAttributes = Files.readAttributes(
                registrations,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!registrationAttributes.isDirectory()
                || registrationAttributes.isSymbolicLink()) {
            return false;
        }
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
        Path dotGitTarget = readPointerPath(dotGit, worktree, "gitdir: ");
        if (!dotGitTarget.getParent().equals(registrations)
                || Files.isSymbolicLink(dotGitTarget)) {
            return false;
        }
        Path adminBackpointer = readPointer(
                gitDirectory.resolve("gitdir"), gitDirectory, "");
        Path commonBackpointer = readPointer(
                gitDirectory.resolve("commondir"), gitDirectory, "");
        return dotGitTarget.toRealPath().equals(gitDirectory)
                && adminBackpointer.equals(dotGit.toRealPath())
                && commonBackpointer.equals(common);
    }

    private static Path readPointer(Path file, Path relativeTo, String prefix)
            throws IOException
    {
        return readPointerPath(file, relativeTo, prefix).toRealPath();
    }

    private static Path readPointerPath(
            Path file, Path relativeTo, String prefix)
            throws IOException
    {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(SCALAR_OUTPUT_LIMIT + 1);
        }
        if (bytes.length == 0 || bytes.length > SCALAR_OUTPUT_LIMIT) {
            throw new IOException("invalid bounded Git pointer");
        }
        String value = decodePath(bytes, 0, bytes.length).strip();
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
        return target.normalize().toAbsolutePath();
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

        int outputLimit = switch (outputMode) {
            case SCALAR -> SCALAR_OUTPUT_LIMIT;
            case BYTES -> PATH_OUTPUT_LIMIT;
            case DISCARD -> 0;
            default -> STREAM_OUTPUT_LIMIT;
        };
        OutputDrain stdout = new OutputDrain(
                process.getInputStream(), outputLimit, outputMode);
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
                outputMode == OutputMode.BYTES ? stdout.bytes() : new byte[0],
                stdout.hadOutput(),
                stdout.unsafeOutput(),
                stdout.digest());
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

    /**
     * Observational value only. Callers can construct this record, so it must
     * never authorize persistence, admission, cleanup, or reservation. The
     * runtime owner must later wrap a fresh inspection in a private token and
     * revalidate its exact claim, fence, run, Task epoch, and subject. The
     * digest seals Git-relevant paths from the current index, predecessor and
     * actual HEAD trees, plus all untracked and ignored paths. Oversized cache
     * or build trees fail closed instead of weakening the seal.
     */
    public record NonCleanInspection(
            String actualHeadSha,
            String branchHeadSha,
            AttachmentState attachmentState,
            NonCleanKind kind,
            List<GitOperation> operations,
            String stateDigest)
    {
        public NonCleanInspection
        {
            requireNonNull(actualHeadSha, "actualHeadSha is null");
            requireNonNull(branchHeadSha, "branchHeadSha is null");
            requireNonNull(attachmentState, "attachmentState is null");
            requireNonNull(kind, "kind is null");
            operations = List.copyOf(operations);
            requireNonNull(stateDigest, "stateDigest is null");
        }
    }

    public enum AttachmentState
    {
        ATTACHED,
        DETACHED
    }

    public enum NonCleanKind
    {
        DIRTY,
        GIT_OPERATION_IN_PROGRESS
    }

    public enum GitOperation
    {
        MERGE,
        CHERRY_PICK,
        REVERT,
        REBASE,
        SEQUENCER,
        BISECT
    }

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
        CLEAN,
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
        BYTES,
        HASH,
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
        STATUS_V2,
        TRACKED_PATHS,
        UNTRACKED_PATHS,
        SHARED_INDEX,
        HEAD_ENTRIES,
        HEAD_PATHS,
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
                case STATUS_V2 -> List.of(
                        "status",
                        "--porcelain=v2",
                        "-z",
                        "--untracked-files=all",
                        "--ignore-submodules=all");
                case UNTRACKED_PATHS -> List.of(
                        "ls-files", "--others", "-z");
                case TRACKED_PATHS -> List.of("ls-files", "-z");
                case SHARED_INDEX -> List.of(
                        "rev-parse", "--shared-index-path");
                case HEAD_ENTRIES -> List.of("ls-tree", "-r", "-z", first);
                case HEAD_PATHS -> List.of(
                        "ls-tree", "-r", "--name-only", "-z", first);
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
            byte[] bytes,
            boolean hadOutput,
            boolean unsafeOutput,
            String digest)
    {
        private CommandResult
        {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes()
        {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof CommandResult result
                    && exitCode == result.exitCode
                    && stdout.equals(result.stdout)
                    && Arrays.equals(bytes, result.bytes)
                    && hadOutput == result.hadOutput
                    && unsafeOutput == result.unsafeOutput
                    && digest.equals(result.digest);
        }

        @Override
        public int hashCode()
        {
            int hash = Objects.hash(
                    exitCode, stdout, hadOutput, unsafeOutput, digest);
            return 31 * hash + Arrays.hashCode(bytes);
        }
    }

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

    private record ControlMarker(
            Path path, String label, GitOperation operation) {}

    private record ControlSnapshot(
            List<GitOperation> operations, String digest)
    {
        private ControlSnapshot
        {
            operations = List.copyOf(operations);
        }

        private boolean permitsDetachedHead()
        {
            return !operations.isEmpty();
        }
    }

    private record NonCleanObservation(
            String actualHeadSha,
            String branchHeadSha,
            AttachmentState attachmentState,
            NonCleanKind kind,
            List<GitOperation> operations,
            String stateDigest)
    {
        private NonCleanObservation
        {
            operations = List.copyOf(operations);
        }
    }

    private record ParentEntry(
            Path path, Object fileKey, long lastModifiedMillis) {}

    private record ParentSnapshot(List<ParentEntry> entries, Path firstMissing)
    {
        private ParentSnapshot
        {
            entries = List.copyOf(entries);
        }

        private void revalidate()
                throws IOException
        {
            for (ParentEntry expected : entries) {
                ParentEntry actual = parentEntry(expected.path());
                if (!Objects.equals(expected.fileKey(), actual.fileKey())
                        || expected.lastModifiedMillis()
                        != actual.lastModifiedMillis()) {
                    throw failure(FailureCode.MOVED_DURING_INSPECTION);
                }
            }
            if (firstMissing != null
                    && Files.exists(firstMissing, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(FailureCode.MOVED_DURING_INSPECTION);
            }
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

    private static final class ContentBudget
    {
        private final int maxEntries;
        private final long maxBytes;
        private int entries;
        private long bytes;

        private ContentBudget(int maxEntries, long maxBytes)
        {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        private void entry()
        {
            entries++;
            if (entries > maxEntries) {
                throw failure(FailureCode.OUTPUT_LIMIT);
            }
        }

        private void bytes(long added)
        {
            if (added < 0 || bytes > maxBytes - added) {
                throw failure(FailureCode.OUTPUT_LIMIT);
            }
            bytes += added;
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
        private final MessageDigest digest = sha256();

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
                    digest.update(buffer, 0, read);
                    if ((mode == OutputMode.SCALAR || mode == OutputMode.BYTES)
                            && !overflow) {
                        output.write(buffer, 0, read);
                    }
                    inspectRecords(buffer, read);
                    if (total <= limit) {
                        total += read;
                    }
                }
            }
            catch (IOException | RuntimeException e) {
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
                String entry = decodePath(bytes, 0, bytes.length);
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
            if (key.equals("core.sparsecheckout")
                    || key.equals("core.sparsecheckoutcone")
                    || key.equals("index.sparse")
                    || key.equals("rerere.enabled")
                    || key.equals("rerere.autoupdate")) {
                return !List.of("false", "no", "off", "0", "")
                        .contains(value.toLowerCase(Locale.ROOT));
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
            byte[] bytes = output.toByteArray();
            return decodePath(bytes, 0, bytes.length);
        }

        private byte[] bytes()
        {
            return output.toByteArray();
        }

        private String digest()
        {
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
