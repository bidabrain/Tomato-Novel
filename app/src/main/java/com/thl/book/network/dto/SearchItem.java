package com.thl.book.network.dto;

import com.google.gson.annotations.SerializedName;

public class SearchItem {
    @SerializedName("book_id")
    public String bookId;

    @SerializedName("book_name")
    public String bookName;

    @SerializedName("author")
    public String author;

    @SerializedName("abstract")
    public String summary;

    @SerializedName("thumb_url")
    public String coverUrl;

    @SerializedName("serial_count")
    public int chapterCount;

    /** 字数，来自 raw.word_number */
    public long wordNumber;

    /** 连载状态：1=连载中，0=已完结；来自 raw.update_status */
    public int updateStatus;

    /** 评分，来自 raw.score */
    public String score;

    /** 分类标签，来自 raw.category */
    public String category;
}
