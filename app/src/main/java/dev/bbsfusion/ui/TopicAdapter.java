package dev.bbsfusion.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.bbsfusion.core.TopicSummary;

import java.util.List;

public final class TopicAdapter extends BaseAdapter {
    private final Context context;
    private final List<TopicSummary> topics;

    public TopicAdapter(Context context, List<TopicSummary> topics) {
        this.context = context;
        this.topics = topics;
    }

    @Override
    public int getCount() {
        return topics.size();
    }

    @Override
    public TopicSummary getItem(int position) {
        return topics.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        RowHolder holder;
        if (convertView == null) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundColor(Color.TRANSPARENT);

            TextView title = new TextView(context);
            title.setTextColor(Color.rgb(32, 33, 36));
            title.setTextSize(16);
            title.setMaxLines(3);

            TextView meta = new TextView(context);
            meta.setTextColor(Color.rgb(95, 99, 104));
            meta.setTextSize(12);
            meta.setPadding(0, dp(4), 0, 0);

            row.addView(title);
            row.addView(meta);

            holder = new RowHolder(title, meta);
            row.setTag(holder);
            convertView = row;
        } else {
            holder = (RowHolder) convertView.getTag();
        }

        TopicSummary topic = topics.get(position);
        holder.title.setText(topic.title);
        holder.meta.setText(topic.meta);
        return convertView;
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static final class RowHolder {
        final TextView title;
        final TextView meta;

        RowHolder(TextView title, TextView meta) {
            this.title = title;
            this.meta = meta;
        }
    }
}
