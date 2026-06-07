package com.thl.book.download;

import android.text.Html;

/**
 * Converts Fanqie API HTML chapter content to plain TXT
 * formatted for BookUtil's parser.
 */
public class HtmlToTxt {

    /**
     * Convert a chapter's HTML content to TXT paragraphs.
     * Each paragraph is prefixed with two ideographic spaces (as BookUtil expects).
     */
    public static String convert(String html) {
        if (html == null || html.isEmpty()) return "";

        // Normalise line breaks before stripping tags
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)</div>", "\n");

        // Strip remaining HTML tags
        text = Html.fromHtml(text).toString();

        // Split into paragraphs, indent each non-empty line
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                sb.append("\u3000\u3000").append(trimmed).append("\r\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Format a full chapter block ready to be appended to the TXT file.
     * Format matches BookUtil.getChapter() regex: .*第.{1,8}章.*
     */
    public static String formatChapter(String title, String htmlContent) {
        return "\r\n" + title + "\r\n\r\n" + convert(htmlContent) + "\r\n";
    }
}
