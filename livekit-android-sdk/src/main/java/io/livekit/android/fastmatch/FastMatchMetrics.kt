/*
 * Copyright 2026 LiveKit, Inc.
 *
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

package io.livekit.android.fastmatch

/**
 * Latency milestones for a single match, as [android.os.SystemClock.elapsedRealtime]
 * timestamps. A snapshot is emitted on [FastMatchSession.metrics] each time a
 * milestone is reached, so the last emission per [matchIndex] is the most complete.
 *
 * The headline number is [totalMs]: time from requesting the match to the first
 * remote video frame being rendered.
 */
data class FastMatchMetrics(
    /** Monotonically increasing index of the match within this session. */
    val matchIndex: Int,

    /** When the match was requested ([FastMatchSession.nextMatch]/[FastMatchSession.joinMatch]). */
    val matchRequestedAt: Long,

    /** When the room finished connecting/switching to the match room. */
    val connectedAt: Long? = null,

    /** When the peer's video track was subscribed. */
    val remoteVideoSubscribedAt: Long? = null,

    /** When the first remote video frame was rendered. */
    val firstFrameRenderedAt: Long? = null,
) {
    /** Total match-request-to-first-frame time, or null if no frame has rendered yet. */
    val totalMs: Long?
        get() = firstFrameRenderedAt?.minus(matchRequestedAt)
}
