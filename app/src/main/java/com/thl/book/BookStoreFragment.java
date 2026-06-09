package com.thl.book;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.thl.book.network.BookStoreApi;
import com.thl.book.network.dto.RankCategory;
import com.thl.book.ServerConfig;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookStoreFragment extends Fragment {

    private RecyclerView rvCategories;
    private View progressLoading;
    private View layoutError;
    private BookCategoryAdapter categoryAdapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_book_store, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvCategories = view.findViewById(R.id.rv_categories);
        progressLoading = view.findViewById(R.id.progress_loading);
        layoutError = view.findViewById(R.id.layout_error);

        view.findViewById(R.id.btn_retry).setOnClickListener(v -> fetchData());

        // 书城搜索栏
        EditText etStoreSearch = view.findViewById(R.id.et_store_search);
        etStoreSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = etStoreSearch.getText().toString().trim();
                if (!TextUtils.isEmpty(query)) {
                    Intent intent = new Intent(requireContext(), SearchResultActivity.class);
                    intent.putExtra(SearchResultActivity.EXTRA_QUERY, query);
                    startActivity(intent);
                }
                return true;
            }
            return false;
        });

        rvCategories.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvCategories.setHasFixedSize(true);

        if (RankDataCache.hasData()) {
            // Show cached data immediately, then silently refresh in background
            showCategories(RankDataCache.getCategories());
            refreshInBackground();
        } else {
            // First launch: show loading spinner, wait for data
            fetchData();
        }
    }

    /** First-time fetch: show loading state, then either show data or error. */
    private void fetchData() {
        showLoading();
        final String url = ServerConfig.getStoreUrl(requireContext());
        executor.execute(() -> {
            List<RankCategory> categories = BookStoreApi.fetchRankData(url);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (categories != null && !categories.isEmpty()) {
                    RankDataCache.setCategories(categories);
                    showCategories(RankDataCache.getCategories());
                } else {
                    showError();
                }
            });
        });
    }

    /**
     * Background refresh when cache already exists.
     * On success: update the displayed list silently.
     * On failure: do nothing — keep showing the cached data.
     */
    private void refreshInBackground() {
        final String url = ServerConfig.getStoreUrl(requireContext());
        executor.execute(() -> {
            List<RankCategory> categories = BookStoreApi.fetchRankData(url);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (categories != null && !categories.isEmpty()) {
                    RankDataCache.setCategories(categories);
                    if (categoryAdapter != null) {
                        categoryAdapter.setCategories(RankDataCache.getCategories());
                    }
                }
                // Network failure: silently keep the cached data already on screen
            });
        });
    }

    private void showCategories(List<RankCategory> categories) {
        if (getView() == null) return;
        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        rvCategories.setVisibility(View.VISIBLE);

        if (categoryAdapter == null) {
            categoryAdapter = new BookCategoryAdapter(
                    categories,
                    book -> BookDetailActivity.start(requireActivity(),
                            book.title, book.author, book.reads, book.intro, book.cover),
                    position -> {
                        RankDataCache.randomizeCategory(categories.get(position));
                        categoryAdapter.notifyItemChanged(position);
                    }
            );
            rvCategories.setAdapter(categoryAdapter);
        } else {
            categoryAdapter.notifyDataSetChanged();
        }
    }

    private void showLoading() {
        if (getView() == null) return;
        progressLoading.setVisibility(View.VISIBLE);
        layoutError.setVisibility(View.GONE);
        rvCategories.setVisibility(View.GONE);
    }

    private void showError() {
        if (getView() == null) return;
        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        rvCategories.setVisibility(View.GONE);
    }
}
