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
import com.bytequay.core.hosting.PullRequestWriter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

@Service
public class HostingPullRequestWrites
{
    private final Map<HostingProvider, PullRequestWriter> writers;

    public HostingPullRequestWrites(List<PullRequestWriter> writers)
    {
        this.writers = writers.stream().collect(toImmutableMap(PullRequestWriter::provider, identity()));
    }

    public PullRequestWriter forSession(HostingReadSession session)
    {
        return requireNonNull(writers.get(session.access().server().provider()), "Hosting writer is unavailable");
    }
}
