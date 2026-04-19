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
package com.bytequay.app.domain;

/**
 * GitHub-style reaction tally on a single comment. Field names mirror the
 * eight reaction types GitHub exposes; counts default to zero so callers
 * can always read the record without null checks.
 */
public record Reactions(
        int plusOne,
        int minusOne,
        int laugh,
        int hooray,
        int confused,
        int heart,
        int rocket,
        int eyes)
{
    public static final Reactions EMPTY = new Reactions(0, 0, 0, 0, 0, 0, 0, 0);

    public int totalCount()
    {
        return plusOne + minusOne + laugh + hooray + confused + heart + rocket + eyes;
    }
}
