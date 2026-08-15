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
package com.bytequay.app.repository.github;

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.RepoRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubActionsClient
{
    public record CheckRunAnnotation(String title, String message, String path, Integer startLine) {}

    private static final Pattern ACTIONS_JOB_URL =
            Pattern.compile("/actions/runs/\\d+/job/(\\d+)(?:[/?#]|$)");

    private final RestClient gitHubRestClient;

    public GitHubActionsClient(RestClient gitHubRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
    }

    public List<PrCheckRunState> fetchPrCheckRuns(String pat, String owner, String repo, String sha)
    {
        return fetchPrCheckRuns(pat, owner, repo, sha, false);
    }

    public List<PrCheckRunState> fetchPrCheckRunsStrict(String pat, String owner, String repo, String sha)
    {
        return fetchPrCheckRuns(pat, owner, repo, sha, true);
    }

    private List<PrCheckRunState> fetchPrCheckRuns(
            String pat, String owner, String repo, String sha, boolean strict)
    {
        ImmutableList.Builder<PrCheckRunState> result = ImmutableList.builder();
        int collected = 0;
        Integer total = null;
        boolean complete = false;
        for (int page = 1; page <= 5; page++) {
            int currentPage = page;
            try {
                GitHubCheckRunsResponse response = gitHubRestClient.get()
                        .uri(uri -> uri.path("/repos/{owner}/{repo}/commits/{sha}/check-runs")
                                .queryParam("per_page", 100)
                                .queryParam("page", currentPage)
                                .build(owner, repo, sha))
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .body(GitHubCheckRunsResponse.class);
                if (response == null || response.checkRuns() == null) {
                    if (strict) {
                        throw incompleteCheckRunsResponse(collected, total);
                    }
                    break;
                }
                if (strict) {
                    if (total == null) {
                        total = response.totalCount();
                    }
                    else if (total != response.totalCount()) {
                        throw incompleteCheckRunsResponse(collected, total);
                    }
                }
                for (GitHubCheckRunsResponse.CheckRun check : response.checkRuns()) {
                    result.add(new PrCheckRunState(
                            check.id(), check.name(), check.status(), check.conclusion(), check.htmlUrl(),
                            check.output() == null ? null : check.output().title(),
                            check.output() == null ? null : check.output().summary()));
                }
                collected += response.checkRuns().size();
                complete = strict && collected == total;
                if (complete || (!strict && (collected >= response.totalCount() || response.checkRuns().size() < 100))) {
                    break;
                }
                if (strict && (collected > total || response.checkRuns().size() < 100)) {
                    throw incompleteCheckRunsResponse(collected, total);
                }
            }
            catch (RestClientResponseException e) {
                if (strict) {
                    throw toReadableException(e);
                }
                break;
            }
        }
        if (strict && !complete) {
            throw incompleteCheckRunsResponse(collected, total);
        }
        return result.build();
    }

    private static ResponseStatusException incompleteCheckRunsResponse(int collected, Integer total)
    {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(502),
                "GitHub check-runs pagination was incomplete: collected " + collected + " of " + total);
    }

    public int rerunFailedChecks(String pat, RepoRef repo, String headSha)
    {
        if (headSha == null || headSha.isBlank()) {
            return 0;
        }
        try {
            GitHubWorkflowRunsResponse runs = gitHubRestClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/actions/runs")
                            .queryParam("head_sha", headSha)
                            .queryParam("per_page", 30)
                            .build(repo.owner(), repo.repo()))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubWorkflowRunsResponse.class);
            if (runs == null || runs.workflowRuns() == null) {
                return 0;
            }
            int rerun = 0;
            for (GitHubWorkflowRunsResponse.Run run : runs.workflowRuns()) {
                if (!"completed".equals(run.status()) || !isFailedConclusion(run.conclusion())) {
                    continue;
                }
                gitHubRestClient.post()
                        .uri("/repos/{owner}/{repo}/actions/runs/{id}/rerun-failed-jobs",
                                repo.owner(), repo.repo(), run.id())
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .toBodilessEntity();
                rerun++;
            }
            return rerun;
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static boolean isFailedConclusion(String conclusion)
    {
        return "failure".equals(conclusion)
                || "cancelled".equals(conclusion)
                || "timed_out".equals(conclusion)
                || "startup_failure".equals(conclusion);
    }

    public Optional<String> fetchCheckRunLog(String pat, RepoRef repo, long checkRunId)
    {
        Long jobId;
        try {
            GitHubCheckRunDetail detail = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/check-runs/{id}", repo.owner(), repo.repo(), checkRunId)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubCheckRunDetail.class);
            jobId = detail == null ? null : extractActionsJobId(detail.detailsUrl());
            if (jobId == null && detail != null) {
                jobId = extractActionsJobId(detail.htmlUrl());
            }
        }
        catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 403 || status == 404) {
                return Optional.empty();
            }
            throw toReadableException(e);
        }
        if (jobId == null) {
            return Optional.empty();
        }

        long resolvedJobId = jobId;
        return gitHubRestClient.get()
                .uri("/repos/{owner}/{repo}/actions/jobs/{id}/logs", repo.owner(), repo.repo(), resolvedJobId)
                .header("Authorization", authorization(pat))
                .header("Accept", "text/plain, */*")
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 401 || status == 403 || status == 404 || status == 410) {
                        return Optional.empty();
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new ResponseStatusException(
                                response.getStatusCode(), "GitHub Actions job log request failed");
                    }
                    String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    return text.isBlank() ? Optional.empty() : Optional.of(text);
                });
    }

    public List<CheckRunAnnotation> fetchCheckRunAnnotations(String pat, RepoRef repo, long checkRunId)
    {
        List<GitHubCheckRunAnnotation> body;
        try {
            body = gitHubRestClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/check-runs/{id}/annotations")
                            .queryParam("per_page", 100)
                            .build(repo.owner(), repo.repo(), checkRunId))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw toReadableException(e);
        }
        if (body == null) {
            return List.of();
        }
        return body.stream()
                .filter(annotation -> "failure".equals(annotation.annotationLevel()))
                .map(annotation -> new CheckRunAnnotation(
                        annotation.title(), annotation.message(), annotation.path(), annotation.startLine()))
                .filter(GitHubActionsClient::hasSourceLocation)
                .collect(toImmutableList());
    }

    private static boolean hasSourceLocation(CheckRunAnnotation annotation)
    {
        return annotation.startLine() != null
                && annotation.path() != null
                && !annotation.path().isBlank()
                && !annotation.path().equals(".github")
                && !annotation.path().startsWith(".github/");
    }

    private static Long extractActionsJobId(String url)
    {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher matcher = ACTIONS_JOB_URL.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubWorkflowRunsResponse(@JsonProperty("workflow_runs") List<Run> workflowRuns)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Run(long id, String status, String conclusion) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCheckRunDetail(
            @JsonProperty("details_url") String detailsUrl,
            @JsonProperty("html_url") String htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCheckRunAnnotation(
            @JsonProperty("path") String path,
            @JsonProperty("start_line") Integer startLine,
            @JsonProperty("annotation_level") String annotationLevel,
            @JsonProperty("title") String title,
            @JsonProperty("message") String message) {}
}
