package com.kaoyan.wordhelper.data.repository

import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.CardSeedLoader
import com.kaoyan.wordhelper.data.model.CardSeedPayload
import com.kaoyan.wordhelper.data.model.PresetBookCatalog
import com.kaoyan.wordhelper.data.model.PresetBookSeed
import com.kaoyan.wordhelper.data.model.WordDraft
import kotlinx.coroutines.flow.Flow

internal class WordBookStore(
    private val database: AppDatabase,
    private val wordContent: WordContentStore,
    private val notebook: WordNotebookStore
) {
    private val bookDao = database.bookDao()
    private val wordDao = database.wordDao()
    private val progressDao = database.progressDao()
    private val earlyReviewDao = database.earlyReviewDao()
    private val wordMemoryDao = database.wordMemoryDao()
    private val rewriteQueueDao = database.rewriteQueueDao()
    private val bookMemoryDao = database.bookMemoryDao()

    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks()

    suspend fun getBookById(bookId: Long): Book? = bookDao.getBookById(bookId)

    suspend fun getActiveBook(): Book? = bookDao.getActiveBook()

    fun getActiveBookFlow(): Flow<Book?> = bookDao.getActiveBookFlow()

    suspend fun switchBook(newBookId: Long) {
        bookDao.deactivateAll()
        bookDao.activate(newBookId)
    }

    suspend fun insertBook(book: Book): Long = bookDao.insert(book)

    suspend fun deleteBook(book: Book) = bookDao.delete(book)

    suspend fun deleteBookWithData(book: Book): Boolean {
        if (book.type == Book.TYPE_NEW_WORDS) {
            return false
        }
        database.withTransaction {
            val wasActive = book.isActive
            earlyReviewDao.deleteByBook(book.id)
            progressDao.deleteByBook(book.id)
            // 队列行没有外键；不清理会留下 needs_reinsert=1 的孤儿，永久挡住同词。
            rewriteQueueDao.deleteByBook(book.id)
            wordDao.deleteByBook(book.id)
            wordDao.deleteOrphanWords()
            bookDao.delete(book)

            if (wasActive) {
                val remaining = bookDao.getAllBooksList().filter { it.id != book.id }
                val fallback = remaining.firstOrNull { it.type != Book.TYPE_NEW_WORDS } ?: remaining.firstOrNull()
                if (fallback != null) {
                    bookDao.deactivateAll()
                    bookDao.activate(fallback.id)
                }
            }
        }
        return true
    }

    suspend fun importBook(name: String, drafts: List<WordDraft>): Long {
        return database.withTransaction {
            val bookId = bookDao.insert(
                Book(name = name, type = Book.TYPE_IMPORTED, totalCount = 0, isActive = false)
            )
            wordContent.upsertDraftsForBook(bookId, drafts, memorySource = WordMemory.SOURCE_IMPORT)
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
            bookId
        }
    }

    /**
     * 把内置种子词库落到一本词书里（没有就新建，有就原地更新），返回词书 id。
     *
     * 内容与记忆思路按 word_key 去重后 REPLACE 写入，不碰 tb_progress，
     * 所以重复执行只刷新「词义 / 例句 / 记忆思路 / 搭配」，学习进度不会重置。
     * 没有任何可用的其他活跃词书时，自动把这本书设为当前词书。
     */
    suspend fun ensureCardSeedBook(seed: CardSeedPayload): Long {
        val bookName = seed.book.trim().ifBlank { CardSeedLoader.DEFAULT_BOOK_NAME }
        val drafts = CardSeedLoader.toDrafts(seed)
        if (drafts.isEmpty()) return 0L

        var bookId = 0L
        database.withTransaction {
            val existing = bookDao.findByNameAndType(bookName, Book.TYPE_IMPORTED)
            bookId = existing?.id ?: bookDao.insert(
                Book(name = bookName, type = Book.TYPE_IMPORTED, totalCount = 0, isActive = false)
            )
            wordContent.upsertDraftsForBook(
                bookId,
                drafts,
                seedFingerprints = SeedMemoryUpgrade.previousFingerprints(seed.version, bookName),
                externalSeedOverride = seed.externalOverride
            )
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
            val active = bookDao.getActiveBook()
            if (active == null || active.type == Book.TYPE_NEW_WORDS) {
                bookDao.deactivateAll()
                bookDao.activate(bookId)
            }
        }
        return bookId
    }

    /**
     * 20 词测试书：只建书和内容，不切成当前学习书（用户想测时自己切）。
     * 已存在同名书时就地更新，不重复建。
     */
    suspend fun ensureTestDeck(seed: CardSeedPayload): Long {
        val bookName = seed.book.trim().ifBlank { "测试·20词" }
        val drafts = CardSeedLoader.toDrafts(seed)
        if (drafts.isEmpty()) return 0L

        var bookId = 0L
        database.withTransaction {
            val existing = bookDao.findByNameAndType(bookName, Book.TYPE_IMPORTED)
            bookId = existing?.id ?: bookDao.insert(
                Book(name = bookName, type = Book.TYPE_IMPORTED, totalCount = 0, isActive = false)
            )
            wordContent.upsertDraftsForBook(bookId, drafts)
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
        }
        return bookId
    }

    /**
     * 1.4.4：给种子卡片回填记忆方法标签（六脉神剑之一）。
     * 语料来自 assets/word_method_map.json（by-word 导出里本来就带 method，零模型成本）。
     * 按方法分组、分批 UPDATE，只填空值，不覆盖重写过的词。
     * 返回标注后库里已有标签的行数，方便日志核对。
     */
    suspend fun backfillWordMethods(map: Map<String, String>): Int {
        if (map.isEmpty()) return 0
        val byMethod = map.entries.groupBy({ it.value }, { it.key })
        database.withTransaction {
            byMethod.forEach { (method, keys) ->
                keys.chunked(400).forEach { chunk -> wordDao.backfillMethod(chunk, method) }
            }
        }
        return wordDao.countMarkedMethod()
    }

    /**
     * 1.4.6（P0）：种子重导只更新正文，不动 method，而 WordDraft 根本没有 method 字段 ——
     * 于是新导进来的词带着默认的 "root"，老行也永远停在 "root"。
     * 这里按 word_method_map 把「种子来源、且还是 fromMethod」的行改成真正的方法标签：
     * - 只动种子行，source='user'（用户重写过）一律不碰；
     * - 候选集来自 DAO 的「仍在 root 的行」，改完 method 变了，下次启动候选集自然为空 —— 幂等。
     * 返回实际改掉的行数（tb_word_memory + tb_book_memory），方便日志核对。
     */
    suspend fun refreshRootMethods(
        map: Map<String, String>,
        fromMethod: String
    ): Int {
        if (map.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var changed = 0
        database.withTransaction {
            val candidates = LinkedHashSet<Long>()
            candidates += wordMemoryDao.getWordIdsByMethod(fromMethod, WordMemory.SOURCE_USER)
            candidates += bookMemoryDao.getWordIdsByMethod(fromMethod, WordMemory.SOURCE_USER)
            candidates.forEach { wordId ->
                val key = wordDao.getWordKeyById(wordId) ?: return@forEach
                val method = map[key]?.trim()
                    ?.takeIf { it.isNotEmpty() && it != fromMethod }
                    ?: return@forEach
                changed += wordMemoryDao.refreshMethod(
                    wordId = wordId,
                    fromMethod = fromMethod,
                    method = method,
                    updatedAt = now,
                    userSource = WordMemory.SOURCE_USER
                )
                changed += bookMemoryDao.refreshMethod(
                    wordId = wordId,
                    fromMethod = fromMethod,
                    method = method,
                    updatedAt = now,
                    userSource = WordMemory.SOURCE_USER
                )
            }
        }
        return changed
    }

    suspend fun ensurePresetBooks(presetSeeds: List<PresetBookSeed>) {
        database.withTransaction {
            // 必须连隐藏书一起读：隐藏只是软停用，同名预设回来时要原地复活，
            // 否则会新建一本，旧书上的学习进度就永远对不上了。
            val books = bookDao.getAllBooksIncludingHidden().toMutableList()
            if (books.none { it.type == Book.TYPE_NEW_WORDS }) {
                val newWordsId = bookDao.insert(
                    Book(
                        name = PresetBookCatalog.NEW_WORDS_BOOK_NAME,
                        type = Book.TYPE_NEW_WORDS,
                        totalCount = 0,
                        isActive = false
                    )
                )
                bookDao.getBookById(newWordsId)?.let { books.add(it) }
            }

            val presetNames = presetSeeds.map { it.name }.toSet()
            val booksToHide = PresetBookPlanner.booksToHide(books, presetNames)
            if (booksToHide.isNotEmpty()) {
                // P0：下架/重名的预设书只软停用（total_count 打成隐藏哨兵），
                // 不删 tb_book、不删 tb_book_word_content、更不删 tb_progress。
                // 学习进度全部按 book_id 保存，旧实现删书行 = 永久丢进度。
                val hiddenIds = booksToHide.map { it.id }.toSet()
                booksToHide.forEach { bookDao.markPresetHidden(it.id) }
                books.replaceAll { book ->
                    if (book.id in hiddenIds) {
                        book.copy(isActive = false, totalCount = Book.HIDDEN_TOTAL_COUNT)
                    } else {
                        book
                    }
                }
            }

            var hasActiveBook = books.any { it.isActive }
            presetSeeds.forEachIndexed { index, preset ->
                val sameNameBooks = books.filter { it.type == Book.TYPE_PRESET && it.name == preset.name }
                val existing = sameNameBooks.firstOrNull { !it.isHidden } ?: sameNameBooks.firstOrNull()
                val targetBook = if (existing == null) {
                    val createdId = bookDao.insert(
                        Book(
                            name = preset.name,
                            type = Book.TYPE_PRESET,
                            totalCount = 0,
                            isActive = !hasActiveBook && index == 0
                        )
                    )
                    val created = bookDao.getBookById(createdId)
                    if (created != null) {
                        books.add(created)
                        if (created.isActive) {
                            hasActiveBook = true
                        }
                    }
                    created
                } else if (existing.isHidden) {
                    // 预设重新上架：原地复活，tb_progress 原样保留。
                    bookDao.unhidePreset(existing.id)
                    val restored = bookDao.getBookById(existing.id)
                    if (restored != null) {
                        val position = books.indexOfFirst { it.id == restored.id }
                        if (position >= 0) books[position] = restored else books.add(restored)
                        if (restored.isActive) hasActiveBook = true
                    }
                    restored
                } else {
                    existing
                }
                if (targetBook != null) {
                    syncPresetWords(targetBook.id, preset.drafts)
                    // sync 只改 total_count，回读一次让内存视图与库里保持一致。
                    val refreshed = bookDao.getBookById(targetBook.id)
                    if (refreshed != null) {
                        val position = books.indexOfFirst { it.id == refreshed.id }
                        if (position >= 0) books[position] = refreshed
                        if (refreshed.isActive) hasActiveBook = true
                    }
                }
            }

            if (!hasActiveBook) {
                val fallbackBook = books.firstOrNull { it.type == Book.TYPE_PRESET && !it.isHidden }
                    ?: books.firstOrNull { !it.isHidden }
                if (fallbackBook != null) {
                    bookDao.deactivateAll()
                    bookDao.activate(fallbackBook.id)
                }
            }
            notebook.updateNewWordsBookCount()
        }
    }

    suspend fun updateBookTotalCount(bookId: Long) {
        val count = wordDao.getWordCount(bookId)
        bookDao.updateTotalCount(bookId, count)
    }

    private suspend fun syncPresetWords(bookId: Long, drafts: List<WordDraft>) {
        if (drafts.isEmpty()) {
            bookDao.updateTotalCount(bookId, 0)
            return
        }

        val currentCount = wordDao.getWordCount(bookId)
        // 只比词数不够：词数不变、释义/例句换了内容时旧逻辑会永远 return，用户一直看到旧正文。
        // 词数相同且内容指纹一致才跳过；指纹只覆盖 upsert 会覆盖的正文列。
        if (currentCount == drafts.size &&
            PresetBookContentFingerprint.matches(drafts, wordDao.getBookContentForFingerprint(bookId))
        ) {
            return
        }

        // 全量 upsert：REPLACE 会覆盖正文列，旧词也一起刷新；tb_progress 不受影响。
        wordContent.upsertDraftsForBook(bookId, drafts)
        bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
    }
}
