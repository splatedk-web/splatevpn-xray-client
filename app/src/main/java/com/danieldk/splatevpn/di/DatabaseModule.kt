package com.danieldk.splatevpn.di

import android.content.Context
import androidx.room.Room
import com.danieldk.splatevpn.data.AppDatabase
import com.danieldk.splatevpn.data.ServerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "splatevpn.db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideServerDao(database: AppDatabase): ServerDao {
        return database.serverDao()
    }

    @Provides
    fun provideSubscriptionDao(database: AppDatabase): com.danieldk.splatevpn.data.SubscriptionDao {
        return database.subscriptionDao()
    }
}
