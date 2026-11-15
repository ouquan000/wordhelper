package com.kaoyan.wordhelper.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.CardSeedLoader
import com.kaoyan.wordhelper.data.model.CardSeedPayload
import com.kaoyan.wordhelper.data.model.CardSeedWord
import com.kaoyan.wordhelper.data.model.WordDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedMemoryUpgradeInstrumentedTest {
    @Test
    fun v3RefreshesTheBundledPredecessorAndKeepsImportedOrRevisedNotes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundled = context.assets.open(CardSeedLoader.ASSET_NAME).bufferedReader().use {
            Gson().fromJson(it, CardSeedPayload::class.java)
        }
        assertEquals(3, bundled.version)
        val keys = setOf("adolescent", "technology", "experience", "mental")
        val v3 = bundled.copy(words = bundled.words.filter { it.word in keys })
        val v2 = v3.copy(version = 2, words = listOf(
            CardSeedWord(
                word = "adolescent",
                approach = listOf(
                    "① 想象一群刚上初中的孩子，个子猛蹿、声音变粗，卡在“小孩”和“大人”中间那一站。",
                    "② 这一站的人还没到站，既不算儿童也不算成人，所以名词就是青少年。",
                    "③ 用来形容这个阶段的特点——爱冲动、爱问“我是谁”，就是青春期的、青少年的。",
                    "④ 对比着记：adult 是已经到站的成年人，adolescent 是还在半路上、正在长的那一个。"
                ),
                collocations = listOf(
                    "adolescent development 青春期发育",
                    "adolescent behavior 青少年行为",
                    "during adolescence 在青春期"
                )
            ),
            CardSeedWord(word = "experience", approach = listOf("legacy import")),
            CardSeedWord(word = "mental", approach = listOf("custom revision"))
        ))
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = WordRepository(database)
            val memories = database.wordMemoryDao()
            suspend fun memory(word: String): WordMemory = memories.getByWordId(
                requireNotNull(database.wordDao().getWordIdByKey(word))
            )!!

            repository.ensureCardSeedBook(v2)
            repository.importBook("自用词书", listOf(WordDraft("technology", approach = "from my file")))
            memories.upsert(memory("mental").copy(rev = 1, prevApproach = "previous custom revision"))
            memories.upsert(memory("adolescent").copy(method = "meaning"))
            val before = keys.associateWith { memory(it) }
            assertEquals(WordMemory.SOURCE_IMPORT, before.getValue("technology").source)

            repository.ensureCardSeedBook(v3)
            val after = keys.associateWith { memory(it) }
            val revised = after.getValue("adolescent")
            assertNotEquals(before.getValue("adolescent").approach, revised.approach)
            assertEquals(CardSeedLoader.toDrafts(v3).first { it.word == "adolescent" }.approach, revised.approach)
            assertEquals("meaning", revised.method)
            for (key in keys - "adolescent") assertEquals(before.getValue(key), after.getValue(key))

            repository.ensureCardSeedBook(v3)
            assertEquals(after, keys.associateWith { memory(it) })
        } finally {
            database.close()
        }
    }
}
