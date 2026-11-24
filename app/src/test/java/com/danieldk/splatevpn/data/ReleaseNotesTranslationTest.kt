package com.danieldk.splatevpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesTranslationTest {
    @Test fun translatesProseAndLinkLabelsWhileKeepingCodeAndDestinations() = runBlocking {
        val markdown = "# Новое\n- **Исправлено** `path=/` [Скачать](https://example.com/файл)\n```json\n{\"name\":\"Без перевода\"}\n```\n"
        val calls = mutableListOf<String>()
        val result = translateReleaseMarkdown(markdown) { text ->
            calls += text
            when (text) {
                "Новое" -> "New"
                "Исправлено" -> "Fixed"
                "Скачать" -> "Download"
                else -> error("Unexpected translation input: $text")
            }
        }
        assertEquals("# New\n- **Fixed** `path=/` [Download](https://example.com/файл)\n```json\n{\"name\":\"Без перевода\"}\n```\n", result)
        assertEquals(listOf("Новое", "Исправлено", "Скачать"), calls)
    }

    @Test fun englishTextAndTildeCodeFencesRemainUntouched() = runBlocking {
        val markdown = "## Xray 26.11.9\n~~~\nИсходный код\n~~~\n"
        assertEquals(markdown, translateReleaseMarkdown(markdown) { error("Code must not be translated") })
    }
}
