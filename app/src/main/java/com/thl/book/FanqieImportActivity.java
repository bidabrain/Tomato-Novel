package com.thl.book;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.thl.book.base.BaseActivity;
import com.thl.book.download.NovelDownloadManager;
import com.thl.reader.db.BookList;
import com.thl.reader.db.DB;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FanqieImportActivity extends BaseActivity {

    private static final String TAG = "FanqieImport";
    private static final String URL_LOGIN  = "https://fanqienovel.com/main/writer/login";
    private static final String URL_SHELF  = "https://fanqienovel.com/bookshelf";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvTitle;
    private TextView tvStatus;
    private TextView btnAction;

    // 书籍选择界面
    private View layoutSelect;
    private View layoutImporting;
    private TextView tvSelectCount;
    private TextView btnToggleAll;
    private RecyclerView rvBooks;
    private TextView tvImportProgress;
    private TextView tvImportBook;
    private BookSelectAdapter selectAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // LOGIN_UNKNOWN: 初始状态；ON_LOGIN: 当前在登录页；LOGGED_IN: 已确认登录
    private enum LoginState { UNKNOWN, ON_LOGIN, LOGGED_IN }
    private LoginState loginState = LoginState.UNKNOWN;
    private boolean importing = false;
    private boolean loggedIn = false;

    @Override
    protected int initLayout() {
        return R.layout.activity_fanqie_import;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void initView() {
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        tvTitle    = findViewById(R.id.tv_title);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus   = findViewById(R.id.tv_status);
        btnAction  = findViewById(R.id.btn_action);

        btnAction.setOnClickListener(v -> {
            if (!loggedIn) return;
            if (layoutSelect.getVisibility() == View.VISIBLE) {
                // 选择界面已显示 → 确认导入
                confirmImport();
            } else {
                startExtractBooks();
            }
        });

        layoutSelect   = findViewById(R.id.layout_select);
        layoutImporting = findViewById(R.id.layout_importing);
        tvSelectCount  = findViewById(R.id.tv_select_count);
        btnToggleAll   = findViewById(R.id.btn_toggle_all);
        tvImportProgress = findViewById(R.id.tv_import_progress);
        tvImportBook   = findViewById(R.id.tv_import_book);
        rvBooks = findViewById(R.id.rv_books);
        rvBooks.setLayoutManager(new LinearLayoutManager(this));

        btnToggleAll.setOnClickListener(v -> {
            if (selectAdapter == null) return;
            boolean allSelected = selectAdapter.isAllSelected();
            selectAdapter.setAllSelected(!allSelected);
            updateSelectUi();
        });

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // 桌面 UA，触发 PC 版页面（含二维码登录）
        s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsBridge(), "Android");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (!importing) {
                    progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null) return;
                Log.d(TAG, "pageFinished: " + url + "  state=" + loginState);

                // 注入缩放，让桌面页面适配手机屏幕宽度（可捏合缩放）
                injectViewportScale(view);

                if (url.contains("login")) {
                    // 进入登录页
                    loginState = LoginState.ON_LOGIN;
                    setNotLoggedInUi();

                } else if (loginState == LoginState.ON_LOGIN) {
                    // 曾在登录页，URL 现在离开了 → 说明登录成功
                    Log.d(TAG, "login succeeded, navigating to bookshelf");
                    loginState = LoginState.LOGGED_IN;
                    loggedIn = true;
                    if (!url.contains("/bookshelf")) {
                        // 跳转到书架（replace 当前历史条目，避免返回键回登录页）
                        webView.loadUrl(URL_SHELF);
                    } else {
                        setLoggedInUi();
                    }

                } else if (url.contains("/bookshelf") && loginState == LoginState.LOGGED_IN) {
                    // 登录后加载到书架
                    setLoggedInUi();

                } else if (url.contains("/bookshelf") && loginState == LoginState.UNKNOWN) {
                    // 初次冷启动书架：用 JS 检测是否真的已登录
                    verifyLoginState(view);
                }
            }
        });
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        webView.loadUrl(URL_SHELF);
        tvStatus.setText("正在检测登录状态…");
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private void injectViewportScale(WebView view) {
        view.evaluateJavascript(
            "(function(){" +
            "  var w=1280,s=(screen.width/devicePixelRatio)/w;" +
            "  var c='width='+w+',initial-scale='+s+',user-scalable=yes';" +
            "  var m=document.querySelector('meta[name=viewport]');" +
            "  if(m) m.content=c;" +
            "  else{m=document.createElement('meta');m.name='viewport';" +
            "    m.content=c;document.head&&document.head.appendChild(m);}" +
            "})();", null);
    }

    // ── 登录状态 ──────────────────────────────────────────────────────────────

    private void goToLogin() {
        webView.loadUrl(URL_LOGIN);
        setNotLoggedInUi();
    }

    private void setNotLoggedInUi() {
        loggedIn = false;
        tvTitle.setText("番茄账号登录");
        tvStatus.setText("请扫码或输入账号密码登录");
        btnAction.setVisibility(View.GONE);
    }

    private void setLoggedInUi() {
        loggedIn = true;
        tvTitle.setText("导入番茄书架");
        tvStatus.setText("已登录，可以开始导入书架");
        btnAction.setVisibility(View.VISIBLE);
        btnAction.setText("提取书架");
        btnAction.setEnabled(true);
    }

    /**
     * 书架页加载完后，注入 JS 检查是否真正已登录
     * （未登录时 URL 仍是 /bookshelf，但页面显示登录引导）
     */
    private void verifyLoginState(WebView view) {
        handler.postDelayed(() -> {
            if (isDestroyed() || isFinishing()) return;
            view.evaluateJavascript(
                "(function(){" +
                "  var body=document.body?document.body.innerText:'';" +
                "  var hints=['请先登录','立即登录','登录后查看','去登录','登录/注册','扫码登录'];" +
                "  for(var i=0;i<hints.length;i++){" +
                "    if(body.indexOf(hints[i])!==-1) return 'no';" +
                "  }" +
                "  var els=document.querySelectorAll('a,button');" +
                "  for(var j=0;j<els.length;j++){" +
                "    var t=els[j].innerText.trim();" +
                "    if(t==='登录'||t==='立即登录'||t==='去登录') return 'no';" +
                "  }" +
                "  try{" +
                "    var nd=window.__NEXT_DATA__,s=nd?JSON.stringify(nd):'';" +
                "    if(s.indexOf('\"isLogin\":false')!==-1) return 'no';" +
                "    if(s.indexOf('\"isLogin\":true')!==-1||" +
                "       s.indexOf('\"userId\":')!==-1||" +
                "       s.indexOf('\"userInfo\":')!==-1) return 'yes';" +
                "  }catch(e){}" +
                "  return 'unknown';" +
                "})();",
                raw -> {
                    String r = raw != null ? raw.replace("\"","").trim() : "unknown";
                    Log.d(TAG, "login check: " + r);
                    if ("yes".equals(r)) {
                        loginState = LoginState.LOGGED_IN;
                        loggedIn = true;
                        setLoggedInUi();
                    } else if ("no".equals(r)) {
                        goToLogin();
                    } else {
                        // unknown：React 未渲染（force_mobile 等情况）
                        // 用 body 长度辅助判断：< 8000 字节说明空壳，视为未登录
                        view.evaluateJavascript(
                            "document.body?document.body.innerHTML.length:0;",
                            lenRaw -> {
                                int len = 0;
                                try { len = Integer.parseInt(
                                        lenRaw != null ? lenRaw.trim() : "0"); }
                                catch (NumberFormatException ignored) {}
                                Log.d(TAG, "body len=" + len);
                                if (len > 8000) {
                                    loginState = LoginState.LOGGED_IN;
                                    loggedIn = true;
                                    setLoggedInUi();
                                } else {
                                    goToLogin();
                                }
                            });
                    }
                });
        }, 1500);
    }

    // ── 提取书架 ──────────────────────────────────────────────────────────────

    private void startExtractBooks() {
        importing = true;
        btnAction.setEnabled(false);
        tvStatus.setText("正在读取书架，请稍候…");
        progressBar.setVisibility(View.VISIBLE);

        // 安装拦截 WebViewClient，然后重新加载书架页
        // 由 JS fetch/XHR 拦截器捕获书架 API 响应，通过 JsBridge 传回 Android
        setupInterceptingClient();
        webView.loadUrl(URL_SHELF);
    }

    /**
     * 设置拦截 WebViewClient：页面加载完成后注入 JS fetch/XHR 拦截器，
     * 等待页面的 React 代码发起 /api/bookshelf 请求，
     * 将响应体通过 JavascriptInterface 传回 Android 解析。
     * 优点：无需 OkHttp 复制请求，不受 a_bogus/msToken 安全校验影响。
     */
    private void setupInterceptingClient() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null) return;
                Log.d(TAG, "interceptingClient pageFinished: " + url);

                if (url.contains("login")) {
                    runOnUiThread(() -> {
                        importing = false;
                        progressBar.setVisibility(View.GONE);
                        tvStatus.setText("会话已过期，请重新登录");
                        btnAction.setEnabled(true);
                    });
                    return;
                }
                injectViewportScale(view);
                injectApiInterceptor(view);
            }
        });
    }

    /**
     * 向 WebView 注入 JS fetch/XHR 拦截器。
     * 当页面内的 fetch/XHR 命中书架相关 URL 时，
     * 将响应文本通过 Android.onApiResponse() 传回。
     */
    private void injectApiInterceptor(WebView view) {
        // 重置捕获标志（应对重复注入）
        view.evaluateJavascript("window.__apiCaptured=false;", null);

        String js =
            "(function(){" +
            // --- fetch 拦截 ---
            "var _f=window.fetch;" +
            "window.fetch=function(i,o){" +
            "  var u=typeof i==='string'?i:(i&&i.url?i.url:'');" +
            "  var p=_f.call(this,i,o);" +
            "  if(u&&(u.indexOf('/api/bookshelf')!==-1||u.indexOf('/shelf')!==-1)){" +
            "    p.then(function(r){r.clone().text().then(function(t){" +
            "      if(t&&t.length>50&&!window.__apiCaptured){" +
            "        window.__apiCaptured=true;" +
            "        try{Android.onApiResponse(u,t);}catch(e){console.error(e);}" +
            "      }" +
            "    });}).catch(function(){});" +
            "  }" +
            "  return p;" +
            "};" +
            // --- XHR 拦截（备用）---
            "var _op=XMLHttpRequest.prototype.open;" +
            "var _se=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){" +
            "  this._apiUrl=u;return _op.apply(this,arguments);" +
            "};" +
            "XMLHttpRequest.prototype.send=function(){" +
            "  var s=this,u=s._apiUrl||'';" +
            "  if(u&&(u.indexOf('/api/bookshelf')!==-1||u.indexOf('/shelf')!==-1)){" +
            "    s.addEventListener('load',function(){" +
            "      if(s.responseText&&s.responseText.length>50&&!window.__apiCaptured){" +
            "        window.__apiCaptured=true;" +
            "        try{Android.onApiResponse(u,s.responseText);}catch(e){console.error(e);}" +
            "      }" +
            "    });" +
            "  }" +
            "  return _se.apply(this,arguments);" +
            "};" +
            "})();";

        view.evaluateJavascript(js, result ->
            Log.d(TAG, "apiInterceptor injected, result=" + result));
    }

    /** 尝试解析各种可能的书架 API 响应格式 */
    private List<BookEntry> parseBookshelfApi(String json) {
        List<BookEntry> books = new ArrayList<>();
        try {
            com.google.gson.JsonElement root = new Gson().fromJson(json, com.google.gson.JsonElement.class);
            if (!root.isJsonObject()) return books;
            JsonObject obj = root.getAsJsonObject();
            Log.d(TAG, "API keys: " + obj.keySet());

            // 递归寻找包含 bookId+bookName 的数组
            findBooks(obj, books, 0);
        } catch (Exception e) {
            Log.e(TAG, "parseBookshelfApi error", e);
        }
        return books;
    }

    private void findBooks(com.google.gson.JsonElement el, List<BookEntry> out, int depth) {
        if (depth > 5 || out.size() > 0) return;
        if (el.isJsonArray()) {
            com.google.gson.JsonArray arr = el.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonElement item = arr.get(i);
                if (item.isJsonObject()) {
                    JsonObject o = item.getAsJsonObject();
                    String id    = jsonStr(o, "bookId", "book_id", "id");
                    String title = jsonStr(o, "bookName", "book_name", "title", "name");
                    if (id != null && title != null && !id.isEmpty() && !title.isEmpty()) {
                        String cover  = jsonStr(o, "thumb_url", "coverUrl", "cover_url");
                        String author = jsonStr(o, "author", "author_name");
                        out.add(new BookEntry(id, title, cover, author));
                    } else {
                        findBooks(item, out, depth + 1);
                    }
                }
            }
        } else if (el.isJsonObject()) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                    : el.getAsJsonObject().entrySet()) {
                findBooks(entry.getValue(), out, depth + 1);
            }
        }
    }

    private String jsonStr(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                String v = o.get(k).getAsString();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** 提取完成 → 展示选择界面（必须在后台线程调用，此方法内含 DB 查询） */
    private void startImport(List<BookEntry> books) {
        if (books.isEmpty()) {
            runOnUiThread(() -> {
                importing = false;
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("未发现书架书籍，请确认已登录且书架有内容");
                btnAction.setEnabled(true);
            });
            return;
        }

        // DB 查询在后台线程执行（调用者保证此方法在非主线程调用）
        List<String> onShelf = new ArrayList<>();
        for (BookEntry b : books) {
            List<BookList> existing = DB.bookList().findByTomatoBookId(b.bookId);
            if (!existing.isEmpty() && existing.get(0).getBookpath() != null
                    && !existing.get(0).getBookpath().isEmpty()) {
                onShelf.add(b.bookId);
            }
        }

        final List<String> onShelfFinal = onShelf;
        runOnUiThread(() -> {
            importing = false;
            progressBar.setVisibility(View.GONE);

            selectAdapter = new BookSelectAdapter(books, onShelfFinal);
            rvBooks.setAdapter(selectAdapter);
            layoutSelect.setVisibility(View.VISIBLE);
            updateSelectUi();

            tvStatus.setText("选择要导入的书籍");
            btnAction.setText("导入选中");
            btnAction.setEnabled(true);
            btnAction.setVisibility(View.VISIBLE);
        });
    }

    private void updateSelectUi() {
        if (selectAdapter == null) return;
        int selected = selectAdapter.getSelectedCount();
        int total    = selectAdapter.getItemCount();
        tvSelectCount.setText("共 " + total + " 本，已选 " + selected + " 本");
        btnToggleAll.setText(selectAdapter.isAllSelected() ? "取消全选" : "全选");
        btnAction.setText("导入选中（" + selected + "）");
        btnAction.setEnabled(selected > 0);
    }

    /** 确认导入选中的书籍 */
    private void confirmImport() {
        List<BookEntry> selected = selectAdapter.getSelectedBooks();
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一本书", Toast.LENGTH_SHORT).show();
            return;
        }

        layoutSelect.setVisibility(View.GONE);
        layoutImporting.setVisibility(View.VISIBLE);
        btnAction.setVisibility(View.GONE);
        tvStatus.setText("正在导入 " + selected.size() + " 本…");
        importing = true;

        executor.execute(() -> {
            int added = 0, skipped = 0;
            for (BookEntry entry : selected) {
                try {
                    List<BookList> existing = DB.bookList().findByTomatoBookId(entry.bookId);
                    if (!existing.isEmpty() && existing.get(0).getBookpath() != null
                            && !existing.get(0).getBookpath().isEmpty()) {
                        skipped++; continue;
                    }
                    // 创建占位记录并在后台开始下载，不阻塞等待完成
                    addBookToShelf(entry.bookId, entry.title, entry.author, entry.coverUrl);
                    added++;
                } catch (Exception e) {
                    Log.e(TAG, "import error: " + entry.title, e);
                }
            }
            final String msg = "已加入书架 " + added + " 本"
                    + (skipped > 0 ? "，已有 " + skipped + " 本" : "")
                    + "，下载在后台进行";
            runOnUiThread(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                finish(); // 立刻返回书架
            });
        });
    }

    private void addBookToShelf(String bookId, String bookName, String author, String coverUrl) {
        String outputPath = new File(
                NovelDownloadManager.getTomatoDir(this),
                bookName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"
        ).getAbsolutePath();
        android.content.Context appCtx = getApplicationContext();

        List<BookList> existing = DB.bookList().findByTomatoBookId(bookId);
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
            DB.bookList().updateDownloadResult(
                    bookId, bookName, "", "下载中…", null, coverUrl);
        }
        appCtx.sendBroadcast(new android.content.Intent(UpdateChecker.ACTION_UPDATE_DONE)
                .putExtra("total_new", 0));

        // 后台线程下载，不阻塞导入流程
        NovelDownloadManager manager = new NovelDownloadManager(appCtx);
        new Thread(() -> manager.downloadFull(bookId, bookName, author, coverUrl, outputPath,
                new NovelDownloadManager.ProgressCallback() {
                    @Override public void onProgress(int d, int t) {}
                    @Override public void onComplete() {
                        NotifyHelper.send(appCtx, "下载完成",
                                "《" + bookName + "》已添加到书架");
                        appCtx.sendBroadcast(new android.content.Intent(
                                UpdateChecker.ACTION_UPDATE_DONE).putExtra("total_new", 0));
                    }
                    @Override public void onError(String message) {
                        DB.bookList().updateDownloadResult(
                                bookId, bookName, "", "下载失败，点击重试",
                                null, coverUrl);
                        appCtx.sendBroadcast(new android.content.Intent(
                                UpdateChecker.ACTION_UPDATE_DONE).putExtra("total_new", 0));
                    }
                })).start();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        webView.destroy();
        super.onDestroy();
    }

    // ── JavaScript bridge ─────────────────────────────────────────────────────

    private class JsBridge {

        /** fetch/XHR 拦截器捕获到 API 响应 */
        @JavascriptInterface
        public void onApiResponse(String url, String body) {
            Log.d(TAG, "onApiResponse url=" + url + " bodyLen=" + body.length()
                    + " preview=" + body.substring(0, Math.min(200, body.length())));
            List<BookEntry> books = parseBookshelfApi(body);
            if (!books.isEmpty()) {
                Log.d(TAG, "Found " + books.size() + " books via probe: " + url);
                startImport(books);
            }
        }


        @JavascriptInterface
        public void onBooksExtracted(String json) {
            try {
                JsonArray arr = new Gson().fromJson(json, JsonArray.class);
                List<BookEntry> books = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    String bookId = obj.has("bookId") ? obj.get("bookId").getAsString() : "";
                    String title  = obj.has("title")  ? obj.get("title").getAsString()  : "";
                    if (!bookId.isEmpty() && !title.isEmpty())
                        books.add(new BookEntry(bookId, title, "", ""));
                }
                Log.d(TAG, "extracted " + books.size() + " books");
                startImport(books);
            } catch (Exception e) {
                Log.e(TAG, "parse error", e);
                runOnUiThread(() -> tvStatus.setText("解析书架数据失败，请重试"));
            }
        }

        @JavascriptInterface
        public void onError(String msg) {
            Log.e(TAG, "JS error: " + msg);
            runOnUiThread(() -> tvStatus.setText("提取失败：" + msg));
        }
    }

    private static class BookEntry {
        final String bookId;
        final String title;
        final String coverUrl;
        final String author;
        BookEntry(String bookId, String title, String coverUrl, String author) {
            this.bookId   = bookId;
            this.title    = title;
            this.coverUrl = coverUrl != null ? coverUrl : "";
            this.author   = author  != null ? author   : "";
        }
    }

    // ── 书籍选择 Adapter ──────────────────────────────────────────────────────

    private class BookSelectAdapter extends RecyclerView.Adapter<BookSelectAdapter.VH> {

        private final List<BookEntry> books;
        private final boolean[] checked;
        private final boolean[] alreadyOnShelf;

        BookSelectAdapter(List<BookEntry> books, List<String> onShelfIds) {
            this.books = books;
            this.checked = new boolean[books.size()];
            this.alreadyOnShelf = new boolean[books.size()];
            for (int i = 0; i < books.size(); i++) {
                alreadyOnShelf[i] = onShelfIds.contains(books.get(i).bookId);
                // 默认全选（已有的也选中，导入时会跳过）
                checked[i] = true;
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_book_select, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            BookEntry entry = books.get(position);
            holder.tvTitle.setText(entry.title);
            holder.checkbox.setChecked(checked[position]);
            if (alreadyOnShelf[position]) {
                holder.tvHint.setText("已有");
                holder.tvHint.setVisibility(View.VISIBLE);
            } else {
                holder.tvHint.setVisibility(View.GONE);
            }
            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_ID) return;
                checked[pos] = !checked[pos];
                notifyItemChanged(pos);
                updateSelectUi();
            });
        }

        @Override
        public int getItemCount() { return books.size(); }

        int getSelectedCount() {
            int n = 0;
            for (boolean c : checked) if (c) n++;
            return n;
        }

        boolean isAllSelected() {
            for (boolean c : checked) if (!c) return false;
            return true;
        }

        void setAllSelected(boolean select) {
            for (int i = 0; i < checked.length; i++) checked[i] = select;
            notifyDataSetChanged();
        }

        List<BookEntry> getSelectedBooks() {
            List<BookEntry> result = new ArrayList<>();
            for (int i = 0; i < books.size(); i++)
                if (checked[i]) result.add(books.get(i));
            return result;
        }

        class VH extends RecyclerView.ViewHolder {
            CheckBox checkbox;
            TextView tvTitle, tvHint;
            VH(View v) {
                super(v);
                checkbox = v.findViewById(R.id.checkbox);
                tvTitle  = v.findViewById(R.id.tv_title);
                tvHint   = v.findViewById(R.id.tv_hint);
            }
        }
    }
}
