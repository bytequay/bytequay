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
package com.bytequay.integration.gitlab.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;

/** An emoji reaction on a merge request or note. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GitLabAward(long id, String name, GitLabUser user)
{
    // Award names whose app reaction is spelled differently; other names are shared.
    private static final BiMap<String, String> REACTIONS = ImmutableBiMap.of("thumbsup", "+1", "thumbsdown", "-1", "tada", "hooray", "smile", "laugh");

    static String reaction(String awardName)
    {
        return REACTIONS.getOrDefault(awardName, awardName);
    }

    static String awardName(String reaction)
    {
        return REACTIONS.inverse().getOrDefault(reaction, reaction);
    }
}
