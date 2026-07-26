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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

/** Issue detail and exact-prompt start-work behavior. */
@Service
public class WorkspaceIssueService
{
    private final WorkspaceRepositoryResolver resolver;
    private final RepoService repos;
    private final ThreadService threads;
    private final JdbcTemplate jdbc;
    private final WorkModelResolver workModels;

    public WorkspaceIssueService(
            WorkspaceRepositoryResolver resolver,
            RepoService repos,
            ThreadService threads,
            JdbcTemplate jdbc,
            WorkModelResolver workModels)
    {
        this.resolver = requireNonNull(resolver, "resolver is null");
        this.repos = requireNonNull(repos, "repos is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.workModels = requireNonNull(workModels, "workModels is null");
    }

    public IssueDetail readFresh(String workspaceId, int number)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return repos.getIssueDetail(repo.owner(), repo.repo(), number);
    }

    @Transactional
    public StartIssueResult start(
            String workspaceId,
            int number,
            String requestedThreadId)
    {
        Thread thread = resolveTrunk(workspaceId, number, requestedThreadId);
        link(workspaceId, number, thread.id());
        String turnId = threads.sendTrunk(
                thread.id(), "Work on issue #" + number);
        return new StartIssueResult(thread.id(), turnId, number);
    }

    /**
     * Resolves the issue's owning trunk without scheduling work. This is used
     * when the issue is parked in the backlog: creating the relationship must
     * not imply that the agent has been asked to begin.
     */
    @Transactional
    public String linkToTrunk(
            String workspaceId,
            int number,
            String requestedThreadId)
    {
        Thread thread = resolveTrunk(workspaceId, number, requestedThreadId);
        link(workspaceId, number, thread.id());
        return thread.id();
    }

    private Thread resolveTrunk(
            String workspaceId,
            int number,
            String requestedThreadId)
    {
        if (number <= 0) {
            throw new IllegalArgumentException("issue number must be positive");
        }
        return requestedThreadId == null || requestedThreadId.isBlank()
                ? createTrunk(workspaceId, number)
                : threads.find(requestedThreadId)
                        .filter(candidate -> workspaceId.equals(candidate.workspaceId()))
                        .orElseThrow(() -> new NoSuchElementException(
                                "no trunk in workspace: " + requestedThreadId));
    }

    private void link(String workspaceId, int number, String threadId)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO workspace_issue_trunk (
                    workspace_id, issue_number, thread_id, created_at_ms)
                VALUES (?, ?, ?, ?)
                """,
                workspaceId, number, threadId, Instant.now().toEpochMilli());
    }

    public List<String> linkedTrunks(String workspaceId, int number)
    {
        return jdbc.queryForList("""
                SELECT thread_id
                FROM workspace_issue_trunk
                WHERE workspace_id = ? AND issue_number = ?
                ORDER BY created_at_ms
                """, String.class, workspaceId, number);
    }

    private Thread createTrunk(String workspaceId, int number)
    {
        IssueDetail issue = readFresh(workspaceId, number);
        // Stamp the row from the workspace's planning engine — the same
        // thing the registry will spawn for this trunk's turns.
        WorkModel workModel = workModels
                .resolveForWorkspace(workspaceId, SessionAudience.PLAN).choice();
        return threads.create(new ThreadService.NewTaskRequest(
                workModel.kind() == WorkModelKind.API
                        ? ThreadKind.LOGIC_LOOP
                        : ThreadKind.CLI_AGENT,
                workModel.agentOrProvider(),
                workModel.model(),
                issue.title(),
                null,
                null,
                null,
                List.of(),
                null,
                null,
                number,
                ThreadFlow.BUILD,
                workspaceId,
                // No scope override: the workspace owns the engine, and this
                // trunk has no reason to dial its own reasoning effort.
                null));
    }

    public record StartIssueResult(
            String trunkId,
            String turnId,
            int issueNumber) {}
}
