package com.dutrail.app.core.common

/** Relógio de parede (epoch millis). Injetado para que a lógica de gravação seja testável. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
