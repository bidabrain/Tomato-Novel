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
    // React 页面加载时自动捕获的书架数据（含 a_bogus，可直接使用）
    private volatile List<BookEntry> cachedBooks = null;

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
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return interceptNavigation(view, request.getUrl().toString());
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                // 仅拦截书架主页（去掉 X-Requested-With），其余请求全部放行
                return interceptBookshelfPage(request);
            }

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
        // 清除 ByteDance 移动端识别 Cookie，防止服务器加 force_mobile=1
        clearMobileCookies();
        webView.loadUrl(URL_SHELF);
        tvStatus.setText("正在检测登录状态…");
    }

    /**
     * 拦截书架主页请求，用 OkHttp 代发（不含 X-Requested-With 和设备指纹 Cookie），
     * 让服务器以为是真实桌面浏览器，返回桌面版 HTML（无 force_mobile）。
     */
    private android.webkit.WebResourceResponse interceptBookshelfPage(
            android.webkit.WebResourceRequest request) {
        String url = request.getUrl().toString();
        // 只拦截书架主页，不拦截 API / JS / CSS 等子资源
        if (!url.equals("https://fanqienovel.com/bookshelf") &&
            !url.equals("https://fanqienovel.com/bookshelf?force_mobile=1")) return null;
        if (request.getRequestHeaders().containsKey("Sec-Fetch-Dest") &&
            !"document".equals(request.getRequestHeaders().get("Sec-Fetch-Dest"))) return null;

        try {
            String allCookies = CookieManager.getInstance().getCookie("https://fanqienovel.com");
            // 去掉设备指纹 Cookie，只保留会话认证 Cookie
            String cleanCookies = stripMobileCookies(allCookies);
            Log.d(TAG, "interceptBookshelfPage url=" + url + " cleanCookies=" + cleanCookies.substring(0, Math.min(100, cleanCookies.length())));

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url("https://fanqienovel.com/bookshelf")  // 始终请求干净 URL
                    .header("Cookie", cleanCookies)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    // 故意不发 X-Requested-With，避免被识别为 WebView
                    .build();
            okhttp3.Response resp = client.newCall(req).execute();
            Log.d(TAG, "interceptBookshelfPage http=" + resp.code() + " finalUrl=" + resp.request().url());
            if (!resp.isSuccessful() || resp.body() == null) return null;
            byte[] rawBytes = resp.body().bytes();
            String ct = resp.header("Content-Type", "text/html; charset=utf-8");
            // 拆分 mimeType 和 charset（WebResourceResponse 需要分开传）
            String mimeType = "text/html";
            String charset  = "utf-8";
            if (ct != null && ct.contains(";")) {
                mimeType = ct.substring(0, ct.indexOf(";")).trim();
                if (ct.contains("charset="))
                    charset = ct.substring(ct.indexOf("charset=") + 8).trim();
            } else if (ct != null) {
                mimeType = ct.trim();
            }
            // 在 <head> 内最前面注入 fetch/XHR 拦截脚本，
            // 确保在 React 代码执行之前就已就位，能捕获含 a_bogus 的 API 响应
            String html = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
            String injectedScript = "<script>" + buildFetchInterceptorJs() + "</script>";
            // 优先注入到 <head>；如果没有 <head> 则注入到 <html> 或文档最前面
            if (html.contains("<head>")) {
                html = html.replace("<head>", "<head>" + injectedScript);
            } else if (html.contains("<HEAD>")) {
                html = html.replace("<HEAD>", "<HEAD>" + injectedScript);
            } else {
                html = injectedScript + html;
            }
            byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Log.d(TAG, "interceptBookshelfPage bodyLen=" + bytes.length + " mime=" + mimeType + " (injected interceptor)");
            return new android.webkit.WebResourceResponse(mimeType, charset,
                    new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.e(TAG, "interceptBookshelfPage error", e);
            return null;
        }
    }

    /**
     * 构建注入到 HTML <head> 的 fetch/XHR 拦截脚本。
     * 此脚本在 React 代码执行前已就绪，可捕获 React 发出的含 a_bogus 的 API 响应，
     * 通过 JavascriptInterface 传回 Android。
     */
    private String buildFetchInterceptorJs() {
        return
            "(function(){" +
            "if(window.__tomatoInterceptorInstalled)return;" +
            "window.__tomatoInterceptorInstalled=true;" +
            "window.__apiCaptured=false;" +
            // --- fetch 拦截 ---
            "var _f=window.fetch;" +
            "window.fetch=function(i,o){" +
            "  var u=typeof i==='string'?i:(i&&i.url?i.url:'');" +
            "  var p=_f.apply(this,arguments);" +
            "  if(u&&(u.indexOf('/api/bookshelf')!==-1||u.indexOf('bookshelf')!==-1)&&!window.__apiCaptured){" +
            "    p.then(function(r){r.clone().text().then(function(t){" +
            "      if(t&&t.indexOf('bookId')!==-1&&t.indexOf('{')===0&&!window.__apiCaptured){" +
            "        window.__apiCaptured=true;" +
            "        try{Android.onApiResponse(u+'|fetched',t);}catch(e){}" +
            "      }" +
            "    });}).catch(function(){});" +
            "  }" +
            "  return p;" +
            "};" +
            // --- XHR 拦截 ---
            "var _op=XMLHttpRequest.prototype.open,_se=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){this._tUrl=u;return _op.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.send=function(){" +
            "  var s=this,u=s._tUrl||'';" +
            "  if(u&&(u.indexOf('/api/bookshelf')!==-1||u.indexOf('bookshelf')!==-1)&&!window.__apiCaptured){" +
            "    s.addEventListener('load',function(){" +
            "      var t=s.responseText;" +
            "      if(t&&t.indexOf('bookId')!==-1&&t.indexOf('{')===0&&!window.__apiCaptured){" +
            "        window.__apiCaptured=true;" +
            "        try{Android.onApiResponse(u+'|xhr',t);}catch(e){}" +
            "      }" +
            "    });" +
            "  }" +
            "  return _se.apply(this,arguments);" +
            "};" +
            "})();";
    }

    /** 从 Cookie 字符串中去掉移动端设备指纹相关的条目 */
    private String stripMobileCookies(String cookies) {
        if (cookies == null) return "";
        String[] skipNames = {"odin_tt", "ttwid", "s_v_web_id", "x-web-secsdk-uid"};
        StringBuilder sb = new StringBuilder();
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            boolean skip = false;
            for (String name : skipNames) {
                if (trimmed.startsWith(name + "=")) { skip = true; break; }
            }
            if (!skip) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    /** 删除 ByteDance 设备指纹 Cookie，让服务器默认返回桌面版页面 */
    private void clearMobileCookies() {
        String[] mobileCookies = {
            "s_v_web_id", "x-web-secsdk-uid", "odin_tt", "ttwid"
        };
        CookieManager cm = CookieManager.getInstance();
        for (String name : mobileCookies) {
            // 设置 Max-Age=0 / Expires 过去时间来删除该 Cookie
            cm.setCookie("https://fanqienovel.com",
                name + "=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
            cm.setCookie("https://www.fanqienovel.com",
                name + "=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
        }
        cm.flush();
        Log.d(TAG, "cleared mobile cookies");
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 拦截 WebView 导航：
     * 阻止跳转到作家中心（/main/writer/author 等），重定向回书架。
     * 返回 true = 拦截，false = 正常加载。
     */
    private boolean interceptNavigation(WebView view, String url) {
        if (url == null) return false;
        // 阻止跳转到作家中心（登录页除外）
        if (url.contains("/main/writer/") && !url.contains("login")) {
            Log.d(TAG, "blocked writer redirect → back to bookshelf");
            view.loadUrl(URL_SHELF);
            return true;
        }
        return false;
    }

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

        // 打印全部 Cookie，用于诊断 force_mobile 来源
        String allCookies = CookieManager.getInstance().getCookie("https://fanqienovel.com");
        if (allCookies != null) {
            int len = allCookies.length();
            Log.d(TAG, "ALL cookies (len=" + len + "): " + allCookies);
        }
    }

    /**
     * 书架页加载完后，注入 JS 检查是否真正已登录
     * （未登录时 URL 仍是 /bookshelf，但页面显示登录引导）
     */
    private void verifyLoginState(WebView view) {
        // 用 Cookie 判断是否已登录：session_id 或 user_id 类 Cookie 存在即视为已登录
        // 比检测 DOM 更可靠，不受 React 渲染时机和 force_mobile 影响
        String cookies = CookieManager.getInstance().getCookie("https://fanqienovel.com");
        Log.d(TAG, "verifyLoginState cookies: " +
                (cookies != null ? cookies.substring(0, Math.min(200, cookies.length())) : "null"));

        if (cookies != null && (
                cookies.contains("novel_web_id=") ||
                cookies.contains("sessionid=") ||
                cookies.contains("sid_ucp_v1=") ||
                cookies.contains("uid_tt=") ||
                cookies.contains("passport_uid=") ||
                cookies.contains("login_time="))) {
            Log.d(TAG, "login check: yes (cookie)");
            loginState = LoginState.LOGGED_IN;
            loggedIn = true;
            setLoggedInUi();
        } else {
            Log.d(TAG, "login check: no (no session cookie)");
            goToLogin();
        }
    }

    // ── 提取书架 ──────────────────────────────────────────────────────────────

    private void startExtractBooks() {
        importing = true;
        btnAction.setEnabled(false);
        tvStatus.setText("正在读取书架，请稍候…");
        progressBar.setVisibility(View.VISIBLE);

        // 优先使用页面加载时自动捕获的含 a_bogus 数据
        if (cachedBooks != null && !cachedBooks.isEmpty()) {
            List<BookEntry> books = cachedBooks;
            cachedBooks = null;
            executor.execute(() -> startImport(books));
            return;
        }

        // 回退：直接从 WebView 已渲染的 DOM 中提取书籍信息
        // 书架页每本书都有 /page/{bookId} 链接，可以直接读取 bookId 和标题
        extractBooksFromDom();
    }

    private void extractBooksFromDom() {
        // 用 evaluateJavascript 回调直接拿结果，避免 JavascriptInterface 的异步问题
        // 通过 React fiber 读取书卡 props：card 往上一层是 BookCard 组件，
        // 其 memoizedProps 中有 book / bookDetail 对象，包含 bookId、bookName 等
        String js =
            "(function(){" +
            "try{" +
            "  var cards=document.querySelectorAll('.book-card');" +
            "  var results=[];" +
            "  var seen={};" +
            "  for(var i=0;i<cards.length;i++){" +
            "    var el=cards[i];" +
            "    var fk=null;" +
            "    for(var k in el){if(k.indexOf('__react')===0){fk=k;break;}}" +
            "    if(!fk)continue;" +
            // L0 = div.book-card，L1 = BookCard 组件（含 book prop）
            "    var fiberL1=el[fk].return;" +
            "    if(!fiberL1)continue;" +
            "    var mp=fiberL1.memoizedProps||{};" +
            // book 是书籍数据对象，bookDetail 是补充信息
            "    var bk=mp.book||{};" +
            "    var bd=mp.bookDetail||{};" +
            "    var bookId=String(bk.bookId||bk.book_id||bd.bookId||bd.book_id||'');" +
            "    if(!bookId||bookId==='undefined'||seen[bookId])continue;" +
            "    seen[bookId]=true;" +
            "    var title=bk.bookName||bk.book_name||bd.bookName||bd.book_name||'';" +
            "    var author=bk.author||bk.authorName||bd.author||bd.authorName||'';" +
            "    var cover=bk.coverUrl||bk.thumb_url||bd.coverUrl||bd.thumb_url||'';" +
            // DOM 兜底：从 .book-title 读标题，从 img 读封面
            "    if(!title){var td=el.querySelector('.book-title');if(td)title=td.textContent.trim();}" +
            "    if(!cover){var img=el.querySelector('img');if(img){cover=img.src||'';if(!title)title=img.alt||'';}}" +
            "    results.push({bookId:bookId,title:title,coverUrl:cover,author:author});" +
            "  }" +
            "  var diag='cards='+cards.length+' found='+results.length;" +
            "  return JSON.stringify({ok:true,diag:diag,books:results});" +
            "}catch(e){" +
            "  return JSON.stringify({ok:false,err:e.message});" +
            "}" +
            "})();";


        webView.evaluateJavascript(js, result -> {
            if (result == null || result.equals("null")) {
                runOnUiThread(() -> {
                    importing = false;
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("JS 返回空，请重试");
                    btnAction.setEnabled(true);
                });
                return;
            }
            // evaluateJavascript 返回的字符串外面有一层 JSON 引号转义，需要先 unescape
            String json = result;
            if (json.startsWith("\"")) {
                json = json.substring(1, json.length() - 1)
                           .replace("\\\"", "\"")
                           .replace("\\\\", "\\");
            }
            try {
                com.google.gson.JsonObject obj = new Gson().fromJson(json, com.google.gson.JsonObject.class);
                String diag = obj.has("diag") ? obj.get("diag").getAsString() : "";
                Log.d(TAG, "DOM extracted: " + diag);
                if (obj.has("err")) {
                    Log.e(TAG, "DOM JS error: " + obj.get("err").getAsString());
                    runOnUiThread(() -> {
                        importing = false;
                        progressBar.setVisibility(View.GONE);
                        tvStatus.setText("JS错误，请重试");
                        btnAction.setEnabled(true);
                    });
                    return;
                }
                com.google.gson.JsonArray arr = obj.getAsJsonArray("books");
                List<BookEntry> books = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    com.google.gson.JsonObject b = arr.get(i).getAsJsonObject();
                    books.add(new BookEntry(
                        b.get("bookId").getAsString(),
                        b.get("title").getAsString(),
                        b.has("coverUrl") ? b.get("coverUrl").getAsString() : "",
                        ""
                    ));
                }
                Log.d(TAG, "DOM extracted " + books.size() + " books");
                executor.execute(() -> startImport(books));
            } catch (Exception e) {
                Log.e(TAG, "DOM parse error: " + e.getMessage() + " raw=" + result);
                runOnUiThread(() -> {
                    importing = false;
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("解析失败，请重试");
                    btnAction.setEnabled(true);
                });
            }
        });
    }

    private void fetchBookshelfDirect(String cookies) {
        String[] endpoints = {
            "https://fanqienovel.com/api/bookshelf/multidetail?needMeta=1&needUpdated=1&limit=200&offset=0",
            "https://fanqienovel.com/api/bookshelf/multidetail?limit=200&offset=0",
            "https://fanqienovel.com/api/bookshelf/all_items?limit=200&offset=0",
        };
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .followRedirects(false)  // 不跟随重定向，直接看状态码
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        for (String url : endpoints) {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(url)
                        .header("Cookie", cookies != null ? cookies : "")
                        .header("User-Agent", ua)
                        .header("Referer", "https://fanqienovel.com/bookshelf")
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Origin", "https://fanqienovel.com")
                        .header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\"")
                        .header("sec-ch-ua-mobile", "?0")
                        .header("sec-ch-ua-platform", "\"Windows\"")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                        .build();
                okhttp3.Response resp = client.newCall(req).execute();
                String body = resp.body() != null ? resp.body().string() : "";
                Log.d(TAG, "direct API " + url.substring(url.lastIndexOf('/')) +
                        " http=" + resp.code() +
                        " location=" + resp.header("Location", "-") +
                        " bodyLen=" + body.length() +
                        " preview=" + body.substring(0, Math.min(200, body.length())));
                if (resp.code() == 200 && body.contains("bookId")) {
                    List<BookEntry> books = parseBookshelfApi(body);
                    if (!books.isEmpty()) { startImport(books); return; }
                }
            } catch (Exception e) {
                Log.e(TAG, "direct API error", e);
            }
        }
        // OkHttp 直连失败 → 回退：注入 JS 在页面内触发
        Log.d(TAG, "OkHttp failed, falling back to JS inject");
        runOnUiThread(() -> webView.evaluateJavascript(
            "(function(){" +
            "  var urls=['/api/bookshelf/multidetail?needMeta=1&needUpdated=1&limit=200&offset=0'," +
            "             '/api/bookshelf/multidetail?limit=200&offset=0'];" +
            "  var i=0;function next(){" +
            "    if(i>=urls.length){Android.onError('all failed');return;}" +
            "    var u=urls[i++];" +
            "    fetch(u,{credentials:'include',headers:{'Accept':'application/json'}})" +
            "      .then(function(r){return r.text();})" +
            "      .then(function(t){" +
            "        Android.onApiResponse(u+'|code='+200,t);" +
            "      }).catch(function(e){next();});" +
            "  }next();" +
            "})();", null));
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
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return interceptNavigation(view, request.getUrl().toString());
            }

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
                Log.d(TAG, "Found " + books.size() + " books via interceptor, caching");
                cachedBooks = books;
                // 若用户已点击"提取书架"（importing=true），直接进入选择界面
                if (importing) {
                    startImport(books);
                } else {
                    // 否则仅缓存，等用户点击按钮时使用
                    runOnUiThread(() -> tvStatus.setText("书架就绪，点击【提取书架】导入"));
                }
            }
        }


        @JavascriptInterface
        public void onBooksExtracted(String json) {
            try {
                JsonArray arr = new Gson().fromJson(json, JsonArray.class);
                List<BookEntry> books = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    String bookId   = obj.has("bookId")   ? obj.get("bookId").getAsString()   : "";
                    String title    = obj.has("title")    ? obj.get("title").getAsString()    : "";
                    String coverUrl = obj.has("coverUrl") ? obj.get("coverUrl").getAsString() : "";
                    String author   = obj.has("author")   ? obj.get("author").getAsString()   : "";
                    if (!bookId.isEmpty() && !title.isEmpty())
                        books.add(new BookEntry(bookId, title, coverUrl, author));
                }
                Log.d(TAG, "DOM extracted " + books.size() + " books");
                if (books.isEmpty()) {
                    runOnUiThread(() -> {
                        importing = false;
                        progressBar.setVisibility(View.GONE);
                        tvStatus.setText("未找到书籍，请确认书架页面已完全加载");
                        btnAction.setEnabled(true);
                    });
                    return;
                }
                startImport(books);
            } catch (Exception e) {
                Log.e(TAG, "DOM parse error", e);
                runOnUiThread(() -> {
                    importing = false;
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("解析失败，请重试");
                    btnAction.setEnabled(true);
                });
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
