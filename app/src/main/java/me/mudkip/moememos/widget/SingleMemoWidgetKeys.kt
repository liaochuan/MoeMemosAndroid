package me.mudkip.moememos.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Per-widget state for the single memo widget. One widget is bound to exactly one memo, so the
 * identifier and the account it belongs to are stored alongside a refresh counter.
 */
object SingleMemoWidgetKeys {
    val memoId: Preferences.Key<String> = stringPreferencesKey("single_memo_id")
    val accountKey: Preferences.Key<String> = stringPreferencesKey("single_memo_account_key")
    val appWidgetId: Preferences.Key<Int> = intPreferencesKey("single_memo_app_widget_id")
    val refreshKey: Preferences.Key<Long> = longPreferencesKey("single_memo_refresh_key")
}
