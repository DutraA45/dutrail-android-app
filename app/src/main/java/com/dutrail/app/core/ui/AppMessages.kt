package com.dutrail.app.core.ui

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

enum class AppMessage { LOGOUT_ALL_NOT_CONFIRMED }

/** Avisos que precisam sobreviver à troca de tela (ex.: logout leva da área logada para o login). */
@Singleton
class AppMessages @Inject constructor() {
    private val channel = Channel<AppMessage>(Channel.BUFFERED)
    val messages: Flow<AppMessage> = channel.receiveAsFlow()

    fun post(message: AppMessage) {
        channel.trySend(message)
    }
}
