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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.PullRequestContextBridge;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Reads local data only; callers cannot choose a different task, repository or PR. */
@Component
public class PullRequestContextBridgeResolverImpl
        implements BridgeResolver<PullRequestContextBridge>
{
    private static final int PAGE_CHARACTERS = 12_000;
    private final AgentTaskStore tasks;
    private final PullRequestTaskStore assignments;
    private final WorkspaceStore workspaces;
    private final PullRequestStore rows;
    private final PrDetailStore details;
    private final ObjectMapper mapper;

    public PullRequestContextBridgeResolverImpl(AgentTaskStore tasks, PullRequestTaskStore assignments, WorkspaceStore workspaces,
            PullRequestStore rows, PrDetailStore details, ObjectMapper mapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.rows = requireNonNull(rows, "rows is null");
        this.details = requireNonNull(details, "details is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public Optional<PullRequestContextBridge> resolve(String taskId)
    {
        return scope(taskId).map(ignored -> new PullRequestContextBridge(query -> read(taskId, query)));
    }

    private Optional<PullRequestTask> scope(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId))
                .filter(task -> task.interactive() && task.state() == TaskState.OPEN && task.worktreeRemovedAt().isEmpty())
                .flatMap(task -> assignments.findByTaskId(taskId)
                        .filter(link -> link.taskId().equals(taskId) && task.workspaceId().filter(link.workspaceId()::equals).isPresent()))
                .filter(link -> workspaces.findById(link.workspaceId())
                        .filter(workspace -> workspace.monitoredRepository().fullName().equalsIgnoreCase(link.repo())).isPresent());
    }

    private String read(String taskId, PullRequestContextBridge.Query query)
    {
        PullRequestTask assignment = scope(taskId).orElseThrow(() -> new AgentConflictException("This task no longer has readable PR context."));
        ObjectNode result = mapper.createObjectNode().put("pullRequest", assignment.repo() + "#" + assignment.number())
                .put("section", query.section()).put("referenceOnly", true).put("source", "ByteQuay local storage; no remote request")
                .put("warning", "Historical or cached text may be stale or incomplete. Missing data does not mean no discussion. Verify claims against current code.");
        String text;
        if (query.section().equals("snapshot")) {
            result.put("status", "retained").put("coverage", "Assignment-time excerpts, including historical checks; use CI tools for current checks and logs.");
            text = assignment.initialContext();
        }
        else {
            Optional<Long> row = rows.findIdByRepoAndNumber(assignment.repo(), assignment.number());
            Optional<StoredPrDetail> cached = row.flatMap(details::find);
            PrDetailFreshness freshness = row.map(details::findFreshness).orElseGet(PrDetailFreshness::unknown);
            result.put("status", cached.isPresent() ? "cached" : "missing").put("mayBeStale", true);
            result.set("freshness", mapper.valueToTree(freshness));
            result.put("coverage", "Only data retained in the local cache; page offsets apply to this cached version. Restart at zero if freshness changes.");
            if (cached.isEmpty()) {
                text = "Cached PR context unavailable. Read section snapshot for retained assignment excerpts or refresh the PR in ByteQuay.";
            }
            else {
                StoredPrDetail detail = cached.orElseThrow();
                result.set("unavailableSections", mapper.valueToTree(detail.unavailableSections()));
                result.put("headSha", detail.raw().headSha());
                if (query.section().equals("description")) {
                    text = mapper.createObjectNode().put("title", detail.raw().title()).put("body", detail.raw().body())
                            .put("author", detail.raw().author()).put("state", detail.raw().state())
                            .put("headRef", detail.raw().headRef()).put("baseRef", detail.raw().baseRef()).toString();
                }
                else {
                    result.put("completeWhenCached", detail.timelineComplete() && detail.reviewsComplete());
                    ObjectNode discussion = mapper.createObjectNode();
                    discussion.set("reviews", mapper.valueToTree(detail.reviews()));
                    discussion.set("timeline", mapper.valueToTree(detail.timeline()));
                    text = discussion.toString();
                }
            }
        }
        int start = query.offset();
        checkArgument(start <= text.length(), "offset exceeds this section's length");
        checkArgument(start == 0 || start == text.length() || !Character.isLowSurrogate(text.charAt(start)), "offset splits a character");
        int end = (int) Math.min(text.length(), (long) start + PAGE_CHARACTERS);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        result.put("offset", start).put("totalCharacters", text.length()).put("text", text.substring(start, end));
        if (end < text.length()) {
            result.put("nextOffset", end);
        }
        else {
            result.putNull("nextOffset");
        }
        if (!scope(taskId).filter(assignment::equals).isPresent()) {
            throw new AgentConflictException("The task's PR assignment changed while reading context.");
        }
        return result.toString();
    }
}
