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
package com.bytequay.core.discussion;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The provider conversation a discussion resumes, as the driver reported it. Its lane is the
 * discussion's own {@code providerLane}, so it is not repeated here.
 */
public record DiscussionSession(int contractVersion, String providerValue)
{
    public DiscussionSession
    {
        requireNonNull(providerValue, "providerValue is null");
        checkArgument(contractVersion > 0, "contractVersion must be positive");
        checkArgument(!providerValue.isBlank() && providerValue.length() <= 200, "Invalid provider session value");
    }
}
