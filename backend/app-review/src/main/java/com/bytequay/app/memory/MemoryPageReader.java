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
package com.bytequay.app.memory;

import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.context.RepositoryGuidelineReader;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.memory.Memory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * What the Memory page shows: the memories with the scope paths gone from the monitored branch head, the switches, and the guideline
 * files every turn reads. Local reads only; a read that fails leaves its part empty.
 */
@Component
public class MemoryPageReader
{
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int LISTING_BYTES = 4 * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(MemoryPageReader.class);

    private final MemoryService memories;
    private final ContextBuildService builds;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final RepositoryGuidelineReader guidelines;
    private final MemoryHarvestService harvests;

    public MemoryPageReader(MemoryService memories, ContextBuildService builds, WorkspaceRepositoryService workspaces, GitCommandRunner git,
            RepositoryGuidelineReader guidelines, MemoryHarvestService harvests)
    {
        this.memories = requireNonNull(memories, "memories is null");
        this.builds = requireNonNull(builds, "builds is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.guidelines = requireNonNull(guidelines, "guidelines is null");
        this.harvests = requireNonNull(harvests, "harvests is null");
    }

    public MemoryPage page(String workspaceId)
    {
        List<Memory> list = memories.list(workspaceId);
        Optional<String> head = builds.baseline(workspaceId).map(ContextBuildService.Baseline::sha);
        Set<String> missing = ImmutableSet.of();
        List<GuidelineView> files = ImmutableList.of();
        if (head.isPresent()) {
            try {
                WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
                missing = missing(workspace, head.orElseThrow(), list);
                String blob = "https://github.com/" + workspace.monitoredRepository().fullName() + "/blob/" + head.orElseThrow() + "/";
                files = guidelines.files(workspace.repository(), head.orElseThrow()).stream()
                        .map(file -> new GuidelineView(file.path(), file.bytes(), file.cut(), blob + file.path()))
                        .collect(toImmutableList());
            }
            catch (RuntimeException failure) {
                log.warn("The Memory page of workspace {} shows no scope checks or guidelines", workspaceId, failure);
            }
        }
        Set<String> gone = missing;
        MemoryHarvestService.HarvestState harvest = harvests.state(workspaceId);
        return new MemoryPage(list.stream().map(memory -> new Entry(memory, memory.scope().stream().filter(gone::contains).collect(toImmutableList())))
                .collect(toImmutableList()), memories.switches(workspaceId), files, head, new HarvestView(harvest.cursor(), harvest.last()));
    }

    /** Scope paths without a file or directory at the head; a glob is never reported, since it may match files added later. */
    private Set<String> missing(WorkspaceRepositoryContext workspace, String head, List<Memory> list)
    {
        Set<String> paths = new TreeSet<>();
        list.forEach(memory -> memory.scope().stream()
                .filter(path -> path.chars().noneMatch(character -> character == '*' || character == '?' || character == '[' || character == '{'))
                .forEach(paths::add));
        if (paths.isEmpty()) {
            return ImmutableSet.of();
        }
        // Without -t, a directory drops out of the listing whenever another scope path, such as "dir/" or a file in it, makes ls-tree list its entries instead.
        GitCommandRunner.Result listing = git.runLocalBoundedStdout(workspace.repository(), TIMEOUT, LISTING_BYTES,
                ImmutableList.<String>builder().add("--literal-pathspecs", "ls-tree", "-t", "-z", "--name-only", "--full-tree", head, "--").addAll(paths).build());
        if (!listing.successful() || listing.truncated()) {
            return ImmutableSet.of();
        }
        Set<String> found = ImmutableSet.copyOf(listing.output().split("\0"));
        return paths.stream()
                .filter(path -> !found.contains(path.endsWith("/") ? path.substring(0, path.length() - 1) : path))
                .collect(ImmutableSet.toImmutableSet());
    }

    /** The page's memories in their stored order, newest first. */
    public record MemoryPage(List<Entry> memories, MemorySwitches switches, List<GuidelineView> guidelines, Optional<String> head, HarvestView harvest)
    {
        public MemoryPage
        {
            memories = ImmutableList.copyOf(memories);
            requireNonNull(switches, "switches is null");
            guidelines = ImmutableList.copyOf(guidelines);
            requireNonNull(head, "head is null");
            requireNonNull(harvest, "harvest is null");
        }
    }

    /** How far Learn from my PRs has read, and the latest harvest. */
    public record HarvestView(Optional<MemoryHarvestService.Cursor> cursor, Optional<MemoryHarvestService.LastRun> last)
    {
        public HarvestView
        {
            requireNonNull(cursor, "cursor is null");
            requireNonNull(last, "last is null");
        }
    }

    /** One memory and the paths of its scope that no longer exist at the monitored head. */
    public record Entry(Memory memory, List<String> missingScope)
    {
        public Entry
        {
            requireNonNull(memory, "memory is null");
            missingScope = ImmutableList.copyOf(missingScope);
        }
    }

    public record GuidelineView(String path, long bytes, boolean cut, String url) {}
}
