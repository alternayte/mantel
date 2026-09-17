package com.mantel.support

import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot

/** Every route the application registers, as "METHOD /path". */
fun Application.routeInventory(): Set<String> {
    fun walk(
        route: RoutingNode,
        path: String,
    ): List<String> {
        val selector = route.selector
        val here = if (selector is HttpMethodRouteSelector) path else "$path/$selector".replace("//", "/")
        val method = (selector as? HttpMethodRouteSelector)?.method?.value
        val mine = method?.let { listOf("$it $path") } ?: emptyList()
        return mine + route.children.flatMap { walk(it, if (method == null) here else path) }
    }
    return walk(plugin(RoutingRoot), "").toSet()
}
