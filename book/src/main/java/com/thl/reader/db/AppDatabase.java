package com.thl.reader.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.thl.reader.db.dao.BookCatalogueDao;
import com.thl.reader.db.dao.BookListDao;
import com.thl.reader.db.dao.BookMarksDao;
import com.thl.reader.db.dao.TomatoBookDao;

@Database(
    entities = {BookList.class, BookMarks.class, BookCatalogue.class, TomatoBook.class},
    version = 3,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;

    public abstract BookListDao bookListDao();
    public abstract BookMarksDao bookMarksDao();
    public abstract BookCatalogueDao bookCatalogueDao();
    public abstract TomatoBookDao tomatoBookDao();

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "ALTER TABLE book_list ADD COLUMN lastReadAt INTEGER NOT NULL DEFAULT 0"
            );
        }
    };

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "book.db"
                    ).addMigrations(MIGRATION_2_3)
                     .fallbackToDestructiveMigration()
                     .build();
                }
            }
        }
        return instance;
    }
}
