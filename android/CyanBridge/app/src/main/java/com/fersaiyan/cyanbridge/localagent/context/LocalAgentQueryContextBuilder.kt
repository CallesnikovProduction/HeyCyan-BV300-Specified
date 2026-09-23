package com.fersaiyan.cyanbridge.localagent.context

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemorySearch
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import java.text.SimpleDateFormat
import java.util.Locale

/** Selects local memory for an assistant query and builds its bounded system context. */
class LocalAgentQueryContextBuilder(private val context: Context) {
    private companion object {
        const val QUERY_MAX_AGENT_PERSONA_CHARS = 1200
        const val QUERY_MAX_USER_FACTS_CHARS = 1400
        const val QUERY_MAX_CONFIRMED_FACTS_CHARS = 1800
        const val QUERY_MAX_DAILY_SUMMARY_CHARS = 2200
        const val QUERY_MAX_TOTAL_CONTEXT_CHARS = 6500
    }

    fun todayDateString(tsMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(java.util.Date(tsMs))
    }

    private fun tokenizeMemoryQuery(text: String): List<String> {
        val stopwords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "what", "when",
            "how", "who", "why", "are", "was", "were", "can", "could", "should", "would",
            "will", "just", "like", "your", "you", "about", "have", "has", "had", "then",
            "que", "para", "com", "uma", "nao", "não", "isso", "essa", "esse", "foi", "tem",
            "como", "porque", "por", "das", "dos", "uns", "umas"
        )

        return text
            .lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopwords }
            .distinct()
    }

    private fun selectRelevantMemoryItems(items: List<String>, queryText: String, maxItems: Int): List<String> {
        val clean = items
            .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (clean.isEmpty()) return emptyList()
        val tokens = tokenizeMemoryQuery(queryText)
        if (tokens.isEmpty()) return clean.take(minOf(maxItems, 2))

        val scored = clean.map { item ->
            val hay = item.lowercase(Locale.US)
            var score = 0
            for (token in tokens) {
                if (hay.contains(token)) score += 1
            }
            item to score
        }

        val hits = scored
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .map { it.first }
            .take(maxItems)

        return if (hits.isNotEmpty()) hits else clean.take(minOf(maxItems, 2))
    }

    fun buildSystemPrompt(queryText: String, date: String, ragProfile: RagProfile): String {
        if (ragProfile == RagProfile.NONE) return ""
        val light = ragProfile == RagProfile.LIGHT
        val extraSections = mutableListOf<LocalAgentContextBuilder.Section>()

        val retrieval = LocalAgentMemorySearch.buildRelevantMemoryBlock(
            context = context,
            queryText = queryText,
            date = date,
            lookbackDaysFacts = if (light) 3 else 5,
            topFacts = if (light) 2 else 4,
            topSummaryLines = if (light) 1 else 3,
            maxChars = if (light) 500 else 900,
            ragProfile = ragProfile,
        )
        if (retrieval.isNotBlank()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Relevant memory (search hits)",
                content = retrieval,
            )
        }

        val draftFacts = runCatching { DailyFactsStorage.load(context, date).draft }.getOrDefault(emptyList())
        val draftRef = LocalAgentMemoryStore.memoryRefForFile(
            context,
            LocalAgentMemoryStore.dailyFactsFileForDate(context, date),
        )
        val relevantDraft = if (MemoryPolicyService.isMemoryRefSearchEligible(context, draftRef)) {
            selectRelevantMemoryItems(draftFacts, queryText, maxItems = if (light) 1 else 4)
        } else {
            emptyList()
        }
        if (relevantDraft.isNotEmpty()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Today's draft daily facts (unconfirmed)",
                content = relevantDraft.joinToString("\n") { "- $it" },
            )
        }

        val candidateFacts = runCatching { CandidateUserFactsStorage.load(context, date) }.getOrDefault(emptyList())
        val candidateRef = LocalAgentMemoryStore.memoryRefForFile(
            context,
            LocalAgentMemoryStore.userFactsCandidatesFileForDate(context, date),
        )
        val relevantCandidates = if (MemoryPolicyService.isMemoryRefSearchEligible(context, candidateRef)) {
            selectRelevantMemoryItems(candidateFacts, queryText, maxItems = if (light) 1 else 3)
        } else {
            emptyList()
        }
        if (relevantCandidates.isNotEmpty()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Candidate user facts (pending review)",
                content = relevantCandidates.joinToString("\n") { "- $it" },
            )
        }

        val builder = LocalAgentContextBuilder(
            maxAgentPersonaChars = if (light) 300 else QUERY_MAX_AGENT_PERSONA_CHARS,
            maxUserFactsChars = if (light) 400 else QUERY_MAX_USER_FACTS_CHARS,
            maxConfirmedDailyFactsChars = if (light) 350 else QUERY_MAX_CONFIRMED_FACTS_CHARS,
            maxDailySummaryChars = if (light) 300 else QUERY_MAX_DAILY_SUMMARY_CHARS,
            maxTotalChars = if (light) 1_800 else QUERY_MAX_TOTAL_CONTEXT_CHARS,
        )

        return builder.buildSystemMessage(
            context = context,
            date = date,
            extraSections = extraSections,
        )
    }
}
