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

import com.bytequay.app.service.local.GitRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Fails startup before an agent can run against an ambiguous workspace. */
@Component
public class WorkspaceIntegrityVerifier
{
    private final JdbcTemplate jdbc;
    private final GitRunner git;

    public WorkspaceIntegrityVerifier(JdbcTemplate jdbc, GitRunner git)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.git = requireNonNull(git, "git is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify()
    {
        List<String> failures = new ArrayList<>();
        jdbc.query("""
                SELECT w.id,
                       count(wr.repo_full_name) AS repo_count,
                       max(watched.local_clone_path) AS clone_path
                FROM workspaces w
                LEFT JOIN workspace_repos wr ON wr.workspace_id = w.id
                LEFT JOIN watched_repos watched
                  ON lower(watched.owner || '/' || watched.repo)
                   = lower(wr.repo_full_name)
                WHERE w.detached_at_ms IS NULL
                GROUP BY w.id
                """, (RowCallbackHandler) rs -> {
                    String workspaceId = rs.getString("id");
                    int repoCount = rs.getInt("repo_count");
                    String clonePath = rs.getString("clone_path");
                    if (repoCount != 1) {
                        failures.add(workspaceId + " owns " + repoCount
                                + " repositories (expected 1)");
                    }
                    else if (!verifiedClone(clonePath)) {
                        failures.add(workspaceId
                                + " has no verified local clone");
                    }
                });
        jdbc.query("""
                SELECT watched.owner || '/' || watched.repo AS repo,
                       watched.local_clone_path
                FROM watched_repos watched
                LEFT JOIN workspace_repos wr
                  ON lower(wr.repo_full_name)
                   = lower(watched.owner || '/' || watched.repo)
                WHERE watched.local_clone_path IS NOT NULL
                  AND trim(watched.local_clone_path) <> ''
                  AND wr.workspace_id IS NULL
                """, (RowCallbackHandler) rs -> {
                    if (verifiedClone(rs.getString("local_clone_path"))) {
                        failures.add("verified clone is orphaned: "
                                + rs.getString("repo"));
                    }
                });
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "workspace integrity check failed: "
                            + String.join("; ", failures));
        }
    }

    private boolean verifiedClone(String value)
    {
        try {
            return value != null && !value.isBlank()
                    && Files.isDirectory(Path.of(value))
                    && git.isGitWorkingTree(Path.of(value));
        }
        catch (RuntimeException e) {
            return false;
        }
    }
}
