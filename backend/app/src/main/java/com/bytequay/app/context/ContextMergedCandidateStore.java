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
package com.bytequay.app.context;

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Bounded, durable task-to-PR links and the exact bundle accepted for one history-learning request. */
@Component
public class ContextMergedCandidateStore
{
    static final String PREFIX = "workspace.repositoryContextMergedCandidates.";
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public ContextMergedCandidateStore(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public State get(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Saved candidate links exceed 1 MiB");
                State state = requireNonNull(mapper.readValue(json, State.class), "Saved candidate links are null");
                checkArgument(state.workspaceId().equals(workspaceId), "Candidate links belong to another workspace");
                return state;
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved candidate links cannot be read", failure);
            }
        }).orElseGet(() -> new State(workspaceId, ImmutableList.of(), Optional.empty()));
    }

    public State save(State expected, State replacement)
    {
        checkArgument(expected.workspaceId().equals(replacement.workspaceId()), "Candidate workspace changed");
        return WorkspaceOperationLocks.withLock(expected.workspaceId(), () -> {
            checkArgument(get(expected.workspaceId()).equals(expected), "Candidate links changed; inspect current list");
            try {
                String json = mapper.writeValueAsString(replacement);
                checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Candidate links exceed 1 MiB");
                settings.put(PREFIX + expected.workspaceId(), json);
                return replacement;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Candidate links cannot be saved", failure);
            }
        });
    }

    public record State(String workspaceId, List<Link> links, Optional<Selection> selection)
    {
        public State
        {
            ContextLimits.text(workspaceId, "workspaceId", 200);
            links = ImmutableList.copyOf(links);
            ContextLimits.unique(links.stream().map(Link::id).toList(), "candidate links", 50);
            requireNonNull(selection, "selection is null");
        }
    }

    public record Link(String id, String repo, int number, String taskId, RepositoryIdentity physicalRepository,
            boolean verifiedPublication, Instant observedAt)
    {
        public Link
        {
            ContextLimits.text(id, "candidate id", 200);
            ContextLimits.text(repo, "candidate repository", 200);
            checkArgument(number > 0, "Invalid candidate PR number");
            ContextLimits.text(taskId, "taskId", 200);
            requireNonNull(physicalRepository, "physicalRepository is null");
            requireNonNull(observedAt, "observedAt is null");
        }
    }

    public record Selection(String requestKey, long expectedRevision, long expectedBuildSequence, ContextMergedCandidate candidate)
    {
        public Selection
        {
            ContextLimits.text(requestKey, "requestKey", 100);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Invalid selected request revision");
            requireNonNull(candidate, "candidate is null");
        }
    }
}
