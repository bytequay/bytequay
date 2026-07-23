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
package com.bytequay.app.beans.review;

import java.util.List;

/** Body for submitting a private local review: optional selected comment ids,
 *  a free-text PR-level comment, and a verdict ({@code COMMENT} / {@code
 *  APPROVE} / {@code REQUEST_CHANGES}). A null {@code commentIds} selects all
 *  pending user roots; an explicit list dispatches only those roots. */
public record SubmitReviewRequest(String body, String verdict, List<String> commentIds)
{
}
