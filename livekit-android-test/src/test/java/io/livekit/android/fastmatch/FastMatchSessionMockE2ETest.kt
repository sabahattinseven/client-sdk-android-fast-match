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

import io.livekit.android.room.Room
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockMediaStream
import io.livekit.android.test.mock.MockRtpReceiver
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.createMediaStreamId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class FastMatchSessionMockE2ETest : MockE2ETest() {

    private lateinit var session: FastMatchSession
    private val collectedEvents = mutableListOf<FastMatchEvent>()
    private val collectedMetrics = mutableListOf<FastMatchMetrics>()

    @Before
    fun sessionSetup() {
        session = FastMatchSession(room, coroutineRule.dispatcher)
    }

    private fun TestScope.startCollectors() {
        backgroundScope.launch {
            session.events.collect { collectedEvents.add(it) }
        }
        backgroundScope.launch {
            session.metrics.collect { collectedMetrics.add(it) }
        }
    }

    private suspend fun TestScope.joinFirstMatch(): Result<Unit> {
        var result: Result<Unit>? = null
        val job = coroutineRule.scope.launch {
            result = session.joinMatch(TestData.EXAMPLE_URL, "token")
        }
        prepareSignal(TestData.JOIN)
        job.join()
        connectPeerConnection()
        advanceUntilIdle()
        return result!!
    }

    private fun TestScope.completeSwitchHandshake(
        joinResponse: livekit.LivekitRtc.SignalResponse = TestData.JOIN_B,
    ) {
        testScheduler.advanceTimeBy(1000)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(joinResponse)
        testScheduler.advanceTimeBy(1000)
        simulateMessageFromServer(TestData.OFFER_B)
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceUntilIdle()
    }

    @Test
    fun joinMatchEmitsMatchLifecycleEvents() = runTest {
        startCollectors()
        val result = joinFirstMatch()
        assertTrue(result.isSuccess)
        assertEquals(FastMatchState.WAITING_FOR_PEER, session.state.value)

        // Peer joins.
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()
        assertEquals(FastMatchState.IN_MATCH, session.state.value)
        assertTrue(collectedEvents.any { it is FastMatchEvent.MatchStarted })

        // Peer video arrives.
        room.onAddTrack(
            MockRtpReceiver.create(),
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid,
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(collectedEvents.any { it is FastMatchEvent.PeerVideoReady })

        // Peer leaves.
        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)
        advanceUntilIdle()
        val ended = collectedEvents.filterIsInstance<FastMatchEvent.MatchEnded>()
        assertEquals(1, ended.size)
        assertEquals(MatchEndReason.PEER_LEFT, ended.first().reason)
        assertEquals(FastMatchState.WAITING_FOR_PEER, session.state.value)
    }

    @Test
    fun nextMatchSwitchesAndStartsNewMatch() = runTest {
        startCollectors()
        joinFirstMatch()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

        var result: Result<Unit>? = null
        val job = coroutineRule.scope.launch {
            result = session.nextMatch("ws://www.example2.com", "token_b")
        }
        completeSwitchHandshake()
        job.join()

        assertTrue(result!!.isSuccess)
        assertEquals(FastMatchState.IN_MATCH, session.state.value)
        val ended = collectedEvents.filterIsInstance<FastMatchEvent.MatchEnded>()
        assertEquals(MatchEndReason.SKIPPED, ended.first().reason)
        // New match started with the JOIN_B peer.
        val started = collectedEvents.filterIsInstance<FastMatchEvent.MatchStarted>()
        assertEquals(2, started.size)
        assertEquals(TestData.REMOTE_PARTICIPANT_B.identity, started.last().peer.identity?.value)
    }

    @Test
    fun nextMatchDuringInitialConnectAbortsAndReplaces() = runTest {
        startCollectors()

        // Start the first join but never deliver its join response.
        var firstResult: Result<Unit>? = null
        val firstJob = coroutineRule.scope.launch {
            firstResult = session.joinMatch(TestData.EXAMPLE_URL, "token")
        }
        testScheduler.advanceTimeBy(100)
        assertEquals(Room.State.CONNECTING, room.state)

        // Spamming next while still connecting must abort and replace, not error.
        var secondResult: Result<Unit>? = null
        val secondJob = coroutineRule.scope.launch {
            secondResult = session.nextMatch("ws://www.example2.com", "token_b")
        }
        testScheduler.advanceTimeBy(100)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.JOIN_B)
        testScheduler.advanceTimeBy(100)
        simulateMessageFromServer(TestData.OFFER_B)
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceUntilIdle()
        firstJob.join()
        secondJob.join()

        assertTrue(firstResult!!.isFailure)
        assertTrue(secondResult!!.isSuccess)
        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals("room_b_name", room.name)
        assertEquals(FastMatchState.IN_MATCH, session.state.value)
    }

    @Test
    fun nextMatchSpamKeepsExactlyOneSwitch() = runTest {
        startCollectors()
        joinFirstMatch()

        val jobs = mutableListOf<Job>()
        repeat(3) { i ->
            jobs += coroutineRule.scope.launch {
                session.nextMatch("ws://www.example$i.com", "token_$i")
            }
            testScheduler.advanceTimeBy(100)
        }
        completeSwitchHandshake(joinResponse = TestData.joinResponse(roomName = "final_room"))
        jobs.forEach { it.join() }

        assertEquals("final_room", room.name)
        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals(FastMatchState.IN_MATCH, session.state.value)
    }

    @Test
    fun metricsAreMonotonicPerMatch() = runTest {
        startCollectors()
        joinFirstMatch()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        room.onAddTrack(
            MockRtpReceiver.create(),
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(collectedMetrics.isNotEmpty())
        val last = collectedMetrics.last()
        assertEquals(1, last.matchIndex)
        val connectedAt = last.connectedAt
        val videoAt = last.remoteVideoSubscribedAt
        assertTrue(connectedAt != null && connectedAt >= last.matchRequestedAt)
        assertTrue(videoAt != null && connectedAt != null && videoAt >= connectedAt)
    }

    @Test
    fun sendChatSucceedsInMatch() = runTest {
        joinFirstMatch()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

        val result = session.sendChat("hello")
        assertTrue(result.isSuccess)
    }

    @Test
    fun leaveMatchKeepsSessionUsable() = runTest {
        joinFirstMatch()
        session.leaveMatch()
        advanceUntilIdle()

        assertEquals(Room.State.DISCONNECTED, room.state)
        assertEquals(FastMatchState.IDLE, session.state.value)

        // Next match after a leave goes through connect() again.
        val result = joinFirstMatch()
        assertTrue(result.isSuccess)
    }
}
