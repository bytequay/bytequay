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

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

/** Renames change coordinates, never this retained local identity. */
public record HostingRepository(
        long id,
        long instanceId,
        String remoteId,
        String fullPath,
        URI webUrl,
        URI httpCloneUrl,
        Optional<String> sshCloneUrl,
        Optional<String> defaultBranch)
{
    public HostingRepository
    {
        checkArgument(id > 0, "id must be positive");
        checkArgument(instanceId > 0, "instanceId must be positive");
        checkText(remoteId, "remoteId");
        HostingUrls.checkRepositoryPath(fullPath);
        webUrl = HostingUrls.canonicalHttpUrl(webUrl);
        httpCloneUrl = HostingUrls.canonicalHttpUrl(httpCloneUrl);
        checkOptionalText(sshCloneUrl, "sshCloneUrl").ifPresent(HostingUrls::checkSshCloneUrl);
        checkOptionalText(defaultBranch, "defaultBranch");
    }
}
