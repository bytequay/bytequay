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
 * A single recorded visit to a tracked surface. {@code surfaceId} is the
 * renderer's navigable key for the surface (the backend stores it
 * verbatim and never parses it). {@code title} and {@code context} are a
 * point-in-time snapshot of the surface's label, so a trail stop renders
 * even after the surface is renamed or removed.
 */
public record SurfaceVisit(
        String id,
        SurfaceType surfaceType,
        String surfaceId,
        String title,
        String context,
        Instant visitedAt) {}
