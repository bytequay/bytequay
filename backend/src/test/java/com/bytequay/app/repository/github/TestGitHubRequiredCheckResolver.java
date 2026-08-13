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

import com.bytequay.app.repository.github.GitHubRequiredCheckResolver.Snapshot;
import com.bytequay.app.repository.github.GitHubRequiredCheckResolver.UnresolvedRequiredCheckException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubRequiredCheckResolver
{
    @Test
    void resolvesExactAppAndCheckNamesFromActiveRules()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/acme/fork/rules/branches/main?per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("""
                        [
                          {"type":"required_status_checks","parameters":{"required_status_checks":[
                            {"context":"test","integration_id":9},
                            {"context":"build","integration_id":7}
                          ]}},
                          {"type":"pull_request"},
                          {"type":"required_status_checks","parameters":{"required_status_checks":[
                            {"context":"build","integration_id":7}
                          ]}}
                        ]
                        """, MediaType.APPLICATION_JSON));
        expectClassicAbsent(fixture);

        Snapshot resolved = fixture.resolver.resolve(
                "secret", "acme", "fork", "main");

        assertThat(resolved.sourceRef())
                .isEqualTo("github:required-checks/branches/main");
        assertThat(resolved.sourceDigest()).startsWith("sha256:");
        assertThat(resolved.selectors()).containsExactly(
                "GITHUB_CHECK:7:build", "GITHUB_CHECK:9:test");
        fixture.server.verify();
    }

    @Test
    void resolvesAnUnprotectedBranchAsExplicitlyEmpty()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/acme/fork/rules/branches/main?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        expectClassicAbsent(fixture);

        assertThat(fixture.resolver.resolve(
                "secret", "acme", "fork", "main").selectors()).isEmpty();
        fixture.server.verify();
    }

    @Test
    void refusesARequiredContextWithoutAnExactAppIdentity()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/acme/fork/rules/branches/main?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"type":"required_status_checks","parameters":{"required_status_checks":[
                          {"context":"build","integration_id":null}
                        ]}}]
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.resolver.resolve(
                "secret", "acme", "fork", "main"))
                .isInstanceOf(UnresolvedRequiredCheckException.class)
                .hasMessageContaining("no exact GitHub App identity");
        fixture.server.verify();
    }

    @Test
    void resolvesClassicBranchProtectionChecks()
    {
        Fixture fixture = fixture();
        expectEmptyRulesets(fixture);
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/acme/fork/branches/main/protection/required_status_checks"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("""
                        {
                          "contexts":["build","test"],
                          "checks":[
                            {"context":"test","app_id":9},
                            {"context":"build","app_id":7}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Snapshot resolved = fixture.resolver.resolve(
                "secret", "acme", "fork", "main");

        assertThat(resolved.selectors()).containsExactly(
                "GITHUB_CHECK:7:build", "GITHUB_CHECK:9:test");
        fixture.server.verify();
    }

    @Test
    void refusesClassicContextsWithoutMatchingExactChecks()
    {
        Fixture fixture = fixture();
        expectEmptyRulesets(fixture);
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/acme/fork/branches/main/protection/required_status_checks"))
                .andRespond(withSuccess("""
                        {
                          "contexts":["build","legacy"],
                          "checks":[{"context":"build","app_id":7}]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.resolver.resolve(
                "secret", "acme", "fork", "main"))
                .isInstanceOf(UnresolvedRequiredCheckException.class)
                .hasMessageContaining("do not match exact checks");
        fixture.server.verify();
    }

    private static void expectEmptyRulesets(Fixture fixture)
    {
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/acme/fork/rules/branches/main?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    }

    private static void expectClassicAbsent(Fixture fixture)
    {
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/acme/fork/branches/main/protection/required_status_checks"))
                .andRespond(withStatus(NOT_FOUND));
    }

    private static Fixture fixture()
    {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder).build();
        return new Fixture(new GitHubRequiredCheckResolver(
                builder.build(), new ObjectMapper()), server);
    }

    private record Fixture(
            GitHubRequiredCheckResolver resolver,
            MockRestServiceServer server) {}
}
