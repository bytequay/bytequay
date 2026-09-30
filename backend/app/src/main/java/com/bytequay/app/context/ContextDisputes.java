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

import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextLimits;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Which notes agents disagree about and how each note's last dispute was settled, kept beside the notebook and changed
 * under the same workspace lock. A contest lasts until a settling save or the user's Keep, Restore, Edit or Disable.
 */
public record ContextDisputes(Map<String, Contest> contested, Map<String, Settlement> settlements)
{
    public ContextDisputes
    {
        contested = ImmutableMap.copyOf(contested);
        settlements = ImmutableMap.copyOf(settlements);
    }

    public static ContextDisputes empty()
    {
        return new ContextDisputes(ImmutableMap.of(), ImmutableMap.of());
    }

    public Optional<Contest> contest(String entryId)
    {
        return Optional.ofNullable(contested.get(entryId));
    }

    public Optional<Settlement> settlement(String entryId)
    {
        return Optional.ofNullable(settlements.get(entryId));
    }

    /** The statement agents disagree about and the version on which the disagreement arose. */
    public record Contest(String statement, long version)
    {
        public Contest
        {
            ContextLimits.text(statement, "contested statement", ContextGrade.MAX_QUOTE_BYTES);
            checkArgument(version > 0, "Contested version must be positive");
        }
    }

    /**
     * The version a settlement produced, the statement it settled and, for an investigation or a task's settling save,
     * the root cause shown on the note.
     */
    public record Settlement(long version, By by, String statement, Optional<String> rootCause)
    {
        public static final int MAX_ROOT_CAUSE_BYTES = 300;

        public Settlement
        {
            checkArgument(version > 0, "Settled version must be positive");
            requireNonNull(by, "by is null");
            ContextLimits.text(statement, "settled statement", ContextGrade.MAX_QUOTE_BYTES);
            requireNonNull(rootCause, "rootCause is null");
            rootCause.ifPresent(value -> ContextLimits.text(value, "root cause", MAX_ROOT_CAUSE_BYTES));
            checkArgument(by == By.USER || rootCause.isPresent(), "An agent's settlement names its root cause");
        }
    }

    public enum By
    {
        INVESTIGATION,
        TASK,
        USER
    }
}
