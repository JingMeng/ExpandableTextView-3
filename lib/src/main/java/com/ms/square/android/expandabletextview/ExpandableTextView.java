/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright 2014 Manabu Shimobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ms.square.android.expandabletextview;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


public class ExpandableTextView extends LinearLayout implements View.OnClickListener {

    private static final String TAG = ExpandableTextView.class.getSimpleName();

    private static final int EXPAND_INDICATOR_IMAGE_BUTTON = 0;

    private static final int EXPAND_INDICATOR_TEXT_VIEW = 1;

    private static final int DEFAULT_TOGGLE_TYPE = EXPAND_INDICATOR_IMAGE_BUTTON;

    /* The default number of lines */
    private static final int MAX_COLLAPSED_LINES = 8;

    /* The default animation duration */
    private static final int DEFAULT_ANIM_DURATION = 300;

    /* The default alpha value when the animation starts */
    private static final float DEFAULT_ANIM_ALPHA_START = 0.7f;

    protected TextView mTv;

    protected View mToggleView; // View to expand/collapse

    private boolean mRelayout;

    private boolean mCollapsed = true; // Show short version as default.

    private int mCollapsedHeight;

    private int mTextHeightWithMaxLines;

    private int mMaxCollapsedLines;

    private int mMarginBetweenTxtAndBottom;

    private ExpandIndicatorController mExpandIndicatorController;

    private int mAnimationDuration;

    private float mAnimAlphaStart;

    private boolean mAnimating;

    @IdRes
    private int mExpandableTextId = R.id.expandable_text;

    @IdRes
    private int mExpandCollapseToggleId = R.id.expand_collapse;

    private boolean mExpandToggleOnTextClick;

    /* Listener for callback */
    private OnExpandStateChangeListener mListener;

    /* For saving collapsed status when used in ListView */
    private SparseBooleanArray mCollapsedStatus;
    private int mPosition;

    public ExpandableTextView(Context context) {
        this(context, null);
    }

    public ExpandableTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public ExpandableTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(attrs);
    }

    @Override
    public void setOrientation(int orientation) {
        if (LinearLayout.HORIZONTAL == orientation) {
            throw new IllegalArgumentException("ExpandableTextView only supports Vertical Orientation.");
        }
        super.setOrientation(orientation);
    }

    @Override
    public void onClick(View view) {
        if (mToggleView.getVisibility() != View.VISIBLE) {
            return;
        }

        mCollapsed = !mCollapsed;
        mExpandIndicatorController.changeState(mCollapsed);

        if (mCollapsedStatus != null) {
            mCollapsedStatus.put(mPosition, mCollapsed);
        }

        // mark that the animation is in progress
        mAnimating = true;

        Animation animation;
        if (mCollapsed) {
            /**
             *  getHeight() 当前大小 --- 这个大小是最大的代销，因为 上面的 onClick 第一步就是  mCollapsed = !mCollapsed;
             *
             *  mCollapsedHeight 是最终的大小，也就是最小的高度
             */
            animation = new ExpandCollapseAnimation(this, getHeight(), mCollapsedHeight);
        } else {
            /**
             * getHeight() 得到的是最小高度
             *
             * mTextHeightWithMaxLines 是 textView 占用的真实大小
             *
             * mTextHeightWithMaxLines - mTv.getHeight() 得到的是被折叠的那部分大小
             */
            animation = new ExpandCollapseAnimation(this, getHeight(), getHeight() +
                    mTextHeightWithMaxLines - mTv.getHeight());
        }

        animation.setFillAfter(true);
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                applyAlphaAnimation(mTv, mAnimAlphaStart);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                // clear animation here to avoid repeated applyTransformation() calls
                clearAnimation();
                // clear the animation flag
                mAnimating = false;

                // notify the listener
                if (mListener != null) {
                    mListener.onExpandStateChanged(mTv, !mCollapsed);
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });

        clearAnimation();
        startAnimation(animation);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // while an animation is in progress, intercept all the touch events to children to
        // prevent extra clicks during the animation
        return mAnimating;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        findViews();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If no change, measure and return
        if (false) {
            //fixme 虽然说这个地方依旧会执行，但是考虑 这个地方一直在测量，对view动画没有影响吗
            Log.i(TAG, "========onMeasure=======" + System.currentTimeMillis());
        }
        if (!mRelayout || getVisibility() == View.GONE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        /**
         *
         * 这个地方也仅仅会执行一次
         * todo
         *
         * {@link  this#onClick(View)} }
         *
         *  因为点击的时候是使用动画修改的
         *
         *  并不是代表 动画不会导致 onMeasure 重新调用，是我们这部分重新计算不需要重新计算了
         *
         *  只有出现新的文本的时候才有必要进行新的计算
         */
        mRelayout = false;

        // Setup with optimistic case
        // i.e. Everything fits. No button needed
        mToggleView.setVisibility(View.GONE);
        mTv.setMaxLines(Integer.MAX_VALUE);

        // Measure
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // If the text fits in collapsed mode, we are done.
        if (mTv.getLineCount() <= mMaxCollapsedLines) {
            return;
        }

        // Saves the text height w/ max lines
        mTextHeightWithMaxLines = getRealTextViewHeight(mTv);

        // Doesn't fit in collapsed mode. Collapse text view as needed. Show
        // button.
        if (mCollapsed) {
            mTv.setMaxLines(mMaxCollapsedLines);
        }
        mToggleView.setVisibility(View.VISIBLE);

        // Re-measure with new setup
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (mCollapsed) {
            // Gets the margin between the TextView's bottom and the ViewGroup's bottom
            mTv.post(new Runnable() {
                @Override
                public void run() {
                    /**
                     * 1. 首先这个地方post了 ， 这个意味着
                     *
                     *  1.  mTv.getHeight() 获取的大小一定是实际的大小  也就是  setMaxLines 之后的大小
                     *
                     *  2. getHeight() 的大小是
                     * @see View
                     * {@link  View#layout(int, int, int, int)}
                     * {@link  View#setFrame} 这个时间才会设置  左上右下这几个参数
                     *
                     */
                    ViewGroup.MarginLayoutParams params = (MarginLayoutParams) mTv.getLayoutParams();
                    int i = getHeight() - mTv.getHeight() - mToggleView.getHeight() - params.topMargin;
                    /**
                     * 这个高度是一个最大的可以调控的大小
                     */
                    mMarginBetweenTxtAndBottom = getHeight() - mTv.getHeight();
                    //这个得到的 i 就不存在偏差了
                    Log.i(TAG, "========mMarginBetweenTxtAndBottom=======" + mMarginBetweenTxtAndBottom + "=======" + i);
                }
            });
            //这个大小是 收缩之后的大小
            // Saves the collapsed height of this ViewGroup
            mCollapsedHeight = getMeasuredHeight();
        }
    }

    public void setOnExpandStateChangeListener(@Nullable OnExpandStateChangeListener listener) {
        mListener = listener;
    }

    public void setText(@Nullable CharSequence text) {
        mRelayout = true;
        mTv.setText(text);
        setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        clearAnimation();
        getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        requestLayout();
    }

    public void setText(@Nullable CharSequence text, @NonNull SparseBooleanArray collapsedStatus, int position) {
        mCollapsedStatus = collapsedStatus;
        mPosition = position;
        boolean isCollapsed = collapsedStatus.get(position, true);
        clearAnimation();
        mCollapsed = isCollapsed;
        mExpandIndicatorController.changeState(mCollapsed);
        setText(text);
    }

    @Nullable
    public CharSequence getText() {
        if (mTv == null) {
            return "";
        }
        return mTv.getText();
    }

    private void init(AttributeSet attrs) {
        TypedArray typedArray = getContext().obtainStyledAttributes(attrs, R.styleable.ExpandableTextView);
        mMaxCollapsedLines = typedArray.getInt(R.styleable.ExpandableTextView_maxCollapsedLines, MAX_COLLAPSED_LINES);
        mAnimationDuration = typedArray.getInt(R.styleable.ExpandableTextView_animDuration, DEFAULT_ANIM_DURATION);
        mAnimAlphaStart = typedArray.getFloat(R.styleable.ExpandableTextView_animAlphaStart, DEFAULT_ANIM_ALPHA_START);
        mExpandableTextId = typedArray.getResourceId(R.styleable.ExpandableTextView_expandableTextId, R.id.expandable_text);
        mExpandCollapseToggleId = typedArray.getResourceId(R.styleable.ExpandableTextView_expandCollapseToggleId, R.id.expand_collapse);
        mExpandToggleOnTextClick = typedArray.getBoolean(R.styleable.ExpandableTextView_expandToggleOnTextClick, true);

        mExpandIndicatorController = setupExpandToggleController(getContext(), typedArray);

        typedArray.recycle();

        // enforces vertical orientation
        setOrientation(LinearLayout.VERTICAL);

        // default visibility is gone
        setVisibility(GONE);
    }

    private void findViews() {
        mTv = (TextView) findViewById(mExpandableTextId);
        if (mExpandToggleOnTextClick) {
            mTv.setOnClickListener(this);
        } else {
            mTv.setOnClickListener(null);
        }
        mToggleView = findViewById(mExpandCollapseToggleId);
        mExpandIndicatorController.setView(mToggleView);
        mExpandIndicatorController.changeState(mCollapsed);
        mToggleView.setOnClickListener(this);
    }

    private static boolean isPostHoneycomb() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB;
    }

    private static boolean isPostLolipop() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private static void applyAlphaAnimation(View view, float alpha) {
        if (isPostHoneycomb()) {
            view.setAlpha(alpha);
        } else {
            AlphaAnimation alphaAnimation = new AlphaAnimation(alpha, alpha);
            // make it instant
            alphaAnimation.setDuration(0);
            alphaAnimation.setFillAfter(true);
            view.startAnimation(alphaAnimation);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static Drawable getDrawable(@NonNull Context context, @DrawableRes int resId) {
        Resources resources = context.getResources();
        if (isPostLolipop()) {
            return resources.getDrawable(resId, context.getTheme());
        } else {
            return resources.getDrawable(resId);
        }
    }

    private static int getRealTextViewHeight(@NonNull TextView textView) {
        int textHeight = textView.getLayout().getLineTop(textView.getLineCount());
        int padding = textView.getCompoundPaddingTop() + textView.getCompoundPaddingBottom();
        return textHeight + padding;
    }

    private static ExpandIndicatorController setupExpandToggleController(@NonNull Context context, TypedArray typedArray) {
        final int expandToggleType = typedArray.getInt(R.styleable.ExpandableTextView_expandToggleType, DEFAULT_TOGGLE_TYPE);
        final ExpandIndicatorController expandIndicatorController;
        switch (expandToggleType) {
            case EXPAND_INDICATOR_IMAGE_BUTTON:
                Drawable expandDrawable = typedArray.getDrawable(R.styleable.ExpandableTextView_expandIndicator);
                Drawable collapseDrawable = typedArray.getDrawable(R.styleable.ExpandableTextView_collapseIndicator);

                if (expandDrawable == null) {
                    expandDrawable = getDrawable(context, R.drawable.ic_expand_more_black_12dp);
                }
                if (collapseDrawable == null) {
                    collapseDrawable = getDrawable(context, R.drawable.ic_expand_less_black_12dp);
                }
                expandIndicatorController = new ImageButtonExpandController(expandDrawable, collapseDrawable);
                break;
            case EXPAND_INDICATOR_TEXT_VIEW:
                String expandText = typedArray.getString(R.styleable.ExpandableTextView_expandIndicator);
                String collapseText = typedArray.getString(R.styleable.ExpandableTextView_collapseIndicator);
                expandIndicatorController = new TextViewExpandController(expandText, collapseText);
                break;
            default:
                throw new IllegalStateException("Must be of enum: ExpandableTextView_expandToggleType, one of EXPAND_INDICATOR_IMAGE_BUTTON or EXPAND_INDICATOR_TEXT_VIEW.");
        }

        return expandIndicatorController;
    }

    class ExpandCollapseAnimation extends Animation {
        private final View mTargetView;
        private final int mStartHeight;
        private final int mEndHeight;

        public ExpandCollapseAnimation(View view, int startHeight, int endHeight) {
            mTargetView = view;
            mStartHeight = startHeight;
            mEndHeight = endHeight;
            setDuration(mAnimationDuration);
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            //这个高度是当前view的高度
            final int newHeight = (int) ((mEndHeight - mStartHeight) * interpolatedTime + mStartHeight);
            //这个最大高度是自己的高度，减掉的是空白区域的高度-----所以这个地方需要限制一下
            mTv.setMaxHeight(newHeight - mMarginBetweenTxtAndBottom);
            if (Float.compare(mAnimAlphaStart, 1.0f) != 0) {
                applyAlphaAnimation(mTv, mAnimAlphaStart + interpolatedTime * (1.0f - mAnimAlphaStart));
            }
            mTargetView.getLayoutParams().height = newHeight;
            mTargetView.requestLayout();
        }

        @Override
        public void initialize(int width, int height, int parentWidth, int parentHeight) {
            super.initialize(width, height, parentWidth, parentHeight);
        }

        @Override
        public boolean willChangeBounds() {
            return true;
        }
    }

    public interface OnExpandStateChangeListener {
        /**
         * Called when the expand/collapse animation has been finished
         *
         * @param textView   - TextView being expanded/collapsed
         * @param isExpanded - true if the TextView has been expanded
         */
        void onExpandStateChanged(TextView textView, boolean isExpanded);
    }

    interface ExpandIndicatorController {
        void changeState(boolean collapsed);

        void setView(View toggleView);
    }

    static class ImageButtonExpandController implements ExpandIndicatorController {

        private final Drawable mExpandDrawable;
        private final Drawable mCollapseDrawable;

        private ImageButton mImageButton;

        public ImageButtonExpandController(Drawable expandDrawable, Drawable collapseDrawable) {
            mExpandDrawable = expandDrawable;
            mCollapseDrawable = collapseDrawable;
        }

        @Override
        public void changeState(boolean collapsed) {
            mImageButton.setImageDrawable(collapsed ? mExpandDrawable : mCollapseDrawable);
        }

        @Override
        public void setView(View toggleView) {
            mImageButton = (ImageButton) toggleView;
        }
    }

    static class TextViewExpandController implements ExpandIndicatorController {

        private final String mExpandText;
        private final String mCollapseText;

        private TextView mTextView;

        public TextViewExpandController(String expandText, String collapseText) {
            mExpandText = expandText;
            mCollapseText = collapseText;
        }

        @Override
        public void changeState(boolean collapsed) {
            mTextView.setText(collapsed ? mExpandText : mCollapseText);
        }

        @Override
        public void setView(View toggleView) {
            mTextView = (TextView) toggleView;
        }
    }
}