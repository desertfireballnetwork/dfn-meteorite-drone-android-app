package au.edu.fireballs.stage4

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import au.edu.fireballs.stage4.data.tiles.AuthenticatedTileHttpInterceptor
import coil.Coil
import coil.ImageLoader
import com.mapbox.common.HttpServiceFactory
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class Stage4App :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var tileHttpInterceptor: AuthenticatedTileHttpInterceptor

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        WorkManager.initialize(this, workManagerConfiguration)
        if (BuildConfig.MAPBOX_TOKEN.isBlank()) {
            Log.e("Stage4App", "MAPBOX_TOKEN is blank; Mapbox cannot initialize")
        }
        MapboxOptions.accessToken = BuildConfig.MAPBOX_TOKEN
        HttpServiceFactory.setHttpServiceInterceptor(tileHttpInterceptor)
        tileHttpInterceptor.installCancellationCallback()
        Coil.setImageLoader(imageLoader)
    }

    override fun onTerminate() {
        HttpServiceFactory.reset()
        super.onTerminate()
    }
}
