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

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPaginator
{
    private static final String PATH = "/repos/owner/repo/pulls/7/files";
    private static final ParameterizedTypeReference<List<Integer>> TYPE = new ParameterizedTypeReference<>() {};

    private MockRestServiceServer server;
    private GitHubPaginator paginator;

    @BeforeEach
    void setUp()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        server = MockRestServiceServer.bindTo(builder).build();
        paginator = new GitHubPaginator(builder.build());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsAnExplicitEmptyList(boolean conditional)
    {
        server.expect(requestTo(pageUrl(1))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(read(conditional)).isEmpty();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAMissingFirstPageBody(boolean conditional)
    {
        server.expect(requestTo(pageUrl(1))).andRespond(withSuccess());

        assertThatThrownBy(() -> read(conditional))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("Empty response");

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAMissingLaterPageRatherThanReturningPartialData(boolean conditional)
    {
        expectFullFirstPage();
        server.expect(requestTo(pageUrl(2))).andRespond(withSuccess());

        assertThatThrownBy(() -> read(conditional))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("Empty response");

        server.verify();
    }

    @Test
    void preservesTheStoredCopyForAConditionalFirstPage()
    {
        server.expect(requestTo(pageUrl(1)))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"files-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, "\"files-2\""));

        ConditionalList<Integer> result = paginator.paginateConditional("pat", PATH, TYPE, Optional.of("\"files-1\""));

        assertThat(result.unchanged()).isTrue();
        assertThat(result.orElse(ImmutableList.of(7))).containsExactly(7);
        assertThat(result.etag()).contains("\"files-2\"");
        server.verify();
    }

    @Test
    void rejectsAnUnsolicitedNotModifiedFirstPage()
    {
        server.expect(requestTo(pageUrl(1)))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        assertThatThrownBy(() -> read(true))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("Unexpected not-modified");

        server.verify();
    }

    @Test
    void rejectsAnUnsolicitedNotModifiedLaterPage()
    {
        expectFullFirstPage();
        server.expect(requestTo(pageUrl(2)))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        assertThatThrownBy(() -> read(true))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("Unexpected not-modified");

        server.verify();
    }

    @Test
    void rejectsAMissingDirectPageBody()
    {
        server.expect(requestTo(pageUrl(2))).andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        assertThatThrownBy(() -> paginator.fetchPage("pat", PATH, TYPE, null, 2))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("Empty response");

        server.verify();
    }

    private List<Integer> read(boolean conditional)
    {
        return conditional
                ? paginator.paginateConditional("pat", PATH, TYPE, Optional.empty()).values().orElseThrow()
                : paginator.paginate("pat", PATH, TYPE);
    }

    private void expectFullFirstPage()
    {
        String rows = IntStream.range(0, GitHubPaginator.PAGE_SIZE).mapToObj(Integer::toString).collect(joining(",", "[", "]"));
        server.expect(requestTo(pageUrl(1))).andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));
    }

    private static String pageUrl(int page)
    {
        return "https://api.github.test" + PATH + "?per_page=100&page=" + page;
    }
}
