package com.locky.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.locky.app.ui.theme.AccentBlue
import com.locky.app.ui.theme.GlassFill
import com.locky.app.ui.theme.GlassOutline

private val KEYS = listOf(
    "1", "2", "3",
    "4", "5", "6",
    "7", "8", "9",
    "", "0", "del",
)

/**
 * The row of filled/empty circles showing how much of the PIN has been entered.
 *
 * Rendered as a single node so a screen reader announces "PIN, 3 of 6 digits"
 * rather than six meaningless circles.
 */
@Composable
fun PinDots(
    length: Int,
    maxLength: Int,
    modifier: Modifier = Modifier,
) {
    val description = "PIN, $length of $maxLength digits entered"
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(maxLength) { index ->
            // Outlined until filled, rather than two solid circles. A filled grey
            // dot and a filled accent dot are hard to tell apart at a glance on a
            // dark screen; an empty ring reads unambiguously as "not yet".
            val filled = index < length
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .then(
                        if (filled) {
                            Modifier.background(AccentBlue)
                        } else {
                            Modifier.border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                                shape = CircleShape,
                            )
                        },
                    ),
            )
        }
    }
}

/**
 * A 3x4 numeric keypad.
 *
 * The keys are real buttons rather than gestures so they get the platform
 * ripple, focus order and accessibility labels for free.
 */
@Composable
fun PinKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KEYS.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { key ->
                    when (key) {
                        "" -> Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.35f),
                        )

                        "del" -> KeypadKey(
                            label = null,
                            onClickLabel = "Delete",
                            onClick = onBackspace,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.35f),
                        )

                        else -> KeypadKey(
                            label = key,
                            onClickLabel = key,
                            onClick = { onDigit(key.first()) },
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.35f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(
    label: String?,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            // Translucent with a hairline edge, so a key reads as a pane of glass
            // lit from behind rather than a grey tile painted on the background.
            // The alpha is relative to the theme's on-surface colour, so this
            // stays correct if the dark palette is ever retuned.
            .background(GlassFill)
            .border(1.dp, GlassOutline, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = onClickLabel },
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            androidx.compose.material3.Icon(
                imageVector = LockyIcons.Backspace,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}
