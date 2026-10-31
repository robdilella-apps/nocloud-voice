package com.robd.robvoice

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTest {
    private fun p(s: String, salon: Boolean = false) = PostProcessor.processWith(s, true, true, PostProcessor.DEFAULT_DICT, salon)
    @Test fun fillers() = assertEquals("So I think we go.", p("um so I uh think we go."))
    @Test fun stutter() = assertEquals("The the cat".let { "The cat" }, p("the the cat"))
    @Test fun newLine() = assertEquals("Hi\nThere", p("hi new line there"))
    @Test fun question() = assertEquals("Are you there?", p("are you there question mark"))
    @Test fun dict() = assertEquals("Try NoCloud Voice in Gmail", p("try no cloud voice in g mail"))
    @Test fun salonShade() = assertEquals("Use 7/01 and 1:1.5", p("use seven oh one and one to one and a half", salon = true))
    @Test fun salonOff() = assertEquals("Use seven oh one", p("use seven oh one"))
    @Test fun empty() = assertEquals("", p("   "))
}
