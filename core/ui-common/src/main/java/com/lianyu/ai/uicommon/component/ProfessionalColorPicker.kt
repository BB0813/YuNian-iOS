package com.lianyu.ai.uicommon.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.uicommon.theme.AppTheme
import kotlin.math.roundToInt

/**
 * 专业颜色合成器：HSV 色相条 + 饱和度/明度面板 + RGB 滑条 + 实时预览。
 */
@Composable
fun ProfessionalColorPickerDialog(
    currentColor: Color,
    onColorPicked: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember(currentColor) { currentColor.toHsv() }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }

    val selectedColor = remember(hue, saturation, value) {
        Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "专业取色盘",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 预览
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(selectedColor)
                        .border(1.dp, AppTheme.colors.outline, RoundedCornerShape(12.dp))
                )

                Text(
                    "#%02X%02X%02X".format(
                        (selectedColor.red * 255).roundToInt(),
                        (selectedColor.green * 255).roundToInt(),
                        (selectedColor.blue * 255).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                // SV 面板
                SaturationValuePanel(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { s, v ->
                        saturation = s
                        value = v
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                )

                // 色相条
                HueBar(
                    hue = hue,
                    onHueChange = { hue = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                )

                // RGB 滑条（便于精确合成）
                RgbSlider("R", selectedColor.red) { r ->
                    val c = selectedColor.copy(red = r)
                    val h = c.toHsv()
                    hue = h[0]; saturation = h[1]; value = h[2]
                }
                RgbSlider("G", selectedColor.green) { g ->
                    val c = selectedColor.copy(green = g)
                    val h = c.toHsv()
                    hue = h[0]; saturation = h[1]; value = h[2]
                }
                RgbSlider("B", selectedColor.blue) { b ->
                    val c = selectedColor.copy(blue = b)
                    val h = c.toHsv()
                    hue = h[0]; saturation = h[1]; value = h[2]
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorPicked(selectedColor) }) {
                Text("确定", color = AppTheme.colors.success, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppTheme.colors.onSurfaceVariant)
            }
        },
        containerColor = AppTheme.colors.surface
    )
}

@Composable
private fun RgbSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, modifier = Modifier.width(18.dp), fontSize = 13.sp, color = AppTheme.colors.onSurface)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = AppTheme.colors.success,
                activeTrackColor = AppTheme.colors.success
            )
        )
        Text(
            (value * 255).roundToInt().toString(),
            modifier = Modifier.width(32.dp),
            fontSize = 12.sp,
            color = AppTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var panelSize by remember { mutableStateOf(Offset.Zero) }
    val pureHue = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0) {
                        onChange(
                            (offset.x / size.width).coerceIn(0f, 1f),
                            (1f - offset.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0 && size.height > 0) {
                        onChange(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            panelSize = Offset(size.width, size.height)
            // 横向：白 → 纯色；纵向：透明 → 黑
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.White, pureHue))
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
            )
            val cx = saturation * size.width
            val cy = (1f - value) * size.height
            drawCircle(
                color = Color.White,
                radius = 10f,
                center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = 12f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
        }
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hues = listOf(
        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(hues))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onHueChange((offset.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0) {
                        onHueChange((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                }
            }
    ) {
        val fraction = (hue / 360f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceAtLeast(0.001f))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
            )
        }
    }
}

private fun Color.toHsv(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}
