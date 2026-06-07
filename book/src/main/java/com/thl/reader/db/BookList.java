package com.thl.reader.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "book_list")
public class BookList implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String bookname;
    private String bookpath;
    private long begin;
    private String charset;
    private String msg;
    private int isTomato;       // 0=local, 1=Fanqie
    private String tomatoBookId;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getBookname() { return bookname; }
    public void setBookname(String bookname) { this.bookname = bookname; }

    public String getBookpath() { return bookpath; }
    public void setBookpath(String bookpath) { this.bookpath = bookpath; }

    public long getBegin() { return begin; }
    public void setBegin(long begin) { this.begin = begin; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }

    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }

    public int getIsTomato() { return isTomato; }
    public void setIsTomato(int isTomato) { this.isTomato = isTomato; }

    public String getTomatoBookId() { return tomatoBookId; }
    public void setTomatoBookId(String tomatoBookId) { this.tomatoBookId = tomatoBookId; }

    private String coverUrl;
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
}
