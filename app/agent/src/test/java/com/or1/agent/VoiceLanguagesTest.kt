package com.or1.agent

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceLanguagesTest {
    private val en = listOf("en-US")

    @Test fun candidatesPutAnEnglishDeviceLocaleFirst() {
        assertEquals(listOf("en-GB", "en-US"), VoiceLanguages.candidates(Locale.UK))
        assertEquals(listOf("en-US"), VoiceLanguages.candidates(Locale.US))
        assertEquals(listOf("en-US", "de-DE"), VoiceLanguages.candidates(Locale.GERMANY))
    }

    @Test fun matchPrefersTheSameTagThenTheSameLanguage() {
        assertEquals("en-US", VoiceLanguages.match(listOf("en-US"), listOf("en-GB", "en-US")))
        assertEquals("en_GB", VoiceLanguages.match(listOf("en-US"), listOf("fr-FR", "en_GB")))
        assertNull(VoiceLanguages.match(listOf("en-US"), listOf("fr-FR")))
    }

    @Test fun installedOrOnlineIsReady() {
        assertEquals(LanguagePlan(LanguagePlan.Kind.READY, "en-US"), VoiceLanguages.plan(en, LanguageSupport(installed = listOf("en-US")), RecognizerRoute.DEFAULT))
        assertEquals(LanguagePlan.Kind.READY, VoiceLanguages.plan(en, LanguageSupport(online = listOf("en-IN")), RecognizerRoute.DEFAULT).kind)
    }

    @Test fun supportedButNotInstalledIsADownload() {
        val p = VoiceLanguages.plan(en, LanguageSupport(supported = listOf("en-US", "de-DE")), RecognizerRoute.ON_DEVICE)
        assertEquals(LanguagePlan(LanguagePlan.Kind.DOWNLOAD, "en-US", RecognizerRoute.ON_DEVICE), p)
        assertEquals(LanguagePlan.Kind.PENDING, VoiceLanguages.plan(en, LanguageSupport(pending = listOf("en-US"), supported = listOf("en-US")), RecognizerRoute.DEFAULT).kind)
    }

    @Test fun cantCheckMeansJustTryAndNothingMeansNone() {
        assertEquals(LanguagePlan.Kind.UNKNOWN, VoiceLanguages.plan(en, LanguageSupport(error = 14), RecognizerRoute.DEFAULT).kind)
        assertEquals(LanguagePlan.Kind.NONE, VoiceLanguages.plan(en, LanguageSupport(installed = listOf("fr-FR")), RecognizerRoute.DEFAULT).kind)
    }

    @Test fun bestIsTheMostUsableAndTheDefaultOnATie() {
        val download = LanguagePlan(LanguagePlan.Kind.DOWNLOAD, "en-US", RecognizerRoute.DEFAULT)
        val ready = LanguagePlan(LanguagePlan.Kind.READY, "en-US", RecognizerRoute.ON_DEVICE)
        assertEquals(ready, VoiceLanguages.best(listOf(download, ready)))
        val unknown = LanguagePlan(LanguagePlan.Kind.UNKNOWN, "en-US", RecognizerRoute.DEFAULT)
        assertEquals(unknown, VoiceLanguages.best(listOf(unknown, unknown.copy(route = RecognizerRoute.ON_DEVICE))))
        assertNull(VoiceLanguages.best(emptyList()))
    }

    @Test fun notesNameTheLanguage() {
        assertEquals("Voice input isn't ready yet — downloading English", VoiceLanguages.downloading("en-US"))
    }
}
