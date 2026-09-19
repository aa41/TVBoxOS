package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

public class BatchDownloadDialog extends BaseDialog {
    public interface OnConfirm { void confirm(List<Integer> positions); }

    private final List<VodInfo.VodSeries> episodes;
    private final boolean[] selected;
    private final OnConfirm onConfirm;
    private final BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> adapter;
    private final TextView countView;
    private final TextView confirmView;

    public BatchDownloadDialog(@NonNull Context context, String title, String route,
                               List<VodInfo.VodSeries> episodes, OnConfirm onConfirm) {
        super(context);
        this.episodes = new ArrayList<>(episodes);
        this.selected = new boolean[episodes.size()];
        this.onConfirm = onConfirm;
        setContentView(R.layout.dialog_download_batch);
        View panel = findViewById(R.id.batch_panel);
        panel.getLayoutParams().width = Math.min((int) (480 * context.getResources().getDisplayMetrics().density),
                context.getResources().getDisplayMetrics().widthPixels - (int) (48 * context.getResources().getDisplayMetrics().density));
        panel.getLayoutParams().height = Math.min((int) (520 * context.getResources().getDisplayMetrics().density),
                (int) (context.getResources().getDisplayMetrics().heightPixels * .82f));
        panel.requestLayout();
        ((TextView) findViewById(R.id.batch_title)).setText(title);
        ((TextView) findViewById(R.id.batch_route)).setText(route + " · " + episodes.size() + " 集");
        countView = findViewById(R.id.batch_count);
        confirmView = findViewById(R.id.batch_confirm);
        findViewById(R.id.batch_dismiss).setOnClickListener(v -> dismiss());
        findViewById(R.id.batch_cancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.batch_all).setOnClickListener(v -> selectAll(true));
        findViewById(R.id.batch_none).setOnClickListener(v -> selectAll(false));
        confirmView.setOnClickListener(v -> {
            List<Integer> positions = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) if (selected[i]) positions.add(i);
            if (positions.isEmpty()) return;
            dismiss();
            onConfirm.confirm(positions);
        });
        TvRecyclerView list = findViewById(R.id.batch_list);
        list.setLayoutManager(new V7LinearLayoutManager(context, 1, false));
        adapter = new BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder>(R.layout.item_download_episode, this.episodes) {
            @Override protected void convert(BaseViewHolder holder, VodInfo.VodSeries episode) {
                holder.setText(R.id.batch_episode_name, episode.name);
                int position = holder.getAdapterPosition();
                ((android.widget.CheckBox) holder.getView(R.id.batch_episode_check))
                        .setChecked(position >= 0 && position < selected.length && selected[position]);
            }
        };
        list.setAdapter(adapter);
        adapter.setOnItemClickListener((quickAdapter, view, position) -> {
            if (position < 0 || position >= selected.length) return;
            selected[position] = !selected[position];
            adapter.notifyItemChanged(position);
            refreshCount();
        });
        refreshCount();
    }

    @Override public void show() {
        super.show();
        Window window = getWindow();
        if (isShowing() && window != null)
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }

    private void selectAll(boolean value) {
        java.util.Arrays.fill(selected, value);
        adapter.notifyDataSetChanged();
        refreshCount();
    }

    private void refreshCount() {
        int count = 0;
        for (boolean value : selected) if (value) count++;
        countView.setText("已选 " + count + " 集");
        confirmView.setText("加入下载 (" + count + ")");
        confirmView.setEnabled(count > 0);
        confirmView.setAlpha(count > 0 ? 1f : .45f);
    }
}
