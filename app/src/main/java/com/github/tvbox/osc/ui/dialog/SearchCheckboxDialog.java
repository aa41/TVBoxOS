package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.CheckboxSearchAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.SearchHelper;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class SearchCheckboxDialog extends BaseDialog{

    private TvRecyclerView mGridView;
    private CheckboxSearchAdapter checkboxSearchAdapter;
    public List<SourceBean> mSourceList;
    TextView checkAll;
    TextView clearAll;

    public HashMap<String, String> mCheckSourcees;

    public SearchCheckboxDialog(@NonNull @NotNull Context context, List<SourceBean> sourceList, HashMap<String, String> checkedSources) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setCanceledOnTouchOutside(false);
        setCancelable(true);
        mSourceList = sourceList;
        mCheckSourcees = checkedSources == null ? SearchHelper.getSources() : checkedSources;
        setContentView(R.layout.dialog_checkbox_search);
        initView(context);
    }

    @Override
    public void dismiss() {
        checkboxSearchAdapter.setMCheckedSources();
        super.dismiss();
    }

    @Override
    public void show() {
        super.show();
        if (isPortrait() && isShowing()) {
            Window window = getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(0.55f);
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            }
        }
    }

    private boolean isPortrait() {
        return getContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private void updatePanelSize() {
        View root = findViewById(R.id.root);
        boolean empty = mSourceList.isEmpty();
        findViewById(R.id.sourceActions).setVisibility(empty ? View.GONE : View.VISIBLE);
        findViewById(R.id.emptyState).setVisibility(empty ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(empty ? View.GONE : View.VISIBLE);
        ViewGroup.LayoutParams params = root.getLayoutParams();
        if (isPortrait()) {
            int screenHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            int contentHeight = AutoSizeUtils.dp2px(getContext(), empty ? 112 : 108 + 46 * mSourceList.size());
            params.height = Math.min(contentHeight, Math.min(
                    screenHeight - AutoSizeUtils.dp2px(getContext(), 80),
                    AutoSizeUtils.dp2px(getContext(), 560)));
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            int columns = Math.max(1, Math.min(3, (int) Math.floor(mSourceList.size() / 10.0)));
            params.width = AutoSizeUtils.mm2px(getContext(), 400 + 260 * (columns - 1));
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        root.setLayoutParams(params);
    }

    protected void initView(Context context) {
        mGridView = findViewById(R.id.mGridView);
        checkAll = findViewById(R.id.checkAll);
        clearAll = findViewById(R.id.clearAll);
        checkboxSearchAdapter = new CheckboxSearchAdapter(new DiffUtil.ItemCallback<SourceBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getKey().equals(newItem.getKey());
            }

            @Override
            public boolean areContentsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getName().equals(newItem.getName());
            }
        });
        mGridView.setHasFixedSize(true);

        int size = mSourceList.size();
        int spanCount = isPortrait() ? 1 : Math.max(1, Math.min(3, size / 10));
        mGridView.setLayoutManager(new V7GridLayoutManager(getContext(), spanCount));
        updatePanelSize();

        mGridView.setAdapter(checkboxSearchAdapter);
        checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
        int pos = 0;
        if (mCheckSourcees != null) {
            for(int i=0; i<mSourceList.size(); i++) {
                String key = mSourceList.get(i).getKey();
                if (mCheckSourcees.containsKey(key)) {
                    pos = i;
                    break;
                }
            }
        }
//        final int scrollPosition = pos;
//        mGridView.post(new Runnable() {
//            @Override
//            public void run() {
//                mGridView.smoothScrollToPosition(scrollPosition);
//            }
//        });
        checkAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                mCheckSourcees = new HashMap<>();
                for(SourceBean sourceBean : mSourceList) {
                    mCheckSourcees.put(sourceBean.getKey(), "1");
                }
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
            }
        });
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                mCheckSourcees = new HashMap<>();
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
            }
        });
        View done = findViewById(R.id.done);
        if (done != null) done.setOnClickListener(v -> dismiss());
    }

    public void setMSourceList(List<SourceBean> SourceBeanList) {
        mSourceList = SourceBeanList;
        checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
        updatePanelSize();
    }
}
