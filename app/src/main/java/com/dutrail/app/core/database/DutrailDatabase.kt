package com.dutrail.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RecordingEntity
import com.dutrail.app.recording.data.TimerEventEntity
import com.dutrail.app.recording.data.TrackPointEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [RecordingEntity::class, TrackPointEntity::class, TimerEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DutrailDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DutrailDatabase =
        Room.databaseBuilder(context, DutrailDatabase::class.java, "dutrail.db").build()

    @Provides
    fun recordingDao(db: DutrailDatabase): RecordingDao = db.recordingDao()
}
