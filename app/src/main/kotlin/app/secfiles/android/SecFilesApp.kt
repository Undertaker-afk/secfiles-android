package app.secfiles.android

import android.app.Application

class SecFilesApp : Application() {
    val registry by lazy { VolumeRegistry(this) }
    val settings by lazy { Settings(this) }
    val mounts by lazy { MountManager(this) }
}
