package com.kaoyan.wordhelper.ui.component

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/** Keep controls clear even when a transient system bar is currently hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun studyContentInsets(): WindowInsets =
    WindowInsets.statusBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.navigationBarsIgnoringVisibility)
