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
import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import './css/error-details.css';

export default function ErrorDetails({ message, className = '' }: { message: string; className?: string }) {
  const copy = async () => {
    let version: string | null = null;
    try { version = (await window.bridge.getAppVersion()).version; }
    catch { /* The original failure can still be copied when app details are unavailable. */ }
    return version ? `ByteQuay ${version}\n\n${message}` : message;
  };
  return <div className={`error-details ${className}`}>
    <div className="error-details__message" role="alert">{message}</div>
    <CopyLinkButton key={message} value={copy} label="error details" showLabel />
  </div>;
}
