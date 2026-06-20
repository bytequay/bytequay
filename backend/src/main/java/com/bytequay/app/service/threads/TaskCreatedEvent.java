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
package com.bytequay.app.service.threads;

/**
 * Fired right after a brand-new Task row is first persisted — the first
 * task a thread materialises and each ship-and-continue successor.
 * Listeners that need to initialise per-Task state (e.g. opening the
 * Development stage) react to it; it carries no payload beyond the id, so
 * a listener loads whatever it needs.
 */
public record TaskCreatedEvent(String taskId)
{
}
