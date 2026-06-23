package com.thl.reader.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "tomato_book", indices = {@Index(value = "bookId", unique = true)})
public class TomatoBook {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String bookId;
    private String bookName;
    private String author;
    private String coverUrl;
    private int totalChapters;
    private String lastChapterId;
    private long lastCheckedAt;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }

    public String getBookName() { return bookName; }
    public void setBookName(String bookName) { this.bookName = bookName; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }

    public String getLastChapterId() { return lastChapterId; }
    public void setLastChapterId(String lastChapterId) { this.lastChapterId = lastChapterId; }

    public long getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(long lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
}
