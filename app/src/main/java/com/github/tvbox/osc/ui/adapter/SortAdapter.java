package com.github.tvbox.osc.ui.adapter;

import android.content.res.Configuration;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class SortAdapter extends BaseQuickAdapter<MovieSort.SortData, BaseViewHolder> {
    private int portraitSelection = 0;

    public SortAdapter() {
        super(R.layout.item_home_sort, new ArrayList<>());
    }

    public void setPortraitSelection(int position) {
        if (portraitSelection == position) return;
        int previous = portraitSelection;
        portraitSelection = position;
        if (previous < getData().size()) notifyItemChanged(previous);
        if (position < getData().size()) notifyItemChanged(position);
    }

    @Override
    protected void convert(BaseViewHolder helper, MovieSort.SortData item) {
        helper.setText(R.id.tvTitle, item.name);
        if (helper.itemView.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            boolean selected = helper.getAdapterPosition() == portraitSelection;
            helper.itemView.setSelected(selected);
            helper.setTextColor(R.id.tvTitle, helper.itemView.getResources().getColor(
                    selected ? R.color.color_FFFFFF : R.color.color_BBFFFFFF));
        }
    }
}
