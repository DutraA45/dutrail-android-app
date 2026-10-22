package com.dutrail.app.recording.ui.map

import android.content.Context
import com.dutrail.app.BuildConfig
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Configuração do mapa. O provedor de tiles vem do BuildConfig (propriedade Gradle
 * `DUTRAIL_TILE_URL`), então dá para trocar sem mexer no código.
 *
 * Política de uso do tile.openstreetmap.org: User-Agent que identifica o app, atribuição visível e
 * nada de pré-download em massa. Não usamos regiões offline; o cache do MapLibre só guarda o que já
 * foi visto, respeitando os headers HTTP de cache.
 */
object MapTiles {

    val userAgent = "Dutrail/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"

    val attribution: String get() = BuildConfig.MAP_TILE_ATTRIBUTION

    @Volatile
    private var initialized = false

    /** Inicializa o MapLibre de forma preguiçosa: só quando o usuário abre o mapa. */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            MapLibre.getInstance(context.applicationContext)
            HttpRequestUtil.setOkHttpClient(
                // Cliente separado do da API: nunca leva o Bearer para o servidor de tiles.
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                    }
                    .build(),
            )
            initialized = true
        }
    }

    /** Estilo mínimo: uma única camada raster com os tiles configurados. */
    fun styleJson(): String = JSONObject()
        .put("version", 8)
        .put(
            "sources",
            JSONObject().put(
                TILE_SOURCE,
                JSONObject()
                    .put("type", "raster")
                    .put("tiles", JSONArray().put(BuildConfig.MAP_TILE_URL))
                    .put("tileSize", 256)
                    .put("maxzoom", 19)
                    .put("attribution", attribution),
            ),
        )
        .put("layers", JSONArray().put(JSONObject().put("id", TILE_SOURCE).put("type", "raster").put("source", TILE_SOURCE)))
        .toString()

    private const val TILE_SOURCE = "base-tiles"
}
