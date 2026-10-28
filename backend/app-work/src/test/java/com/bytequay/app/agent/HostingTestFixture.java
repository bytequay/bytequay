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
package com.bytequay.app.agent;

import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;

import java.net.URI;
import java.util.Optional;

import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;

final class HostingTestFixture
{
    private HostingTestFixture() {}

    static WorkspaceHostingRead route(String repository, String token)
    {
        HostingInstance instance = new HostingInstance(1, GITHUB_SERVER.provider(), GITHUB_SERVER.webBaseUrl(), GITHUB_SERVER.apiBaseUrl());
        HostingReadSession session = new HostingReadSession(2, "selected", new HostingAccess(instance, token));
        return new WorkspaceHostingRead(session, new HostingRepository(3, 1, "4", repository,
                URI.create("https://github.com/" + repository), URI.create("https://github.com/" + repository + ".git"), Optional.empty(), Optional.of("main")), "contributor");
    }
}
