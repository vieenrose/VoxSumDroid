package studio.voxsum.core.util

actual fun voxLogWarn(tag: String, msg: String, t: Throwable?) {
    if (t != null) android.util.Log.w(tag, msg, t) else android.util.Log.w(tag, msg)
}
