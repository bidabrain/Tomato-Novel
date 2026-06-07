package com.thl.book.network.dto;

import java.util.List;

public class RankCategory {
    public String name;
    /** Full list of books from the API (up to 20). */
    public List<RankBook> allBooks;
    /** Randomly selected subset shown in the UI (up to 10). */
    public List<RankBook> displayBooks;
}
