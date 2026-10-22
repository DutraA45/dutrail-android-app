package com.dutrail.app.activities.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.dutrail.app.activities.domain.Activity
import com.dutrail.app.activities.domain.Sport
import com.dutrail.app.core.network.ApiError
import com.dutrail.app.core.network.apiCall
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class ActivitiesRepository @Inject constructor(private val api: ActivitiesApi) {

    fun pagedActivities(): Flow<PagingData<Activity>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, enablePlaceholders = false),
        pagingSourceFactory = { ActivitiesPagingSource(api) },
    ).flow

    suspend fun activity(id: String): Activity = apiCall { api.get(id) }.toDomain()

    private companion object {
        const val PAGE_SIZE = 20
    }
}

/** Paginação por cursor opaco: a chave de cada página é o `nextCursor` da anterior. */
class ActivitiesPagingSource(private val api: ActivitiesApi) : PagingSource<String, Activity>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Activity> = try {
        val page = apiCall { api.list(params.loadSize.coerceIn(1, ActivitiesApi.MAX_PAGE_SIZE), params.key) }
        LoadResult.Page(data = page.items.map { it.toDomain() }, prevKey = null, nextKey = page.nextCursor)
    } catch (e: ApiError) {
        LoadResult.Error(e)
    }

    // O cursor não permite reabrir no meio da lista: refresh sempre recomeça do topo.
    override fun getRefreshKey(state: PagingState<String, Activity>): String? = null
}

fun ActivityDto.toDomain() = Activity(
    id = id,
    name = name,
    sport = Sport.fromApi(sport),
    startedAt = Instant.parse(startedAt),
    elapsedTimeSeconds = elapsedTimeSeconds,
    movingTimeSeconds = movingTimeSeconds,
    distanceMeters = distanceMeters,
    elevationGainMeters = elevationGainMeters,
    averageHeartRateBpm = averageHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    calories = calories,
)
