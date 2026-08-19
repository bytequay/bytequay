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
package com.bytequay.app.cherrypick;

import com.bytequay.app.cherrypick.ConflictPreview.ConflictFile;
import com.bytequay.app.cherrypick.ConflictPreview.Section;
import com.bytequay.app.cherrypick.ConflictPreview.Type;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Simulates the planned picks in a throwaway worktree to find conflicts.
 *
 * <p>The simulation never touches the repository's own worktree, and it never
 * decides anything: it reports scope so the user can choose what to skip.
 */
@Component
class CherryPickConflictScout
{
    private static final Logger log = LoggerFactory.getLogger(CherryPickConflictScout.class);
    private static final int MAX_CONFLICT_FILES = 100;
    private static final int MAX_SECTIONS = 50;
    private static final int MAX_SCANNED_BYTES = 2 * 1024 * 1024;
    private static final String CONFLICT_START = "<<<<<<<";
    private static final String CONFLICT_END = ">>>>>>>";
    private static final List<String> IDENTITY = ImmutableList.of(
            "-c", "user.name=ByteQuay Preview",
            "-c", "user.email=preview@bytequay.invalid");

    private final GitCommandRunner git;

    CherryPickConflictScout(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * Replays the planned commits against the frozen target.
     *
     * @param repository clone that owns the objects
     * @param targetSha frozen target the simulation starts from
     * @param plannedSourceShas commits to attempt, in cherry-pick order
     * @return one preview per planned commit
     */
    Map<String, ConflictPreview> scout(Path repository, String targetSha, List<String> plannedSourceShas)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(targetSha, "targetSha is null");
        requireNonNull(plannedSourceShas, "plannedSourceShas is null");
        if (plannedSourceShas.isEmpty()) {
            return ImmutableMap.of();
        }
        Path worktree = repository.resolve(".git")
                .resolve("bytequay-preview-" + UUID.randomUUID());
        Result created = git.run(repository, "worktree", "add", "--detach", "--quiet",
                worktree.toString(), targetSha);
        if (!created.successful()) {
            log.debug("Cherry-pick conflict preview unavailable: {}", created.output());
            return notAnalyzed(plannedSourceShas);
        }
        try {
            return simulate(worktree, plannedSourceShas);
        }
        finally {
            cleanUp(repository, worktree);
        }
    }

    private Map<String, ConflictPreview> simulate(Path worktree, List<String> plannedSourceShas)
    {
        Map<String, ConflictPreview> previews = new LinkedHashMap<>();
        for (int index = 0; index < plannedSourceShas.size(); index++) {
            String sourceSha = plannedSourceShas.get(index);
            Result pick = git.run(worktree, argv("cherry-pick", "-x", sourceSha));
            if (pick.successful()) {
                previews.put(sourceSha, ConflictPreview.clean());
                continue;
            }
            List<ConflictFile> conflicts = readConflicts(worktree);
            if (conflicts.isEmpty()) {
                if (!stoppedOn(worktree, sourceSha)) {
                    // The pick failed without opening an operation: a missing
                    // object, a bad ref, or something else this simulation
                    // cannot reason about. Do not call that clean.
                    return fillNotAnalyzed(previews, plannedSourceShas, index);
                }
                // Nothing to apply: Git stops the same way for a commit that was
                // empty upstream and one the target already contains. Execution
                // records EMPTY_SKIPPED; preview only needs to keep going.
                previews.put(sourceSha, ConflictPreview.clean());
                if (!git.run(worktree, argv("cherry-pick", "--skip")).successful()) {
                    return fillNotAnalyzed(previews, plannedSourceShas, index + 1);
                }
                continue;
            }
            previews.put(sourceSha, ConflictPreview.conflict(conflicts));
            // Exact simulation ends here: the user's resolution decides what
            // every later commit would meet.
            return fillNotAnalyzed(previews, plannedSourceShas, index + 1);
        }
        return ImmutableMap.copyOf(previews);
    }

    /** True when Git stopped on exactly this commit, which is what makes a skip safe. */
    private boolean stoppedOn(Path worktree, String sourceSha)
    {
        Result head = git.run(worktree, "rev-parse", "--verify", "--quiet", "CHERRY_PICK_HEAD");
        return head.successful() && head.output().strip().equals(sourceSha);
    }

    private List<ConflictFile> readConflicts(Path worktree)
    {
        Result unmerged = git.run(worktree, "ls-files", "-u");
        if (!unmerged.successful() || unmerged.output().isBlank()) {
            return ImmutableList.of();
        }
        Map<String, Set<Integer>> stagesByPath = new LinkedHashMap<>();
        Map<String, String> modeByPath = new LinkedHashMap<>();
        for (String line : unmerged.output().lines().collect(toImmutableList())) {
            // "<mode> <object> <stage>\t<path>"
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String[] fields = line.substring(0, tab).strip().split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            String path = line.substring(tab + 1);
            stagesByPath.computeIfAbsent(path, key -> new LinkedHashSet<>()).add(Integer.parseInt(fields[2]));
            modeByPath.putIfAbsent(path, fields[0]);
        }

        ImmutableList.Builder<ConflictFile> files = ImmutableList.builder();
        int count = 0;
        for (Map.Entry<String, Set<Integer>> entry : stagesByPath.entrySet()) {
            if (++count > MAX_CONFLICT_FILES) {
                break;
            }
            String path = entry.getKey();
            Type type = classify(entry.getValue(), modeByPath.get(path), worktree.resolve(path));
            files.add(new ConflictFile(
                    path,
                    type,
                    module(worktree, path),
                    type == Type.CONTENT ? sections(worktree.resolve(path)) : ImmutableList.of()));
        }
        return files.build();
    }

    private static Type classify(Set<Integer> stages, String mode, Path file)
    {
        if ("160000".equals(mode)) {
            return Type.SUBMODULE;
        }
        if (!stages.contains(1)) {
            return Type.ADD_ADD;
        }
        if (!stages.contains(2) || !stages.contains(3)) {
            return Type.MODIFY_DELETE;
        }
        return binary(file) ? Type.BINARY : Type.CONTENT;
    }

    private static boolean binary(Path file)
    {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_SCANNED_BYTES) {
                return true;
            }
            byte[] content = Files.readAllBytes(file);
            for (byte value : content) {
                if (value == 0) {
                    return true;
                }
            }
            return false;
        }
        catch (IOException e) {
            return true;
        }
    }

    /** Reads real marker positions; it never invents a line number. */
    private static List<Section> sections(Path file)
    {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_SCANNED_BYTES) {
                return ImmutableList.of();
            }
            ImmutableList.Builder<Section> sections = ImmutableList.builder();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = 0;
            int found = 0;
            for (int index = 0; index < lines.size() && found < MAX_SECTIONS; index++) {
                String line = lines.get(index);
                if (line.startsWith(CONFLICT_START)) {
                    start = index + 1;
                }
                else if (line.startsWith(CONFLICT_END) && start > 0) {
                    sections.add(new Section(start, index + 1));
                    start = 0;
                    found++;
                }
            }
            return sections.build();
        }
        catch (IOException | RuntimeException e) {
            return ImmutableList.of();
        }
    }

    private static Optional<String> module(Path worktree, String path)
    {
        Path directory = worktree.resolve(path).getParent();
        while (directory != null && directory.startsWith(worktree) && !directory.equals(worktree)) {
            if (Files.isRegularFile(directory.resolve("pom.xml"))) {
                return Optional.of(worktree.relativize(directory).toString());
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    private void cleanUp(Path repository, Path worktree)
    {
        git.run(worktree, argv("cherry-pick", "--abort"));
        Result removed = git.run(repository, "worktree", "remove", "--force", worktree.toString());
        if (!removed.successful()) {
            log.warn("Cannot remove cherry-pick preview worktree {}: {}", worktree, removed.output());
        }
        git.run(repository, "worktree", "prune");
    }

    private static Map<String, ConflictPreview> notAnalyzed(List<String> sourceShas)
    {
        ImmutableMap.Builder<String, ConflictPreview> previews = ImmutableMap.builder();
        sourceShas.forEach(sha -> previews.put(sha, ConflictPreview.notAnalyzed()));
        return previews.build();
    }

    private static Map<String, ConflictPreview> fillNotAnalyzed(
            Map<String, ConflictPreview> previews,
            List<String> sourceShas,
            int from)
    {
        for (int index = from; index < sourceShas.size(); index++) {
            previews.put(sourceShas.get(index), ConflictPreview.notAnalyzed());
        }
        return ImmutableMap.copyOf(previews);
    }

    private static List<String> argv(String... arguments)
    {
        return ImmutableList.<String>builderWithExpectedSize(IDENTITY.size() + arguments.length)
                .addAll(IDENTITY)
                .add(arguments)
                .build();
    }
}
