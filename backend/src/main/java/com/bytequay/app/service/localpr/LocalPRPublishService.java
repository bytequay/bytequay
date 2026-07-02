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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * The user-gated push transition (local → remote). This is the one place the
 * local PR touches GitHub: it pushes the branch to origin and opens a Draft
 * PR, then hands off to {@link LocalPRService#recordPush} which strips the
 * private local record (design #47) and flips the status. Agents never reach
 * here — push is user-initiated through {@code LocalPRController}.
 *
 * <p>A local PR pushes to its own origin and opens against origin's base, so
 * (unlike the fork-aware {@code PublishService}) there is no cross-fork head:
 * the head is the bare branch name.
 */
@Service
public class LocalPRPublishService
{
    private static final String LINKED_STATUS_DRAFT = "draft";

    private final LocalPRService localPr;
    private final TaskStore taskStore;
    private final GitRunner git;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;

    public LocalPRPublishService(
            LocalPRService localPr,
            TaskStore taskStore,
            GitRunner git,
            PullRequestRepository pullRequests,
            PatResolver patResolver)
    {
        this.localPr = requireNonNull(localPr, "localPr is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
    }

    /** Push {@code prId}'s branch and open a Draft PR, then strip locals + flip
     *  {@code local-open → remote-drafted}. */
    public LocalPR push(String prId)
    {
        LocalPR pr = localPr.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (!LocalPR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "local PR " + prId + " is not ready to push (status=" + pr.status() + ")");
        }
        Task task = taskStore.findTaskById(pr.taskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no task for local PR " + prId));
        if (task.workingDir() == null || task.workingDir().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task " + task.id() + " has no working dir");
        }
        RepoRef repo = remoteSlug(task);
        pushBranch(task, pr.branchName());
        String pat = patResolver.resolve(repo.owner() + "/" + repo.repo());
        PullRequest opened = pullRequests.createPullRequest(
                pat, repo, CreatePullRequestCommand.draft(pr.branchName(), pr.baseBranch(), pr.title(), pr.description()));
        if (opened == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub did not return the opened PR");
        }
        // Mirror the push onto the task row so the rest of the app sees the
        // pushed/linked state, then strip locals + flip the local PR status.
        taskStore.markPushed(task.id(), Instant.now());
        taskStore.linkPullRequest(task.id(), opened.number(), LINKED_STATUS_DRAFT);
        taskStore.linkTaskToPr(task.id(), opened.repo() + "#" + opened.number());
        return localPr.recordPush(prId, opened.number(), opened.htmlUrl());
    }

    private RepoRef remoteSlug(Task task)
    {
        try {
            return git.remoteSlug(Path.of(task.workingDir()), "origin")
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "could not resolve origin repo for task " + task.id()));
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "reading origin remote failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted resolving origin remote");
        }
    }

    /** Push the branch from the task worktree unless it is already on origin. */
    private void pushBranch(Task task, String branch)
    {
        String worktreePath = task.worktreePath() == null || task.worktreePath().isBlank()
                ? task.workingDir() : task.worktreePath();
        Path worktree = Path.of(worktreePath);
        try {
            if (git.refExists(worktree, "origin/" + branch)) {
                return;
            }
            git.push(worktree);
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "git push failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "git push interrupted");
        }
    }
}
