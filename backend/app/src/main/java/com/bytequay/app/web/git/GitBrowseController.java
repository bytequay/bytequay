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
package com.bytequay.app.web.git;

import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.git.GitBrowseService.DiffFile;
import com.bytequay.app.git.GitBrowseService.RepoOverview;
import com.bytequay.app.git.GitBrowseService.WorktreeStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Local checkout browsing for the Commits tab. Every endpoint targets the
 * repository path the caller names, like the cherry-pick surfaces do.
 */
@RestController
@RequestMapping("/api/git")
public class GitBrowseController
{
    private final GitBrowseService service;

    public GitBrowseController(GitBrowseService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    @GetMapping("/overview")
    public RepoOverview overview(@RequestParam String repository)
    {
        return service.overview(repositoryPath(repository));
    }

    @GetMapping("/branches")
    public List<String> remoteBranches(@RequestParam String repository, @RequestParam String remote)
    {
        return service.remoteBranches(repositoryPath(repository), remote);
    }

    @GetMapping("/log")
    public List<Commit> log(
            @RequestParam String repository,
            @RequestParam String ref,
            @RequestParam(defaultValue = "200") int limit)
    {
        return service.log(repositoryPath(repository), ref, limit);
    }

    @GetMapping("/diff")
    public List<DiffFile> diff(@RequestParam String repository, @RequestParam String base, @RequestParam String head)
    {
        return service.diff(repositoryPath(repository), base, head);
    }

    @GetMapping("/status")
    public WorktreeStatus status(@RequestParam String repository)
    {
        return service.status(repositoryPath(repository));
    }

    @PostMapping("/fetch")
    public Map<String, Boolean> fetch(@RequestBody GitFetchRequest request)
    {
        service.fetch(repositoryPath(request.repository()), request.remotes());
        return Map.of("fetched", true);
    }

    @PostMapping("/commit")
    public Map<String, String> commit(@RequestBody GitCommitRequest request)
    {
        return Map.of("sha", service.commit(
                repositoryPath(request.repository()),
                request.summary(),
                request.description() == null ? "" : request.description()));
    }

    @PostMapping("/reword")
    public Map<String, String> reword(@RequestBody GitRewordRequest request)
    {
        return Map.of("sha", service.reword(
                repositoryPath(request.repository()),
                request.branch(),
                request.sha(),
                request.message()));
    }

    @PostMapping("/squash")
    public Map<String, String> squash(@RequestBody GitSquashRequest request)
    {
        return Map.of("sha", service.squash(
                repositoryPath(request.repository()),
                request.branch(),
                request.baseSha(),
                request.tipSha(),
                request.message()));
    }

    private static Path repositoryPath(String value)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("repository is blank");
        }
        try {
            return Path.of(value);
        }
        catch (InvalidPathException e) {
            throw new IllegalArgumentException("repository is not a valid path");
        }
    }

    public record GitFetchRequest(String repository, List<String> remotes) {}

    public record GitCommitRequest(String repository, String summary, String description) {}

    public record GitRewordRequest(String repository, String branch, String sha, String message) {}

    public record GitSquashRequest(String repository, String branch, String baseSha, String tipSha, String message) {}
}
