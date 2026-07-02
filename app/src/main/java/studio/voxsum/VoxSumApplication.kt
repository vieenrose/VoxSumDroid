package studio.voxsum

import android.app.Application
import studio.voxsum.core.prefs.AndroidKeyValueStore
import studio.voxsum.core.prefs.KeyValueStore

/** Wires the [KeyValueStore] seam to SharedPreferences before any Activity/Service (ThemeStore,
 *  ConfigStore) can run — Application.onCreate always runs first in the process. */
class VoxSumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KeyValueStore.forName = { name -> AndroidKeyValueStore(this, name) }
    }
}
