package au.edu.fireballs.stage4

import android.app.Application
import android.util.Log
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class Stage4App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.MAPBOX_TOKEN.isBlank()) {
            Log.e("Stage4App", "MAPBOX_TOKEN is blank; Mapbox cannot initialize")
        }
        MapboxOptions.accessToken = BuildConfig.MAPBOX_TOKEN
    }
}
