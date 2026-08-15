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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubActionsClient
{
    @Test
    void readsCheckRuns()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"check_runs":[{
                          "id":99,"name":"build","status":"completed","conclusion":"failure",
                          "html_url":"https://github.com/owner/repo/actions/runs/7/job/9",
                          "output":{"title":"Tests failed","summary":"One failure"}
                        }]}
                        """, MediaType.APPLICATION_JSON));

        PrCheckRunState check = fixture.client()
                .fetchPrCheckRunsStrict("pat", "owner", "repo", "head")
                .getFirst();

        assertThat(check.githubId()).isEqualTo(99L);
        assertThat(check.name()).isEqualTo("build");
        assertThat(check.outputTitle()).isEqualTo("Tests failed");
        fixture.server().verify();
    }

    @Test
    void readsActionsJobLog()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/check-runs/99"))
                .andRespond(withSuccess("""
                        {"details_url":"https://github.com/owner/repo/actions/runs/1/job/101"}
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/101/logs"))
                .andRespond(withSuccess("failure details", MediaType.TEXT_PLAIN));

        assertThat(fixture.client().fetchCheckRunLog("pat", RepoRef.of("owner", "repo"), 99L))
                .contains("failure details");
        fixture.server().verify();
    }

    @Test
    void keepsOnlySourceFailureAnnotations()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/check-runs/99/annotations?per_page=100"))
                .andRespond(withSuccess("""
                        [
                          {"annotation_level":"failure","message":"exit 1",
                           "path":".github","start_line":20},
                          {"annotation_level":"failure","title":"Tests","message":"expected true",
                           "path":"src/Test.java","start_line":42}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<GitHubActionsClient.CheckRunAnnotation> annotations = fixture.client()
                .fetchCheckRunAnnotations("pat", RepoRef.of("owner", "repo"), 99L);

        assertThat(annotations).singleElement().satisfies(annotation -> {
            assertThat(annotation.title()).isEqualTo("Tests");
            assertThat(annotation.path()).isEqualTo("src/Test.java");
            assertThat(annotation.startLine()).isEqualTo(42);
        });
        fixture.server().verify();
    }

    private static Fixture fixture()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GitHubActionsClient(builder.build()), server);
    }

    private record Fixture(GitHubActionsClient client, MockRestServiceServer server) {}
}
