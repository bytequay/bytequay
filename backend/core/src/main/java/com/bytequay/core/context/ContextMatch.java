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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/** Explicit repository-wide applicability or selected path/term alternatives. */
public sealed interface ContextMatch
        permits ContextMatch.Repository, ContextMatch.Selected
{
    String getKind();

    record Repository()
            implements ContextMatch
    {
        @Override
        public String getKind()
        {
            return "REPOSITORY";
        }
    }

    record Selected(List<String> pathPrefixes, List<String> terms)
            implements ContextMatch
    {
        public Selected
        {
            pathPrefixes = ImmutableList.copyOf(pathPrefixes);
            terms = ImmutableList.copyOf(terms);
            ContextLimits.unique(pathPrefixes, "path prefixes", 20);
            ContextLimits.unique(terms, "terms", 20);
            checkArgument(!pathPrefixes.isEmpty() || !terms.isEmpty(), "Selected scope has no matching rule");
            pathPrefixes.forEach(ContextLimits::pathPrefix);
            terms.forEach(term -> ContextLimits.text(term, "term", 100));
        }

        @Override
        public String getKind()
        {
            return "SELECTED";
        }
    }
}
