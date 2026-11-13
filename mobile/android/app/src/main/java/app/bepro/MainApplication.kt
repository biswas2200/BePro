package app.bepro

import android.app.Application
import app.bepro.bridge.BeproPackage
import app.bepro.collector.ApiClient
import app.bepro.collector.BackgroundWork
import app.bepro.collector.Notifier
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(BeproPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    Notifier.createChannels(this)
    // MIUI may have cancelled our jobs while the app was force-stopped; re-arm them on every start.
    if (ApiClient(this).isPaired()) {
      BackgroundWork.schedule(this)
      BackgroundWork.syncSoon(this)
    }
    loadReactNative(this)
  }
}
