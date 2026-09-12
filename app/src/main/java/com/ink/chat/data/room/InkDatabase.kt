package com.ink.chat.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ink.chat.data.room.dao.ConversationDao
import com.ink.chat.data.room.dao.MessageDao
import com.ink.chat.data.room.dao.ModelCacheDao
import com.ink.chat.data.room.dao.UsageDao
import com.ink.chat.data.room.entity.ConversationEntity
import com.ink.chat.data.room.entity.MessageEntity
import com.ink.chat.data.room.entity.ModelCacheEntity
import com.ink.chat.data.room.entity.UsageEntity

/**
 * ink-chat 数据库（§4.4 四表）。
 * 开发期使用 fallbackToDestructiveMigration（【心得·代码层 Room-3】）；
 * **首次发布起**换用正式 Migration，版本号每次结构变更 +1。
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        UsageEntity::class,
        ModelCacheEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class InkDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun usageDao(): UsageDao
    abstract fun modelCacheDao(): ModelCacheDao

    companion object {
        /** M5.6：usage_logs 增加缓存命中/未命中列（正式迁移，保数据；fallback 仍为开发兜底） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_logs ADD COLUMN cache_hit_tokens INTEGER")
                db.execSQL("ALTER TABLE usage_logs ADD COLUMN cache_miss_tokens INTEGER")
            }
        }

        fun build(context: Context): InkDatabase =
            Room.databaseBuilder(context, InkDatabase::class.java, "ink_chat.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}