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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextLimits;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * When each retained note version appeared, and which task saved it. This is presentation data kept beside the
 * notebook, so the notebook's persisted shape stays unchanged.
 */
public record ContextTimes(Map<String, Stamp> entries)
{
    public ContextTimes
    {
        entries = ImmutableMap.copyOf(entries);
    }

    public static ContextTimes empty()
    {
        return new ContextTimes(ImmutableMap.of());
    }

    /** Versions come from the notebook revision, so a version prefix keeps every retained version distinct. */
    public static String key(String entryId, long version)
    {
        return version + ":" + entryId;
    }

    public record Stamp(Instant at, Optional<String> taskId)
    {
        public Stamp
        {
            requireNonNull(at, "at is null");
            requireNonNull(taskId, "taskId is null");
            taskId.ifPresent(value -> ContextLimits.text(value, "saving task", 200));
        }
    }
}
