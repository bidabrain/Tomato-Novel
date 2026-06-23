package com.thl.reader.db;

import android.content.Context;

import com.thl.reader.db.dao.BookCatalogueDao;
import com.thl.reader.db.dao.BookListDao;
import com.thl.reader.db.dao.BookMarksDao;
import com.thl.reader.db.dao.TomatoBookDao;

/**
 * Static accessor for Room DAOs — replaces LitePal's DataSupport static API.
 * Must call DB.init(context) once from Application.onCreate().
 * All methods are synchronous — call from a background thread.
 */
public class DB {
    private static AppDatabase db;

    public static void init(Context context) {
        db = AppDatabase.get(context);
    }

    public static BookListDao bookList() { return db.bookListDao(); }
    public static BookMarksDao bookMarks() { return db.bookMarksDao(); }
    public static BookCatalogueDao catalogue() { return db.bookCatalogueDao(); }
    public static TomatoBookDao tomatoBook() { return db.tomatoBookDao(); }

    /** Insert or update a BookList: insert if id==0, update otherwise. */
    public static void save(BookList b) {
        if (b.getId() == 0) { long id = db.bookListDao().insert(b); b.setId((int) id); }
        else db.bookListDao().update(b);
    }

    /** Insert or update a BookMarks (always insert — no update path needed). */
    public static void save(BookMarks m) {
        db.bookMarksDao().insert(m);
    }

    /**
     * Insert or update a TomatoBook, keyed by bookId.
     * 即便调用方 new 了一个 id==0 的对象，只要 bookId 已存在就走 update，
     * 避免同一本书在 tomato_book 里产生重复行（曾导致章节数统计读到旧记录）。
     */
    public static void save(TomatoBook t) {
        if (t.getId() == 0 && t.getBookId() != null) {
            TomatoBook existing = db.tomatoBookDao().findByBookId(t.getBookId());
            if (existing != null) t.setId(existing.getId());
        }
        if (t.getId() == 0) { long id = db.tomatoBookDao().insert(t); t.setId((int) id); }
        else db.tomatoBookDao().update(t);
    }
}
