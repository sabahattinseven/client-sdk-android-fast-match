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

import android.content.Context
import android.os.SystemClock
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.LKLog
import io.livekit.android.util.rethrowIfCancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Options for creating a [FastMatchSession].
 *
 * The default [roomOptions] are tuned for 1:1 fast matching: no simulcast, no
 * adaptive stream/dynacast, H.264 video.
 */
class FastMatchOptions(
    val roomOptions: RoomOptions = fastMatchRoomOptions(),
    val overrides: LiveKitOverrides = LiveKitOverrides(),
) {
    companion object {
        /**
         * Room options tuned for 1:1 fast matching.
         */
        fun fastMatchRoomOptions(): RoomOptions = RoomOptions(
            adaptiveStream = false,
            dynacast = false,
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                simulcast = false,
                videoCodec = VideoCodec.H264.codecName,
            ),
        )
    }
}

/**
 * A high-level session for OmeTV-style 1:1 fast video matching.
 *
 * Wraps a single [Room] that is reused across matches: the camera, microphone and
 * audio session stay live between matches, and switching to the next match reuses
 * the existing local tracks so the self-view never blinks.
 *
 * Typical flow:
 * ```
 * val session = FastMatchSession.create(context)
 * session.start()                          // camera preview + mic, no network
 * session.joinMatch(url, tokenForMatch1)   // first match
 * session.prepareNext(url)                 // warm up as soon as next match is known
 * session.nextMatch(url, tokenForMatch2)   // skip: switches without dropping media
 * ...
 * session.end()                            // releases everything
 * ```
 *
 * Matchmaking itself (deciding who pairs with whom and minting tokens) lives in
 * your backend; this class only makes the transition between matches fast.
 */
@Suppress("TooManyFunctions") // Intentionally a wide façade over Room.
class FastMatchSession internal constructor(
    /**
     * The underlying [Room], for anything not covered by this class
     * (e.g. `room.initVideoRenderer`).
     */
    val room: Room,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        /**
         * Creates a fast-match session backed by a new [Room].
         */
        fun create(
            context: Context,
            options: FastMatchOptions = FastMatchOptions(),
        ): FastMatchSession {
            val room = LiveKit.create(context, options.roomOptions, options.overrides)
            return FastMatchSession(room)
        }

        private const val CHAT_TOPIC = "chat"
    }

    private val sessionScope = CoroutineScope(coroutineDispatcher + SupervisorJob())

    private val stateFlow = MutableStateFlow(FastMatchState.IDLE)

    /** High-level session state. */
    val state: StateFlow<FastMatchState> = stateFlow

    private val eventsFlow = MutableSharedFlow<FastMatchEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Session events. See [FastMatchEvent]. */
    val events: SharedFlow<FastMatchEvent> = eventsFlow

    private val localVideoTrackFlow = MutableStateFlow<LocalVideoTrack?>(null)

    /**
     * The local camera track for the self-view. The same track object is kept for
     * the entire session, so a renderer attached to it never needs re-attaching.
     */
    val localVideoTrack: StateFlow<LocalVideoTrack?> = localVideoTrackFlow

    private val metricsFlow = MutableSharedFlow<FastMatchMetrics>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Per-match latency milestones. A snapshot is emitted each time a milestone is
     * reached; see [FastMatchMetrics].
     */
    val metrics: SharedFlow<FastMatchMetrics> = metricsFlow

    @Volatile
    private var cameraDesired = false

    @Volatile
    private var micDesired = false

    private var connectAttempt: Deferred<Unit>? = null
    private val connectRouteMutex = Mutex()

    @Volatile
    private var currentPeer: RemoteParticipant? = null

    private val metricsLock = Any()
    private var currentMetrics: FastMatchMetrics? = null
    private var matchCount = 0

    init {
        room.keepLocalMediaOnDisconnect = true
        sessionScope.launch {
            room.events.collect { event -> handleRoomEvent(event) }
        }
    }

    /**
     * Starts local media without any network activity: opens the camera for the
     * self-view, prewarms the microphone, and acquires the audio session. Call
     * when entering the match UI, before or while the first match is requested.
     */
    fun start(cameraEnabled: Boolean = true, micEnabled: Boolean = true): Result<Unit> {
        if (stateFlow.value == FastMatchState.ENDED) {
            return Result.failure(IllegalStateException("Session has ended."))
        }
        cameraDesired = cameraEnabled
        micDesired = micEnabled
        return try {
            room.startAudioSession()
            if (micEnabled) {
                room.localParticipant.getOrCreateDefaultAudioTrack().prewarm()
            }
            if (cameraEnabled) {
                val track = room.localParticipant.getOrCreateDefaultVideoTrack()
                track.startCapture()
                localVideoTrackFlow.value = track
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            Result.failure(e)
        }
    }

    /**
     * Joins the first match (or a match after a disconnect).
     */
    suspend fun joinMatch(url: String, token: String): Result<Unit> {
        if (stateFlow.value == FastMatchState.ENDED) {
            return Result.failure(IllegalStateException("Session has ended."))
        }
        beginMatchMetrics()
        return connectToMatch(url, token).emitErrorEvents()
    }

    /**
     * Skips to the next match. If currently in a match, switches rooms without
     * touching local media; the aborted/ended match emits
     * [FastMatchEvent.MatchEnded] with [MatchEndReason.SKIPPED].
     *
     * Safe to call rapidly in any state: a call while a previous switch or join is
     * still in flight aborts the previous attempt and starts the new one.
     */
    suspend fun nextMatch(url: String, token: String): Result<Unit> {
        if (stateFlow.value == FastMatchState.ENDED) {
            return Result.failure(IllegalStateException("Session has ended."))
        }
        beginMatchMetrics()
        return when (room.state) {
            Room.State.DISCONNECTED, Room.State.CONNECTING -> connectToMatch(url, token)
            else -> room.switchRoom(url, token)
        }.emitErrorEvents()
    }

    /**
     * Connects to a match room, aborting and replacing any connect attempt that is
     * still in flight (skip-spam during the initial connect).
     */
    private suspend fun connectToMatch(url: String, token: String): Result<Unit> {
        val attempt: Deferred<Unit>
        connectRouteMutex.withLock {
            connectAttempt?.let {
                it.cancel()
                it.join()
            }
            connectAttempt = null
            if (room.state != Room.State.DISCONNECTED) {
                // An aborted connect can leave the room CONNECTING; reset it.
                // Local media survives via keepLocalMediaOnDisconnect.
                room.disconnect()
            }
            stateFlow.value = FastMatchState.CONNECTING
            attempt = sessionScope.async {
                room.connect(
                    url = url,
                    token = token,
                    options = ConnectOptions(audio = micDesired, video = cameraDesired),
                )
            }
            connectAttempt = attempt
        }

        return try {
            attempt.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(CancellationException("Match join was aborted by a newer request.", e))
        } catch (e: Exception) {
            stateFlow.value = FastMatchState.IDLE
            Result.failure(e)
        }
    }

    private fun Result<Unit>.emitErrorEvents(): Result<Unit> = onFailure { error ->
        if (error !is CancellationException) {
            eventsFlow.tryEmit(FastMatchEvent.SessionError(error))
        }
    }

    /**
     * Warms up the connection (DNS/TLS) to the next match's server. Works in any
     * connection state — call as soon as the matchmaker reveals where the next
     * match will be, even while still connected to the current one.
     */
    fun prepareNext(url: String) {
        sessionScope.launch {
            room.warmConnection(url)
        }
    }

    /**
     * Sends an in-match text chat message to the peer.
     */
    suspend fun sendChat(message: String): Result<Unit> {
        return room.localParticipant.publishData(
            data = message.toByteArray(Charsets.UTF_8),
            reliability = DataPublishReliability.RELIABLE,
            topic = CHAT_TOPIC,
        )
    }

    /**
     * Enables or disables the camera. The preference carries over to future
     * matches.
     */
    suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> {
        cameraDesired = enabled
        return try {
            if (room.state == Room.State.CONNECTED) {
                room.localParticipant.setCameraEnabled(enabled)
                localVideoTrackFlow.value = room.localParticipant
                    .getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                    ?: localVideoTrackFlow.value
            } else {
                val track = room.localParticipant.getOrCreateDefaultVideoTrack()
                if (enabled) {
                    track.startCapture()
                    localVideoTrackFlow.value = track
                } else {
                    track.stopCapture()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            Result.failure(e)
        }
    }

    /**
     * Enables or disables the microphone. The preference carries over to future
     * matches.
     */
    suspend fun setMicEnabled(enabled: Boolean): Result<Unit> {
        micDesired = enabled
        return try {
            if (room.state == Room.State.CONNECTED) {
                room.localParticipant.setMicrophoneEnabled(enabled)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            Result.failure(e)
        }
    }

    /**
     * Switches between front and back camera without interrupting the match.
     */
    fun switchCamera(deviceId: String? = null, position: CameraPosition? = null) {
        (localVideoTrackFlow.value ?: room.localParticipant.defaultVideoTrack)
            ?.switchCamera(deviceId, position)
    }

    /**
     * Call when the app goes to the background: releases the camera device (an
     * Android requirement) while keeping the track object and renderers intact.
     */
    fun onAppBackgrounded() {
        localVideoTrackFlow.value?.stopCapture()
    }

    /**
     * Call when the app returns to the foreground: resumes camera capture on the
     * same track, so the self-view recovers without re-attaching renderers.
     */
    fun onAppForegrounded() {
        if (cameraDesired) {
            localVideoTrackFlow.value?.startCapture()
        }
    }

    /**
     * Leaves the current match but keeps local media hot (e.g. while the
     * matchmaker searches for the next match).
     */
    fun leaveMatch() {
        room.disconnect()
    }

    /**
     * Ends the session and releases all resources, including the camera, mic and
     * audio session. The session is unusable afterwards.
     */
    fun end() {
        if (stateFlow.value == FastMatchState.ENDED) {
            return
        }
        stateFlow.value = FastMatchState.ENDED
        sessionScope.cancel()
        localVideoTrackFlow.value = null
        currentPeer = null
        room.release()
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> onMatchRoomConnected()
            is RoomEvent.RoomSwitched -> onMatchRoomConnected()

            is RoomEvent.RoomSwitching -> {
                endCurrentMatch(MatchEndReason.SKIPPED)
                stateFlow.value = FastMatchState.SWITCHING
            }

            is RoomEvent.ParticipantConnected -> maybeStartMatch(event.participant)

            is RoomEvent.ParticipantDisconnected -> {
                if (event.participant === currentPeer && stateFlow.value == FastMatchState.IN_MATCH) {
                    endCurrentMatch(MatchEndReason.PEER_LEFT)
                    stateFlow.value = FastMatchState.WAITING_FOR_PEER
                }
            }

            is RoomEvent.TrackSubscribed -> onTrackSubscribed(event)

            is RoomEvent.Disconnected -> {
                endCurrentMatch(MatchEndReason.DISCONNECTED)
                if (stateFlow.value != FastMatchState.ENDED) {
                    stateFlow.value = FastMatchState.IDLE
                }
            }

            is RoomEvent.DataReceived -> {
                if (event.topic == CHAT_TOPIC) {
                    eventsFlow.tryEmit(
                        FastMatchEvent.ChatReceived(
                            message = event.data.toString(Charsets.UTF_8),
                            peer = event.participant,
                        ),
                    )
                }
            }

            else -> {}
        }
    }

    private fun onMatchRoomConnected() {
        updateMetrics { it.copy(connectedAt = SystemClock.elapsedRealtime()) }
        val peer = room.remoteParticipants.values.firstOrNull()
        if (peer != null) {
            maybeStartMatch(peer)
        } else {
            stateFlow.value = FastMatchState.WAITING_FOR_PEER
        }
    }

    private fun maybeStartMatch(peer: RemoteParticipant) {
        if (currentPeer === peer) {
            return
        }
        currentPeer = peer
        stateFlow.value = FastMatchState.IN_MATCH
        eventsFlow.tryEmit(FastMatchEvent.MatchStarted(peer))
    }

    private fun endCurrentMatch(reason: MatchEndReason) {
        val hadMatch = currentPeer != null
        currentPeer = null
        if (hadMatch) {
            eventsFlow.tryEmit(FastMatchEvent.MatchEnded(reason))
        }
    }

    private fun onTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        val participant = event.participant as? RemoteParticipant ?: return
        maybeStartMatch(participant)
        when (val track = event.track) {
            is VideoTrack -> {
                updateMetrics { it.copy(remoteVideoSubscribedAt = SystemClock.elapsedRealtime()) }
                eventsFlow.tryEmit(FastMatchEvent.PeerVideoReady(participant, track))
                attachFirstFrameSink(track)
            }

            is AudioTrack -> {
                eventsFlow.tryEmit(FastMatchEvent.PeerAudioReady(participant, track))
            }

            else -> {}
        }
    }

    private fun attachFirstFrameSink(track: VideoTrack) {
        val recorded = AtomicBoolean(false)
        val sinkRef = arrayOfNulls<VideoSink>(1)
        val sink = VideoSink { _ ->
            if (recorded.compareAndSet(false, true)) {
                val now = SystemClock.elapsedRealtime()
                sessionScope.launch {
                    sinkRef[0]?.let { track.removeRenderer(it) }
                    onFirstFrameRendered(now)
                }
            }
        }
        sinkRef[0] = sink
        track.addRenderer(sink)
    }

    private fun onFirstFrameRendered(timestamp: Long) {
        updateMetrics { it.copy(firstFrameRenderedAt = timestamp) }
        currentMetrics?.let { m ->
            LKLog.i {
                "FastMatch#${m.matchIndex} " +
                    "request→connected=${m.connectedAt?.minus(m.matchRequestedAt)}ms " +
                    "connected→video=${m.remoteVideoSubscribedAt?.let { v -> m.connectedAt?.let { c -> v - c } }}ms " +
                    "video→frame=${m.remoteVideoSubscribedAt?.let { v -> timestamp - v }}ms " +
                    "total=${m.totalMs}ms"
            }
        }
    }

    private fun beginMatchMetrics() {
        val metrics = synchronized(metricsLock) {
            matchCount++
            FastMatchMetrics(
                matchIndex = matchCount,
                matchRequestedAt = SystemClock.elapsedRealtime(),
            ).also { currentMetrics = it }
        }
        metricsFlow.tryEmit(metrics)
    }

    private fun updateMetrics(update: (FastMatchMetrics) -> FastMatchMetrics) {
        val updated = synchronized(metricsLock) {
            val current = currentMetrics ?: return
            update(current).also { currentMetrics = it }
        }
        metricsFlow.tryEmit(updated)
    }
}
