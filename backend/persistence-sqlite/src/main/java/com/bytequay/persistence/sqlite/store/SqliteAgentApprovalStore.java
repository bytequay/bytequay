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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.persistence.sqlite.entity.AgentApprovalEntity;
import com.bytequay.persistence.sqlite.repository.AgentApprovalJpaRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapConstraint;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAgentApprovalStore
        implements AgentApprovalStore
{
    private final AgentApprovalJpaRepository repository;

    public SqliteAgentApprovalStore(AgentApprovalJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public void insert(AgentApproval approval)
    {
        requireNonNull(approval, "approval is null");
        if (repository.existsById(approval.request().id().toString())) {
            throw new AgentConflictException("Approval already exists: " + approval.request().id());
        }
        try {
            repository.saveAndFlush(AgentApprovalEntity.fromDomain(approval));
        }
        catch (DataAccessException e) {
            throw mapConstraint("Approval conflicts with durable state: " + approval.request().id(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentApproval> find(ApprovalId approvalId)
    {
        requireNonNull(approvalId, "approvalId is null");
        return repository.findById(approvalId.toString()).map(AgentApprovalEntity::toDomain);
    }

    @Override
    @Transactional
    public boolean resolve(
            ApprovalId approvalId,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        requireNonNull(approvalId, "approvalId is null");
        requireNonNull(decision, "decision is null");
        requireNonNull(outcome, "outcome is null");
        checkArgument(decision.approvalId().equals(approvalId), "decision belongs to another approval");
        checkArgument(matches(decision, outcome), "decision and outcome do not match");
        try {
            return repository.resolvePending(
                    approvalId.toString(),
                    decision.decision(),
                    decision.scopedPermission().orElse(null),
                    decision.forTask(),
                    decision.resolvedAt(),
                    decision.timedOut(),
                    outcome) == 1;
        }
        catch (DataAccessException e) {
            throw new AgentStorageException("Cannot resolve approval " + approvalId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentApproval> listPending(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return repository.findByTurnIdAndDecisionIsNullOrderByRequestedAtAscIdAsc(turnId.toString()).stream()
                .map(AgentApprovalEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentApproval> listPending()
    {
        return repository.findByDecisionIsNullOrderByRequestedAtAscIdAsc().stream()
                .map(AgentApprovalEntity::toDomain)
                .collect(toImmutableList());
    }

    private static boolean matches(
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        return switch (outcome) {
            case ALLOWED -> decision.decision() == ApprovalDecision.Decision.ALLOW && !decision.timedOut();
            case DENIED, CANCELLED -> decision.decision() == ApprovalDecision.Decision.DENY && !decision.timedOut();
            case EXPIRED -> decision.decision() == ApprovalDecision.Decision.DENY && decision.timedOut();
        };
    }
}
