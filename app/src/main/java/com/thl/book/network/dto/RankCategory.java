package com.thl.book.network.dto;

import java.util.List;

public class RankCategory {
    public String name;
    /** 频道："female"（女频）/ "male"（男频）；数据里没有时为空串 */
    public String channel;
    /** Full list of books from the API (up to 20). */
    public List<RankBook> allBooks;
    /** Randomly selected subset shown in the UI (up to 10). */
    public List<RankBook> displayBooks;
}
