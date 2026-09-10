package au.edu.fireballs.stage4

import android.app.Application
import android.util.Log
import coil.Coil
import coil.ImageLoader
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class Stage4App : Application() {
    @Inject
    lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.MAPBOX_TOKEN.isBlank()) {
            Log.e("Stage4App", "MAPBOX_TOKEN is blank; Mapbox cannot initialize")
        }
        MapboxOptions.accessToken = BuildConfig.MAPBOX_TOKEN
        Coil.setImageLoader(imageLoader)
    }
}
