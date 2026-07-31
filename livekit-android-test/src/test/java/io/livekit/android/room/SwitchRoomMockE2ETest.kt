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

package io.livekit.android.room

import io.livekit.android.events.RoomEvent
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.flow
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class SwitchRoomMockE2ETest : MockE2ETest() {

    private var switchResult: Result<Unit>? = null

    private fun launchSwitch(
        url: String = "ws://www.example2.com",
        token: String = "token_b",
    ): Job {
        switchResult = null
        return coroutineRule.scope.launch {
            switchResult = room.switchRoom(url, token)
        }
    }

    private fun TestScope.completeSwitchHandshake(
        joinResponse: LivekitRtc.SignalResponse = TestData.JOIN_B,
        offer: LivekitRtc.SignalResponse = TestData.OFFER_B,
    ) {
        testScheduler.advanceTimeBy(1000)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(joinResponse)
        testScheduler.advanceTimeBy(1000)
        simulateMessageFromServer(offer)
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceUntilIdle()
    }

    private suspend fun TestScope.switchRoom(
        joinResponse: LivekitRtc.SignalResponse = TestData.JOIN_B,
    ): Result<Unit> {
        val job = launchSwitch()
        completeSwitchHandshake(joinResponse)
        job.join()
        return switchResult!!
    }

    @Test
    fun switchRoomConnectsToNewRoom() = runTest {
        connect()

        val result = switchRoom()

        assertTrue(result.isSuccess)
        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals("room_b_name", room.name)
        assertTrue(room.remoteParticipants.containsKey(Participant.Identity(TestData.REMOTE_PARTICIPANT_B.identity)))
    }

    @Test
    fun switchRoomNeverDisconnects() = runTest {
        connect()

        val stateHistory = mutableListOf<Room.State>()
        val stateJob = coroutineRule.scope.launch {
            room::state.flow.collect { stateHistory.add(it) }
        }

        switchRoom()
        stateJob.cancel()

        assertFalse(stateHistory.contains(Room.State.DISCONNECTED))
        assertTrue(stateHistory.contains(Room.State.SWITCHING))
    }

    @Test
    fun switchRoomTearsDownOldTransport() = runTest {
        connect()
        val oldWs = wsFactory.ws
        val oldSub = getSubscriberPeerConnection()
        val oldPub = getPublisherPeerConnection()

        switchRoom()

        assertTrue(oldWs.isClosed)
        assertEquals(PeerConnection.IceConnectionState.CLOSED, oldSub.iceConnectionState())
        assertEquals(PeerConnection.IceConnectionState.CLOSED, oldPub.iceConnectionState())
        assertNotSame(oldWs, wsFactory.ws)
        assertNotSame(oldSub, getSubscriberPeerConnection())
        assertNotSame(oldPub, getPublisherPeerConnection())
    }

    @Test
    fun switchRoomEventSequence() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

        val collector = EventCollector(room.events, coroutineRule.scope)
        switchRoom()
        val events = collector.stopCollecting()

        assertIsClass(RoomEvent.RoomSwitching::class.java, events.first())
        assertTrue(events.any { it is RoomEvent.ParticipantDisconnected })
        assertIsClass(RoomEvent.RoomSwitched::class.java, events.last())
        assertFalse(events.any { it is RoomEvent.Disconnected })
        assertFalse(events.any { it is RoomEvent.Connected })
    }

    @Test
    fun switchRoomRepublishesSameTrackObjects() = runTest {
        connect()
        val source = mock(VideoSource::class.java)
        val capturer = mock(VideoCapturer::class.java)
        val videoTrack = createLocalVideoTrack(source, capturer)
        room.localParticipant.publishVideoTrack(videoTrack)

        val result = switchRoom()

        assertTrue(result.isSuccess)
        assertEquals(1, room.localParticipant.videoTrackPublications.size)
        assertSame(videoTrack, room.localParticipant.videoTrackPublications.first().second)
        Mockito.verify(capturer, Mockito.never()).stopCapture()
        Mockito.verify(capturer, Mockito.never()).dispose()
        Mockito.verify(source, Mockito.never()).dispose()

        val sentAddTrack = wsFactory.ws.sentRequests.any { requestString ->
            LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(requestString.toPBByteString())
                .build()
                .hasAddTrack()
        }
        assertTrue(sentAddTrack)
    }

    @Test
    fun secondSwitchAbortsFirst() = runTest {
        connect()

        // Start switch to B, but never let its handshake finish.
        val jobB = launchSwitch(url = "ws://www.example-b.com", token = "token_b")
        testScheduler.advanceTimeBy(1000)
        val resultBBeforeAbort = switchResult
        val wsB = wsFactory.ws
        val listenerB = wsFactory.listener

        // Immediately skip again to C.
        val jobC = coroutineRule.scope.launch {
            switchResult = room.switchRoom("ws://www.example-c.com", "token_c")
        }
        completeSwitchHandshake(joinResponse = TestData.joinResponse(roomName = "room_c_name"))
        jobB.join()
        jobC.join()

        // Late JOIN for B lands on the abandoned socket and must be ignored.
        listenerB.onMessage(wsB, TestData.JOIN_B.toOkioByteString())
        advanceUntilIdle()

        assertNull(resultBBeforeAbort)
        assertTrue(switchResult!!.isSuccess)
        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals("room_c_name", room.name)
    }

    @Test
    fun disconnectDuringSwitchRetainsMedia() = runTest {
        connect()
        room.keepLocalMediaOnDisconnect = true
        val source = mock(VideoSource::class.java)
        val videoTrack = createLocalVideoTrack(source = source)
        room.localParticipant.defaultVideoTrack = videoTrack
        room.localParticipant.publishVideoTrack(videoTrack)

        val job = launchSwitch()
        testScheduler.advanceTimeBy(1000)
        room.disconnect()
        job.join()
        advanceUntilIdle()

        assertEquals(Room.State.DISCONNECTED, room.state)
        Mockito.verify(source, Mockito.never()).dispose()

        room.release()
        Mockito.verify(source, Mockito.times(1)).dispose()
    }

    @Test
    fun switchRoomFromDisconnectedFails() = runTest {
        connect()
        room.disconnect()

        val result = room.switchRoom("ws://www.example2.com", "token_b")

        assertTrue(result.isFailure)
    }

    @Test
    fun switchRoomDuringInitialConnectFails() = runTest {
        // Start a connect but never deliver the join response.
        val connectJob = coroutineRule.scope.launch {
            runCatching { room.connect(url = "ws://www.example.com", token = "token") }
        }
        testScheduler.advanceTimeBy(100)
        assertEquals(Room.State.CONNECTING, room.state)

        val result = room.switchRoom("ws://www.example2.com", "token_b")

        assertTrue(result.isFailure)
        connectJob.cancel()
        advanceUntilIdle()
    }

    @Test
    fun abortedSwitchDoesNotPoisonNextSwitch() = runTest {
        connect()

        // Switch to B fails after B's socket errors out, but a switch to C has
        // already replaced it: C must succeed and stay connected.
        val jobB = launchSwitch(url = "ws://www.example-b.com", token = "token_b")
        testScheduler.advanceTimeBy(1000)

        val jobC = coroutineRule.scope.launch {
            switchResult = room.switchRoom("ws://www.example-c.com", "token_c")
        }
        completeSwitchHandshake(joinResponse = TestData.joinResponse(roomName = "room_c_name"))
        jobB.join()
        jobC.join()
        advanceUntilIdle()

        assertTrue(switchResult!!.isSuccess)
        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals("room_c_name", room.name)
    }

    @Test
    fun switchRoomFailureDisconnects() = runTest {
        connect()

        val job = launchSwitch()
        testScheduler.advanceTimeBy(1000)
        // Fail the new socket.
        wsFactory.listener.onFailure(wsFactory.ws, IOException("mock failure"), null)
        advanceUntilIdle()
        job.join()

        assertTrue(switchResult!!.isFailure)
        assertEquals(Room.State.DISCONNECTED, room.state)

        // Recovery via a fresh connect must work.
        connect()
        assertEquals(Room.State.CONNECTED, room.state)
    }

    private fun createLocalVideoTrack(
        source: VideoSource = mock(VideoSource::class.java),
        capturer: VideoCapturer = mock(VideoCapturer::class.java),
    ) = LocalVideoTrack(
        capturer = capturer,
        source = source,
        name = "",
        options = LocalVideoTrackOptions(
            isScreencast = false,
            deviceId = null,
            position = null,
            captureParams = VideoCaptureParameter(width = 1280, height = 720, maxFps = 30),
        ),
        rtcTrack = MockVideoStreamTrack(),
        peerConnectionFactory = component.peerConnectionFactory(),
        context = context,
        eglBase = MockEglBase(),
        defaultsManager = DefaultsManager(),
        trackFactory = mock(LocalVideoTrack.Factory::class.java),
        rtcThreadToken = MockRTCThreadToken(),
    )
}
