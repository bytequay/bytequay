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
package com.bytequay.app.service.hosting;

import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.PullRequestContentReader;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Service
public class HostingPullRequestContents
{
    private final Map<HostingProvider, PullRequestContentReader> readers;

    public HostingPullRequestContents(List<PullRequestContentReader> readers)
    {
        this.readers = readers.stream().collect(toImmutableMap(PullRequestContentReader::provider, reader -> reader));
    }

    public PullRequestContentReader reader(HostingReadSession session)
    {
        return requireNonNull(readers.get(session.access().server().provider()), "Hosting content reader is unavailable");
    }
}
