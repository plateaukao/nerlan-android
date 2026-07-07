package com.example.nerlan.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIServiceTest {

  @Test
  fun reconcileKeepsInteriorBlankLinesInPlace() {
    // Rule 3 lets the model answer a punctuation-only line with a blank;
    // sentences after it must stay on their own translations.
    val raw = "line one\nline two\n\nline four\nline five"
    assertEquals(
      listOf("line one", "line two", "", "line four", "line five"),
      OpenAIService.reconcileBatch(raw, 5),
    )
  }

  @Test
  fun reconcileStripsSurroundingPadding() {
    val raw = "\n\nline one\nline two\n\n"
    assertEquals(listOf("line one", "line two"), OpenAIService.reconcileBatch(raw, 2))
  }

  @Test
  fun reconcileDropsExtraLinesFromTheEnd() {
    val raw = "a\nb\nc\nd"
    assertEquals(listOf("a", "b", "c"), OpenAIService.reconcileBatch(raw, 3))
  }

  @Test
  fun reconcilePadsMissingLines() {
    val raw = "a\nb"
    assertEquals(listOf("a", "b", "", ""), OpenAIService.reconcileBatch(raw, 4))
  }

  @Test
  fun reconcileHandlesExactMatch() {
    val raw = "a\nb\nc"
    assertEquals(listOf("a", "b", "c"), OpenAIService.reconcileBatch(raw, 3))
  }
}
