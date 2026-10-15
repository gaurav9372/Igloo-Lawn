package com.android.launcher3.allapps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.launcher3.R;

import java.text.NumberFormat;

import app.lawnchair.theme.color.tokens.ColorTokens;

/** A single accessible percentage ring for the launcher's drawer load. */
public final class DrawerLoadingView extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRing = new RectF();
    private final NumberFormat mPercentFormat = NumberFormat.getPercentInstance();
    private final NumberFormat mCountFormat = NumberFormat.getIntegerInstance();
    private final float mDensity;
    private final int mAccent;
    private final int mSurface;
    private int mProgress;
    private String mPercentage;
    private String mCount;
    private final Runnable mHide = () -> setVisibility(GONE);

    public DrawerLoadingView(Context context) {
        super(context);
        mDensity = getResources().getDisplayMetrics().density;
        mAccent = ColorTokens.PrimaryButton.resolveColor(context);
        mSurface = ColorTokens.Surface.resolveColor(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setVisibility(GONE);
        mPercentage = mPercentFormat.format(0);
    }

    public void setProgress(int progress) {
        setProgress(progress, 0, -1);
    }

    public void setProgress(DrawerLoadProgress.State state) {
        setProgress(state.progress, state.completed, state.total);
    }

    private void setProgress(int progress, int completed, int total) {
        removeCallbacks(mHide);
        if (progress < 0) {
            setVisibility(GONE);
            return;
        }
        // Recreating an already-loaded drawer should not flash a completed indicator.
        if (progress == 100 && getVisibility() != VISIBLE) return;
        mProgress = progress;
        mPercentage = mPercentFormat.format(progress / 100.0);
        mCount = total < 0 ? null : getContext().getString(R.string.drawer_loading_count,
                mCountFormat.format(completed), mCountFormat.format(total));
        setContentDescription(mCount == null
                ? getContext().getString(R.string.drawer_loading_progress, mPercentage)
                : getContext().getString(R.string.drawer_loading_progress_with_count,
                        mPercentage, mCount));
        setVisibility(VISIBLE);
        invalidate();
        if (progress == 100) postDelayed(mHide, 250);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mHide);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mSurface);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 24 * mDensity, 24 * mDensity, mPaint);
        float radius = 36 * mDensity;
        mRing.set(cx - radius, cy - radius - 24 * mDensity,
                cx + radius, cy + radius - 24 * mDensity);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(4 * mDensity);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setColor(mAccent);
        mPaint.setAlpha(40);
        canvas.drawOval(mRing, mPaint);
        mPaint.setAlpha(255);
        canvas.drawArc(mRing, -90, mProgress * 3.6f, false, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
        float textY = mRing.centerY() - (mPaint.ascent() + mPaint.descent()) / 2;
        canvas.drawText(mPercentage, cx, textY, mPaint);
        mPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(getContext().getString(R.string.drawer_loading_label), cx,
                mRing.bottom + 26 * mDensity, mPaint);
        if (mCount != null) {
            canvas.drawText(mCount, cx, mRing.bottom + 48 * mDensity, mPaint);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.ProgressBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 100, mProgress));
    }
}
