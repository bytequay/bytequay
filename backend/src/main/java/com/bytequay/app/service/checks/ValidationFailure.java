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
package com.bytequay.app.service.checks;

/**
 * One failure surfaced by a {@link ValidationCheck} — handed to the
 * agent's fix turn and recorded on the validation_pass audit row.
 *
 * @param source where it came from, e.g. {@code "test"}, {@code
 *               "checkstyle"}, {@code "repo_rule"}
 * @param detail a one-line, human/agent-readable description
 */
public record ValidationFailure(String source, String detail)
{
}
