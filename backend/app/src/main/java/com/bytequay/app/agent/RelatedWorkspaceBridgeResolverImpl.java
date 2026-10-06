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

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.RelatedWorkspaceBridge;
import com.bytequay.app.backport.BackportChainService;
import com.bytequay.app.json.JsonContracts;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Component
public class RelatedWorkspaceBridgeResolverImpl
        implements BridgeResolver<RelatedWorkspaceBridge>
{
    private static final ObjectMapper REQUESTS = JsonContracts.mapper();
    private final ObjectProvider<RelatedWorkspaceWork> services;
    private final AgentTurnStore turns;
    private final ObjectProvider<BackportChainService> chains;
    private final ObjectMapper mapper;

    public RelatedWorkspaceBridgeResolverImpl(ObjectProvider<RelatedWorkspaceWork> services, AgentTurnStore turns, ObjectMapper mapper, ObjectProvider<BackportChainService> chains)
    {
        this.services = requireNonNull(services, "services is null");
        this.chains = requireNonNull(chains, "chains is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public Optional<RelatedWorkspaceBridge> resolve(String taskId)
    {
        return services.getObject().eligible(taskId)
                // App Server cannot add dynamic root tools to a previously created thread.
                .filter(task -> task.laneId().usesClaude() || task.sessionReference().stream().noneMatch(reference -> reference.contractVersion() == 1))
                .flatMap(task -> turns.findActive(TaskId.valueOf(taskId)))
                .map(turn -> new RelatedWorkspaceBridge(json -> call(taskId, turn.id(), json)));
    }

    private String call(String taskId, TurnId turnId, String json)
    {
        try {
            Request request = REQUESTS.readValue(json, Request.class);
            checkArgument(request != null, "Expected an object");
            Object result = switch (request) {
                case Read read -> services.getObject().read(taskId, turnId, read.operationKey());
                case DiscoverBackport ignored -> chains.getObject().discover(taskId, turnId);
                case ReadChain read -> chains.getObject().read(taskId, turnId, read.chainId());
                case Start start -> services.getObject().start(taskId, turnId, start.request());
                case SaveChain save -> chains.getObject().save(taskId, turnId, save.request(taskId));
            };
            return mapper.writeValueAsString(result);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid related work request", failure);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Read.class, name = "READ"),
            @JsonSubTypes.Type(value = DiscoverBackport.class, name = "DISCOVER_BACKPORT"),
            @JsonSubTypes.Type(value = ReadChain.class, name = "READ_CHAIN"),
            @JsonSubTypes.Type(value = Start.class, name = "START"),
            @JsonSubTypes.Type(value = SaveChain.class, name = "SAVE_CHAIN")})
    private sealed interface Request permits Read, DiscoverBackport, ReadChain, Start, SaveChain {}

    private record Read(String operationKey) implements Request
    {
        private Read
        {
            checkText(operationKey, "operationKey", 100);
        }
    }

    private record DiscoverBackport(String operationKey) implements Request
    {
        private DiscoverBackport
        {
            checkText(operationKey, "operationKey", 100);
        }
    }

    private record ReadChain(String operationKey, String chainId) implements Request
    {
        private ReadChain
        {
            checkText(operationKey, "operationKey", 100);
            checkText(chainId, "chainId", 100);
        }
    }

    private record Start(String operationKey, String workspaceId, RelatedWorkspaceWork.Purpose purpose, String question, Optional<String> commit,
            Optional<String> revision) implements Request
    {
        private Start
        {
            // Reuse the service's admission contract before dispatch, including its purpose/commit relationship.
            new RelatedWorkspaceWork.Request(operationKey, workspaceId, purpose, question, commit, revision);
        }

        RelatedWorkspaceWork.Request request()
        {
            return new RelatedWorkspaceWork.Request(operationKey, workspaceId, purpose, question, commit, revision);
        }
    }

    private record SaveChain(String operationKey, Optional<String> sourceTaskId, Optional<String> chainId, List<String> destinations,
            Optional<BackportChainService.SourcePullRequest> sourcePullRequest, Optional<List<Integer>> sourceHops) implements Request
    {
        private SaveChain
        {
            checkText(operationKey, "operationKey", 100);
            requireNonNull(sourceTaskId, "sourceTaskId is null");
            sourceTaskId.ifPresent(value -> checkText(value, "sourceTaskId", 100));
            requireNonNull(sourcePullRequest, "sourcePullRequest is null");
            checkArgument(sourceTaskId.isEmpty() || sourcePullRequest.isEmpty(), "Choose a source conversation or an existing PR");
            requireNonNull(chainId, "chainId is null");
            chainId.ifPresent(value -> checkText(value, "chainId", 100));
            destinations = ImmutableList.copyOf(destinations);
            checkArgument(!destinations.isEmpty() && destinations.size() <= 20, "Choose 1 to 20 destinations");
            destinations.forEach(value -> checkText(value, "destination", 100));
            requireNonNull(sourceHops, "sourceHops is null");
            sourceHops = sourceHops.map(ImmutableList::copyOf);
            checkArgument(sourceHops.isEmpty() || sourceHops.orElseThrow().size() == destinations.size(), "Each destination needs a source hop");
        }

        BackportChainService.Request request(String taskId)
        {
            Optional<String> source = sourcePullRequest.isPresent() ? sourceTaskId : sourceTaskId.or(() -> Optional.of(taskId));
            return new BackportChainService.Request(operationKey, source, chainId, destinations, sourcePullRequest, sourceHops);
        }
    }
}
