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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubActionsClient
{
    private final RestClient gitHubRestClient;

    public GitHubActionsClient(RestClient gitHubRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
    }

    public List<PrCheckRunState> fetchPrCheckRuns(String pat, String owner, String repo, String sha)
    {
        List<PrCheckRunState> result = new ArrayList<>();
        int collected = 0;
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
                    break;
                }
                for (GitHubCheckRunsResponse.CheckRun check : response.checkRuns()) {
                    result.add(new PrCheckRunState(
                            check.id(), check.name(), check.status(), check.conclusion()));
                }
                collected += response.checkRuns().size();
                if (collected >= response.totalCount() || response.checkRuns().size() < 100) {
                    break;
                }
            }
            catch (RestClientResponseException e) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
