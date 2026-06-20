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

import java.time.Instant;

/**
 * One stop on the footprints trail — a single surface visited during the
 * day, merged across all its visits. {@code visitCount} is how many
 * times it was opened (the "N×" badge); {@code latestVisitAt} is the
 * most-recent visit, which fixes the stop's position on the trail.
 * {@code title} / {@code context} are the snapshot from that latest
 * visit.
 */
public record FootprintStop(
        SurfaceType surfaceType,
        String surfaceId,
        String title,
        String context,
        Instant latestVisitAt,
        int visitCount) {}
