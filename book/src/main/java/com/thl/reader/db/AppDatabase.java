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
    version = 4,
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

    // 去重并给 tomato_book.bookId 建唯一索引：历史上同一 bookId 会被重复 INSERT，
    // 导致 findByBookId(LIMIT 1) 读到旧记录、章节数统计错乱。
    // 每个 bookId 仅保留 totalChapters 最大（并列时 id 最大）的那行。
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "DELETE FROM tomato_book WHERE id NOT IN (" +
                "  SELECT id FROM tomato_book t1 WHERE NOT EXISTS (" +
                "    SELECT 1 FROM tomato_book t2 WHERE t2.bookId = t1.bookId AND (" +
                "      t2.totalChapters > t1.totalChapters OR" +
                "      (t2.totalChapters = t1.totalChapters AND t2.id > t1.id)" +
                "    )" +
                "  )" +
                ")"
            );
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_tomato_book_bookId " +
                "ON tomato_book (bookId)"
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
                    ).addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                     .fallbackToDestructiveMigration()
                     .build();
                }
            }
        }
        return instance;
    }
}
