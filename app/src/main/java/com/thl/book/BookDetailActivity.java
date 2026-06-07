package com.thl.book;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.thl.book.base.BaseActivity;

public class BookDetailActivity extends BaseActivity {

    public static final String EXTRA_TITLE  = "detail_title";
    public static final String EXTRA_AUTHOR = "detail_author";
    public static final String EXTRA_READS  = "detail_reads";
    public static final String EXTRA_INTRO  = "detail_intro";
    public static final String EXTRA_COVER  = "detail_cover";

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

    @Override
    protected int initLayout() {
        return R.layout.activity_book_detail;
    }

    @Override
    protected void initView() {
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
                ? "阅读量：" + reads : "");
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

        Button btnSearch = findViewById(R.id.btn_search);
        btnSearch.setOnClickListener(v -> {
            Intent intent = new Intent(this, SearchResultActivity.class);
            intent.putExtra(SearchResultActivity.EXTRA_QUERY, title);
            startActivity(intent);
        });
    }

    @Override
    protected void initData(Bundle savedInstanceState) {}
}
