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
package com.bytequay.app.service.pr;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

record RefreshPolicy(Duration freshness, Duration discussionFreshness, boolean checksOnly, boolean force, boolean warm)
{
    RefreshPolicy
    {
        requireNonNull(freshness, "freshness is null");
        requireNonNull(discussionFreshness, "discussionFreshness is null");
    }

    static RefreshPolicy detail()
    {
        return new RefreshPolicy(Duration.ofSeconds(60), Duration.ofMinutes(30), false, false, false);
    }

    static RefreshPolicy background(Duration freshness)
    {
        return new RefreshPolicy(freshness, Duration.ofMinutes(30), false, false, true);
    }

    static RefreshPolicy opened()
    {
        return new RefreshPolicy(Duration.ofSeconds(60), Duration.ofSeconds(60), false, false, false);
    }

    static RefreshPolicy checks()
    {
        return new RefreshPolicy(Duration.ofSeconds(30), Duration.ofMinutes(30), true, false, false);
    }

    static RefreshPolicy forced()
    {
        return new RefreshPolicy(Duration.ZERO, Duration.ZERO, false, true, false);
    }

    boolean canReuse(RefreshPolicy running)
    {
        return (!force || running.force())
                && (checksOnly || !running.checksOnly())
                && (warm || !running.warm())
                && freshness.compareTo(running.freshness()) >= 0
                && (checksOnly || discussionFreshness.compareTo(running.discussionFreshness()) >= 0);
    }
}
