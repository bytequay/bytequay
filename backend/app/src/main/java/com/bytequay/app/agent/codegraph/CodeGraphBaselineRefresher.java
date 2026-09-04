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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.CodeGraphException;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

/**
 * Keeps a repository's CodeGraph baseline at the remote default branch.
 *
 * <p>Called after every workspace fetch. It resolves the fetched default-branch commit and hands it
 * to the graph service on its own thread; the service itself decides whether anything changed, so
 * the common no-op costs one {@code rev-parse}. Failures are logged and never reach the fetch.
 */
@Component
public class CodeGraphBaselineRefresher
{
    private static final Logger LOG = LoggerFactory.getLogger(CodeGraphBaselineRefresher.class);

    private final CodeGraphService codeGraph;
    private final WorkspacePaths paths;
    private final GitCommandRunner git;
    private final Executor executor;

    @Autowired
    public CodeGraphBaselineRefresher(CodeGraphService codeGraph, WorkspacePaths paths, GitCommandRunner git)
    {
        this(codeGraph, paths, git, command -> Thread.ofVirtual().name("bytequay-codegraph-baseline").start(command));
    }

    public CodeGraphBaselineRefresher(CodeGraphService codeGraph, WorkspacePaths paths, GitCommandRunner git, Executor executor)
    {
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.git = requireNonNull(git, "git is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /** Schedules a baseline refresh for the workspace's just-fetched default branch; cheap when nothing moved. */
    public void refreshAfterFetch(Workspace workspace)
    {
        requireNonNull(workspace, "workspace is null");
        if (!codeGraph.available()) {
            return;
        }
        Path repository = paths.repository(workspace);
        Result head = git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/" + workspace.defaultBranch() + "^{commit}");
        if (!head.successful() || head.truncated() || head.output().isBlank()) {
            LOG.debug("CodeGraph baseline skipped for workspace {}: the default branch has no fetched commit", workspace.id());
            return;
        }
        String commit = head.output().strip();
        RepositoryIdentity identity = new RepositoryIdentity(workspace.selectedRepository().owner(), workspace.selectedRepository().repo());
        executor.execute(() -> {
            try {
                codeGraph.refreshBaseline(identity, repository, commit);
            }
            catch (CodeGraphException e) {
                LOG.warn("CodeGraph baseline refresh for {} failed: {}", identity, e.getMessage());
            }
        });
    }
}
