package io.github.psd2live.core

import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*
import kotlin.test.*

class EyeOpacityChannelsTest {
    @Test fun closedEyeHidesRasterFragmentsAndRetainsToggleAndLayerOpacity() {
        val layer = classify(rasterLayer("eyewhite-l", 20, 10).copy(opacity = .6f))
        val bare = eyeOpacityChannels(layer, ChannelGrids.Empty)
        fun read(channels: ChannelGrids, open: Float, toggle: Float = 1f): Float =
            channels.scalarAt(FormChannel.OPACITY, 1f, { if (it == StandardParameters.EYE_L_OPEN) open else toggle })
        assertEquals(0f, read(bare, 0f))
        assertEquals(.3f, read(bare, .05f), 1e-6f)
        assertEquals(.6f, read(bare, .1f), 1e-6f)
        assertEquals(.6f, read(bare, 1f), 1e-6f)
        val toggle = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(
            listOf(KeyformAxis(ParameterId("EyeVisible"), floatArrayOf(0f, 1f))),
            listOf(KeyformCell(intArrayOf(0), ChannelValue.Scalar(0f)), KeyformCell(intArrayOf(1), ChannelValue.Scalar(.8f))))))
        val combined = eyeOpacityChannels(layer, toggle)
        assertEquals(0f, read(combined, 0f))
        assertEquals(0f, read(combined, 1f, 0f))
        assertEquals(.8f, read(combined, 1f), 1e-6f)
    }

    @Test fun combinedEyeLayerRemainsVisibleForTheOtherOpenEye() {
        val layer = classify(rasterLayer("eyewhite", 40, 10))
        val channels = eyeOpacityChannels(layer, ChannelGrids.Empty)
        val wink = channels.scalarAt(FormChannel.OPACITY, 1f, { if (it == StandardParameters.EYE_L_OPEN) 0f else 1f })
        assertEquals(1f, wink)
        assertEquals(0f, channels.scalarAt(FormChannel.OPACITY, 1f, { 0f }))
    }
}
