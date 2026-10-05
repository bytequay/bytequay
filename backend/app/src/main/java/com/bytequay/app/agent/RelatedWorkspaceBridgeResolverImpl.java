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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Component
public class RelatedWorkspaceBridgeResolverImpl
        implements BridgeResolver<RelatedWorkspaceBridge>
{
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
            var input = mapper.readTree(json);
            checkArgument(input != null && input.isObject(), "Expected an object");
            var allowed = ImmutableSet.of("action", "operationKey", "workspaceId", "purpose", "question", "commit", "revision", "sourceTaskId", "chainId", "destinations");
            input.fieldNames().forEachRemaining(name -> checkArgument(allowed.contains(name), "Unknown field: %s", name));
            checkArgument(input.path("action").isTextual() && input.path("operationKey").isTextual(), "action and operationKey are required");
            var service = services.getObject();
            var result = switch (input.path("action").asText()) {
                case "READ" -> {
                    checkArgument(input.size() == 2, "READ accepts only action and operationKey");
                    yield service.read(taskId, turnId, input.path("operationKey").asText());
                }
                case "SAVE_CHAIN" -> {
                    checkArgument(!input.has("workspaceId") && !input.has("purpose") && !input.has("question") && !input.has("commit") && !input.has("revision"),
                            "SAVE_CHAIN accepts operationKey, sourceTaskId, destinations and optional chainId");
                    ((ObjectNode) input).remove("action");
                    if (!input.has("sourceTaskId")) {
                        ((ObjectNode) input).put("sourceTaskId", taskId);
                    }
                    yield chains.getObject().save(taskId, turnId, mapper.treeToValue(input, BackportChainService.Request.class));
                }
                case "READ_CHAIN" -> {
                    checkArgument(input.size() == 3 && input.path("chainId").isTextual(), "READ_CHAIN accepts action, operationKey and chainId");
                    yield chains.getObject().read(taskId, turnId, input.path("chainId").asText());
                }
                case "START" -> {
                    checkArgument(!input.has("sourceTaskId") && !input.has("chainId") && !input.has("destinations"), "START does not accept chain fields");
                    ((ObjectNode) input).remove("action");
                    yield service.start(taskId, turnId, mapper.treeToValue(input, RelatedWorkspaceWork.Request.class));
                }
                default -> throw new IllegalArgumentException("Choose START, READ, SAVE_CHAIN or READ_CHAIN");
            };
            return mapper.writeValueAsString(result);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid related work request", failure);
        }
    }
}
