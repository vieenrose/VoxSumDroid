package studio.voxsum.core.util

/** Minimal cross-platform logging seam — androidMain uses android.util.Log, jvmMain uses stderr. */
expect fun voxLogWarn(tag: String, msg: String, t: Throwable? = null)
