package com.discountscreener.core.engine

import java.util.regex.Matcher

/**
 * On ART, `Regex.findAll` builds a new Matcher for every match, and every Matcher copies the
 * whole input into native memory. On a large input with many matches that churns gigabytes of
 * native allocations and stalls the process in NativeAlloc GC. One Matcher per scan keeps the
 * copy to one. Read groups inside the action; the Matcher moves on after it returns.
 */
internal inline fun Regex.forEachMatch(input: CharSequence, action: (Matcher) -> Unit) {
    var matcher = toPattern().matcher(input)
    while (matcher.find()) {
        action(matcher)
    }
}
