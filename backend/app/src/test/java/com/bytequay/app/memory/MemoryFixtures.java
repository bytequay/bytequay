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
package com.bytequay.app.memory;

import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

public final class MemoryFixtures
{
    public static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private MemoryFixtures() {}

    public static Memory rule(String id, String workspaceId, String text, List<String> scope, int... pullRequests)
    {
        List<MemorySource> sources = Arrays.stream(pullRequests.length == 0 ? new int[] {31201} : pullRequests)
                .mapToObj(number -> pullRequestSource(number, "alice")).collect(toImmutableList());
        return new Memory(id, workspaceId, Memory.Kind.RULE, text, scope, sources, new Memory.Origin(false, "Learn from my PRs", Optional.of("harvest-task")), 1,
                Optional.empty(), 0, 0, ImmutableList.of(), NOW.plusSeconds(Long.parseLong(id.substring(1))), NOW);
    }

    public static MemorySource pullRequestSource(int number, String author)
    {
        return new MemorySource(MemorySource.Kind.PULL_REQUEST, "https://github.com/trinodb/trino/pull/" + number + "#discussion_r" + number,
                "Please document the new property.", NOW, Optional.of(new MemorySource.PullRequestPart(number, MemorySource.Part.REVIEW_COMMENT,
                String.valueOf(number), author, true, true, true, false)), Optional.empty());
    }
}
