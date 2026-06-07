package com.thl.book.base;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

/**
 * 功能描述：activity 基类
 **/
public abstract class BaseActivity extends FragmentActivity {

    protected Activity mContext;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(initLayout());
        initView();
        initData(savedInstanceState);
        initListener();
    }

    protected void initListener() {
    }

    protected abstract void initView();

    protected abstract void initData(Bundle savedInstanceState);

    protected abstract int initLayout();



}