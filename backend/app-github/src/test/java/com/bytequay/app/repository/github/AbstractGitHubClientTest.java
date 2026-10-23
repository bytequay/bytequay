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

import org.junit.jupiter.api.AfterEach;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

abstract class AbstractGitHubClientTest
{
    protected final MockRestServiceServer server;
    protected final MockRestServiceServer graphServer;
    protected final RestClient restClient;
    protected final RestClient graphQlClient;

    protected AbstractGitHubClientTest()
    {
        RestClient.Builder rest = RestClient.builder().baseUrl("https://api.github.test");
        RestClient.Builder graph = RestClient.builder().baseUrl("https://api.github.test/graphql");
        server = MockRestServiceServer.bindTo(rest).build();
        graphServer = MockRestServiceServer.bindTo(graph).build();
        restClient = rest.build();
        graphQlClient = graph.build();
    }

    @AfterEach
    final void verifyRequests()
    {
        server.verify();
        graphServer.verify();
    }
}
