package com.mantel.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext

/**
 * A model for the `@Preview` functions to hand their screens.
 *
 * A screen takes the model so that a tap has somewhere to go. Building one touches nothing: the
 * settings store opens on first read and a preview never reads it, so the screen draws in the IDE
 * with no device, no server and no session behind it.
 */
@Composable
internal fun previewModel(): AppModel {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { AppModel(context, scope) }
}
