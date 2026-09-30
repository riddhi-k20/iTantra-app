package com.tactical.walkietalkie;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class MeshNodeMapView extends View {
    public static class MeshNode {
        public String id;
        public float x;
        public float y;
        public boolean isSelf;
        public boolean isConnected;

        public MeshNode(String id, float x, float y, boolean isSelf, boolean isConnected) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.isSelf = isSelf;
            this.isConnected = isConnected;
        }
    }

    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<MeshNode> nodes = new ArrayList<>();

    public MeshNodeMapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        nodePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(20f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        linkPaint.setColor(Color.parseColor("#00FFA3"));
        linkPaint.setStyle(Paint.Style.STROKE);
        linkPaint.setStrokeWidth(2.5f);

        nodes.add(new MeshNode("LOCAL", 0.5f, 0.5f, true, true));
    }

    public synchronized void setDiscoveredNodes(List<String> deviceIds) {
        nodes.clear();
        nodes.add(new MeshNode("HOST", 0.5f, 0.5f, true, true));
        int count = deviceIds.size();
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI / Math.max(1, count)) * i;
            float nx = (float) (0.5f + 0.35f * Math.cos(angle));
            float ny = (float) (0.5f + 0.35f * Math.sin(angle));
            nodes.add(new MeshNode(deviceIds.get(i), nx, ny, false, true));
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        MeshNode host = null;
        for (MeshNode node : nodes) {
            if (node.isSelf) {
                host = node;
                break;
            }
        }

        if (host != null) {
            float hx = host.x * w;
            float hy = host.y * h;
            for (MeshNode node : nodes) {
                if (!node.isSelf && node.isConnected) {
                    canvas.drawLine(hx, hy, node.x * w, node.y * h, linkPaint);
                }
            }
        }

        for (MeshNode node : nodes) {
            float cx = node.x * w;
            float cy = node.y * h;
            if (node.isSelf) {
                nodePaint.setColor(Color.parseColor("#00FFA3"));
                canvas.drawCircle(cx, cy, 14f, nodePaint);
            } else {
                nodePaint.setColor(node.isConnected ? Color.parseColor("#00E5FF") : Color.parseColor("#D32F2F"));
                canvas.drawCircle(cx, cy, 10f, nodePaint);
            }
            canvas.drawText(node.id, cx, cy - 16f, textPaint);
        }
    }
}
