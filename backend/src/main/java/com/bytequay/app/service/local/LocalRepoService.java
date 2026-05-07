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
package com.bytequay.app.service.local;

import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Drives the Repos page. Joins the watched-repos table with the
 * working-tree state of each mapped clone, returning one
 * {@link LocalRepoStatus} per watched repo.
 */
@Service
public class LocalRepoService
{
    private static final Logger log = LoggerFactory.getLogger(LocalRepoService.class);

    private final WatchedRepoStore watchedRepoStore;
    private final GitRunner gitRunner;

    public LocalRepoService(WatchedRepoStore watchedRepoStore, GitRunner gitRunner)
    {
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.gitRunner = requireNonNull(gitRunner, "gitRunner is null");
    }

    /**
     * Returns the local-repo status for every watched repo. Cheap
     * unmapped rows are returned immediately; mapped rows run a small
     * batch of git commands (status --porcelain, rev-parse HEAD)
     * which can take ~50ms each on a large working tree.
     */
    public List<LocalRepoStatus> listAll()
    {
        if (!gitRunner.isAvailable()) {
            return watchedRepoStore.findAll().stream()
                    .map(LocalRepoService::gitUnavailable)
                    .collect(toImmutableList());
        }
        return watchedRepoStore.findAll().stream()
                .map(this::statusOf)
                .collect(toImmutableList());
    }

    private LocalRepoStatus statusOf(WatchedRepo repo)
    {
        if (repo.localClonePath() == null) {
            return LocalRepoStatus.unmapped(repo.owner(), repo.repo());
        }
        Path path = Path.of(repo.localClonePath());
        if (!Files.isDirectory(path)) {
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.MISSING, null, null,
                    "Working copy not found at " + path);
        }
        if (!gitRunner.isGitWorkingTree(path)) {
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.MISSING, null, null,
                    "Path is not a git working tree");
        }
        try {
            int dirty = gitRunner.countDirtyFiles(path);
            String branch = gitRunner.currentBranch(path);
            LocalRepoStatus.State state = dirty == 0 ? LocalRepoStatus.State.CLEAN
                    : LocalRepoStatus.State.MODIFIED;
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    state, branch, dirty, null);
        }
        catch (GitRunner.GitCommandException e) {
            log.warn("git failed on {}: {}", path, e.getMessage());
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null, e.stderr().strip());
        }
        catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("git invocation failed on {}", path, e);
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null, e.getMessage());
        }
    }

    private static LocalRepoStatus gitUnavailable(WatchedRepo repo)
    {
        return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                LocalRepoStatus.State.GIT_UNAVAILABLE, null, null,
                "git not found on PATH — install Xcode Command Line Tools");
    }
}
