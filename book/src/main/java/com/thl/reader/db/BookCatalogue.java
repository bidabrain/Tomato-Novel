package com.thl.reader.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "book_catalogue")
public class BookCatalogue {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String bookpath;
    private String bookCatalogue;
    private long bookCatalogueStartPos;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getBookCatalogue() { return bookCatalogue; }
    public void setBookCatalogue(String bookCatalogue) { this.bookCatalogue = bookCatalogue; }

    public String getBookpath() { return bookpath; }
    public void setBookpath(String bookpath) { this.bookpath = bookpath; }

    public long getBookCatalogueStartPos() { return bookCatalogueStartPos; }
    public void setBookCatalogueStartPos(long bookCatalogueStartPos) { this.bookCatalogueStartPos = bookCatalogueStartPos; }
}
