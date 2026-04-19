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

import java.time.LocalDate;

/**
 * The "daily card" shown on the home page — exactly one per day, stable
 * for the whole day. Card types per docs/mockups/v2/home/quote.md:
 * {@code quote} (primary), {@code review_tip}, {@code open_source_tip},
 * {@code tiny_challenge}, {@code joke}.
 *
 * @param type    one of the supported types above
 * @param text    the body — quote text, tip body, challenge prompt, etc.
 * @param author  attribution for {@code quote} cards; null otherwise
 * @param role    secondary attribution (e.g. "physicist", "founder of X");
 *                only used by {@code quote} cards, null otherwise
 * @param date    the date this card represents (ISO yyyy-MM-dd)
 */
public record DailyCard(
        String type,
        String text,
        String author,
        String role,
        LocalDate date) {}
