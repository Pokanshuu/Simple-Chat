package com.simplechat.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        PresetEntity::class,
        ConversationPresetEntity::class,
        CompactionEntity::class,
        DraftEntity::class,
    ],
    // v2：messages 从「槽位」改成「树」（slotIndex → parentId）
    // v3：messages 增加 attachments（图片 / 纯文本附件）
    // v4：messages 增加 model（记下这条是哪个模型写的）
    // v5：新增 drafts（未发送的输入草稿）
    // v6：drafts 增加 title（「新对话」的待用标题）
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun presetDao(): PresetDao

    abstract fun conversationPresetDao(): ConversationPresetDao

    abstract fun compactionDao(): CompactionDao

    abstract fun draftDao(): DraftDao

    companion object {

        /**
         * v2 → v3：只加一列，纯增量。
         *
         * ⚠️ **每次改 schema 都必须配一条显式 Migration。**
         *
         * 这里原本是 `fallbackToDestructiveMigration(dropAllTables = true)` ——
         * 开发期图省事，但它意味着"用户升级一次 App，全部对话凭空消失"。
         * 用户真机上已经有成篇的稿子，这个代价不能接受。
         * 宁可让它崩（看得见、能修），也不能让它静默清库。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT")
            }
        }

        /**
         * v3 → v4：加一列 `model`，纯增量。
         *
         * 只给**新生成的消息**写得进去 —— 老消息这一列是 NULL，
         * 界面上就不显示模型名（而不是显示一个猜的），这是刻意的：
         * 老会话里可能混着好几个模型，硬填一个反而是假信息。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT")
            }
        }

        /**
         * v4 → v5：新增 `drafts` 表，纯增量。
         *
         * 会话里没发出去的输入草稿。⚠️ SQL 必须和 Room 生成的 schema **逐字对齐**
         * （列序、NOT NULL、主键写法都算），否则升级时的 schema 校验会当场崩。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drafts` (" +
                        "`conversationId` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`conversationId`))"
                )
            }
        }

        /**
         * v5 → v6：`drafts` 加一列 `title`，纯增量。
         *
         * 「新对话」的待用标题 —— 用户在发出第一条消息之前就起好了名，而那时会话行
         * 还不存在。与草稿同一个处境，一起记在 `drafts` 里。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN title TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "simplechat.db",
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
