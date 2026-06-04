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
package com.bytequay.app.service.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-field metadata for an args record component. Used by
 * {@link AgentToolRegistry} to populate the JSON inputSchema's
 * {@code properties[name].description} and {@code required} array.
 *
 * <p>Apply to record components inside an args record:
 * <pre>
 *   public record CreateTaskArgs(
 *       {@literal @}ToolParam(description = "...", required = true) String title,
 *       {@literal @}ToolParam(description = "...") String baseBranch) {}
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
public @interface ToolParam
{
    /** Free-form description shown to the model alongside the field. */
    String description();

    /** When true the field is added to the schema's {@code required}
     *  array. Defaults to false so the common case (optional fields)
     *  stays terse. */
    boolean required() default false;

    /** Wire name override — useful when the JSON-RPC schema uses
     *  snake_case but the Java record component is camelCase. Empty
     *  means "use the record component name verbatim". */
    String wireName() default "";

    /**
     * Cross-link to the concept axis. When set, each value names a
     * {@link com.bytequay.app.service.concepts.Concept} the
     * registry must resolve at scan time; the schema generator
     * renders an {@code enum} of the values plus a per-value
     * {@code oneOf[const]+description} block so the agent reads
     * the meaning of each enum value where it's used, not via a
     * separate lookup call.
     */
    String[] enumFromConcepts() default {};
}
