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
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class FastMatchCycleMockE2ETest : MockE2ETest() {

    private val cycles = 20

    /**
     * Coarse regression guard against slow retry/delay paths sneaking into the
     * switch flow. The handshake explicitly advances virtual time by 2000ms; any
     * significant overshoot means a delay-based retry fired during the switch.
     */
    private val perCycleVirtualTimeBudgetMs = 2500L

    private fun TestScope.doSwitch(roomName: String) {
        val job = coroutineRule.scope.launch {
            val result = room.switchRoom("ws://www.example2.com/$roomName", "token_$roomName")
            assertTrue("switch to $roomName failed: $result", result.isSuccess)
        }
        testScheduler.advanceTimeBy(1000)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.joinResponse(roomName = roomName))
        testScheduler.advanceTimeBy(1000)
        simulateMessageFromServer(TestData.OFFER_B)
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceUntilIdle()
        assertTrue(job.isCompleted)
    }

    @Test
    fun rapidSwitchCyclesRemainStable() = runTest {
        connect()
        room.keepLocalMediaOnDisconnect = true

        val source = mock(VideoSource::class.java)
        val capturer = mock(VideoCapturer::class.java)
        val videoTrack = createLocalVideoTrack(source, capturer)
        room.localParticipant.defaultVideoTrack = videoTrack
        room.localParticipant.publishVideoTrack(videoTrack)
        advanceUntilIdle()

        val collector = EventCollector(room.events, coroutineRule.scope)

        var transceiverCountAfterFirstCycle = -1
        repeat(cycles) { i ->
            val roomName = "match_$i"
            val startTime = testScheduler.currentTime
            doSwitch(roomName)
            val elapsed = testScheduler.currentTime - startTime

            assertTrue(
                "cycle $i took $elapsed virtual ms (budget $perCycleVirtualTimeBudgetMs)",
                elapsed <= perCycleVirtualTimeBudgetMs,
            )
            assertEquals(roomName, room.name)
            assertEquals(Room.State.CONNECTED, room.state)
            assertEquals(1, room.remoteParticipants.size)

            // Same track object republished every cycle.
            assertEquals(1, room.localParticipant.videoTrackPublications.size)
            assertSame(videoTrack, room.localParticipant.videoTrackPublications.first().second)

            val transceiverCount = getPublisherPeerConnection().transceivers.size
            if (i == 0) {
                transceiverCountAfterFirstCycle = transceiverCount
            } else {
                assertEquals(
                    "publisher transceiver count grew by cycle $i",
                    transceiverCountAfterFirstCycle,
                    transceiverCount,
                )
            }
        }

        val events = collector.stopCollecting()
        assertEquals(cycles, events.count { it is RoomEvent.RoomSwitching })
        assertEquals(cycles, events.count { it is RoomEvent.RoomSwitched })
        assertEquals(0, events.count { it is RoomEvent.Disconnected })
        assertEquals(0, events.count { it is RoomEvent.Connected })

        // Camera never touched across all cycles; released exactly once at the end.
        Mockito.verify(capturer, Mockito.never()).stopCapture()
        Mockito.verify(source, Mockito.never()).dispose()

        room.release()
        Mockito.verify(source, Mockito.times(1)).dispose()
    }

    private fun createLocalVideoTrack(
        source: VideoSource,
        capturer: VideoCapturer,
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
