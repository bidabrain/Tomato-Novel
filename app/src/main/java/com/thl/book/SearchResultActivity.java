package com.thl.book;

import android.app.ProgressDialog;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.thl.book.base.BaseActivity;
import com.thl.book.download.NovelDownloadManager;
import com.thl.book.network.FanqieApi;
import com.thl.book.network.FanqieClient;
import com.thl.book.network.dto.SearchItem;
import com.thl.reader.db.BookList;

import com.thl.reader.db.DB;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchResultActivity extends BaseActivity {

    public static final String EXTRA_QUERY = "query";

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private List<SearchItem> results = new ArrayList<>();
    private SearchAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected int initLayout() {
        return R.layout.activity_search_result;
    }

    @Override
    protected void initView() {
        String query = getIntent().getStringExtra(EXTRA_QUERY);
        TextView tvTitle = findViewById(R.id.tv_title);
        tvTitle.setText("搜索：" + query);

        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recyclerview);
        tvEmpty = findViewById(R.id.tv_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SearchAdapter();
        recyclerView.setAdapter(adapter);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        String query = getIntent().getStringExtra(EXTRA_QUERY);
        doSearch(query);
    }

    private void doSearch(String query) {
        executor.execute(() -> {
            FanqieApi api = new FanqieApi(
                    FanqieClient.getProxyUrl(this),
                    FanqieClient.getDownloaderUrl(this),
                    FanqieClient.getDownloaderPassword(this));
            List<SearchItem> items = api.search(query);
            runOnUiThread(() -> {
                results.clear();
                results.addAll(items);
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void addToShelf(SearchItem item) {
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage("正在检查书架…");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setCancelable(false);
        dialog.show();

        String outputPath = new File(
                NovelDownloadManager.getTomatoDir(this),
                item.bookName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"
        ).getAbsolutePath();

        executor.execute(() -> {
            // 重复检查也在后台线程
            List<BookList> existing = DB.bookList().findByTomatoBookId(item.bookId);
            if (!existing.isEmpty()) {
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "书架中已有《" + item.bookName + "》", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            runOnUiThread(() -> dialog.setMessage("正在下载《" + item.bookName + "》…"));

            NovelDownloadManager manager = new NovelDownloadManager(this);
            manager.downloadFull(item.bookId, item.bookName, item.author,
                    item.coverUrl, outputPath,
                    new NovelDownloadManager.ProgressCallback() {
                        @Override
                        public void onStatus(String message) {
                            runOnUiThread(() -> dialog.setMessage(message));
                        }

                        @Override
                        public void onProgress(int downloaded, int total) {
                            runOnUiThread(() -> {
                                dialog.setMax(total);
                                dialog.setProgress(downloaded);
                            });
                        }

                        @Override
                        public void onComplete() {
                            runOnUiThread(() -> {
                                dialog.dismiss();
                                Toast.makeText(SearchResultActivity.this,
                                        "《" + item.bookName + "》已添加到书架", Toast.LENGTH_SHORT).show();
                            });
                        }

                        @Override
                        public void onError(String message) {
                            runOnUiThread(() -> {
                                dialog.dismiss();
                                Toast.makeText(SearchResultActivity.this,
                                        "下载失败：" + message, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        });
    }

    // --- Adapter ---

    private class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.VH> {

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            SearchItem item = results.get(position);
            holder.tvName.setText(item.bookName);
            holder.tvAuthor.setText(item.author);
            holder.tvSummary.setText(item.summary);
            if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
                Glide.with(holder.ivCover.getContext())
                        .load(item.coverUrl)
                        .placeholder(R.mipmap.cover_default_new)
                        .into(holder.ivCover);
            } else {
                holder.ivCover.setImageResource(R.mipmap.cover_default_new);
            }
            holder.btnAdd.setOnClickListener(v -> addToShelf(item));
        }

        @Override
        public int getItemCount() { return results.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivCover;
            TextView tvName, tvAuthor, tvSummary;
            View btnAdd;

            VH(View v) {
                super(v);
                ivCover = v.findViewById(R.id.iv_cover);
                tvName = v.findViewById(R.id.tv_name);
                tvAuthor = v.findViewById(R.id.tv_author);
                tvSummary = v.findViewById(R.id.tv_summary);
                btnAdd = v.findViewById(R.id.btn_add);
            }
        }
    }
}
