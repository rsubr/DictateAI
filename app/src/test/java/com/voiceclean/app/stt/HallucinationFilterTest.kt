package com.voiceclean.app.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class HallucinationFilterTest {
    private val f = HallucinationFilter

    @Test fun blankAudioTags() {
        assertEquals("", f.clean("[BLANK_AUDIO]"))
        assertEquals("hello there", f.clean("hello [Music] there (applause)"))
        assertEquals("", f.clean(" (silence) "))
    }

    @Test fun phantomPhraseOnlyWhenLittleSpeech() {
        assertEquals("", f.clean("Thank you.", speechSeconds = 0.4f))
        assertEquals("Thank you.", f.clean("Thank you.", speechSeconds = 3f))
        assertEquals("", f.clean("Thanks for watching!", speechSeconds = 0.5f))
    }

    @Test fun repeatedPhrasesCollapse() {
        assertEquals("the", f.collapseRepeats("the the the the the"))
        assertEquals("I think so. okay", f.collapseRepeats("I think so. I think so. I think so. okay"))
        assertEquals("the the quick", f.collapseRepeats("the the quick")) // only 2 repeats: kept
    }

    @Test fun normalSpeechUntouched() {
        assertEquals("Let's meet at three.", f.clean("Let's meet at three.", 2f))
    }

    @Test fun punctuationOnly() {
        assertEquals("", f.clean("...", 2f))
    }

    @Test fun moreSilencePhantoms() {
        for (x in listOf("you", "Thank you.", "Thanks for watching", "[BLANK_AUDIO]", "(silence)", ".", "Thank you so much!"))
            assertEquals(x, "", f.clean(x, speechSeconds = 0.5f))
        assertEquals("", f.clean("you", speechSeconds = 2.5f, speechRatio = 0.1f))
        assertEquals("you", f.clean("you", speechSeconds = 3f, speechRatio = 0.9f))
    }
}
