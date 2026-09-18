// Generated from design/tokens.json by `just tokens`. Do not edit.
package com.mantel.app.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The design, as the same values the web client reads. DESIGN.md is the rationale. */
object Tokens {
    object Colour {
        /** The page. Near-black, not black: a true black makes dark photographs look like holes. */
        val surface: Color = Color(0xFF0C0C0D)

        /** Anything sitting on the surface: the PIN card, a placeholder tile. */
        val surfaceLift: Color = Color(0xFF161617)

        /** Type. Warm off-white, because pure white vibrates against a warm photograph. */
        val ink: Color = Color(0xFFECE9E4)

        /** The album title, captions, states. Present, never competing. */
        val muted: Color = Color(0xFF87837C)

        /** The one-pixel line. Used almost never. */
        val line: Color = Color(0xFF2A2A2C)

        /** A failed item. Desaturated on purpose: a red alert next to a photograph is an alarm in a gallery. */
        val fail: Color = Color(0xFFB0705F)
    }

    object Space {
        /** Between photographs. Small enough that the set reads as one thing. */
        val gutter: Dp = 8.dp

        /** Phone gutter. */
        val gutterTight: Dp = 3.dp

        /** Above and below the album title. */
        val titleY: Dp = 64.dp

        /** After the last photograph, so the album ends rather than stops. */
        val tail: Dp = 128.dp
    }

    object Type {
        /** The album title. Small, tracked, upper case: a title page, not a masthead. */
        val title: TextUnit = 13.12.sp
        val titleTrack: TextUnit = 0.2f.em
        val body: TextUnit = 14.4.sp
        val caption: TextUnit = 12.48.sp
    }

    object Radius {
        /** Photographs are not rounded. A rounded photograph is a card. */
        val none: Dp = 0.dp

        /** The PIN card and placeholder tiles only. */
        val card: Dp = 10.dp
    }

    object Motion {
        /** A control appearing. */
        val fast: Int = 120

        /** The lightbox opening. */
        val medium: Int = 220
        val ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }

    object Layout {
        /** Target row height the solver aims at on a wide screen. */
        val rowHeight: Dp = 340.dp
        val rowHeightSm: Dp = 220.dp

        /** Below this a row becomes a column. */
        val phoneBreakpoint: Dp = 640.dp
    }
}

// Not on Android, because the unit does not exist here. Compose lays these out in code:
//   space.page-x: 2vw
//   type.family: ui-sans-serif, -apple-system, 'Helvetica Neue', Arial, sans-serif
//   layout.measure: min(96vw, 1560px)
