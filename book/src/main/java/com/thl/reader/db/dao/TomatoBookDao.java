package com.thl.reader.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.thl.reader.db.TomatoBook;

import java.util.List;

@Dao
public interface TomatoBookDao {
    @Query("SELECT * FROM tomato_book WHERE bookId = :bookId LIMIT 1")
    TomatoBook findByBookId(String bookId);

    @Insert
    long insert(TomatoBook book);

    @Update
    void update(TomatoBook book);
}
