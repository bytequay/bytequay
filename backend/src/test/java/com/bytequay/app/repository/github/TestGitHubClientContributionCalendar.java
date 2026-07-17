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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientContributionCalendar
{
    @Test
    void graphQlErrorsDoNotBecomeAnEmptyCalendar()
    {
        RestClient.Builder graphBuilder = RestClient.builder()
                .baseUrl("https://api.github.test/graphql");
        MockRestServiceServer server = MockRestServiceServer.bindTo(graphBuilder).build();
        GitHubClient client = new GitHubClient(
                RestClient.builder().baseUrl("https://api.github.test").build(),
                graphBuilder.build());

        server.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "data": null,
                          "errors": [{
                            "type": "RESOURCE_LIMITS_EXCEEDED",
                            "message": "Resource limits exceeded"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchContributionCalendar("pat", "chenjian2664"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitHub contribution calendar returned errors")
                .hasMessageContaining("RESOURCE_LIMITS_EXCEEDED");

        server.verify();
    }
}
