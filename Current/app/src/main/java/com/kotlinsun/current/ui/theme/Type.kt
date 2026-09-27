package com.kotlinsun.current.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kotlinsun.current.R

val CurrentFontFamily = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

val Typography = Typography(
    headlineLarge = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = CurrentFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp),
)
