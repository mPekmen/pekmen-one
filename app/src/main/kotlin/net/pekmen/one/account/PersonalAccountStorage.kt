package net.pekmen.one.account

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.AccountId

internal class PersonalAccountStorage(context: Context, val accountId: AccountId, scope: CoroutineScope) {
    val database = PekmenDatabase.forAccount(context, accountId)
    val preferences = UserPreferencesDataStore.forAccount(context, accountId, scope)
}
