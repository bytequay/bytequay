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
package com.bytequay.app.beans.personas;

/**
 * Request body for {@code POST /api/personas} (create) and
 * {@code PUT /api/personas/{id}} (update). Same shape both times — on
 * create, {@code id} is ignored (the service mints a fresh UUID); on
 * update, the path parameter wins regardless of what's in the body.
 *
 * <p>Validation is done in the service impl rather than via bean-
 * validation annotations so the error messages stay specific to the
 * persona contract (role must be LEAD/REVIEWER, name must be
 * non-blank, etc.).
 *
 * @param name          short display label
 * @param systemPrompt  reviewing voice — flows into per-reviewer
 *                      system message on each pass
 * @param role          {@code "LEAD"} or {@code "REVIEWER"}
 */
public record PersonaRequest(
        String name,
        String systemPrompt,
        String role) {}
