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
 * How a pull request came to be marked as handled.
 * APPROVED / MERGED come from actions the user took via the app;
 * COMMENTED, CHANGES_REQUESTED, DISMISSED cover other review paths (future use);
 * MANUAL is set when the user hits the "Handled" button on a card without reviewing.
 */
public enum HandledAction
{
    APPROVED,
    MERGED,
    COMMENTED,
    CHANGES_REQUESTED,
    DISMISSED,
    MANUAL
}
