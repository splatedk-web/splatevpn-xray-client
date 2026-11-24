package com.danieldk.splatevpn.data

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.ui.theme.appLocale
import com.danieldk.splatevpn.ui.theme.localizedResources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ReleaseNotesTranslatorNativeTest {
    @Test fun connectionMessagesFollowAllSupportedLanguages() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val messages = listOf("ru" to "Сервера не найдены. Поиск…", "en" to "No servers found. Searching…",
            "zh" to "未找到服务器，正在搜索…", "fa" to "سروری پیدا نشد. در حال جستجو…")
        for ((language, expected) in messages) {
            val resources = localizedResources(app, appLocale(language, Locale.ENGLISH))
            assertEquals(expected, ConnectionStatusText.localize("Сервера не найдены. Поиск…", resources))
            assertEquals("custom server error", ConnectionStatusText.localize("custom server error", resources))
        }
    }

    @Test fun russianReleaseNotesTranslateOnDeviceIntoEnglishAndChinese() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val translator = ReleaseNotesTranslator(app)
        val original = "# Обновление\nИсправлены ошибки подключения.\n\n`path=/` https://example.com/download\n"
        for (language in listOf("en", "zh")) {
            val translated = translator.translate(original, language)
            assertNotEquals(original, translated)
            assertFalse(translated.contains("Исправлены"))
            assertTrue(translated.contains("`path=/`"))
            assertTrue(translated.contains("https://example.com/download"))
            assertEquals(translated, translator.translate(original, language))
        }
    }
}
