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
package com.bytequay.app.developmentflow.task.creation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** One immutable, exactly-shaped reason for creating a V2 Task. */
public sealed interface TaskAssignment
        permits TaskAssignment.NewFromTrunk,
                TaskAssignment.ExistingOwnPr,
                TaskAssignment.ReviewFindings,
                TaskAssignment.Issue,
                TaskAssignment.Automation,
                TaskAssignment.QualityScan
{
    Identity identity();

    Kind kind();

    CreationProvenance provenance();

    BaseSource baseSource();

    enum Kind
    {
        NEW_FROM_TRUNK,
        EXISTING_OWN_PR,
        REVIEW_FINDINGS,
        ISSUE,
        AUTOMATION,
        QUALITY_SCAN,
    }

    enum CreationProvenance
    {
        AGENT_HANDOFF,
        DIRECT_USER,
        ISSUE_MONITOR,
        AUTOMATION,
        QUALITY_SCAN,
        REVIEW_SESSION,
    }

    enum BaseSource
    {
        PLANNING_SNAPSHOT,
        FRESH_REMOTE_BASE,
        EXISTING_PR_HEAD,
    }

    enum RepositoryRoute
    {
        DIRECT,
        FORK,
    }

    record Identity(
            String id,
            String trunkId,
            String creationAuthorizationId,
            String createdBy,
            Instant createdAt)
    {
        public Identity
        {
            requireText(id, "id");
            requireText(trunkId, "trunkId");
            requireText(creationAuthorizationId, "creationAuthorizationId");
            requireText(createdBy, "createdBy");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    sealed interface NewTaskOrigin
            permits AgentHandoff, DirectUser
    {
        CreationProvenance provenance();

        BaseSource baseSource();
    }

    record AgentHandoff(String planningBaseSha)
            implements NewTaskOrigin
    {
        public AgentHandoff
        {
            requireText(planningBaseSha, "planningBaseSha");
        }

        @Override
        public CreationProvenance provenance()
        {
            return CreationProvenance.AGENT_HANDOFF;
        }

        @Override
        public BaseSource baseSource()
        {
            return BaseSource.PLANNING_SNAPSHOT;
        }
    }

    record DirectUser()
            implements NewTaskOrigin
    {
        @Override
        public CreationProvenance provenance()
        {
            return CreationProvenance.DIRECT_USER;
        }

        @Override
        public BaseSource baseSource()
        {
            return BaseSource.FRESH_REMOTE_BASE;
        }
    }

    sealed interface RepositoryRouting
            permits Direct, Fork
    {
        RepositoryRoute route();

        String repositoryId();

        String baseRepositoryId();

        String publishRepositoryId();

        Optional<String> upstreamRepositoryId();
    }

    record Direct(String repositoryId)
            implements RepositoryRouting
    {
        public Direct
        {
            requireText(repositoryId, "repositoryId");
        }

        @Override
        public RepositoryRoute route()
        {
            return RepositoryRoute.DIRECT;
        }

        @Override
        public String baseRepositoryId()
        {
            return repositoryId;
        }

        @Override
        public String publishRepositoryId()
        {
            return repositoryId;
        }

        @Override
        public Optional<String> upstreamRepositoryId()
        {
            return Optional.empty();
        }
    }

    record Fork(String baseRepositoryId, String publishRepositoryId)
            implements RepositoryRouting
    {
        public Fork
        {
            requireText(baseRepositoryId, "baseRepositoryId");
            requireText(publishRepositoryId, "publishRepositoryId");
            if (baseRepositoryId.equalsIgnoreCase(publishRepositoryId)) {
                throw new IllegalArgumentException(
                        "fork base and publish repositories must differ");
            }
        }

        @Override
        public RepositoryRoute route()
        {
            return RepositoryRoute.FORK;
        }

        @Override
        public String repositoryId()
        {
            return publishRepositoryId;
        }

        @Override
        public Optional<String> upstreamRepositoryId()
        {
            return Optional.of(baseRepositoryId);
        }
    }

    record PullRequestRef(
            RepositoryRouting repositories,
            int number,
            String baseRef,
            String headRef,
            String remoteBaseSha,
            String remoteHeadSha)
    {
        public PullRequestRef
        {
            requireNonNull(repositories, "repositories is null");
            if (number <= 0) {
                throw new IllegalArgumentException("number must be positive");
            }
            boolean discovered = baseRef != null || headRef != null
                    || remoteBaseSha != null || remoteHeadSha != null;
            if (discovered) {
                requireText(baseRef, "baseRef");
                requireText(headRef, "headRef");
                requireText(remoteBaseSha, "remoteBaseSha");
                requireText(remoteHeadSha, "remoteHeadSha");
            }
        }

        public boolean discoveryPending()
        {
            return baseRef == null;
        }
    }

    record ReviewFindingRef(
            String sourceReviewId,
            String findingId,
            int findingRevision,
            String contentDigest)
    {
        public ReviewFindingRef
        {
            requireText(sourceReviewId, "sourceReviewId");
            requireText(findingId, "findingId");
            if (findingRevision <= 0) {
                throw new IllegalArgumentException("findingRevision must be positive");
            }
            requireText(contentDigest, "contentDigest");
        }
    }

    record NewFromTrunk(
            Identity identity,
            NewTaskOrigin origin,
            String planSeed,
            String prompt)
            implements TaskAssignment
    {
        public NewFromTrunk
        {
            requireNonNull(identity, "identity is null");
            requireNonNull(origin, "origin is null");
            requireText(planSeed, "planSeed");
            requireText(prompt, "prompt");
        }

        @Override
        public Kind kind()
        {
            return Kind.NEW_FROM_TRUNK;
        }

        @Override
        public CreationProvenance provenance()
        {
            return origin.provenance();
        }

        @Override
        public BaseSource baseSource()
        {
            return origin.baseSource();
        }
    }

    record ExistingOwnPr(Identity identity, PullRequestRef pullRequest)
            implements TaskAssignment
    {
        public ExistingOwnPr
        {
            requireNonNull(identity, "identity is null");
            requireNonNull(pullRequest, "pullRequest is null");
        }

        @Override
        public Kind kind()
        {
            return Kind.EXISTING_OWN_PR;
        }

        @Override
        public CreationProvenance provenance()
        {
            return CreationProvenance.DIRECT_USER;
        }

        @Override
        public BaseSource baseSource()
        {
            return BaseSource.EXISTING_PR_HEAD;
        }
    }

    record ReviewFindings(
            Identity identity,
            String sourceReviewId,
            PullRequestRef pullRequest,
            List<ReviewFindingRef> findings)
            implements TaskAssignment
    {
        public ReviewFindings
        {
            requireNonNull(identity, "identity is null");
            requireText(sourceReviewId, "sourceReviewId");
            requireNonNull(pullRequest, "pullRequest is null");
            findings = List.copyOf(requireNonNull(findings, "findings is null"));
            if (findings.isEmpty()) {
                throw new IllegalArgumentException("findings must not be empty");
            }
            Set<String> findingIds = new HashSet<>();
            for (ReviewFindingRef finding : findings) {
                if (!sourceReviewId.equals(finding.sourceReviewId())) {
                    throw new IllegalArgumentException(
                            "finding source does not match assignment source");
                }
                if (!findingIds.add(finding.findingId())) {
                    throw new IllegalArgumentException("finding ids must be unique");
                }
            }
        }

        @Override
        public Kind kind()
        {
            return Kind.REVIEW_FINDINGS;
        }

        @Override
        public CreationProvenance provenance()
        {
            return CreationProvenance.REVIEW_SESSION;
        }

        @Override
        public BaseSource baseSource()
        {
            return BaseSource.EXISTING_PR_HEAD;
        }
    }

    record Issue(Identity identity, String issueIdentity)
            implements TaskAssignment
    {
        public Issue
        {
            requireNonNull(identity, "identity is null");
            requireText(issueIdentity, "issueIdentity");
        }

        @Override
        public Kind kind()
        {
            return Kind.ISSUE;
        }

        @Override
        public CreationProvenance provenance()
        {
            return CreationProvenance.ISSUE_MONITOR;
        }

        @Override
        public BaseSource baseSource()
        {
            return BaseSource.FRESH_REMOTE_BASE;
        }
    }

    record Automation(Identity identity, String producer, String reason)
            implements TaskAssignment
    {
        public Automation
        {
            requireNonNull(identity, "identity is null");
            requireText(producer, "producer");
            requireText(reason, "reason");
        }

        @Override
        public Kind kind()
        {
            return Kind.AUTOMATION;
        }

        @Override
        public CreationProvenance provenance()
        {
            return CreationProvenance.AUTOMATION;
        }

        @Override
        public BaseSource baseSource()
        {
            return BaseSource.FRESH_REMOTE_BASE;
        }
    }

    record QualityScan(Identity identity, String evidenceIdentity)
            implements TaskAssignment
    {
        public QualityScan
        {
            requireNonNull(identity, "identity is null");
            requireText(evidenceIdentity, "evidenceIdentity");
        }

        @Override
        public Kind kind()
        {
            return Kind.QUALITY_SCAN;
        }

        @Override
        public CreationProvenance provenance()
        {
            return CreationProvenance.QUALITY_SCAN;
        }

        @Override
        public BaseSource baseSource()
        {
            return BaseSource.FRESH_REMOTE_BASE;
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
