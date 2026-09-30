package com.tactical.walkietalkie;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

public class WaveformCanvasView extends View {
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private float[] currentAudioBlock = new float[0];
    private float liveRms = 0f;

    public WaveformCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        wavePaint.setColor(Color.parseColor("#00E5FF"));
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(3.0f);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);

        gridPaint.setColor(Color.parseColor("#152238"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.0f);
    }

    public synchronized void updateAudioData(float[] block, float rms) {
        this.currentAudioBlock = block;
        this.liveRms = rms;
        postInvalidate();
    }

    public synchronized void clearAudioData() {
        this.currentAudioBlock = new float[0];
        this.liveRms = 0f;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float midY = h / 2.0f;

        canvas.drawLine(0, midY, w, midY, gridPaint);
        canvas.drawLine(0, h * 0.25f, w, h * 0.25f, gridPaint);
        canvas.drawLine(0, h * 0.75f, w, h * 0.75f, gridPaint);

        if (currentAudioBlock == null || currentAudioBlock.length == 0) {
            canvas.drawLine(0, midY, w, midY, wavePaint);
            return;
        }

        wavePath.reset();
        int step = Math.max(1, currentAudioBlock.length / w);
        float x = 0;
        wavePath.moveTo(0, midY);

        for (int i = 0; i < currentAudioBlock.length; i += step) {
            float sample = currentAudioBlock[i];
            float y = midY - (sample * midY * 0.95f);
            wavePath.lineTo(x, y);
            x += (float) w / (currentAudioBlock.length / (float) step);
        }
        canvas.drawPath(wavePath, wavePaint);
    }
}
