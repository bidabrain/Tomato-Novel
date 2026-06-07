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
}
