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

import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.VideoTrack

/**
 * High-level state of a [FastMatchSession].
 */
enum class FastMatchState {
    /** Session created; local media may be warming but no match yet. */
    IDLE,

    /** Connecting to a match. */
    CONNECTING,

    /** Connected to a match room, waiting for the peer to arrive. */
    WAITING_FOR_PEER,

    /** Matched with a peer. */
    IN_MATCH,

    /** Switching to the next match. */
    SWITCHING,

    /** Session ended via [FastMatchSession.end]; no longer usable. */
    ENDED,
}

/**
 * Why the current match ended.
 */
enum class MatchEndReason {
    /** The remote peer left the match. */
    PEER_LEFT,

    /** The local user skipped to the next match. */
    SKIPPED,

    /** The room disconnected (network failure, server close, or [FastMatchSession.leaveMatch]). */
    DISCONNECTED,
}

/**
 * Events emitted by a [FastMatchSession].
 */
sealed class FastMatchEvent {

    /**
     * Matched with [peer]. Emitted once per match, as soon as the peer is known.
     */
    class MatchStarted(val peer: RemoteParticipant) : FastMatchEvent()

    /**
     * The peer's video track is subscribed and ready to render. This is the
     * primary signal for swapping the remote renderer to the new match.
     */
    class PeerVideoReady(val peer: RemoteParticipant, val track: VideoTrack) : FastMatchEvent()

    /**
     * The peer's audio track is subscribed and playing.
     */
    class PeerAudioReady(val peer: RemoteParticipant, val track: AudioTrack) : FastMatchEvent()

    /**
     * The current match ended. Local media stays live so the next match can
     * start immediately.
     */
    class MatchEnded(val reason: MatchEndReason) : FastMatchEvent()

    /**
     * An in-match chat message arrived from [peer].
     */
    class ChatReceived(val message: String, val peer: RemoteParticipant?) : FastMatchEvent()

    /**
     * A non-fatal session error. The session remains usable; a new match can be
     * requested via [FastMatchSession.nextMatch] or [FastMatchSession.joinMatch].
     */
    class SessionError(val error: Throwable) : FastMatchEvent()
}
