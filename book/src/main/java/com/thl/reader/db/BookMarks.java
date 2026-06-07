package com.thl.reader.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "book_marks")
public class BookMarks {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private long begin;
    private String text;
    private String time;
    private String bookpath;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public long getBegin() { return begin; }
    public void setBegin(long begin) { this.begin = begin; }

    public String getBookpath() { return bookpath; }
    public void setBookpath(String bookpath) { this.bookpath = bookpath; }
}
