package com.thl.book;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.bumptech.glide.Glide;

import com.jiajunhui.xapp.medialoader.MediaLoader;
import com.jiajunhui.xapp.medialoader.bean.FileItem;
import com.jiajunhui.xapp.medialoader.bean.FileResult;
import com.jiajunhui.xapp.medialoader.bean.FileType;
import com.jiajunhui.xapp.medialoader.callback.OnFileLoaderCallBack;
import com.lcodecore.tkrefreshlayout.RefreshListenerAdapter;
import com.lcodecore.tkrefreshlayout.TwinklingRefreshLayout;
import com.lcodecore.tkrefreshlayout.header.progresslayout.ProgressLayout;
import com.thl.book.base.BaseActivity;
import com.thl.book.base.SingleAdapter;
import com.thl.book.base.SuperViewHolder;
import com.thl.reader.ReadActivity;
import com.thl.reader.db.BookList;
import com.thl.reader.filechooser.FileChooserActivity;
import com.thl.reader.util.FileUtils;

import com.thl.reader.db.DB;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalBookshelfActivity extends BaseActivity implements View.OnClickListener {

    private TwinklingRefreshLayout refreshLayout;
    private RecyclerView mRecyclerView;
    private EditText etSearch;

    private SingleAdapter<BookList> adapter;
    private List<BookList> bookLists;
    private View ib_more;
    private ImageView ib_refresh;

    private CustomPopWindow popWindow;
    private boolean isDel = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // 独立线程用于下载，避免阻塞书架刷新
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    // 有"下载中"条目时每3秒轮询一次数据库
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            executor.execute(() -> {
                List<BookList> books = getBooks();
                boolean hasPending = false;
                for (BookList b : books) {
                    if (b.getBookpath() == null || b.getBookpath().isEmpty()) {
                        hasPending = true;
                        break;
                    }
                }
                final boolean keepPolling = hasPending;
                runOnUiThread(() -> {
                    bookLists.clear();
                    bookLists.addAll(books);
                    adapter.notifyDataSetChanged();
                    if (keepPolling) pollHandler.postDelayed(this, 3000);
                });
            });
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int newCount = intent.getIntExtra("total_new", 0);
            boolean isFinished = intent.getBooleanExtra(UpdateChecker.EXTRA_IS_FINISHED, true);
            // 立即触发一次轮询刷新
            pollHandler.removeCallbacks(pollRunnable);
            pollHandler.post(pollRunnable);
            if (isFinished) {
                setRefreshButtonEnabled(true);
                if (newCount > 0) {
                    Toast.makeText(context, "已更新 " + newCount + " 个新章节", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_book;
    }

    @Override
    protected void initView() {
        TextView tv_title = (TextView) findViewById(R.id.tv_title);
        tv_title.setText("书架");

        etSearch = (EditText) findViewById(R.id.et_search);
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = etSearch.getText().toString().trim();
                if (!TextUtils.isEmpty(query)) {
                    Intent intent = new Intent(this, SearchResultActivity.class);
                    intent.putExtra(SearchResultActivity.EXTRA_QUERY, query);
                    startActivity(intent);
                }
                return true;
            }
            return false;
        });

        refreshLayout = (TwinklingRefreshLayout) findViewById(R.id.refresh);
        mRecyclerView = (RecyclerView) findViewById(R.id.recyclerview);
        ProgressLayout headerView = new ProgressLayout(this);
        refreshLayout.setHeaderView(headerView);
        refreshLayout.setEnableLoadmore(false);
        refreshLayout.setAutoLoadMore(false);
        refreshLayout.setOverScrollRefreshShow(true);
        ib_more = findViewById(R.id.ib_more);
        ib_more.setVisibility(View.VISIBLE);
        ib_more.setOnClickListener(this);

        ib_refresh = findViewById(R.id.ib_refresh);
        ib_refresh.setVisibility(View.VISIBLE);
        ib_refresh.setOnClickListener(v -> {
            if (!UpdateChecker.isRunning()) {
                UpdateChecker.checkOnLaunch(this);
                setRefreshButtonEnabled(false);
            }
        });
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        refreshLayout.setOnRefreshListener(new RefreshListenerAdapter() {
            @Override
            public void onRefresh(final TwinklingRefreshLayout refreshLayout) {
                executor.execute(() -> {
                    List<BookList> books = getBooks();
                    runOnUiThread(() -> {
                        bookLists.clear();
                        bookLists.addAll(books);
                        adapter.notifyDataSetChanged();
                        refreshLayout.finishRefreshing();
                    });
                });
            }
        });

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SingleAdapter<BookList>(LocalBookshelfActivity.this, R.layout.item_book) {
            @Override
            protected void bindData(SuperViewHolder holder, BookList book) {
                LinearLayout lltDel = holder.getView(R.id.llt_del);
                if (isDel) {
                    lltDel.setVisibility(View.VISIBLE);
                    lltDel.setOnClickListener(v -> {
                        executor.execute(() -> {
                            DB.bookList().deleteById(book.getId());
                            // 同步删除本地 TXT 文件
                            String path = book.getBookpath();
                            if (path != null && !path.isEmpty()) {
                                new File(path).delete();
                            }
                            List<BookList> books = getBooks();
                            runOnUiThread(() -> {
                                bookLists.clear();
                                bookLists.addAll(books);
                                adapter.notifyDataSetChanged();
                            });
                        });
                    });
                } else {
                    lltDel.setVisibility(View.GONE);
                }

                ImageView ivCover = holder.getView(R.id.iv_cover);
                TextView tvName = holder.getView(R.id.tv_name);
                TextView tvMsg = holder.getView(R.id.tv_msg);
                View tomatoBadge = holder.getView(R.id.v_tomato_badge);

                String coverUrl = book.getCoverUrl();
                if (coverUrl != null && !coverUrl.isEmpty()) {
                    Glide.with(LocalBookshelfActivity.this)
                            .load(coverUrl)
                            .placeholder(R.mipmap.cover_default_new)
                            .error(R.mipmap.cover_default_new)
                            .into(ivCover);
                } else {
                    ivCover.setImageResource(R.mipmap.cover_default_new);
                }

                tvName.setText(book.getBookname());
                tvMsg.setText(book.getMsg());
                tomatoBadge.setVisibility(book.getIsTomato() == 1 ? View.VISIBLE : View.GONE);

                boolean isDownloading = book.getBookpath() == null || book.getBookpath().isEmpty();
                holder.getRootView().setAlpha(isDownloading ? 0.45f : 1f);
                if (isDownloading) {
                    holder.getRootView().setOnClickListener(v -> retryDownload(book));
                } else {
                    holder.getRootView().setOnClickListener(v ->
                            ReadActivity.openBook(book, LocalBookshelfActivity.this));
                }
            }
        };

        bookLists = new ArrayList<>();
        adapter.setData(bookLists);
        mRecyclerView.setAdapter(adapter);

        // Start update check for Fanqie books
        UpdateChecker.checkOnLaunch(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(UpdateChecker.ACTION_UPDATE_DONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }

        requestStoragePermissionThenInit();
        // 同步按钮状态（可能启动时自动检查正在运行）
        setRefreshButtonEnabled(!UpdateChecker.isRunning());
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(updateReceiver);
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void initFirstData() {
        boolean initFirst = SharedPreferencesUtils.getBoolean(this, "initFristData", true);
        if (initFirst) {
            // Mark as done immediately so a crash during scan doesn't cause repeated imports
            SharedPreferencesUtils.saveBoolean(this, "initFristData", false);
            MediaLoader.getLoader().loadFiles(this, new OnFileLoaderCallBack(FileType.DOC) {
                @Override
                public void onResult(FileResult result) {
                    if (result != null && result.getItems() != null && !result.getItems().isEmpty()) {
                        List<BookList> toSave = new ArrayList<>();
                        for (FileItem item : result.getItems()) {
                            if ("text/plain".equals(item.getMime())) {
                                BookList bookList = new BookList();
                                bookList.setBookname(item.getDisplayName());
                                bookList.setBookpath(item.getPath());
                                bookList.setIsTomato(0);
                                toSave.add(bookList);
                            }
                        }
                        executor.execute(() -> {
                            saveLocalBooks(toSave);
                            runOnUiThread(() -> {
                                bookLists.clear();
                                bookLists.addAll(getBooks());
                                adapter.notifyDataSetChanged();
                            });
                        });
                    }
                }
            });
        } else {
            executor.execute(() -> {
                List<BookList> books = getBooks();
                runOnUiThread(() -> {
                    bookLists.clear();
                    bookLists.addAll(books);
                    adapter.notifyDataSetChanged();
                    // 有下载中条目，启动轮询
                    boolean hasPending = false;
                    for (BookList b : books) {
                        if (b.getBookpath() == null || b.getBookpath().isEmpty()) {
                            hasPending = true;
                            break;
                        }
                    }
                    if (hasPending) {
                        pollHandler.removeCallbacks(pollRunnable);
                        pollHandler.postDelayed(pollRunnable, 3000);
                    }
                });
            });
        }
    }

    private void saveLocalBooks(List<BookList> toSave) {
        for (BookList bookList : toSave) {
            if (DB.bookList().findByBookpath(bookList.getBookpath()) != null) continue;

            // Read first few lines as msg preview
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(bookList.getBookpath()),
                    FileUtils.getCharset(bookList.getBookpath()))) {
                BufferedReader br = new BufferedReader(reader);
                StringBuilder buf = new StringBuilder();
                String line;
                int lines = 0;
                while ((line = br.readLine()) != null && lines < 6) {
                    buf.append(line);
                    lines++;
                }
                bookList.setMsg(buf.toString());
            } catch (Exception e) {
                Log.e("Bookshelf", "msg read failed", e);
            }
            DB.save(bookList);
        }
    }

    private List<BookList> getBooks() {
        return DB.bookList().findAll();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.add_book:
                startActivity(new Intent(this, FileChooserActivity.class));
                popWindow.dissmiss();
                break;

            case R.id.ib_more:
                View view = LayoutInflater.from(this).inflate(R.layout.view_popu2, null);
                view.findViewById(R.id.tv_edit).setOnClickListener(this);
                view.findViewById(R.id.tv_about).setOnClickListener(this);
                view.findViewById(R.id.tv_share).setOnClickListener(this);
                view.findViewById(R.id.add_book).setOnClickListener(this);
                view.findViewById(R.id.find_book).setOnClickListener(this);
                if (popWindow == null) {
                    popWindow = new CustomPopWindow.PopupWindowBuilder(this)
                            .setView(view)
                            .enableBackgroundDark(false)
                            .setFocusable(true)
                            .setOutsideTouchable(true)
                            .create();
                }
                popWindow.showAsDropDown(ib_more, 0, 0);
                break;

            case R.id.tv_edit:
                isDel = !isDel;
                adapter.notifyDataSetChanged();
                popWindow.dissmiss();
                break;

            case R.id.find_book:
                startActivity(new Intent(this, FindBookActivity.class));
                popWindow.dissmiss();
                break;

            case R.id.tv_about:
                startActivity(new Intent(this, AboutActivity.class));
                popWindow.dissmiss();
                break;


            case R.id.tv_share:
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, "推荐一个小说阅读器：Tomato Reader");
                shareIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(shareIntent);
                popWindow.dissmiss();
                break;
        }
    }

    private void setRefreshButtonEnabled(boolean enabled) {
        if (ib_refresh == null) return;
        ib_refresh.setEnabled(enabled);
        ib_refresh.setAlpha(enabled ? 1f : 0.4f);
    }

    private void requestPermissins(PermissionUtils.OnPermissionListener listener) {
        String[] permissions = {
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        };
        PermissionUtils.requestPermissions(this, 0, permissions, listener);
    }

    /**
     * 根据 Android 版本请求合适的存储权限，授权后再初始化数据。
     * - API 30+（Android 11+）：需要"所有文件访问权限"（MANAGE_EXTERNAL_STORAGE）
     * - API 23-29：请求传统读写权限
     * - API < 23：无需运行时权限
     */
    private void requestStoragePermissionThenInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：treader/ 位于共享存储根目录，必须有 MANAGE_EXTERNAL_STORAGE
            if (Environment.isExternalStorageManager()) {
                initFirstData();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("应用需要「所有文件访问权限」才能读写书籍缓存文件及手动添加本地图书，请在下一页面中为本应用开启该权限。")
                        .setPositiveButton("去授权", (d, w) -> {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("暂时跳过", (d, w) -> initFirstData())
                        .setCancelable(false)
                        .show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10：请求传统读写权限
            requestPermissins(new PermissionUtils.OnPermissionListener() {
                @Override
                public void onPermissionGranted() { initFirstData(); }
                @Override
                public void onPermissionDenied(String[] deniedPermissions) {
                    initFirstData(); // 拒绝时仍加载数据库中已有的书
                }
            });
        } else {
            initFirstData();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionUtils.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    private long mExitTime;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isDel) {
            isDel = false;
            adapter.notifyDataSetChanged();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            if (System.currentTimeMillis() - mExitTime > 2000) {
                mExitTime = System.currentTimeMillis();
                Toast.makeText(this, "再次点击返回确认退出", Toast.LENGTH_SHORT).show();
            } else {
                finish();
                System.exit(0);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void retryDownload(BookList book) {
        if (book.getTomatoBookId() == null) return;
        String outputPath = new java.io.File(
                com.thl.book.download.NovelDownloadManager.getTomatoDir(this),
                book.getBookname().replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"
        ).getAbsolutePath();
        final Context appCtx = getApplicationContext();

        downloadExecutor.execute(() -> {
            DB.bookList().updateDownloadResult(
                    book.getTomatoBookId(), book.getBookname(), "", "下载中…", null, book.getCoverUrl());
            sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE).putExtra("total_new", 0));
            runOnUiThread(() -> Toast.makeText(this,
                    "《" + book.getBookname() + "》重新下载中，完成后通知", Toast.LENGTH_SHORT).show());

            com.thl.book.download.NovelDownloadManager manager =
                    new com.thl.book.download.NovelDownloadManager(appCtx);
            manager.downloadFull(book.getTomatoBookId(), book.getBookname(), null, null, outputPath,
                    new com.thl.book.download.NovelDownloadManager.ProgressCallback() {
                        @Override public void onProgress(int d, int t) {}

                        @Override
                        public void onComplete() {
                            NotifyHelper.send(appCtx, "下载完成",
                                    "《" + book.getBookname() + "》已添加到书架");
                            appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .putExtra("total_new", 0));
                        }

                        @Override
                        public void onError(String message) {
                            DB.bookList().updateDownloadResult(
                                    book.getTomatoBookId(), book.getBookname(), "", "下载失败，点击重试", null, book.getCoverUrl());
                            NotifyHelper.send(appCtx, "下载失败",
                                    "《" + book.getBookname() + "》" + message);
                            appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .putExtra("total_new", 0));
                        }
                    });
        });
    }
}
