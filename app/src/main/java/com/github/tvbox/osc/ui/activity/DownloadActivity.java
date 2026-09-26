package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.download.DownloadService;
import com.github.tvbox.osc.download.DownloadStore;
import com.github.tvbox.osc.util.ImgUtil;
import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public class DownloadActivity extends BaseActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TaskAdapter adapter = new TaskAdapter();
    private DownloadStore store;
    private TextView summary, empty;
    private View emptyPanel;
    private View selectionBar, addButton;
    private TextView manageButton, selectedCount, selectAllButton, deleteSelectedButton;
    private TextView[] tabs;
    private int filter;
    private int taskCount;
    private boolean selectionMode;
    private final Set<String> selected = new HashSet<>();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        if (state != null) {
            filter = state.getInt("download_filter", 0);
            selectionMode = state.getBoolean("download_selection_mode", false);
            ArrayList<String> ids = state.getStringArrayList("download_selected_ids");
            if (ids != null) selected.addAll(ids);
        }
        super.onCreate(state);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("download_filter", filter);
        state.putBoolean("download_selection_mode", selectionMode);
        state.putStringArrayList("download_selected_ids", new ArrayList<>(selected));
        super.onSaveInstanceState(state);
    }

    @Override protected int getLayoutResID() { return R.layout.activity_download; }

    @Override protected boolean shouldRefreshAutoSize() { return true; }

    @Override protected void init() {
        store = DownloadStore.get(this);
        summary = findViewById(R.id.download_summary);
        empty = findViewById(R.id.download_empty);
        emptyPanel = findViewById(R.id.download_empty_panel);
        selectionBar = findViewById(R.id.download_selection_bar);
        addButton = findViewById(R.id.download_add);
        manageButton = findViewById(R.id.download_manage);
        selectedCount = findViewById(R.id.download_selected_count);
        selectAllButton = findViewById(R.id.download_select_all);
        deleteSelectedButton = findViewById(R.id.download_delete_selected);
        tabs = new TextView[]{findViewById(R.id.download_tab_all), findViewById(R.id.download_tab_active),
                findViewById(R.id.download_tab_paused), findViewById(R.id.download_tab_done),
                findViewById(R.id.download_tab_failed)};
        for (int i = 0; i < tabs.length; i++) {
            final int position = i;
            tabs[i].setOnClickListener(v -> { filter = position; render(); });
        }
        RecyclerView list = findViewById(R.id.download_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        findViewById(R.id.download_back).setOnClickListener(v -> finish());
        addButton.setOnClickListener(v -> addLink());
        manageButton.setOnClickListener(v -> {
            selectionMode = !selectionMode;
            if (!selectionMode) selected.clear();
            render();
            adapter.notifyDataSetChanged();
        });
        selectAllButton.setOnClickListener(v -> {
            boolean all = !adapter.tasks.isEmpty();
            for (DownloadStore.Task task : adapter.tasks) if (!selected.contains(task.id)) all = false;
            for (DownloadStore.Task task : adapter.tasks) {
                if (all) selected.remove(task.id);
                else selected.add(task.id);
            }
            renderSelection();
            adapter.notifyDataSetChanged();
        });
        deleteSelectedButton.setOnClickListener(v -> deleteSelected());
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        List<DownloadStore.Task> tasks = store.list();
        taskCount = tasks.size();
        Set<String> existing = new HashSet<>();
        for (DownloadStore.Task task : tasks) existing.add(task.id);
        selected.retainAll(existing);
        int active = 0, done = 0;
        for (DownloadStore.Task task : tasks) {
            if (DownloadStore.RUNNING.equals(task.state) || DownloadStore.QUEUED.equals(task.state)) active++;
            if (DownloadStore.DONE.equals(task.state)) done++;
        }
        int failed = 0, paused = 0;
        List<DownloadStore.Task> visible = new ArrayList<>();
        for (DownloadStore.Task task : tasks) {
            boolean isDone = DownloadStore.DONE.equals(task.state);
            boolean isFailed = DownloadStore.FAILED.equals(task.state);
            boolean isPaused = DownloadStore.PAUSED.equals(task.state);
            if (isFailed) failed++;
            if (isPaused) paused++;
            if (filter == 0 || filter == 1 && (DownloadStore.RUNNING.equals(task.state)
                    || DownloadStore.QUEUED.equals(task.state)) || filter == 2 && isPaused
                    || filter == 3 && isDone || filter == 4 && isFailed) visible.add(task);
        }
        summary.setText(tasks.size() + " 个任务  ·  " + active + " 个待处理  ·  " + done + " 个已完成");
        String[] labels = {"全部 " + tasks.size(), "进行中 " + active, "已暂停 " + paused,
                "已完成 " + done, "失败 " + failed};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setText(labels[i]);
            tabs[i].setSelected(filter == i);
        }
        empty.setText(filter == 0 ? "暂无下载任务" : filter == 1 ? "暂无进行中的任务"
                : filter == 2 ? "暂无已暂停任务" : filter == 3 ? "暂无已完成的视频" : "暂无失败任务");
        emptyPanel.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.submit(visible);
        renderSelection();
    }

    private void renderSelection() {
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        addButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        manageButton.setVisibility(taskCount == 0 && !selectionMode ? View.GONE : View.VISIBLE);
        manageButton.setText(selectionMode ? "完成" : "管理");
        selectedCount.setText("已选 " + selected.size() + " 项");
        deleteSelectedButton.setEnabled(!selected.isEmpty());
        deleteSelectedButton.setAlpha(selected.isEmpty() ? .45f : 1f);
        boolean all = !adapter.tasks.isEmpty();
        for (DownloadStore.Task task : adapter.tasks) if (!selected.contains(task.id)) all = false;
        selectAllButton.setText(all ? "取消全选" : "全选当前");
    }

    private void toggleSelection(String id, int position) {
        if (position == RecyclerView.NO_POSITION) return;
        if (!selected.add(id)) selected.remove(id);
        renderSelection();
        adapter.notifyItemChanged(position);
    }

    private void change(String id, String previous, String state) {
        DownloadStore.Task task = store.getTask(id);
        if (task == null) return;
        if (!previous.equals(task.state)) { render(); return; }
        task.state = state;
        task.error = "";
        if (DownloadStore.QUEUED.equals(state) && task.episodeUrl != null && !task.episodeUrl.isEmpty()) {
            task.url = "";
            task.headers = new org.json.JSONObject();
        }
        store.update(task);
        if (DownloadStore.QUEUED.equals(state)) DownloadService.wake(this);
        render();
    }

    private void play(DownloadStore.Task task) {
        if (task.path == null || !new File(task.path).isFile()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, DownloadPlayerActivity.class)
                .putExtra("taskId", task.id)
                .putExtra("path", task.path)
                .putExtra("title", task.title));
    }

    private void delete(DownloadStore.Task task) {
        confirmDelete("删除此任务及下载文件？", () -> removeTasks(java.util.Collections.singleton(task.id)));
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        Set<String> ids = new HashSet<>(selected);
        confirmDelete("删除所选 " + ids.size() + " 个任务及下载文件？", () -> {
            removeTasks(ids);
            selected.clear();
            selectionMode = false;
            render();
            adapter.notifyDataSetChanged();
        });
    }

    private void removeTasks(Set<String> ids) {
        List<DownloadStore.Task> removed = new ArrayList<>();
        for (DownloadStore.Task task : store.list()) if (ids.contains(task.id)) removed.add(task);
        store.removeAll(ids);
        new Thread(() -> {
            for (DownloadStore.Task task : removed)
                if (!DownloadService.isActive(task.id)) DownloadService.deleteFiles(store.directory(task));
        }, "download-delete").start();
        render();
    }

    private void confirmDelete(String message, Runnable action) {
        View panel = LayoutInflater.from(this).inflate(R.layout.dialog_download_confirm, null);
        ((TextView) panel.findViewById(R.id.download_confirm_message)).setText(message);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DownloadDialogStyle).setView(panel).create();
        panel.findViewById(R.id.download_confirm_cancel).setOnClickListener(v -> dialog.dismiss());
        panel.findViewById(R.id.download_confirm_delete).setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        dialog.show();
        int width = Math.min((int) (440 * getResources().getDisplayMetrics().density),
                getResources().getDisplayMetrics().widthPixels - (int) (48 * getResources().getDisplayMetrics().density));
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addLink() {
        View panel = LayoutInflater.from(this).inflate(R.layout.dialog_download_link, null);
        EditText input = panel.findViewById(R.id.download_link_input);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DownloadDialogStyle).setView(panel).create();
        panel.findViewById(R.id.download_link_cancel).setOnClickListener(v -> dialog.dismiss());
        View confirm = panel.findViewById(R.id.download_link_confirm);
        confirm.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (store.add("视频 " + (store.list().size() + 1), url, null) == null) {
                input.setError("仅支持 HTTP/HTTPS 链接");
                return;
            }
            dialog.dismiss();
            DownloadService.wake(this);
            render();
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            confirm.performClick();
            return true;
        });
        dialog.show();
        int width = Math.min((int) (480 * getResources().getDisplayMetrics().density),
                getResources().getDisplayMetrics().widthPixels - (int) (48 * getResources().getDisplayMetrics().density));
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private final class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.Holder> {
        private List<DownloadStore.Task> tasks = new ArrayList<>();

        TaskAdapter() { setHasStableIds(true); }

        void submit(List<DownloadStore.Task> next) {
            List<DownloadStore.Task> previous = tasks;
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return previous.size(); }
                @Override public int getNewListSize() { return next.size(); }
                @Override public boolean areItemsTheSame(int oldIndex, int newIndex) {
                    return previous.get(oldIndex).id.equals(next.get(newIndex).id);
                }
                @Override public boolean areContentsTheSame(int oldIndex, int newIndex) {
                    DownloadStore.Task a = previous.get(oldIndex), b = next.get(newIndex);
                    return a.state.equals(b.state) && a.bytes == b.bytes && a.total == b.total
                            && a.items == b.items && a.itemCount == b.itemCount
                            && same(a.error, b.error) && same(a.path, b.path) && same(a.url, b.url)
                            && same(a.cover, b.cover) && same(a.title, b.title);
                }
            });
            tasks = next;
            diff.dispatchUpdatesTo(this);
        }

        private boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

        @Override public long getItemId(int position) {
            java.util.UUID id = java.util.UUID.fromString(tasks.get(position).id);
            return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_task, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            DownloadStore.Task task = tasks.get(position);
            boolean grouped = !TextUtils.isEmpty(task.collection);
            holder.name.setText(grouped ? task.collection : task.title);
            holder.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            holder.check.setChecked(selected.contains(task.id));
            holder.itemView.setFocusable(selectionMode);
            boolean done = DownloadStore.DONE.equals(task.state);
            boolean running = DownloadStore.RUNNING.equals(task.state);
            boolean failed = DownloadStore.FAILED.equals(task.state);
            holder.itemView.setOnClickListener(selectionMode
                    ? v -> toggleSelection(task.id, holder.getBindingAdapterPosition())
                    : done ? v -> play(task) : null);
            String meta = grouped ? task.episode : "在线视频";
            if (!TextUtils.isEmpty(task.playFlag)) meta += "  ·  " + task.playFlag;
            holder.meta.setText(meta);
            holder.state.setText(done ? "已完成" : running ? task.url.isEmpty() ? "解析中" : "下载中"
                    : DownloadStore.QUEUED.equals(task.state) ? "排队中"
                    : DownloadStore.PAUSED.equals(task.state) ? "已暂停" : "失败");
            holder.state.setTextColor(ContextCompat.getColor(DownloadActivity.this,
                    failed ? R.color.download_error : done ? R.color.color_03DAC5
                            : R.color.download_warning));
            int percent = task.total > 0 ? (int) Math.min(100, task.bytes * 100 / task.total)
                    : task.itemCount > 0 ? Math.min(100, task.items * 100 / task.itemCount) : done ? 100 : 0;
            holder.progress.setVisibility(done || running || task.bytes > 0 || task.items > 0 ? View.VISIBLE : View.GONE);
            holder.progress.setIndeterminate(running && task.total == 0 && task.itemCount == 0);
            if (!holder.progress.isIndeterminate()) holder.progress.setProgress(percent);
            String size = String.format(Locale.ROOT, "%.1f MB", task.bytes / 1048576f);
            String detail = task.total > 0 ? size + " / " + String.format(Locale.ROOT, "%.1f MB  ·  %d%%",
                    task.total / 1048576f, percent) : size;
            if (task.itemCount > 0) detail += "  ·  " + task.items + "/" + task.itemCount + " 分片";
            if (failed && task.error != null && !task.error.isEmpty()) detail = task.error;
            holder.detail.setMaxLines(failed ? 2 : 1);
            holder.detail.setText(detail);
            holder.primary.setText(done ? "播放" : running || DownloadStore.QUEUED.equals(task.state) ? "暂停" : "继续");
            holder.primary.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            holder.delete.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            holder.coverPlay.setVisibility(done && !selectionMode ? View.VISIBLE : View.GONE);
            bindCover(holder.cover, task, done);
            holder.primary.setOnClickListener(v -> {
                if (done) play(task);
                else change(task.id, task.state, running || DownloadStore.QUEUED.equals(task.state)
                        ? DownloadStore.PAUSED : DownloadStore.QUEUED);
            });
            holder.delete.setOnClickListener(v -> delete(task));
        }

        private void bindCover(ImageView cover, DownloadStore.Task task, boolean done) {
            Glide.with(cover).clear(cover);
            cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            cover.setImageResource(R.drawable.icon_img_placeholder);
            if (!TextUtils.isEmpty(task.cover)) {
                ImgUtil.load(task.cover, cover, dp(8), 0, 0,
                        TextUtils.isEmpty(task.collection) ? task.title : task.collection);
            } else if (done && !TextUtils.isEmpty(task.path) && new File(task.path).isFile()) {
                ImgUtil.loadVideoScreenshot(Uri.fromFile(new File(task.path)).toString(), cover, 1_000L);
            }
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        @Override
        public void onViewRecycled(@NonNull Holder holder) {
            Glide.with(holder.cover).clear(holder.cover);
            super.onViewRecycled(holder);
        }

        @Override public int getItemCount() { return tasks.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView name, meta, state, detail, primary;
            final CheckBox check;
            final View delete, coverPlay;
            final ImageView cover;
            final ProgressBar progress;

            Holder(View view) {
                super(view);
                name = view.findViewById(R.id.download_name);
                cover = view.findViewById(R.id.download_cover);
                coverPlay = view.findViewById(R.id.download_cover_play);
                check = view.findViewById(R.id.download_check);
                meta = view.findViewById(R.id.download_meta);
                state = view.findViewById(R.id.download_state);
                detail = view.findViewById(R.id.download_detail);
                primary = view.findViewById(R.id.download_primary);
                delete = view.findViewById(R.id.download_delete);
                progress = view.findViewById(R.id.download_progress);
            }
        }
    }
}
