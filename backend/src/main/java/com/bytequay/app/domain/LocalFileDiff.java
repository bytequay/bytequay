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
 * One file's unified diff at a specific commit. Drives the right pane
 * of the Commits tab. Truncated when the patch exceeds the cap so a
 * giant change doesn't blow up the renderer.
 */
public record LocalFileDiff(
        String path,
        String patch,
        boolean truncated) {}
