package com.mantel

import com.mantel.features.account.Accounts
import com.mantel.features.album.Albums
import com.mantel.features.auth.MagicLinks
import com.mantel.features.auth.Sessions
import com.mantel.features.media.MediaItems
import com.mantel.features.share.AlbumBundles
import com.mantel.features.share.ShareLinks
import org.jetbrains.exposed.sql.Table

/**
 * Every Exposed table the code declares. A feature adds its table here in the milestone that
 * creates it, and SchemaDriftTest asserts this list matches what Flyway actually built.
 * It lives at the composition root because it is the one place allowed to see every feature.
 */
val allTables: List<Table> = listOf(Accounts, MagicLinks, Sessions, Albums, MediaItems, ShareLinks, AlbumBundles)
