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
package com.bytequay.app.beans.footprints;

import com.bytequay.app.domain.FootprintStop;

/** Wire shape for one trail stop. {@code latestVisitAt} is ISO-8601. */
public record FootprintStopDto(
        String surfaceType,
        String surfaceId,
        String title,
        String context,
        String latestVisitAt,
        int visitCount)
{
    public static FootprintStopDto from(FootprintStop stop)
    {
        return new FootprintStopDto(
                stop.surfaceType().name(),
                stop.surfaceId(),
                stop.title(),
                stop.context(),
                stop.latestVisitAt().toString(),
                stop.visitCount());
    }
}
