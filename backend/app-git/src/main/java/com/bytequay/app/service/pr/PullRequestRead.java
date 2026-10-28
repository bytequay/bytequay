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
package com.bytequay.app.service.pr;

import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.hosting.HostingRepository;

import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One account and retained repository selected for the entire request read. */
public record PullRequestRead(HostingReadSession session, HostingRepository repository, int number, String login)
{
    public PullRequestRead
    {
        requireNonNull(session, "session is null");
        requireNonNull(repository, "repository is null");
        checkArgument(number > 0, "number must be positive");
        checkText(login, "login");
        checkArgument(repository.instanceId() == session.access().instanceId().orElseThrow(), "Repository belongs to another hosting instance");
    }

    public String scope()
    {
        return session.cacheScope() + ":" + repository.id();
    }

    public PullRequestRef ref()
    {
        int slash = repository.fullPath().lastIndexOf('/');
        return PullRequestRef.of(repository.fullPath().substring(0, slash), repository.fullPath().substring(slash + 1), number);
    }

    public PullRequestRef githubRef()
    {
        requireGitHub();
        return parseRef(repository.fullPath(), number);
    }

    public String githubToken()
    {
        requireGitHub();
        return session.access().token();
    }

    private void requireGitHub()
    {
        checkArgument(session.access().server().equals(GITHUB_SERVER), "This operation requires a GitHub.com account");
    }
}
