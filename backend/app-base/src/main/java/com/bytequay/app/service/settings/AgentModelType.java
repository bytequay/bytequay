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
package com.bytequay.app.service.settings;

/**
 * The two kinds of agent work that pick a model independently. The split
 * mirrors {@link AgentSkillScope}, whose task classifier is the only existing
 * definition of what makes a run a review rather than development.
 */
public enum AgentModelType
{
    /** Task turns and CI Autofix. */
    DEVELOPMENT,
    /** Pull-request reviews and their discussions. */
    REVIEW
}
