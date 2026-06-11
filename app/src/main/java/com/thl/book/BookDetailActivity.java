package com.thl.book;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.thl.book.base.BaseActivity;
import com.thl.book.download.NovelDownloadManager;
import com.thl.reader.db.BookList;
import com.thl.reader.db.DB;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookDetailActivity extends BaseActivity {

    public static final String EXTRA_TITLE   = "detail_title";
    public static final String EXTRA_AUTHOR  = "detail_author";
    public static final String EXTRA_READS   = "detail_reads";
    public static final String EXTRA_INTRO   = "detail_intro";
    public static final String EXTRA_COVER   = "detail_cover";
    public static final String EXTRA_BOOK_ID = "detail_book_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** 书城入口（无 bookId，底部显示"搜索此书"） */
    public static void start(Context context, String title, String author,
                             String reads, String intro, String cover) {
        Intent intent = new Intent(context, BookDetailActivity.class);
        intent.putExtra(EXTRA_TITLE,  title);
        intent.putExtra(EXTRA_AUTHOR, author);
        intent.putExtra(EXTRA_READS,  reads);
        intent.putExtra(EXTRA_INTRO,  intro);
        intent.putExtra(EXTRA_COVER,  cover);
        context.startActivity(intent);
    }

    /** 搜索结果入口（有 bookId，底部显示"加入书架"） */
    public static void startFromSearch(Context context, String bookId, String title,
                                       String author, String reads, String intro, String cover) {
        Intent intent = new Intent(context, BookDetailActivity.class);
        intent.putExtra(EXTRA_BOOK_ID, bookId);
        intent.putExtra(EXTRA_TITLE,   title);
        intent.putExtra(EXTRA_AUTHOR,  author);
        intent.putExtra(EXTRA_READS,   reads);
        intent.putExtra(EXTRA_INTRO,   intro);
        intent.putExtra(EXTRA_COVER,   cover);
        context.startActivity(intent);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_book_detail;
    }

    @Override
    protected void initView() {
        String bookId = getIntent().getStringExtra(EXTRA_BOOK_ID);
        String title  = getIntent().getStringExtra(EXTRA_TITLE);
        String author = getIntent().getStringExtra(EXTRA_AUTHOR);
        String reads  = getIntent().getStringExtra(EXTRA_READS);
        String intro  = getIntent().getStringExtra(EXTRA_INTRO);
        String cover  = getIntent().getStringExtra(EXTRA_COVER);

        ((TextView) findViewById(R.id.tv_title)).setText(title);
        ((TextView) findViewById(R.id.tv_book_title)).setText(title);
        ((TextView) findViewById(R.id.tv_author)).setText(author != null && !author.isEmpty()
                ? "作者：" + author : "");
        ((TextView) findViewById(R.id.tv_reads)).setText(reads != null && !reads.isEmpty()
                ? reads : "");
        ((TextView) findViewById(R.id.tv_intro)).setText(intro);

        ImageView ivCover = findViewById(R.id.iv_cover);
        if (cover != null && !cover.isEmpty()) {
            Glide.with(this)
                    .load(cover)
                    .placeholder(R.mipmap.cover_default_new)
                    .error(R.mipmap.cover_default_new)
                    .into(ivCover);
        }

        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        Button btn = findViewById(R.id.btn_search);
        if (bookId != null && !bookId.isEmpty()) {
            btn.setText("加入书架");
            btn.setOnClickListener(v -> addToShelf(bookId, title, cover));
        } else {
            btn.setText("搜索此书");
            btn.setOnClickListener(v -> {
                Intent intent = new Intent(this, SearchResultActivity.class);
                intent.putExtra(SearchResultActivity.EXTRA_QUERY, title);
                startActivity(intent);
            });
        }
    }

    @Override
    protected void initData(Bundle savedInstanceState) {}

    private void addToShelf(String bookId, String bookName, String coverUrl) {
        String outputPath = new File(
                NovelDownloadManager.getTomatoDir(this),
                bookName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"
        ).getAbsolutePath();
        final android.content.Context appCtx = getApplicationContext();

        executor.execute(() -> {
            List<BookList> existing = DB.bookList().findByTomatoBookId(bookId);
            if (!existing.isEmpty() &&
                    existing.get(0).getBookpath() != null &&
                    !existing.get(0).getBookpath().isEmpty()) {
                runOnUiThread(() -> Toast.makeText(appCtx,
                        "书架中已有《" + bookName + "》", Toast.LENGTH_SHORT).show());
                return;
            }
            if (existing.isEmpty()) {
                BookList placeholder = new BookList();
                placeholder.setBookname(bookName);
                placeholder.setBookpath("");
                placeholder.setIsTomato(1);
                placeholder.setTomatoBookId(bookId);
                placeholder.setMsg("下载中…");
                placeholder.setCoverUrl(coverUrl);
                DB.save(placeholder);
                WebDavConfig.markBookshelfModified(appCtx);
            } else {
                DB.bookList().updateDownloadResult(bookId, bookName, "", "下载中…", null, coverUrl);
            }
            appCtx.sendBroadcast(new android.content.Intent(UpdateChecker.ACTION_UPDATE_DONE)
                    .setPackage(appCtx.getPackageName())
                    .putExtra("total_new", 0));
            runOnUiThread(() -> {
                Toast.makeText(this, "《" + bookName + "》下载中，完成后通知", Toast.LENGTH_SHORT).show();
                finish();
            });

            NovelDownloadManager manager = new NovelDownloadManager(appCtx);
            manager.downloadFull(bookId, bookName, null, coverUrl, outputPath,
                    new NovelDownloadManager.ProgressCallback() {
                        @Override public void onProgress(int d, int t) {}
                        @Override public void onComplete() {
                            NotifyHelper.send(appCtx, "下载完成", "《" + bookName + "》已添加到书架");
                            appCtx.sendBroadcast(new android.content.Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .setPackage(appCtx.getPackageName())
                                    .putExtra("total_new", 0));
                        }
                        @Override public void onError(String message) {
                            DB.bookList().updateDownloadResult(
                                    bookId, bookName, "", "下载失败，点击重试", null, coverUrl);
                            NotifyHelper.send(appCtx, "下载失败", "《" + bookName + "》" + message);
                            appCtx.sendBroadcast(new android.content.Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .setPackage(appCtx.getPackageName())
                                    .putExtra("total_new", 0));
                        }
                    });
        });
    }
}
