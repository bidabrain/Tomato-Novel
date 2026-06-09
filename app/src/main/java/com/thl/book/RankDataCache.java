package com.thl.book;

import com.thl.book.network.dto.RankBook;
import com.thl.book.network.dto.RankCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * In-memory cache for book store rank data.
 * Lives for the duration of the app process (one session = one fetch).
 */
public class RankDataCache {

    private static final int DISPLAY_COUNT = 12;
    private static final Random RANDOM = new Random();

    private static List<RankCategory> sCategories = null;
    private static boolean sFetchedThisSession = false;

    public static boolean isFetchedThisSession() {
        return sFetchedThisSession;
    }

    public static boolean hasData() {
        return sCategories != null && !sCategories.isEmpty();
    }

    /** Clear cached data (e.g. when server URL changes in settings). */
    public static void invalidate() {
        sCategories = null;
        sFetchedThisSession = false;
    }

    public static List<RankCategory> getCategories() {
        return sCategories;
    }

    /** Store fetched categories and immediately randomize each one's display list. */
    public static void setCategories(List<RankCategory> categories) {
        sCategories = categories;
        sFetchedThisSession = true;
        if (categories != null) {
            for (RankCategory cat : categories) {
                randomizeCategory(cat);
            }
        }
    }

    /**
     * Re-randomize a single category: pick up to {@value DISPLAY_COUNT} books at random
     * from its full list and store them in {@code displayBooks}.
     */
    public static void randomizeCategory(RankCategory category) {
        if (category.allBooks == null || category.allBooks.isEmpty()) {
            category.displayBooks = new ArrayList<>();
            return;
        }
        List<RankBook> shuffled = new ArrayList<>(category.allBooks);
        Collections.shuffle(shuffled, RANDOM);
        int count = Math.min(DISPLAY_COUNT, shuffled.size());
        category.displayBooks = new ArrayList<>(shuffled.subList(0, count));
    }
}
