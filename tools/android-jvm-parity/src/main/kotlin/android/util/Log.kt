package android.util

/** JVM stand-in for the four android.util.Log calls the compiled app files make. */
object Log {
    @JvmStatic fun e(tag: String, msg: String): Int = p("E", tag, msg)
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int = p("E", tag, "$msg: ${tr?.message}")
    @JvmStatic fun w(tag: String, msg: String): Int = p("W", tag, msg)
    @JvmStatic fun i(tag: String, msg: String): Int = p("I", tag, msg)
    @JvmStatic fun d(tag: String, msg: String): Int = p("D", tag, msg)
    private fun p(l: String, tag: String, msg: String): Int {
        if (System.getenv("PARITY_VERBOSE") == "1" || l == "E") System.err.println("$l/$tag: $msg")
        return 0
    }
}
