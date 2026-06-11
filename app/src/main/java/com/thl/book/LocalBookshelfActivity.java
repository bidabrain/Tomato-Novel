package com.thl.book;

import androidx.appcompat.app.AlertDialog;
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
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
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
import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.lcodecore.tkrefreshlayout.RefreshListenerAdapter;
import com.lcodecore.tkrefreshlayout.TwinklingRefreshLayout;
import com.lcodecore.tkrefreshlayout.header.progresslayout.ProgressLayout;
import com.thl.book.base.BaseActivity;
import com.thl.book.base.SingleAdapter;
import com.thl.book.base.SuperViewHolder;
import com.thl.reader.Config;
import com.thl.reader.ReadActivity;
import com.thl.reader.db.BookList;
import com.thl.reader.filechooser.FileChooserActivity;
import com.thl.reader.util.FileUtils;
import com.thl.reader.util.ReadingStatsManager;

import androidx.appcompat.widget.SwitchCompat;

import com.thl.reader.db.DB;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalBookshelfActivity extends BaseActivity implements View.OnClickListener {

    private TwinklingRefreshLayout refreshLayout;
    private RecyclerView mRecyclerView;
    private EditText etSearch;

    private SingleAdapter<BookList> adapter;
    private List<BookList> bookLists;
    private View ib_more;

    private View containerBookshelf;
    private FrameLayout containerBookStore;
    private TextView tvTitle;
    private BookStoreFragment bookStoreFragment;
    private SwitchCompat swEink;
    private SwitchCompat swWebDav;

    private TextView tvUpdateStatus;

    // 顶部双卡片行
    private View rowTopCards;
    private TextView tvWeeklyTime;   // gone，仅保留 id 兼容性
    private TextView tvWeeklyNumber;
    private TextView tvWeeklyUnit;
    private TextView tvSyncLabel;
    // "上次读到"卡片
    private View cardContinueReading;
    private TextView tvContinueTitle;
    private TextView tvContinueProgress;
    private ImageView ivContinueCover;
    private View rowShelfHeader;

    private CustomPopWindow popWindow;
    private boolean isDel = false;
    private boolean coverRefreshDone = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // 独立线程用于下载，避免阻塞书架刷新
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> importLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) importBookshelf(uri); });

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
                    if (isDestroyed() || isFinishing()) return;
                    bookLists.clear();
                    bookLists.addAll(books);
                    adapter.notifyDataSetChanged();
                    updateContinueCard();
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
            int current = intent.getIntExtra(UpdateChecker.EXTRA_CURRENT, 0);
            int total = intent.getIntExtra(UpdateChecker.EXTRA_TOTAL, 0);
            String bookName = intent.getStringExtra(UpdateChecker.EXTRA_BOOK_NAME);
            android.util.Log.d("TomUpdateDbg", "onReceive → isFinished=" + isFinished
                    + " cur=" + current + "/" + total + " name='" + bookName + "'");
            // 立即触发一次轮询刷新
            pollHandler.removeCallbacks(pollRunnable);
            pollHandler.post(pollRunnable);
            if (isFinished) {
                showUpdateBanner(false, null, 0, 0);
                if (newCount > 0) {
                    Toast.makeText(context, "已更新 " + newCount + " 个新章节", Toast.LENGTH_SHORT).show();
                }
            } else {
                showUpdateBanner(true, bookName, current, total);
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
        tvTitle = (TextView) findViewById(R.id.tv_title);
        tvTitle.setText("书架");

        containerBookshelf = findViewById(R.id.container_bookshelf);
        containerBookStore = (FrameLayout) findViewById(R.id.container_book_store);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_bookshelf) {
                showBookshelf();
                return true;
            } else if (id == R.id.nav_book_store) {
                showBookStore();
                return true;
            }
            return false;
        });

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

        tvUpdateStatus = findViewById(R.id.tv_update_status);

        // 顶部双卡片行
        rowTopCards = findViewById(R.id.row_top_cards);
        tvWeeklyTime = findViewById(R.id.tv_weekly_time);
        tvWeeklyNumber = findViewById(R.id.tv_weekly_number);
        tvWeeklyUnit = findViewById(R.id.tv_weekly_unit);
        tvSyncLabel = findViewById(R.id.tv_sync_label);
        // "上次读到"卡片
        cardContinueReading = findViewById(R.id.card_continue_reading);
        tvContinueTitle = findViewById(R.id.tv_continue_title);
        tvContinueProgress = findViewById(R.id.tv_continue_progress);
        ivContinueCover = findViewById(R.id.iv_continue_cover);
        rowShelfHeader = findViewById(R.id.row_shelf_header);

        cardContinueReading.setOnClickListener(v -> {
            // 打开最近阅读的书
            if (bookLists != null && !bookLists.isEmpty()) {
                BookList lastRead = null;
                for (BookList b : bookLists) {
                    if (b.getLastReadAt() > 0) { lastRead = b; break; }
                }
                if (lastRead != null && lastRead.getBookpath() != null && !lastRead.getBookpath().isEmpty()) {
                    ReadActivity.openBook(lastRead, LocalBookshelfActivity.this);
                }
            }
        });

        // 电纸书模式开关
        swEink = findViewById(R.id.sw_eink);
        swEink.setVisibility(View.VISIBLE);
        Config config = Config.createConfig(this);
        swEink.setChecked(config.isEinkMode());
        applyEinkMode(config.isEinkMode());
        swEink.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 只存 eink 标志位，不修改 pageMode/bookBg
            // （阅读界面在 openBook() 完成后再动态应用，避免 PageWidget 状态非法）
            config.setEinkMode(isChecked);
            applyEinkMode(isChecked);
            if (bookStoreFragment != null) {
                bookStoreFragment.onEinkModeChanged();
            }
        });

        // WebDAV 同步开关
        swWebDav = findViewById(R.id.sw_webdav);
        swWebDav.setVisibility(View.VISIBLE);
        swWebDav.setChecked(WebDavConfig.isEnabled(this));
        refreshWebDavSwitchEnabled();
        swWebDav.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferencesUtils.saveBoolean(this, WebDavConfig.KEY_WEBDAV_ENABLED, isChecked);
            refreshSyncLabel();
        });
    }

    /** 如果 WebDAV URL 未配置，锁定（变灰不可点击）同步开关 */
    private void refreshWebDavSwitchEnabled() {
        if (swWebDav == null) return;
        boolean hasUrl = !WebDavConfig.getUrl(this).isEmpty();
        swWebDav.setEnabled(hasUrl);
        swWebDav.setAlpha(hasUrl ? 1f : 0.4f);
        if (!hasUrl) swWebDav.setChecked(false);
        refreshSyncLabel();
    }

    /** 根据自动同步是否开启及上次同步时间，刷新本周阅读卡片底部标签 */
    private void refreshSyncLabel() {
        if (tvSyncLabel == null) return;
        if (!WebDavConfig.isEnabled(this)) {
            tvSyncLabel.setVisibility(View.GONE);
            return;
        }
        long lastSync = WebDavConfig.getLastSyncAt(this);
        String text;
        if (lastSync == 0) {
            text = "同步：从未";
        } else {
            long diffMs = System.currentTimeMillis() - lastSync;
            long minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(diffMs);
            long hours   = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(diffMs);
            long days    = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffMs);
            String ago;
            if (minutes < 1)     ago = "刚刚";
            else if (hours < 1)  ago = minutes + " 分钟前";
            else if (days < 1)   ago = hours + " 小时前";
            else                 ago = days + " 天前";
            text = "同步：" + ago;
        }
        tvSyncLabel.setText(text);
        tvSyncLabel.setVisibility(View.VISIBLE);
    }

    private void applyEinkMode(boolean eink) {
        // 整体背景
        View root = findViewById(R.id.activity_album);
        if (root != null) {
            root.setBackgroundColor(eink ? 0xFFFFFFFF : getResources().getColor(R.color.bg_activity));
        }
        // 上次读到卡片样式
        if (cardContinueReading != null) {
            cardContinueReading.setBackgroundResource(
                    eink ? R.drawable.bg_continue_card_eink : R.drawable.bg_continue_card);
        }
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        refreshLayout.setOnRefreshListener(new RefreshListenerAdapter() {
            @Override
            public void onRefresh(final TwinklingRefreshLayout refreshLayout) {
                // 立即显示横条并启动检查，不等待书单重载完成
                android.util.Log.d("TomUpdateDbg", "onRefresh → isRunning=" + UpdateChecker.isRunning());
                showUpdateBanner(true, null, 0, 0);
                if (!UpdateChecker.isRunning()) {
                    UpdateChecker.checkOnLaunch(LocalBookshelfActivity.this);
                }
                executor.execute(() -> {
                    List<BookList> books = getBooks();
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        bookLists.clear();
                        bookLists.addAll(books);
                        adapter.notifyDataSetChanged();
                        updateContinueCard();
                        refreshLayout.finishRefreshing();
                        // 不重置横条 — 由 BroadcastReceiver 驱动进度更新
                    });
                });
            }
        });

        mRecyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new SingleAdapter<BookList>(LocalBookshelfActivity.this, R.layout.item_book) {
            @Override
            protected void bindData(SuperViewHolder holder, BookList book) {
                LinearLayout lltDel = holder.getView(R.id.llt_del);
                if (isDel) {
                    lltDel.setVisibility(View.VISIBLE);
                    lltDel.setOnClickListener(v -> {
                        executor.execute(() -> {
                            DB.bookList().deleteById(book.getId());
                            WebDavConfig.markBookshelfModified(LocalBookshelfActivity.this);
                            // 仅删除番茄下载的 TXT 文件，本地导入的书保留源文件
                            if (book.getIsTomato() == 1) {
                                String path = book.getBookpath();
                                if (path != null && !path.isEmpty()) {
                                    new File(path).delete();
                                }
                            }
                            List<BookList> books = getBooks();
                            runOnUiThread(() -> {
                                if (isDestroyed() || isFinishing()) return;
                                bookLists.clear();
                                bookLists.addAll(books);
                                adapter.notifyDataSetChanged();
                                updateContinueCard();
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
                TextView tvUpdateBadge = holder.getView(R.id.tv_update_badge);
                TextView tvDownloading = holder.getView(R.id.tv_downloading);

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

                // 精确提取番茄专用格式"（新+X）"作为更新 badge，避免本地书内容误触发
                String rawMsg = book.getMsg() != null ? book.getMsg() : "";
                java.util.regex.Matcher badgeMatcher =
                        java.util.regex.Pattern.compile("（新\\+\\d+）").matcher(rawMsg);
                String updateText = badgeMatcher.find() ? badgeMatcher.group() : "";

                // 进度：优先用 chapterProgress；本地书无进度时不显示摘要内容
                String progressText = "";
                if (book.getChapterProgress() != null) {
                    progressText = book.getChapterProgress();
                } else if (book.getIsTomato() == 1 && updateText.isEmpty()) {
                    // 番茄书尚无进度且无更新信息时，显示 msg（如"下载中…"等状态文字）
                    progressText = rawMsg.replaceAll("（新\\+\\d+）", "").trim();
                }
                tvMsg.setText(progressText);

                // 更新 badge
                if (!updateText.isEmpty()) {
                    tvUpdateBadge.setText(updateText);
                    tvUpdateBadge.setVisibility(View.VISIBLE);
                } else {
                    tvUpdateBadge.setVisibility(View.GONE);
                }

                tomatoBadge.setVisibility(book.getIsTomato() == 1 ? View.VISIBLE : View.GONE);

                boolean isDownloading = book.getBookpath() == null || book.getBookpath().isEmpty();
                holder.getRootView().setAlpha(1f);
                tvDownloading.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
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
        showUpdateBanner(true, null, 0, 0);
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
        // 用户可能刚从设置页面回来，URL 可能变化，刷新开关状态
        refreshWebDavSwitchEnabled();
        // 同步横条状态（可能启动时广播在 receiver 注册前就发出了）
        showUpdateBanner(UpdateChecker.isRunning(),
                UpdateChecker.getCurrentBookName(),
                UpdateChecker.getCurrentBook(),
                UpdateChecker.getTotalBooks());
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(updateReceiver);
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void initFirstData() {
        executor.execute(() -> {
            List<BookList> books = getBooks();
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                bookLists.clear();
                bookLists.addAll(books);
                adapter.notifyDataSetChanged();
                updateContinueCard();
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
                refreshMissingCovers(books);
            });
        });
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
            WebDavConfig.markBookshelfModified(this);
        }
    }

    private List<BookList> getBooks() {
        List<BookList> books = DB.bookList().findAll();
        for (BookList book : books) {
            if (book.getBookpath() == null || book.getBookpath().isEmpty()) continue;
            List<com.thl.reader.db.BookCatalogue> chapters =
                    DB.catalogue().findByBookpath(book.getBookpath());
            if (chapters.isEmpty()) continue;
            // 按起始位置排序
            java.util.Collections.sort(chapters,
                    (a, b) -> Long.compare(a.getBookCatalogueStartPos(), b.getBookCatalogueStartPos()));
            int currentChapter = 0;
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).getBookCatalogueStartPos() <= book.getBegin()) {
                    currentChapter = i + 1;
                }
            }
            if (currentChapter > 0) {
                book.setChapterProgress("读至第" + currentChapter + "章 / 共" + chapters.size() + "章");
            } else {
                book.setChapterProgress("共" + chapters.size() + "章，尚未开始");
            }
        }
        return books;
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
                view.findViewById(R.id.tv_donate).setOnClickListener(this);
                view.findViewById(R.id.add_book).setOnClickListener(this);
                view.findViewById(R.id.find_book).setOnClickListener(this);
                view.findViewById(R.id.tv_settings).setOnClickListener(this);
                view.findViewById(R.id.tv_export).setOnClickListener(this);
                view.findViewById(R.id.tv_import).setOnClickListener(this);
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

            case R.id.tv_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                popWindow.dissmiss();
                break;


            case R.id.tv_export:
                exportBookshelf();
                popWindow.dissmiss();
                break;

            case R.id.tv_import:
                importLauncher.launch("*/*");
                popWindow.dissmiss();
                break;

            case R.id.tv_donate:
                startActivity(new Intent(this, DonateActivity.class));
                popWindow.dissmiss();
                break;
        }
    }

    private void showBookshelf() {
        containerBookshelf.setVisibility(View.VISIBLE);
        containerBookStore.setVisibility(View.GONE);
        tvTitle.setText("书架");
        ib_more.setVisibility(View.VISIBLE);
    }

    private void showBookStore() {
        containerBookshelf.setVisibility(View.GONE);
        containerBookStore.setVisibility(View.VISIBLE);
        tvTitle.setText("书城");
        ib_more.setVisibility(View.GONE);

        if (bookStoreFragment == null) {
            bookStoreFragment = new BookStoreFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container_book_store, bookStoreFragment)
                    .commit();
        }
    }

    /** 根据 bookLists 更新顶部双卡片行（本周阅读 + 上次读到）和书架标题行的显隐 */
    private void updateContinueCard() {
        if (cardContinueReading == null) return;
        boolean hasBooks = !bookLists.isEmpty();

        // 整行显隐
        if (rowTopCards != null) rowTopCards.setVisibility(hasBooks ? View.VISIBLE : View.GONE);

        // 本周阅读时间
        if (tvWeeklyNumber != null) {
            long seconds = ReadingStatsManager.getWeeklySeconds(this);
            String[] parts = splitTimeDisplay(seconds);
            tvWeeklyNumber.setText(parts[0]);
            tvWeeklyUnit.setText(parts[1]);
        }

        // 上次读到
        BookList lastRead = null;
        for (BookList b : bookLists) {
            if (b.getLastReadAt() > 0 && b.getBookpath() != null && !b.getBookpath().isEmpty()) {
                lastRead = b;
                break;
            }
        }
        if (lastRead != null) {
            cardContinueReading.setVisibility(View.VISIBLE);
            tvContinueTitle.setText(lastRead.getBookname());
            String progress = lastRead.getChapterProgress();
            tvContinueProgress.setText(progress != null ? progress : "");
            String coverUrl = lastRead.getCoverUrl();
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Glide.with(this).load(coverUrl)
                        .placeholder(R.mipmap.cover_default_new)
                        .error(R.mipmap.cover_default_new)
                        .into(ivContinueCover);
            } else {
                ivContinueCover.setImageResource(R.mipmap.cover_default_new);
            }
        } else {
            cardContinueReading.setVisibility(View.GONE);
        }
        rowShelfHeader.setVisibility(hasBooks ? View.VISIBLE : View.GONE);
        refreshSyncLabel();
    }

    /** 后台静默修复 coverUrl 为空的番茄书，每次 app 启动只跑一次 */
    private void refreshMissingCovers(List<BookList> books) {
        if (coverRefreshDone) return;
        coverRefreshDone = true;
        List<BookList> needRefresh = new java.util.ArrayList<>();
        for (BookList b : books) {
            if (b.getIsTomato() == 1
                    && b.getTomatoBookId() != null
                    && (b.getCoverUrl() == null || b.getCoverUrl().isEmpty())) {
                needRefresh.add(b);
            }
        }
        if (needRefresh.isEmpty()) return;
        final Context appCtx = getApplicationContext();
        executor.execute(() -> {
            com.thl.book.download.NovelDownloadManager mgr =
                    new com.thl.book.download.NovelDownloadManager(appCtx);
            boolean anyUpdated = false;
            for (BookList b : needRefresh) {
                String fresh = mgr.fetchFreshCoverUrl(b.getTomatoBookId(), b.getBookname());
                if (fresh != null) {
                    DB.bookList().updateDownloadResult(
                            b.getTomatoBookId(), b.getBookname(),
                            b.getBookpath() != null ? b.getBookpath() : "",
                            b.getMsg() != null ? b.getMsg() : "",
                            b.getCharset() != null ? b.getCharset() : "UTF-8",
                            fresh);
                    anyUpdated = true;
                }
            }
            if (anyUpdated) {
                List<BookList> updated = getBooks();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    bookLists.clear();
                    bookLists.addAll(updated);
                    adapter.notifyDataSetChanged();
                    updateContinueCard();
                });
            }
        });
    }

    private void showUpdateBanner(boolean show, String bookName, int current, int total) {
        if (tvUpdateStatus == null) return;
        tvUpdateStatus.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            String text;
            if (total > 0 && current > 0 && bookName != null && !bookName.isEmpty()) {
                // 截断书名避免过长
                String name = bookName.length() > 12 ? bookName.substring(0, 12) + "…" : bookName;
                text = "正在检查更新 " + current + "/" + total + "《" + name + "》";
            } else {
                text = "正在检查更新…";
            }
            android.util.Log.d("TomUpdateDbg", "showBanner → show=" + show
                    + " name='" + bookName + "' cur=" + current + "/" + total + " → '" + text + "'");
            tvUpdateStatus.setText(text);
        }
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

    // ──────────── 导出书架 ────────────

    private void exportBookshelf() {
        executor.execute(() -> {
            List<BookList> books = DB.bookList().findAll();
            String json = new Gson().toJson(books);
            String filename = "tomato_bookshelf_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                    + ".json";
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File out = new File(dir, filename);
            try (FileWriter w = new FileWriter(out)) {
                w.write(json);
                runOnUiThread(() -> Toast.makeText(this,
                        "已导出到 Downloads/" + filename, Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ──────────── 导入书架 ────────────

    private void importBookshelf(android.net.Uri uri) {
        executor.execute(() -> {
            // 读取 JSON
            String json;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                json = sb.toString();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "读取文件失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }

            // 解析
            List<BookList> imported;
            try {
                imported = new Gson().fromJson(json,
                        new TypeToken<List<BookList>>() {}.getType());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "文件格式错误", Toast.LENGTH_SHORT).show());
                return;
            }
            if (imported == null || imported.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this,
                        "备份文件中没有书籍", Toast.LENGTH_SHORT).show());
                return;
            }

            int added = 0;
            int skipped = 0;
            for (BookList book : imported) {
                if (book.getIsTomato() == 1 && book.getTomatoBookId() != null) {
                    // 番茄书：无论是否已在书架，只要没有本地文件就重新下载
                    String outputPath = new File(
                            com.thl.book.download.NovelDownloadManager.getTomatoDir(this),
                            book.getBookname().replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"
                    ).getAbsolutePath();

                    // 文件已存在则跳过（已正常下载完成）
                    if (new java.io.File(outputPath).exists()) { skipped++; continue; }

                    List<BookList> existing = DB.bookList().findByTomatoBookId(book.getTomatoBookId());
                    BookList placeholder;
                    if (!existing.isEmpty()) {
                        // 已有残留条目，复用并重置状态触发重新下载
                        placeholder = existing.get(0);
                        placeholder.setBookpath("");
                        placeholder.setMsg("下载中…");
                        DB.bookList().update(placeholder);
                    } else {
                        placeholder = new BookList();
                        placeholder.setBookname(book.getBookname());
                        placeholder.setBookpath("");
                        placeholder.setIsTomato(1);
                        placeholder.setTomatoBookId(book.getTomatoBookId());
                        placeholder.setMsg("下载中…");
                        placeholder.setCoverUrl(book.getCoverUrl());
                        DB.save(placeholder);
                    }

                    final Context appCtx = getApplicationContext();
                    final String bookId = book.getTomatoBookId();
                    final String bookName = book.getBookname();
                    final String coverUrl = book.getCoverUrl();
                    downloadExecutor.execute(() -> {
                        com.thl.book.download.NovelDownloadManager mgr =
                                new com.thl.book.download.NovelDownloadManager(appCtx);
                        mgr.downloadFull(bookId, bookName, null, coverUrl, outputPath,
                                new com.thl.book.download.NovelDownloadManager.ProgressCallback() {
                                    @Override public void onProgress(int d, int t) {}
                                    @Override public void onComplete() {
                                        NotifyHelper.send(appCtx, "下载完成",
                                                "《" + bookName + "》已添加到书架");
                                        appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                                .setPackage(appCtx.getPackageName())
                                                .putExtra("total_new", 0));
                                    }
                                    @Override public void onError(String msg) {
                                        DB.bookList().updateDownloadResult(
                                                bookId, bookName, "", "下载失败，点击重试", null, coverUrl);
                                        NotifyHelper.send(appCtx, "下载失败",
                                                "《" + bookName + "》" + msg);
                                        appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                                .setPackage(appCtx.getPackageName())
                                                .putExtra("total_new", 0));
                                    }
                                });
                    });
                    added++;

                } else if (book.getIsTomato() == 0
                        && book.getBookpath() != null
                        && new File(book.getBookpath()).exists()) {
                    // 本地书：文件存在且未导入过
                    if (DB.bookList().findByBookpath(book.getBookpath()) != null) {
                        skipped++;
                        continue;
                    }
                    book.setId(0); // 清除旧 id，让 Room 重新分配
                    DB.save(book);
                    added++;
                } else {
                    skipped++;
                }
            }

            final int finalAdded = added;
            final int finalSkipped = skipped;
            if (finalAdded > 0) WebDavConfig.markBookshelfModified(this);
            sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                    .setPackage(getPackageName())
                    .putExtra("total_new", 0));
            runOnUiThread(() -> Toast.makeText(this,
                    "导入完成：新增 " + finalAdded + " 本，跳过 " + finalSkipped + " 本",
                    Toast.LENGTH_SHORT).show());
        });
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
            sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                    .setPackage(getPackageName())
                    .putExtra("total_new", 0));
            runOnUiThread(() -> Toast.makeText(this,
                    "《" + book.getBookname() + "》重新下载中，完成后通知", Toast.LENGTH_SHORT).show());

            com.thl.book.download.NovelDownloadManager manager =
                    new com.thl.book.download.NovelDownloadManager(appCtx);
            manager.downloadFull(book.getTomatoBookId(), book.getBookname(), null, book.getCoverUrl(), outputPath,
                    new com.thl.book.download.NovelDownloadManager.ProgressCallback() {
                        @Override public void onProgress(int d, int t) {}

                        @Override
                        public void onComplete() {
                            NotifyHelper.send(appCtx, "下载完成",
                                    "《" + book.getBookname() + "》已添加到书架");
                            appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .setPackage(appCtx.getPackageName())
                                    .putExtra("total_new", 0));
                        }

                        @Override
                        public void onError(String message) {
                            DB.bookList().updateDownloadResult(
                                    book.getTomatoBookId(), book.getBookname(), "", "下载失败，点击重试", null, book.getCoverUrl());
                            NotifyHelper.send(appCtx, "下载失败",
                                    "《" + book.getBookname() + "》" + message);
                            appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .setPackage(appCtx.getPackageName())
                                    .putExtra("total_new", 0));
                        }
                    });
        });
    }

    /**
     * 将秒数拆成 [数字, 单位] 两部分，分别用大/小字体显示。
     * 例：45分 → ["45","分钟"]，2小时30分 → ["2:30","小时"]，2小时 → ["2","小时"]
     */
    private static String[] splitTimeDisplay(long totalSeconds) {
        if (totalSeconds <= 0) return new String[]{"0", "分钟"};
        long minutes = totalSeconds / 60;
        if (minutes == 0) return new String[]{"<1", "分钟"};
        long hours = minutes / 60;
        long mins  = minutes % 60;
        if (hours == 0) return new String[]{String.valueOf(minutes), "分钟"};
        if (mins == 0)  return new String[]{String.valueOf(hours), "小时"};
        return new String[]{hours + ":" + String.format("%02d", mins), "小时"};
    }
}
