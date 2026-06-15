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
 * How a queued task's branch is cut when it materialises.
 *
 * <ul>
 *   <li>{@link #MAIN} — off the per-repo merge target, resolved to the
 *       <em>current</em> tip of main at materialisation time (so it
 *       picks up any prior slices that have merged since the entry was
 *       queued). The default.</li>
 *   <li>{@link #STACKED_ON_PREVIOUS} — chained on the prior task's
 *       branch. Falls back to {@link #MAIN} (with a notification) when
 *       the prior slice was abandoned.</li>
 * </ul>
 */
public enum BranchBase
{
    MAIN("main"),
    STACKED_ON_PREVIOUS("stacked-on-previous");

    private final String wire;

    BranchBase(String wire)
    {
        this.wire = wire;
    }

    /** The dashed wire token the tools + UI exchange ('main' /
     *  'stacked-on-previous'), distinct from the enum name. */
    public String wire()
    {
        return wire;
    }

    /** Tolerant parse: accepts the wire token or the enum name; an
     *  unknown / null value falls back to {@link #MAIN} rather than
     *  throwing, so a stale persisted entry can't break a queue load. */
    public static BranchBase fromWire(String value)
    {
        if (value == null || value.isBlank()) {
            return MAIN;
        }
        for (BranchBase base : values()) {
            if (base.wire.equals(value) || base.name().equals(value)) {
                return base;
            }
        }
        return MAIN;
    }
}
