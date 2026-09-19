package com.spendly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

val SpendlyTypography = Typography()

/**
 * The running total on the Quick Add screen. Tabular figures matter here: the
 * amount changes on every keypress and proportional digits make it jitter.
 */
val AmountDisplayStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Light,
    fontSize = 64.sp,
    lineHeight = 68.sp,
    textAlign = TextAlign.Center,
)

val AmountDisplayStyleCompact = AmountDisplayStyle.copy(fontSize = 44.sp, lineHeight = 48.sp)
