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

/**
 * What ByteQuay supports for a provider beyond the shared hosting ports; the UI hides the rest and the backend refuses it.
 *
 * @param requestChanges a review can request changes
 * @param multiLineComments a review comment can cover several lines
 * @param forkWorkflows workspaces can be related as upstreams, forks and backport targets
 * @param issueChildren an issue's child issues can be read
 * @param commitAvatars commit history shows account avatars and pull request links
 * @param teamActivity circles follow their members' activity in the provider's watched repositories
 */
public record HostingCapabilities(boolean requestChanges, boolean multiLineComments, boolean forkWorkflows, boolean issueChildren, boolean commitAvatars,
        boolean teamActivity)
{
    public static final HostingCapabilities ALL = new HostingCapabilities(true, true, true, true, true, true);
}
