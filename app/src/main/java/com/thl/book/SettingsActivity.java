package com.thl.book;

import android.os.Bundle;
import com.thl.book.base.BaseActivity;

/** Retained as stub — settings are now hardcoded (see README). */
public class SettingsActivity extends BaseActivity {
    @Override protected int initLayout() { return R.layout.activity_book; }
    @Override protected void initView() { finish(); }
    @Override protected void initData(Bundle savedInstanceState) {}
}
