package net.pekmen.one

import android.content.Context
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.data.UserPreferencesRepositoryImpl
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.platform.SystemTimeProvider

/**
 * Application-scoped dependency composition root.
 * Feature modules never depend on this class; they receive narrow interfaces/slices.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val timeProvider: SystemTimeProvider = SystemTimeProvider(appContext)
    val database: PekmenDatabase by lazy { PekmenDatabase.create(appContext) }
    private val userPreferencesDataStore: UserPreferencesDataStore by lazy {
        UserPreferencesDataStore(appContext)
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepositoryImpl(userPreferencesDataStore)
    }
}
