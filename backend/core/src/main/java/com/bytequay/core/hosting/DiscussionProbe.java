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

import com.google.common.collect.ImmutableMap;

import java.util.Map;

/** Unchanged only when every discussion source was validated; otherwise validators apply to a subsequent refetch. */
public record DiscussionProbe(boolean unchanged, Map<String, String> etags)
{
    public DiscussionProbe
    {
        etags = ImmutableMap.copyOf(etags);
    }
}
