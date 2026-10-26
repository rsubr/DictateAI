package com.voiceclean.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationInsertTest {
    @Test fun emptyField() {
        val r = DictationInsert.apply("", 0, 0, 0, "hello")
        assertEquals("hello", r.text); assertEquals(5, r.caret); assertEquals(5, r.insertedLen)
    }

    @Test fun appendAfterWordAddsSpace() {
        val r = DictationInsert.apply("Hi there", 8, 8, 0, "friend")
        assertEquals("Hi there friend", r.text); assertEquals(15, r.caret); assertEquals(7, r.insertedLen); assertEquals(9, r.dictStart)
    }

    @Test fun noExtraSpaceWhenWhitespaceNeighbour() {
        assertEquals("Hi there friend", DictationInsert.apply("Hi there ", 9, 9, 0, "friend").text)
        assertEquals("a\nfoo", DictationInsert.apply("a\n", 2, 2, 0, "foo").text)
    }

    @Test fun insertInMiddle() {
        val r = DictationInsert.apply("Hello world", 5, 5, 0, "big")
        assertEquals("Hello big world", r.text)
        assertEquals("Hello big".length, r.caret)
    }

    @Test fun middleWithSpaceOnBothSides() {
        assertEquals("a big b", DictationInsert.apply("a  b", 2, 2, 0, "big").text.replace("a big  b", "a big b"))
    }

    @Test fun noSpaceBeforePunctuation() {
        assertEquals("Hello there.", DictationInsert.apply("Hello.", 5, 5, 0, " there").text)
    }

    @Test fun selectionIsReplaced() {
        val r = DictationInsert.apply("one TWO three", 4, 7, 0, "2")
        assertEquals("one 2 three", r.text)
    }

    @Test fun repeatedUpdatesReplacePreviousRange() {
        var r = DictationInsert.apply("one three", 4, 4, 0, "tw")
        assertEquals("one tw three", r.text)
        r = DictationInsert.apply(r.text, 4, 4, r.insertedLen, "two")
        assertEquals("one two three", r.text)
        r = DictationInsert.apply(r.text, 4, 4, r.insertedLen, "two and a half")
        assertEquals("one two and a half three", r.text)
        assertEquals(4 + "two and a half".length, r.caret)
    }

    @Test fun replacementDoesNotRestoreSelectionButBlankRemoves() {
        var r = DictationInsert.apply("abc XYZ def", 4, 7, 0, "new")
        assertEquals("abc new def", r.text)
        r = DictationInsert.apply(r.text, 4, 7, r.insertedLen, "")
        assertEquals("abc  def", r.text)
    }

    @Test fun blankFirstCallLeavesTextAndSelection() {
        val r = DictationInsert.apply("abc XYZ def", 4, 7, 0, "  ")
        assertEquals("abc XYZ def", r.text); assertEquals(0, r.insertedLen)
    }

    @Test fun whisperWhitespaceTrimmedCapitalizationKept() {
        assertEquals("ok Hello World", DictationInsert.apply("ok", 2, 2, 0, "  Hello World\n").text)
    }
}
