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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewTurnPrompt;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.review.ReviewProviderEndpoints.AgentLaunch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY;
import static java.util.Objects.requireNonNull;

/** Admits and controls durable V2 review seats; it never runs provider work. */
public final class ReviewAssignmentTurnRuntime
{
    private static final int PAYLOAD_VERSION = 1;

    private final Store store;
    private final ReviewProviderEndpoints providers;
    private final TicketControl tickets;
    private final ObjectMapper json;
    private final Clock clock;
    private final int serverPort;
    private final Supplier<String> ids;

    public ReviewAssignmentTurnRuntime(
            Store store,
            ReviewProviderEndpoints providers,
            TicketControl tickets,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this(store, providers, tickets, json, clock, serverPort,
                () -> UUID.randomUUID().toString());
    }

    ReviewAssignmentTurnRuntime(
            Store store,
            ReviewProviderEndpoints providers,
            TicketControl tickets,
            ObjectMapper json,
            Clock clock,
            int serverPort,
            Supplier<String> ids)
    {
        this.store = requireNonNull(store, "store is null");
        this.providers = requireNonNull(providers, "providers is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65_535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
        this.ids = requireNonNull(ids, "ids is null");
    }

    public void admit(
            String roundId,
            String startCommit,
            List<Seat> seats)
    {
        requireText(roundId, "roundId");
        requireText(startCommit, "startCommit");
        requireNonNull(seats, "seats is null");
        if (seats.isEmpty()) {
            throw new IllegalArgumentException("a review round needs at least one seat");
        }
        List<Admission> admissions = seats.stream()
                .map(seat -> admission(startCommit, seat, 1))
                .toList();
        store.admitRound(roundId, startCommit, admissions, clock.instant());
    }

    public String retryAssignment(String assignmentId)
    {
        requireText(assignmentId, "assignmentId");
        RetryCandidate candidate = store.retryCandidate(assignmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "review assignment has no exact failed Turn to retry"));
        AgentTurnOperationHandler.LaunchInput oldLaunch;
        try {
            oldLaunch = json.readValue(
                    candidate.launchInput(), AgentTurnOperationHandler.LaunchInput.class);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("stored review launch input is invalid", e);
        }
        String turnId = id("review-turn");
        String operationId = id("review-operation");
        String ticketId = id("review-ticket");
        AgentTurnProviderSession.OwnerToolEndpoint endpoint = endpoint(turnId, operationId);
        AgentTurnOperationHandler.LaunchInput launch = new AgentTurnOperationHandler.LaunchInput(
                PAYLOAD_VERSION, oldLaunch.transport(), oldLaunch.provider(),
                oldLaunch.credentialAccount(), oldLaunch.model(), oldLaunch.reasoningEffort(),
                oldLaunch.workingDirectory(), oldLaunch.systemPrompt(), oldLaunch.prompt(), endpoint);
        Admission admission = new Admission(
                turnId, operationId, ticketId, assignmentId, "investigate",
                candidate.attempt() + 1, candidate.startCommit(),
                oldLaunch.transport(), encode(launch));
        store.retry(admission, clock.instant());
        return turnId;
    }

    public void cancelRound(String roundId)
    {
        requireText(roundId, "roundId");
        store.ticketIds(roundId).forEach(tickets::requestCancel);
    }

    public boolean ownsRound(String roundId)
    {
        requireText(roundId, "roundId");
        return store.ownsRound(roundId);
    }

    private Admission admission(
            String startCommit,
            Seat seat,
            int attempt)
    {
        requireNonNull(seat, "seat is null");
        String turnId = id("review-turn");
        String operationId = id("review-operation");
        String ticketId = id("review-ticket");
        AgentLaunch provider = providers.freeze(seat.provider());
        Path workingDirectory = seat.workingDirectory().toAbsolutePath().normalize();
        AgentTurnOperationHandler.LaunchInput launch = new AgentTurnOperationHandler.LaunchInput(
                PAYLOAD_VERSION, provider.transport(), provider.provider(),
                provider.credentialAccount(), provider.model(), null,
                workingDirectory.toString(), seat.prompt().systemPrompt(),
                seat.prompt().prompt(), endpoint(turnId, operationId));
        return new Admission(
                turnId, operationId, ticketId, seat.assignmentId(), "investigate",
                attempt, startCommit, provider.transport(), encode(launch));
    }

    private AgentTurnProviderSession.OwnerToolEndpoint endpoint(
            String turnId, String operationId)
    {
        String url = "http://127.0.0.1:" + serverPort
                + "/api/v2/review-assignment-turns/" + turnId
                + "/operations/" + operationId + "/mcp";
        return new AgentTurnProviderSession.OwnerToolEndpoint(
                "bytequay", url,
                DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                turnId, operationId, REVIEW_ASSIGNMENT_READ_ONLY,
                "mcp__bytequay__approval_prompt");
    }

    private String encode(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not freeze review launch input", e);
        }
    }

    private String id(String prefix)
    {
        return prefix + ":" + requireText(ids.get(), "generated id");
    }

    public interface Store
    {
        void admitRound(
                String roundId,
                String startCommit,
                List<Admission> admissions,
                Instant requestedAt);

        Optional<RetryCandidate> retryCandidate(String assignmentId);

        void retry(Admission admission, Instant requestedAt);

        List<String> ticketIds(String roundId);

        boolean ownsRound(String roundId);
    }

    @FunctionalInterface
    public interface TicketControl
    {
        boolean requestCancel(String ticketId);
    }

    public record Seat(
            String assignmentId,
            ProviderChoice provider,
            Path workingDirectory,
            ReviewTurnPrompt prompt)
    {
        public Seat
        {
            requireText(assignmentId, "assignmentId");
            requireNonNull(provider, "provider is null");
            requireNonNull(workingDirectory, "workingDirectory is null");
            requireNonNull(prompt, "prompt is null");
        }
    }

    public record Admission(
            String turnId,
            String operationId,
            String ticketId,
            String assignmentId,
            String purpose,
            int attempt,
            String startCommit,
            AgentTurnProviderSession.Transport transport,
            String launchInput)
    {
        public Admission
        {
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireText(ticketId, "ticketId");
            requireText(assignmentId, "assignmentId");
            requireText(purpose, "purpose");
            requireText(startCommit, "startCommit");
            requireNonNull(transport, "transport is null");
            requireText(launchInput, "launchInput");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
        }
    }

    public record RetryCandidate(
            String assignmentId,
            String startCommit,
            int attempt,
            String launchInput)
    {
        public RetryCandidate
        {
            requireText(assignmentId, "assignmentId");
            requireText(startCommit, "startCommit");
            requireText(launchInput, "launchInput");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
        }
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
