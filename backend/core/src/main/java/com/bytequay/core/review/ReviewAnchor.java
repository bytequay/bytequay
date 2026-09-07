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
package com.bytequay.core.review;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Where a finding sits in the frozen publication diff: one file, one side, an ordered line range,
 * and the exact lines that were there. The excerpt is what an outdated finding still shows.
 */
public record ReviewAnchor(String path, String side, int startLine, int endLine, List<String> excerpt)
{
    public ReviewAnchor
    {
        requireNonNull(path, "path is null");
        requireNonNull(side, "side is null");
        checkArgument(side.equals("LEFT") || side.equals("RIGHT"), "side must be LEFT or RIGHT");
        checkArgument(startLine > 0 && endLine >= startLine, "invalid line range");
        excerpt = ImmutableList.copyOf(excerpt);
    }

    /** The diff-style label the pull request page uses, such as {@code R118–R131} or {@code L42}. */
    public String label()
    {
        String prefix = side.equals("LEFT") ? "L" : "R";
        return startLine == endLine ? prefix + startLine : prefix + startLine + "–" + prefix + endLine;
    }
}
