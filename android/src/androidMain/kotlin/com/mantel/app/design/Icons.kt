// Generated from design/icons/lucide/ by `just icons`. Do not edit. Lucide 1.48.0, ISC (design/icons/LICENSE).
package com.mantel.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Every icon the app draws, at the stroke width the design sets (1.5). */
object Icons {
    /** album.svg */
    val Album: ImageVector by lazy {
        lucide(
            "Album",
            "M11 3v7.751a.25.25 0 00.407.195l2.28-1.834a.5.5 0 01.627 0l2.28 1.834a.25.25 0 00.406-.195V3",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z",
        )
    }

    /** chevron-left.svg */
    val ChevronLeft: ImageVector by lazy {
        lucide(
            "ChevronLeft",
            "m15 18-6-6 6-6",
        )
    }

    /** chevron-right.svg */
    val ChevronRight: ImageVector by lazy {
        lucide(
            "ChevronRight",
            "m9 18 6-6-6-6",
        )
    }

    /** circle-user-round.svg */
    val CircleUserRound: ImageVector by lazy {
        lucide(
            "CircleUserRound",
            "M17.925 20.056a6 6 0 0 0-11.851.001",
            "M8 11a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        )
    }

    /** cloud-upload.svg */
    val CloudUpload: ImageVector by lazy {
        lucide(
            "CloudUpload",
            "M12 13v8",
            "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
            "m8 17 4-4 4 4",
        )
    }

    /** images.svg */
    val Images: ImageVector by lazy {
        lucide(
            "Images",
            "m22 11-1.296-1.296a2.4 2.4 0 0 0-3.408 0L11 16",
            "M4 8a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2",
            "M12 7a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
            "M10 2h10a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z",
        )
    }

    /** link-2.svg */
    val Link2: ImageVector by lazy {
        lucide(
            "Link2",
            "M9 17H7A5 5 0 0 1 7 7h2",
            "M15 7h2a5 5 0 1 1 0 10h-2",
            "M8 12L16 12",
        )
    }

    /** log-out.svg */
    val LogOut: ImageVector by lazy {
        lucide(
            "LogOut",
            "m16 17 5-5-5-5",
            "M21 12H9",
            "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",
        )
    }

    /** plus.svg */
    val Plus: ImageVector by lazy {
        lucide(
            "Plus",
            "M5 12h14",
            "M12 5v14",
        )
    }

    /** rotate-ccw.svg */
    val RotateCcw: ImageVector by lazy {
        lucide(
            "RotateCcw",
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
        )
    }

    /** share-2.svg */
    val Share2: ImageVector by lazy {
        lucide(
            "Share2",
            "M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            "M3 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            "M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            "M8.59 13.51L15.42 17.49",
            "M15.41 6.51L8.59 10.49",
        )
    }

    /** trash-2.svg */
    val Trash2: ImageVector by lazy {
        lucide(
            "Trash2",
            "M10 11v6",
            "M14 11v6",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
            "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
        )
    }

    /** x.svg */
    val X: ImageVector by lazy {
        lucide(
            "X",
            "M18 6 6 18",
            "m6 6 12 12",
        )
    }
}

/** A 24-unit Lucide glyph, stroked and not filled. The colour is a tint applied where it is drawn. */
private fun lucide(
    name: String,
    vararg paths: String,
): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = Tokens.Icon.stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
