package com.thl.reader.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.thl.reader.db.BookMarks;

import java.util.List;

@Dao
public interface BookMarksDao {
    @Query("SELECT * FROM book_marks WHERE bookpath = :bookpath")
    List<BookMarks> findByBookpath(String bookpath);

    @Query("SELECT * FROM book_marks WHERE bookpath = :bookpath AND begin = :begin")
    List<BookMarks> findByBookpathAndBegin(String bookpath, long begin);

    @Insert
    void insert(BookMarks bookMarks);

    @Query("DELETE FROM book_marks WHERE id = :id")
    void deleteById(int id);
}
