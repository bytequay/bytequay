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

import java.net.URI;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Provider-reported coordinates; the application assigns the persistent repository identity. */
public record RemoteHostingRepository(
        String remoteId,
        String fullPath,
        URI webUrl,
        URI httpCloneUrl,
        Optional<String> sshCloneUrl,
        Optional<String> defaultBranch)
{
    public RemoteHostingRepository
    {
        checkText(remoteId, "remoteId");
        checkText(fullPath, "fullPath");
        requireNonNull(webUrl, "webUrl is null");
        requireNonNull(httpCloneUrl, "httpCloneUrl is null");
        requireNonNull(sshCloneUrl, "sshCloneUrl is null");
        requireNonNull(defaultBranch, "defaultBranch is null");
    }
}
