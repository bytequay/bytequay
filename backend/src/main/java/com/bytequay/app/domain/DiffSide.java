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

import java.util.Locale;

/**
 * Normalizes a diff-anchor side to the canonical {@code LEFT}/{@code RIGHT} GitHub
 * uses, the same way across every inline-comment system this app has: remote PR
 * review threads, local PR comments, and task/AI review comments.
 */
public final class DiffSide
{
    public static final String LEFT = "LEFT";
    public static final String RIGHT = "RIGHT";

    private DiffSide() {}

    /** Blank/null defaults to {@link #RIGHT} — new-side, single-line is the
     *  common case every existing comment predates this concept with. */
    public static String normalize(String side)
    {
        return side == null || side.isBlank() ? RIGHT : side.toUpperCase(Locale.ROOT);
    }

    /** Blank/null defaults to {@code defaultSide} instead of always {@link #RIGHT} —
     *  used for a range's start side, which should default to the end side. */
    public static String normalizeOptional(String side, String defaultSide)
    {
        return side == null || side.isBlank() ? defaultSide : side.toUpperCase(Locale.ROOT);
    }
}
