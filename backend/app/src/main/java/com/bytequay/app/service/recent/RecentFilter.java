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
package com.bytequay.app.service.recent;

import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;
import com.google.common.collect.ImmutableSet;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * The stream's window and chips. An empty {@code types} means every type, which
 * is the {@code All} chip.
 */
public record RecentFilter(
        LocalDate from,
        LocalDate to,
        Set<RecentItemType> types,
        RecentReason action,
        String workspaceId,
        boolean changedSinceRead,
        String text)
{
    public RecentFilter
    {
        requireNonNull(from, "from is null");
        requireNonNull(to, "to is null");
        types = ImmutableSet.copyOf(requireNonNull(types, "types is null"));
    }

    public Optional<RecentReason> reason()
    {
        return Optional.ofNullable(action);
    }

    public Optional<String> workspace()
    {
        return Optional.ofNullable(workspaceId);
    }

    public Optional<String> narrow()
    {
        return Optional.ofNullable(text).map(String::trim).filter(value -> !value.isEmpty());
    }
}
