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
package com.bytequay.core.hosting;

import static java.util.Objects.requireNonNull;

/** Bundled implementations of the public remote ports. */
public record HostingIntegration(HostingProvider provider, HostingAccounts accounts, HostingRepositories repositories, PullRequestReader pullRequests)
{
    public HostingIntegration
    {
        requireNonNull(provider, "provider is null");
        requireNonNull(accounts, "accounts is null");
        requireNonNull(repositories, "repositories is null");
        requireNonNull(pullRequests, "pullRequests is null");
    }
}
