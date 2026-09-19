package com.spendly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The calendar heatmap ramp.
 *
 * Single hue, monotone lightness, with a visible gap between every step —
 * validated with the data-viz palette validator against this app's own light
 * (#FBFDFA) and dark (#101418) surfaces. Both sets clear all four checks,
 * including the "lightest step still reads against the surface" floor.
 *
 * That last one is the reason the ramp does not start almost-white: on a
 * calendar, a barely-tinted cell is indistinguishable from an empty day, so a
 * small spend would look like no spend at all. "Nothing spent" is encoded by the
 * absence of a fill instead, never by a pale step.
 *
 * Deliberately a fixed hue rather than the Material You primary: the whole point
 * of the grid is comparing cell against cell, and that must not depend on which
 * wallpaper the user happens to have.
 */
@Immutable
data class HeatmapRamp(
    val levels: List<Color>,
) {
    /** Number of filled levels, excluding "no spend". */
    val size: Int get() = levels.size

    fun colorFor(level: Int): Color = levels[level.coerceIn(1, levels.size) - 1]

    /**
     * Black or white, whichever actually reads on that step. Computed from
     * luminance rather than hard-coded per level, so re-tuning the ramp cannot
     * silently leave unreadable labels behind.
     */
    fun onColorFor(level: Int): Color =
        if (colorFor(level).luminance() > 0.42f) Color(0xFF0B1F19) else Color.White
}

private val LightRamp = HeatmapRamp(
    listOf(
        Color(0xFF6CC4A8),
        Color(0xFF41AE8D),
        Color(0xFF1F9474),
        Color(0xFF13795E),
        Color(0xFF0A5C47),
    ),
)

private val DarkRamp = HeatmapRamp(
    listOf(
        Color(0xFF175A48),
        Color(0xFF1D7A61),
        Color(0xFF26997C),
        Color(0xFF3BB897),
        Color(0xFF76D9BA),
    ),
)

@Composable
fun heatmapRamp(dark: Boolean = isSystemInDarkTheme()): HeatmapRamp =
    if (dark) DarkRamp else LightRamp

/**
 * Buckets a day's total into 1..[levels], or 0 for "nothing spent".
 *
 * Scaled against the busiest day of the month rather than an absolute figure, so
 * the grid stays informative whether the month's peak day was 20 or 2,000. The
 * square-root curve stops one unusual day (rent, a flight) from flattening every
 * other day to the palest step.
 */
fun heatLevel(totalMinor: Long, maxMinor: Long, levels: Int): Int {
    if (totalMinor <= 0L || maxMinor <= 0L) return 0
    val ratio = (totalMinor.toDouble() / maxMinor.toDouble()).coerceIn(0.0, 1.0)
    val curved = Math.sqrt(ratio)
    return (Math.ceil(curved * levels)).toInt().coerceIn(1, levels)
}
