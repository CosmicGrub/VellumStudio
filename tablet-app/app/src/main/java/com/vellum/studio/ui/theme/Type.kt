package com.vellum.studio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val VellumTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)
