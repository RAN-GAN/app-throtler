package com.ngdale.appthrottler.di

import android.content.Context
import androidx.room.Room
import com.ngdale.appthrottler.data.local.ThrottleDatabase
import com.ngdale.appthrottler.data.repository.AppRepository
import com.ngdale.appthrottler.data.repository.ProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ThrottleDatabase {
        return Room.databaseBuilder(
            context,
            ThrottleDatabase::class.java,
            "throttle.db",
        ).build()
    }

    @Provides
    fun provideProfileRepository(database: ThrottleDatabase): ProfileRepository {
        return ProfileRepository(database.throttleProfileDao())
    }

    @Provides
    fun provideAppRepository(@ApplicationContext context: Context): AppRepository {
        return AppRepository(context)
    }
}