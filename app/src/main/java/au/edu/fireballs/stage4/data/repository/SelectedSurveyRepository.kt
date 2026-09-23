package au.edu.fireballs.stage4.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SelectedSurveyRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val selectedSurveyId: Flow<Long?> =
            dataStore.data.map { preferences ->
                preferences[KEY_SELECTED_SURVEY_ID]
            }

        val username: Flow<String?> =
            dataStore.data.map { preferences ->
                preferences[KEY_USERNAME]
            }

        suspend fun set(surveyId: Long) {
            dataStore.edit { preferences ->
                preferences[KEY_SELECTED_SURVEY_ID] = surveyId
            }
        }

        suspend fun setUsername(username: String) {
            dataStore.edit { preferences ->
                preferences[KEY_USERNAME] = username
            }
        }

        suspend fun clear() {
            dataStore.edit { preferences ->
                preferences.remove(KEY_SELECTED_SURVEY_ID)
                preferences.remove(KEY_USERNAME)
            }
        }

        companion object {
            val KEY_SELECTED_SURVEY_ID = longPreferencesKey("selected_survey_id")
            val KEY_USERNAME = stringPreferencesKey("username")
        }
    }
