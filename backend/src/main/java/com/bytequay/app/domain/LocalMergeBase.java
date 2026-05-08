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
 * Branch-point info for the Commits tab: the sha where the branch
 * diverged from {@code base}, plus the resolved base name (after
 * "origin/" fallback) so the UI can render an accurate
 * "branched from <base>" divider. Either field may be null when no
 * common ancestor exists or the base couldn't be resolved.
 */
public record LocalMergeBase(
        String sha,
        String base) {}
