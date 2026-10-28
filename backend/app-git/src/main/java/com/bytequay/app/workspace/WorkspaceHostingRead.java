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
package com.bytequay.app.workspace;

import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;

import java.net.URI;
import java.util.function.Supplier;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One captured workspace route; its session guards both credentials and workspace rebinding. */
public record WorkspaceHostingRead(HostingReadSession session, HostingRepository repository, String login)
{
    public WorkspaceHostingRead
    {
        requireNonNull(session, "session is null");
        requireNonNull(repository, "repository is null");
        checkText(login, "login");
        checkArgument(repository.instanceId() == session.access().instanceId().orElseThrow(), "Repository belongs to another hosting instance");
    }

    public <T> T withCurrent(Supplier<T> action)
    {
        return session.withCurrent(action);
    }

    public RepoRef githubRepository()
    {
        requireGitHub();
        String[] coordinates = repository.fullPath().split("/", -1);
        checkArgument(coordinates.length == 2, "GitHub repository must have owner/repository coordinates");
        return withCurrent(() -> RepoRef.of(coordinates[0], coordinates[1]));
    }

    public String githubToken()
    {
        requireGitHub();
        return session.access().token();
    }

    private void requireGitHub()
    {
        HostingServer server = session.access().server();
        checkArgument(server.provider() == GITHUB && server.webBaseUrl().equals(URI.create("https://github.com"))
                && server.apiBaseUrl().equals(URI.create("https://api.github.com")), "This operation requires a GitHub.com workspace");
    }
}
