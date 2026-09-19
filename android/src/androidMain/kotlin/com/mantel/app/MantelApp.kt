package com.mantel.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

/**
 * The application, for one reason: Coil has to be told how to reach the network.
 *
 * Coil 3 registers no fetcher by default, so without this every thumbnail sits at its placeholder
 * and nothing is ever requested — silently, with no error to read. `coil-network-okhttp` provides
 * the fetcher and this is what installs it.
 *
 * The connectivity check is turned off with it. Coil refuses to use the network when Android says
 * the device has no internet, and answers from what it already stored, or not at all. A Mantel
 * instance is a server the person chose, and it may sit on a network with no route to the internet
 * (SDD.md 11): whether that phone can reach Google says nothing about whether it can reach the
 * album. A request that cannot connect now fails on its own, which is the honest answer.
 *
 * The crossfade stays on the request, in `ItemTile`, where DESIGN.md can be read beside it.
 */
class MantelApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(connectivityChecker = { ConnectivityChecker { true } }))
            }
            .build()
}
