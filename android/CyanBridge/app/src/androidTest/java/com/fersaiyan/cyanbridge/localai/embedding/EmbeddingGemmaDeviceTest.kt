package com.fersaiyan.cyanbridge.localai.embedding

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Runs the imported native model on a real Android device; no generated vectors are substituted. */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemmaDeviceTest {
    @Test fun importedModelEmbedsRussianRetrievalPair() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Import EmbeddingGemma before running the device test", EmbeddingGemmaFiles.isReady(context))
        val engine = EmbeddingGemmaEngine(context)
        val query = engine.embed("Какое кодовое слово я попросил запомнить?", EmbeddingRole.QUERY)
        val relevant = engine.embed("Пользователь попросил запомнить кодовое слово синий маяк.", EmbeddingRole.DOCUMENT)
        val unrelated = engine.embed("Сегодня на улице идёт дождь и дует холодный ветер.", EmbeddingRole.DOCUMENT)
        listOf(query, relevant, unrelated).forEach { vector ->
            assertEquals(EmbeddingGemmaEngine.DIMENSIONS, vector.size)
            assertTrue(vector.all(Float::isFinite))
            assertTrue(abs(vector.sumOf { (it * it).toDouble() } - 1.0) < 0.001)
        }
        val relevantScore = query.indices.sumOf { (query[it] * relevant[it]).toDouble() }
        val unrelatedScore = query.indices.sumOf { (query[it] * unrelated[it]).toDouble() }
        Log.i("EmbeddingGemmaDeviceTest", "relevant=$relevantScore unrelated=$unrelatedScore")
        assertTrue("Relevant memory should rank above unrelated weather", relevantScore > unrelatedScore)
    }
}
