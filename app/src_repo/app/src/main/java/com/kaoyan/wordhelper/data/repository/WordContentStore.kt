package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookWordContent
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.entity.WordEntity
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.WordDraft
import kotlinx.coroutines.flow.Flow

internal class WordContentStore(
    private val bookDao: BookDao,
    private val wordDao: WordDao,
    private val wordMemoryDao: WordMemoryDao
) {
    fun getWordsByBook(bookId: Long): Flow<List<Word>> = wordDao.getWordsByBook(bookId)

    suspend fun getWordsByBookList(bookId: Long): List<Word> = wordDao.getWordsByBookList(bookId)

    suspend fun getWordsForExport(book: Book): List<Word> {
        return if (book.type == Book.TYPE_NEW_WORDS) {
            wordDao.getNewWordsList()
        } else {
            wordDao.getWordsByBookList(book.id)
        }
    }

    suspend fun getNewWordsList(): List<Word> = wordDao.getNewWordsList()

    suspend fun getWordById(wordId: Long): Word? = wordDao.getWordById(wordId)

    suspend fun searchWords(query: String, currentBookId: Long?): List<Word> {
        return wordDao.searchWords(query, currentBookId ?: 0L)
    }

    suspend fun insertWords(words: List<Word>) {
        if (words.isEmpty()) return
        val draftsByBook = words.groupBy { it.bookId }
        draftsByBook.forEach { (bookId, list) ->
            if (bookId <= 0L) return@forEach
            val drafts = list.map { word ->
                WordDraft(
                    word = word.word,
                    phonetic = word.phonetic,
                    meaning = word.meaning,
                    example = word.example,
                    phrases = word.phrases,
                    synonyms = word.synonyms,
                    relWords = word.relWords
                )
            }
            upsertDraftsForBook(bookId, drafts)
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
        }
    }

    suspend fun getWordCount(bookId: Long): Int = wordDao.getWordCount(bookId)

    suspend fun getNextNewWord(bookId: Long): Word? = wordDao.getNextNewWord(bookId)

    private fun normalizeWordKey(raw: String): String {
        return raw.trim().lowercase()
    }

    suspend fun upsertDraftsForBook(
        bookId: Long,
        drafts: List<WordDraft>,
        memorySource: String = WordMemory.SOURCE_SEED,
        seedFingerprints: Map<String, String> = emptyMap(),
        externalSeedOverride: Boolean = false
    ) {
        if (drafts.isEmpty()) return

        val normalizedDrafts = LinkedHashMap<String, WordDraft>(drafts.size)
        drafts.forEach { draft ->
            val key = normalizeWordKey(draft.word)
            if (key.isBlank() || normalizedDrafts.containsKey(key)) {
                return@forEach
            }
            normalizedDrafts[key] = draft
        }
        if (normalizedDrafts.isEmpty()) return

        val wordIdsByKey = HashMap<String, Long>(normalizedDrafts.size)
        normalizedDrafts.keys
            .toList()
            .chunked(MAX_DB_IN_CLAUSE_SIZE)
            .forEach { chunk ->
                wordDao.getWordEntitiesByKeys(chunk).forEach { entity ->
                    wordIdsByKey[entity.wordKey] = entity.id
                    val draft = normalizedDrafts[entity.wordKey] ?: return@forEach
                    val phonetic = draft.phonetic.trim()
                    if (entity.phonetic.isBlank() && phonetic.isNotBlank()) {
                        wordDao.updatePhonetic(entity.id, phonetic)
                    }
                }
            }

        normalizedDrafts.forEach { (key, draft) ->
            if (wordIdsByKey.containsKey(key)) {
                return@forEach
            }
            val rawWord = draft.word.trim()
            if (rawWord.isBlank()) {
                return@forEach
            }
            val insertedId = wordDao.insertWordEntity(
                WordEntity(
                    word = rawWord,
                    wordKey = key,
                    phonetic = draft.phonetic.trim()
                )
            )
            val resolvedWordId = if (insertedId > 0) {
                insertedId
            } else {
                wordDao.getWordIdByKey(key)
            }
            if (resolvedWordId != null) {
                wordIdsByKey[key] = resolvedWordId
            }
        }

        val contents = ArrayList<BookWordContent>(normalizedDrafts.size)
        normalizedDrafts.forEach { (key, draft) ->
            val wordId = wordIdsByKey[key] ?: return@forEach
            contents.add(
                BookWordContent(
                    wordId = wordId,
                    bookId = bookId,
                    meaning = draft.meaning,
                    example = draft.example,
                    phrases = draft.phrases,
                    synonyms = draft.synonyms,
                    relWords = draft.relWords
                )
            )
        }
        if (contents.isNotEmpty()) {
            wordDao.upsertBookWordContents(contents)
        }

        // 记忆思路与搭配按 word_id 单独存，重新导入只覆盖它们自己，
        // 不会碰 tb_progress，所以更新内容不会重置学习进度。
        // 已存在的行需先确认来源，只更新正文和来源，保留标签、版本及撤销记录。
        val existingMemories = HashMap<Long, WordMemory>(normalizedDrafts.size)
        wordIdsByKey.values
            .distinct()
            .chunked(MAX_DB_IN_CLAUSE_SIZE)
            .forEach { chunk ->
                wordMemoryDao.getByWordIds(chunk).forEach { existingMemories[it.wordId] = it }
            }
        val updatedAt = System.currentTimeMillis()
        val newMemories = ArrayList<WordMemory>(normalizedDrafts.size)
        normalizedDrafts.forEach { (key, draft) ->
            val wordId = wordIdsByKey[key] ?: return@forEach
            val approach = draft.approach.trim()
            val collocations = draft.collocations.trim()
            if (approach.isBlank() && collocations.isBlank()) return@forEach
            val existingMemory = existingMemories[wordId]
            if (existingMemory != null) {
                if (SeedMemoryUpgrade.canReplace(
                        existingMemory, memorySource, seedFingerprints[key], externalSeedOverride
                    ) &&
                    (existingMemory.approach != approach || existingMemory.collocations != collocations ||
                        existingMemory.source != memorySource)
                ) {
                    wordMemoryDao.updateContent(wordId, approach, collocations, memorySource, updatedAt)
                }
            } else {
                newMemories.add(
                    WordMemory(
                        wordId = wordId,
                        approach = approach,
                        collocations = collocations,
                        updatedAt = updatedAt,
                        source = memorySource
                    )
                )
            }
        }
        if (newMemories.isNotEmpty()) {
            wordMemoryDao.upsertAll(newMemories)
        }
        // 1.4.6：导入新书不再继承别的书的进度（syncBookProgressWithGlobal 已删除）。
        // 按书独立之后，新书就该从零开始；否则「其他书从零」的口径会被一次导入破掉。
    }
}
