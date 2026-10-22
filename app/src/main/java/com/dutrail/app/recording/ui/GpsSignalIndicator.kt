package com.dutrail.app.recording.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dutrail.app.R
import com.dutrail.app.recording.domain.GpsSignal
import kotlin.math.roundToInt

private val GoodColor = Color(0xFF2E7D32)
private val FairColor = Color(0xFFF9A825)
private val WeakColor = Color(0xFFC62828)

/** Barras de sinal (0 a 3) com a precisão atual, ex.: "GPS bom · ±6 m". */
@Composable
fun GpsSignalIndicator(signal: GpsSignal, accuracyMeters: Float?, modifier: Modifier = Modifier, large: Boolean = false) {
    val bars = when (signal) {
        GpsSignal.NONE -> 0
        GpsSignal.WEAK -> 1
        GpsSignal.FAIR -> 2
        GpsSignal.GOOD -> 3
    }
    val color = when (signal) {
        GpsSignal.GOOD -> GoodColor
        GpsSignal.FAIR -> FairColor
        GpsSignal.WEAK, GpsSignal.NONE -> WeakColor
    }
    val label = stringResource(
        when (signal) {
            GpsSignal.NONE -> R.string.gps_none
            GpsSignal.WEAK -> R.string.gps_weak
            GpsSignal.FAIR -> R.string.gps_fair
            GpsSignal.GOOD -> R.string.gps_good
        },
    )
    val text = if (accuracyMeters != null && signal != GpsSignal.NONE) {
        stringResource(R.string.gps_with_accuracy, label, accuracyMeters.roundToInt())
    } else {
        label
    }
    val unit = if (large) 10.dp else 6.dp
    Row(
        modifier = modifier.semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(unit),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(unit / 2)) {
            for (i in 1..3) {
                Box(
                    Modifier
                        .width(unit)
                        .height(unit * (i + 1))
                        .background(
                            if (i <= bars) color else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        Text(text, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
    }
}
