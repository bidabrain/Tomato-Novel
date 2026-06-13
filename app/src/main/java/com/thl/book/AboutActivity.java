package com.thl.book;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import com.thl.book.base.BaseActivity;

public class AboutActivity extends BaseActivity {

    @Override
    protected int initLayout() {
        return R.layout.activity_about;
    }

    @Override
    protected void initView() {
        TextView tv_title = (TextView) findViewById(R.id.tv_title);
        tv_title.setText("关于");
        findViewById(R.id.ib_back).setVisibility(View.VISIBLE);
        findViewById(R.id.ib_back).setOnClickListener(v -> finish());

        findViewById(R.id.tv_feedback).setVisibility(View.GONE);

        String version = "unknown";
        try {
            version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        TextView tvMsg = (TextView) findViewById(R.id.tv_msg);
        tvMsg.setMovementMethod(LinkMovementMethod.getInstance());
        tvMsg.setText(
                "版本：" + version + "（独立版）\n\n" +
                "一款支持番茄小说搜索与下载的本地 TXT 阅读器。\n" +
                "无广告，无追踪。\n\n" +
                "源码：\nhttps://github.com/bidabrain/Tomato-Novel"
        );

        TextView tvChangelog = findViewById(R.id.tv_changelog);
        tvChangelog.setText(BuildConfig.CHANGELOG.replace("\\n", "\n"));
    }

    @Override
    protected void initData(Bundle savedInstanceState) {}
}
