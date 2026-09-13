package buddy.voice

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandParserTest {
    private fun p(s: String) = CommandParser.parse(s)

    @Test
    fun `tell`() {
        assertEquals(Command.Tell("Sam", "I'll be ten minutes late"), p("Hey buddy, tell Sam I'll be ten minutes late."))
        assertEquals(Command.Tell("mum", "dinner's at 7"), p("text mum that dinner's at 7"))
        assertEquals(Command.Tell("Alex", "the doc is on its way"), p("let Alex know the doc is on its way"))
    }

    @Test
    fun `recall and brief`() {
        assertIs<Command.Recall>(p("what did the plumber say about the boiler"))
        assertIs<Command.Recall>(p("when's my dentist appointment?"))
        assertIs<Command.Recall>(p("did Sam reply?"))
        assertEquals(Command.Brief, p("buddy what's the brief"))
        assertEquals(Command.Brief, p("anything for me?"))
        assertEquals(Command.Brief, p("catch me up"))
    }

    @Test
    fun `cancel reschedule reply`() {
        assertEquals(Command.Cancel("Thursday"), p("cancel Thursday"))
        assertEquals(Command.Cancel("dinner with Sam"), p("Cancel dinner with Sam."))
        assertEquals(Command.Reschedule("the dentist", "Friday"), p("move the dentist to Friday"))
        assertEquals(Command.Reschedule("the delivery", "Thursday afternoon"), p("reschedule the delivery to Thursday afternoon"))
        assertEquals(Command.Reschedule("standup", null), p("push standup"))
        assertEquals(Command.Reply("yes that works"), p("reply yes that works"))
        assertEquals(Command.Reply("I'll be there"), p("say that I'll be there"))
    }

    @Test
    fun `control words and standing instructions`() {
        assertEquals(Command.Pause, p("buddy stop listening"))
        assertEquals(Command.Pause, p("pause"))
        assertEquals(Command.Resume, p("start listening again"))
        assertEquals(Command.Undo, p("undo that"))
        assertEquals(Command.Instruction("don't reply to my mum for me"), p("don't reply to my mum for me"))
        assertEquals(Command.Instruction("always accept invites from Alex"), p("always accept invites from Alex"))
        assertIs<Command.Unknown>(p("la la la"))
        assertIs<Command.Unknown>(p(""))
    }

    @Test
    fun `the switches quick settings used to carry`() {
        assertEquals(Command.Torch(true), p("torch on"))
        assertEquals(Command.Torch(false), p("turn off the flashlight"))
        assertEquals(Command.Torch(false), p("buddy, torch off."))
        assertEquals(Command.Torch(null), p("torch"))
        assertEquals(Command.Network, p("wifi"))
        assertEquals(Command.Network, p("connect me to the wi-fi"))
        assertEquals(Command.Network, p("get me back online"))
    }

    /** "stop" opens a standing instruction, so the torch has to be read first. */
    @Test
    fun `a switch is not a standing instruction`() {
        assertIs<Command.Instruction>(p("never text my mum for me"))
        assertEquals(Command.Torch(false), p("switch off the torch"))
    }

    /** The launcher is gone, so this is the only way into an app. */
    @Test
    fun `opening an app`() {
        assertEquals(Command.Open("Monzo"), p("open Monzo"))
        assertEquals(Command.Open("the camera"), p("show me the camera"))
        assertEquals(Command.Open("National Rail"), p("buddy, launch National Rail."))
        // The network picker is buddy's own, not an app called wifi.
        assertEquals(Command.Network, p("show me the wifi"))
    }
}
