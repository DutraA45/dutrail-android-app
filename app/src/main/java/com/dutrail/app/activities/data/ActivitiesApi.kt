package com.dutrail.app.activities.data

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ActivitiesApi {
    /** `cursor` nulo é omitido da query; a API recusa parâmetros desconhecidos com 400. */
    @GET("activities")
    suspend fun list(@Query("limit") limit: Int, @Query("cursor") cursor: String?): ActivityPageDto

    @GET("activities/{id}")
    suspend fun get(@Path("id") id: String): ActivityDto

    /** multipart/form-data com o .fit no campo `file` (até 10 MiB). */
    @Multipart
    @POST("activities/import")
    suspend fun import(@Part file: MultipartBody.Part): ActivityDto

    companion object {
        const val IMPORT_FILE_FIELD = "file"

        /** Limite do contrato (10485760 bytes); acima disso a API responde 413. */
        const val MAX_IMPORT_BYTES = 10L * 1024 * 1024
        const val MAX_PAGE_SIZE = 100
    }
}

@Serializable
data class ActivityPageDto(
    val items: List<ActivityDto>,
    val nextCursor: String? = null,
)

@Serializable
data class ActivityDto(
    val id: String,
    val userId: String,
    val name: String,
    /** String, e não enum, para não quebrar se a API ganhar esportes novos. */
    val sport: String,
    val startedAt: String,
    val elapsedTimeSeconds: Long,
    val movingTimeSeconds: Long? = null,
    val distanceMeters: Double? = null,
    val elevationGainMeters: Double? = null,
    val averageHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val calories: Int? = null,
    val createdAt: String,
    val updatedAt: String,
)
