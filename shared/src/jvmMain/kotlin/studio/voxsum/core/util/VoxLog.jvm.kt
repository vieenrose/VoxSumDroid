package studio.voxsum.core.util

actual fun voxLogWarn(tag: String, msg: String, t: Throwable?) {
    System.err.println("W/$tag: $msg")
    t?.printStackTrace(System.err)
}
