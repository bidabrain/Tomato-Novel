package com.thl.book.network.dto;

import com.google.gson.annotations.SerializedName;

public class ChapterItem {
    @SerializedName("item_id")
    public String itemId;

    @SerializedName("title")
    public String title;
}
