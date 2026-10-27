package com.example.prisma.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ], exportSchema = false, version = 1)
abstract class PrismaDatabase : RoomDatabase() {
    companion object {
        @Volatile private var instance: PrismaDatabase? = null

        fun getInstance(context: Context): PrismaDatabase {
            if (instance != null) { return instance!! }

            synchronized(this) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.applicationContext,
                        PrismaDatabase::class.java,
                        "prisma.db"
                    ).build()
                }
            }

            return instance!!
        }
    }

    abstract fun getDao(): ChatDao
}