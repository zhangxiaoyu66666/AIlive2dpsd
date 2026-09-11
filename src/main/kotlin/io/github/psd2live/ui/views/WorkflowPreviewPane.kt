package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.workflow.WorkflowLayer
import java.awt.image.BufferedImage

@Composable
internal fun WorkflowPreviewPane(image: BufferedImage?, empty: String, modifier: Modifier, emptyAction: (@Composable () -> Unit)? = null) {
    val colors = LocalToolColors.current
    var zoom by remember(image) { mutableStateOf(1f) }
    var pan by remember(image) { mutableStateOf(Offset.Zero) }
    Box(modifier.border(1.dp, colors.border).clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(colors.panelBackground)
            val cell = 16.dp.toPx()
            for (x in 0..(size.width / cell).toInt()) for (y in 0..(size.height / cell).toInt()) {
                if ((x + y) % 2 == 0) drawRect(colors.panelElevated, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
        if (image != null) {
            val bitmap = remember(image) { image.toComposeImageBitmap() }
            Image(bitmap, tr("flow.preview"), Modifier.fillMaxSize().pointerInput(image) {
                detectDragGestures { change, drag -> change.consume(); pan += drag }
            }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp).background(colors.panelBackground).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactButton("−", { zoom = (zoom / 1.25f).coerceAtLeast(0.25f) })
                CompactButton(tr("flow.fit"), { zoom = 1f; pan = Offset.Zero })
                CompactButton("+", { zoom = (zoom * 1.25f).coerceAtMost(8f) })
            }
        } else Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(empty, color = colors.textMuted, fontSize = 13.sp)
            emptyAction?.invoke()
        }
    }
}

@Composable
internal fun WorkflowLayerGallery(layers: List<WorkflowLayer>, selected: Int?, onSelect: (Int?) -> Unit) {
    val colors = LocalToolColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactButton(tr("flow.composite"), { onSelect(null) }, isPrimary = selected == null)
        Text(tr("flow.layerGallery"), color = colors.textMuted, fontSize = 11.sp)
    }
    LazyRow(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(layers) { index, layer ->
            Column(Modifier.width(78.dp).fillMaxHeight().border(1.dp, if (selected == index) colors.accent else colors.border)
                .clickable { onSelect(index) }.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val bitmap = remember(layer) { layer.preview.toComposeImageBitmap() }
                Image(bitmap, layer.name, Modifier.fillMaxWidth().weight(1f))
                Text(layer.name, color = colors.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
