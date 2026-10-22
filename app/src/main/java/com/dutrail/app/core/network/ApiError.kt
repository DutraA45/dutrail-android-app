package com.dutrail.app.core.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException

/** Corpo de erro padrão da API (`ErrorResponseDto`). */
@Serializable
data class ErrorResponseDto(
    val statusCode: Int,
    val error: String? = null,
    /** String única ou lista de mensagens (erros de validação). */
    val message: JsonElement? = null,
    val path: String? = null,
    val timestamp: String? = null,
) {
    val messages: List<String>
        get() = when (val m = message) {
            is JsonArray -> m.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(m.contentOrNull)
            else -> emptyList()
        }
}

/** Falhas de chamada à API, já separadas no que importa para a UI e para a fila de envio. */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Sem resposta do servidor (sem rede, timeout, DNS) ou refresh indisponível no momento. */
    class Network(cause: IOException) : ApiError("Falha de rede: ${cause.message}", cause)

    /**
     * O servidor respondeu com status de erro.
     * @param retryAfterSeconds no 429, quantos segundos esperar antes de tentar de novo
     *   (`X-RateLimit-Reset`), se a API informar.
     */
    class Http(val code: Int, val body: ErrorResponseDto?, val retryAfterSeconds: Long? = null) :
        ApiError("HTTP $code: ${body?.messages?.joinToString().orEmpty()}")
}

val ApiJson = Json {
    ignoreUnknownKeys = true
    // Campos opcionais nulos são omitidos (ex.: `name` no signup), em vez de enviados como null.
    explicitNulls = false
}

/** Executa uma chamada Retrofit convertendo exceções de transporte/HTTP em [ApiError]. */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    throw ApiError.Http(e.code(), e.errorBody(), e.response()?.headers()?.get("X-RateLimit-Reset")?.toLongOrNull())
} catch (e: IOException) {
    throw ApiError.Network(e)
}

private fun HttpException.errorBody(): ErrorResponseDto? = runCatching {
    response()?.errorBody()?.string()?.let { ApiJson.decodeFromString<ErrorResponseDto>(it) }
}.getOrNull()
