package com.mnemolyst.flightRecorder;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Created by joshua on 6/5/17.
 */

class SavedVideoListAdapter extends RecyclerView.Adapter<SavedVideoListAdapter.ViewHolder> {

    private ArrayList<String> dataSet;

    static class ViewHolder extends RecyclerView.ViewHolder {

        TextView textView;

        ViewHolder(TextView view) {
            super(view);
            textView = view;
        }

    }

    public SavedVideoListAdapter(ArrayList<String> dataSet) {

        this.dataSet = dataSet;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

        TextView v = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.saved_video_list_item, parent, false);
        // set the view's size, margins, paddings and layout parameters

        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {

        holder.textView.setText(dataSet.get(position));
    }

    @Override
    public int getItemCount() {

        return dataSet.size();
    }
}
