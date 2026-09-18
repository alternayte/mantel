package com.mantel.app.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.mantel.app.AppModel
import com.mantel.app.Screen
import com.mantel.app.design.Body
import com.mantel.app.design.Button
import com.mantel.app.design.Card
import com.mantel.app.design.Field
import com.mantel.app.design.Page
import com.mantel.app.design.Title
import com.mantel.app.design.Tokens
import com.mantel.app.design.captionStyle
import com.mantel.app.design.failStyle

/**
 * Sign-in, in the order the person meets it: which server, then who they are.
 *
 * The server comes first because Mantel is self-hosted and the app cannot assume an address. Once
 * it has one it asks that instance what it can actually sign somebody in with, rather than offering
 * a GitHub button that answers with an error.
 */
@Composable
fun SignInScreen(
    state: Screen.SignIn,
    model: AppModel,
) {
    Page {
        Title("Mantel")
        Spacer(Modifier.height(Tokens.Space.gutter))

        Card {
            if (state.methods == null) {
                Body("Your albums live on your own server. Give this app its address.", style = captionStyle)
                Field(
                    value = state.serverUrl,
                    onValueChange = model::setServerUrl,
                    label = "Server",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    onSubmit = model::useServer,
                    enabled = !state.busy,
                )
                Button(
                    text = if (state.busy) "Asking…" else "Continue",
                    onClick = model::useServer,
                    enabled = !state.busy,
                )
            } else if (state.linkSentTo != null) {
                Body("Check your email")
                Body(
                    "A sign-in link is on its way to ${state.linkSentTo}. Open it on this device and " +
                        "it brings you back here. It works once, and expires in fifteen minutes.",
                    style = captionStyle,
                )
                Button(text = "Start again", onClick = model::startOver, quiet = true)
            } else {
                Body(state.serverUrl.substringAfter("://"), style = captionStyle)

                if (state.methods.magicLink) {
                    Field(
                        value = state.email,
                        onValueChange = model::setEmail,
                        label = "Email",
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Go,
                        onSubmit = model::requestMagicLink,
                        enabled = !state.busy,
                    )
                    Button(
                        text = if (state.busy) "Sending…" else "Email me a link",
                        onClick = model::requestMagicLink,
                        enabled = !state.busy && state.email.isNotBlank(),
                    )
                }

                if (state.methods.github) {
                    Button(
                        text = "Continue with GitHub",
                        onClick = model::signInWithGitHub,
                        enabled = !state.busy,
                        quiet = true,
                    )
                }
            }

            if (state.error != null) Body(state.error, style = failStyle)
        }
    }
}
