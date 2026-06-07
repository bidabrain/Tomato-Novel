package com.thl.reader.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.thl.reader.db.BookCatalogue;

import java.util.List;

@Dao
public interface BookCatalogueDao {
    @Query("SELECT * FROM book_catalogue WHERE bookpath = :bookpath")
    List<BookCatalogue> findByBookpath(String bookpath);

    @Insert
    void insertAll(List<BookCatalogue> list);

    @Query("DELETE FROM book_catalogue WHERE bookpath = :bookpath")
    void deleteByBookpath(String bookpath);
}
