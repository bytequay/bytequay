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
function NotificationsScreen() {
  return (
    <section className="notifications-screen">
      <header className="notifications-screen__head">
        <h1 className="notifications-screen__title">Notifications</h1>
        <p className="notifications-screen__subtitle">
          Mentions, review requests, and team activity will land here.
        </p>
      </header>
      <div className="settings-stub">
        <div className="settings-stub__title">Coming soon</div>
        <div>
          You'll see a feed of @-mentions, blocking-PR alerts, and per-team digests.
          Quiet hours and per-team mute toggles ship under <em>Settings → Notifications</em>
          once the feed is live.
        </div>
      </div>
    </section>
  );
}

export default NotificationsScreen;
