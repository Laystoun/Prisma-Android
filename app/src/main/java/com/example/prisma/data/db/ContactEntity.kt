package com.example.prisma.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.SecureRandom

@Entity (indices = [Index(value = ["pubKey"], unique = true)])
data class ContactEntity(
    @PrimaryKey (autoGenerate = true)
    val id: Long = 0,
    val pubKey: String,
    val name: String?,
    val created: Long
)

object MessageStatus {
    const val SENDING = 0
    const val SENT = 1
    const val ERROR = 2
}

object MessageDirection {
    const val IN =  0
    const val OUT = 1
}

@Database (entities = [
    ContactEntity::class, MessageEntity::class
    ], exportSchema = false, version = 2)
abstract class PrismaDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL("""
                    ALTER TABLE MessageEntity
                    ADD COLUMN messageClientId TEXT NOT NULL DEFAULT ''
                """)

                db.execSQL("""
                    UPDATE MessageEntity
                    SET messageClientId = lower(hex(randomblob(16)))
                    WHERE messageClientId = ''
                """)

                db.execSQL("""
                    CREATE UNIQUE INDEX index_MessageEntity_messageClientId
                    ON MessageEntity(messageClientId)
                """)
            }
        }
        @Volatile private var instance: PrismaDatabase? = null

        fun getInstance(context: Context): PrismaDatabase {
            if (instance != null) { return instance!! }

            synchronized(this) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.applicationContext,
                        PrismaDatabase::class.java,
                        "prisma.db"
                    ).addMigrations(MIGRATION_1_2).build()
                }
            }

            return instance!!
        }
    }

    abstract fun getDao(): ChatDao
}

fun generateMessageId(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)

    return bytes.joinToString("") {
        "%02x".format(it)
    }
}