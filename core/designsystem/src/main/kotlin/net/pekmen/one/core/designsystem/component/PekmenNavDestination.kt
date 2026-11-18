package net.pekmen.one.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class PekmenNavDestination(
    val id: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int
)
