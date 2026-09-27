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
package com.bytequay.app.service.goal;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** The last change ByteQuay knows of on a goal item, such as "#30702 merged". */
public record GoalActivity(String text, Instant at)
{
    public GoalActivity
    {
        requireNonNull(text, "text is null");
        requireNonNull(at, "at is null");
    }
}
