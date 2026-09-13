package au.edu.fireballs.stage4.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.selectedSurveyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "selected_survey",
)

@Module
@InstallIn(SingletonComponent::class)
object SelectedSurveyModule {
    @Provides
    @Singleton
    fun provideSelectedSurveyDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.selectedSurveyDataStore

    @Provides
    @Singleton
    fun provideSelectedSurveyRepository(
        dataStore: DataStore<Preferences>,
    ): SelectedSurveyRepository = SelectedSurveyRepository(dataStore)
}
