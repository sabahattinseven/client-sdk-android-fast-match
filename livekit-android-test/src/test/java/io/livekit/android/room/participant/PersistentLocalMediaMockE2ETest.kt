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

package io.livekit.android.room.participant

import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.test.mock.MockVideoCapturer
import io.livekit.android.test.mock.MockVideoStreamTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class PersistentLocalMediaMockE2ETest : MockE2ETest() {

    private fun createLocalTrack(
        source: VideoSource = mock(VideoSource::class.java),
        capturer: VideoCapturer = MockVideoCapturer(),
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

    @Test
    fun publishedTrackSurvivesDisconnectWhenRetained() = runTest {
        connect()
        room.keepLocalMediaOnDisconnect = true

        val source = mock(VideoSource::class.java)
        val capturer = mock(VideoCapturer::class.java)
        val videoTrack = createLocalTrack(source = source, capturer = capturer)
        room.localParticipant.publishVideoTrack(videoTrack)

        room.disconnect()

        Mockito.verify(source, Mockito.never()).dispose()
        Mockito.verify(capturer, Mockito.never()).stopCapture()
        Mockito.verify(capturer, Mockito.never()).dispose()
        assertEquals(0, room.localParticipant.videoTrackPublications.size)
    }

    @Test
    fun retainedTrackRepublishesAfterReconnect() = runTest {
        connect()
        room.keepLocalMediaOnDisconnect = true

        val videoTrack = createLocalTrack()
        room.localParticipant.publishVideoTrack(videoTrack)

        room.disconnect()
        connect()

        assertTrue(room.localParticipant.publishVideoTrack(videoTrack))
        assertSame(videoTrack, room.localParticipant.videoTrackPublications.first().second)
    }

    @Test
    fun disconnectWithoutRetentionDisposesDefaultTrack() = runTest {
        connect()

        val source = mock(VideoSource::class.java)
        val videoTrack = createLocalTrack(source = source)
        room.localParticipant.defaultVideoTrack = videoTrack
        room.localParticipant.publishVideoTrack(videoTrack)

        room.disconnect()

        Mockito.verify(source, Mockito.times(1)).dispose()
        assertNull(room.localParticipant.defaultVideoTrack)
    }

    @Test
    fun retainedDefaultTrackKeptUntilRelease() = runTest {
        connect()
        room.keepLocalMediaOnDisconnect = true

        val source = mock(VideoSource::class.java)
        val videoTrack = createLocalTrack(source = source)
        room.localParticipant.defaultVideoTrack = videoTrack
        room.localParticipant.publishVideoTrack(videoTrack)

        room.disconnect()

        Mockito.verify(source, Mockito.never()).dispose()
        assertSame(videoTrack, room.localParticipant.defaultVideoTrack)

        room.release()

        Mockito.verify(source, Mockito.times(1)).dispose()
        assertNull(room.localParticipant.defaultVideoTrack)
    }

    @Test
    fun retainedDefaultTrackDisposedOnceAcrossCycles() = runTest {
        connect()
        room.keepLocalMediaOnDisconnect = true

        val source = mock(VideoSource::class.java)
        val videoTrack = createLocalTrack(source = source)
        room.localParticipant.defaultVideoTrack = videoTrack

        repeat(3) {
            room.localParticipant.publishVideoTrack(videoTrack)
            room.disconnect()
            connect()
        }
        room.release()

        Mockito.verify(source, Mockito.times(1)).dispose()
    }

    @Test
    fun explicitUnpublishStillStopsTrackWhenRetained() = runTest {
        connect()
        room.keepLocalMediaOnDisconnect = true

        val capturer = mock(VideoCapturer::class.java)
        val videoTrack = createLocalTrack(capturer = capturer)
        room.localParticipant.publishVideoTrack(videoTrack)

        room.localParticipant.unpublishTrack(videoTrack)

        Mockito.verify(capturer, Mockito.atLeastOnce()).stopCapture()
        assertEquals(0, room.localParticipant.videoTrackPublications.size)
    }
}
