package com.thl.reader.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.thl.reader.db.BookList;

import java.util.List;

@Dao
public interface BookListDao {
    @Query("SELECT * FROM book_list ORDER BY lastReadAt DESC, id DESC")
    List<BookList> findAll();

    @Query("SELECT * FROM book_list WHERE bookpath = :bookpath LIMIT 1")
    BookList findByBookpath(String bookpath);

    @Query("SELECT * FROM book_list WHERE isTomato = :isTomato")
    List<BookList> findByIsTomato(int isTomato);

    @Query("SELECT * FROM book_list WHERE tomatoBookId = :tomatoBookId")
    List<BookList> findByTomatoBookId(String tomatoBookId);

    @Insert
    long insert(BookList bookList);

    @Insert
    void insertAll(List<BookList> list);

    @Update
    void update(BookList bookList);

    @Query("DELETE FROM book_list WHERE id = :id")
    void deleteById(int id);

    @Query("UPDATE book_list SET charset = :charset, msg = :msg WHERE id = :id")
    void updateCharsetAndMsg(int id, String charset, String msg);

    @Query("UPDATE book_list SET begin = :begin, lastReadAt = :lastReadAt WHERE id = :id")
    void updateBegin(int id, long begin, long lastReadAt);

    @Query("UPDATE book_list SET bookname = :bookname, bookpath = :bookpath, msg = :msg, charset = :charset, coverUrl = :coverUrl WHERE tomatoBookId = :tomatoBookId")
    void updateDownloadResult(String tomatoBookId, String bookname, String bookpath, String msg, String charset, String coverUrl);
}
