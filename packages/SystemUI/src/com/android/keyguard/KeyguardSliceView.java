/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import static android.app.slice.Slice.HINT_LIST_ITEM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.ColorInt;
import android.annotation.StyleRes;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.slice.Slice;
import androidx.slice.SliceItem;
import androidx.slice.SliceViewManager;
import androidx.slice.core.SliceQuery;
import androidx.slice.widget.ListContent;
import androidx.slice.widget.RowContent;
import androidx.slice.widget.SliceContent;
import androidx.slice.widget.SliceLiveData;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;

import com.google.android.systemui.keyguard.KeyguardSliceProviderGoogle;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View visible under the clock on the lock screen and AoD.
 */
public class KeyguardSliceView extends LinearLayout implements View.OnClickListener,
        Observer<Slice>, TunerService.Tunable, ConfigurationController.ConfigurationListener {

    private static final String TAG = "KeyguardSliceView";
    public static final int DEFAULT_ANIM_DURATION = 550;

    private final HashMap<View, PendingIntent> mClickActions;
    private final ActivityStarter mActivityStarter;
    private final ConfigurationController mConfigurationController;
    private final LayoutTransition mLayoutTransition;
    private Uri mKeyguardSliceUri;
    @VisibleForTesting
    TextView mTitle;
    private RelativeLayout mRowContainer;
    private Row mRow;
    private int mTextColor;
    private float mDarkAmount = 0;

    private LiveData<Slice> mLiveData;
    private int mDisplayId = INVALID_DISPLAY;
    private int mIconSize;
    private int mIconSizeWithHeader;
    /**
     * Runnable called whenever the view contents change.
     */
    private Runnable mContentChangeListener;
    private Slice mSlice;
    private boolean mHasHeader;
    private final int mRowWithHeaderPadding;
    private final int mRowPadding;
    private int mRowTextSize;
    private float mRowWithHeaderTextSize;

    @Inject
    public KeyguardSliceView(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            ActivityStarter activityStarter, ConfigurationController configurationController) {
        super(context, attrs);

        TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, Settings.Secure.KEYGUARD_SLICE_URI);

        mClickActions = new HashMap<>();
        mRowPadding = context.getResources().getDimensionPixelSize(R.dimen.subtitle_clock_padding);
        mRowWithHeaderPadding = context.getResources()
                .getDimensionPixelSize(R.dimen.header_subtitle_padding);
        mActivityStarter = activityStarter;
        mConfigurationController = configurationController;

        mLayoutTransition = new LayoutTransition();
        mLayoutTransition.setStagger(LayoutTransition.CHANGE_APPEARING, DEFAULT_ANIM_DURATION / 2);
        mLayoutTransition.setDuration(LayoutTransition.APPEARING, DEFAULT_ANIM_DURATION);
        mLayoutTransition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 2);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        mLayoutTransition.setInterpolator(LayoutTransition.APPEARING,
                Interpolators.FAST_OUT_SLOW_IN);
        mLayoutTransition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        mLayoutTransition.setAnimateParentHierarchy(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitle = findViewById(R.id.title);
        mRowContainer = findViewById(R.id.row_maincenter);
        mRow = findViewById(R.id.row);
        mTextColor = Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
        mIconSize = mRowTextSize;
        mIconSizeWithHeader = (int) mContext.getResources().getDimension(R.dimen.header_icon_size);
        mRowTextSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.lock_date_font_size_18);
        mRowWithHeaderTextSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.header_row_font_size);
        mTitle.setOnClickListener(this);
    }

    public View getTitleView() {
        return mTitle;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mDisplayId = getDisplay().getDisplayId();
        // Make sure we always have the most current slice
        mLiveData.observeForever(this);
        mConfigurationController.addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // TODO(b/117344873) Remove below work around after this issue be fixed.
        if (mDisplayId == DEFAULT_DISPLAY) {
            mLiveData.removeObserver(this);
        }
        mConfigurationController.removeCallback(this);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        setLayoutTransition(isVisible ? mLayoutTransition : null);
    }

    private int getLockDateSize() {
        return Settings.System.getInt(mContext.getContentResolver(),
               Settings.System.LOCKDATE_FONT_SIZE, 18);
    }

    private int getLockDateFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_DATE_FONTS, 28);
    }

    /**
     * Returns whether the current visible slice has a title/header.
     */
    public boolean hasHeader() {
        return mHasHeader;
    }

    private void showSlice() {
        Trace.beginSection("KeyguardSliceView#showSlice");
        if (mSlice == null) {
            mTitle.setVisibility(GONE);
            mRow.setVisibility(GONE);
            mHasHeader = false;
            if (mContentChangeListener != null) {
                mContentChangeListener.run();
            }
            Trace.endSection();
            return;
        }
        mClickActions.clear();

        final ContentResolver resolver = mContext.getContentResolver();
        boolean mClockSelection = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT) == 9
                || Settings.Secure.getIntForUser(resolver,
                Settings.Secure.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT) == 10;
        int mTextClockAlignment = Settings.System.getIntForUser(resolver,
                Settings.System.TEXT_CLOCK_ALIGNMENT, 0, UserHandle.USER_CURRENT);

        ListContent lc = new ListContent(getContext(), mSlice);
        SliceContent headerContent = lc.getHeader();
        mHasHeader = headerContent != null && !headerContent.getSliceItem().hasHint(HINT_LIST_ITEM);
        List<SliceContent> subItems = new ArrayList<>();
        for (int i = 0; i < lc.getRowItems().size(); i++) {
            SliceContent subItem = lc.getRowItems().get(i);
            String itemUri = subItem.getSliceItem().getSlice().getUri().toString();
            // Filter out the action row
            if (!KeyguardSliceProvider.KEYGUARD_ACTION_URI.equals(itemUri)) {
                subItems.add(subItem);
            }
        }
        if (!mHasHeader) {
            mTitle.setVisibility(GONE);
        } else {
            mTitle.setVisibility(VISIBLE);

            RowContent header = lc.getHeader();
            SliceItem mainTitle = header.getTitleItem();
            CharSequence title = mainTitle != null ? mainTitle.getText() : null;
            mTitle.setText(title);
            if (header.getPrimaryAction() != null
                    && header.getPrimaryAction().getAction() != null) {
                mClickActions.put(mTitle, header.getPrimaryAction().getAction());
            }
        }

        final int subItemsCount = subItems.size();
        final int blendedColor = getTextColor();
        final int startIndex = mHasHeader ? 1 : 0; // First item is header; skip it
        if (mClockSelection) {
            switch (mTextClockAlignment) {
                case 0:
                case 4:
                default:
                    mRowContainer.setGravity(Gravity.START);
                    break;
                case 1:
                    mRowContainer.setGravity(Gravity.CENTER);
                    break;
                case 2:
                case 3:
                    mRowContainer.setGravity(Gravity.END);
                    break;
            }
        } else {
            mRowContainer.setGravity(Gravity.CENTER);
        }
        mRow.setVisibility(subItemsCount > 0 ? VISIBLE : GONE);

        for (int i = startIndex; i < subItemsCount; i++) {
            RowContent rc = (RowContent) subItems.get(i);
            SliceItem item = rc.getSliceItem();
            final Uri itemTag = item.getSlice().getUri();
            // Try to reuse the view if already exists in the layout
            KeyguardSliceButton button = mRow.findViewWithTag(itemTag);
            if (button == null) {
                button = new KeyguardSliceButton(mContext);
                button.setTextColor(blendedColor);
                button.setTag(itemTag);
                final int viewIndex = i - (mHasHeader ? 1 : 0);
                mRow.addView(button, viewIndex);
            }

            PendingIntent pendingIntent = null;
            if (rc.getPrimaryAction() != null) {
                pendingIntent = rc.getPrimaryAction().getAction();
            }
            mClickActions.put(button, pendingIntent);

            final SliceItem titleItem = rc.getTitleItem();
            button.setText(titleItem == null ? null : titleItem.getText());
            button.setContentDescription(rc.getContentDescription());
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    mHasHeader ? mRowWithHeaderTextSize : mRowTextSize);
            refreshLockDateFont(button);

            Drawable iconDrawable = null;
            SliceItem icon = SliceQuery.find(item.getSlice(),
                    android.app.slice.SliceItem.FORMAT_IMAGE);
            if (icon != null) {
                final int iconSize = mHasHeader ? mIconSizeWithHeader : mIconSize;
                iconDrawable = icon.getIcon().loadDrawable(mContext);
                if (iconDrawable != null) {
                    final int width = (int) (iconDrawable.getIntrinsicWidth()
                            / (float) iconDrawable.getIntrinsicHeight() * iconSize);
                    iconDrawable.setBounds(0, 0, Math.max(width, 1), iconSize);
                }
            }
            button.setCompoundDrawables(iconDrawable, null, null, null);
            button.setOnClickListener(this);
            button.setClickable(pendingIntent != null);
        }

        // Removing old views
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            if (!mClickActions.containsKey(child)) {
                mRow.removeView(child);
                i--;
            }
        }

        if (mContentChangeListener != null) {
            mContentChangeListener.run();
        }
        Trace.endSection();
    }

    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        mRow.setDarkAmount(darkAmount);
        updateTextColors();
    }

    public void setViewsTypeface(Typeface tf) {
        int childCount = mRow.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = mRow.getChildAt(i);
            if (v instanceof Button) {
                ((Button) v).setTypeface(tf);
            }
        }
    }

    private void updateTextColors() {
        final int blendedColor = getTextColor();
        mTitle.setTextColor(blendedColor);
        int childCount = mRow.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = mRow.getChildAt(i);
            if (v instanceof Button) {
                ((Button) v).setTextColor(blendedColor);
            }
        }
    }

    public void setViewsTextStyles(float textSp, boolean textAllCaps) {
        int childCount = mRow.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = mRow.getChildAt(i);
            if (v instanceof Button) {
                ((Button) v).setLetterSpacing(textSp);
                ((Button) v).setAllCaps(textAllCaps);
            }
        }
    }

    public void setViewBackground(Drawable drawRes) {
        setViewBackground(drawRes, 255);
    }

    public void setViewBackground(Drawable drawRes, int bgAlpha) {
        mRow.setBackground(drawRes);
        mRow.getBackground().setAlpha(bgAlpha);
    }

    public void setViewBackgroundResource(int drawRes) {
        mRow.setBackgroundResource(drawRes);
    }

    public void setViewPadding(int left, int top, int right, int bottom) {
        mRow.setPadding(left,top,right,bottom);
    }

    @Override
    public void onClick(View v) {
        /*final PendingIntent action = mClickActions.get(v);
        if (action != null) {
            mActivityStarter.startPendingIntentDismissingKeyguard(action);
        }*/ //not being used by aosp actually for any slice - see KeyguardSliceProvider.addPrimaryActionLocked
    }

    /**
     * Runnable that gets invoked every time the title or the row visibility changes.
     * @param contentChangeListener The listener.
     */
    public void setContentChangeListener(Runnable contentChangeListener) {
        mContentChangeListener = contentChangeListener;
    }

    /**
     * LiveData observer lifecycle.
     * @param slice the new slice content.
     */
    @Override
    public void onChanged(Slice slice) {
        mSlice = slice;
        showSlice();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        setupUri(newValue);
    }

    /**
     * Sets the slice provider Uri.
     */
    public void setupUri(String uriString) {
        if (uriString == null) {
            uriString = KeyguardSliceProvider.KEYGUARD_SLICE_URI;
        }

        boolean wasObserving = false;
        if (mLiveData != null && mLiveData.hasActiveObservers()) {
            wasObserving = true;
            mLiveData.removeObserver(this);
        }

        mKeyguardSliceUri = Uri.parse(uriString);
        mLiveData = SliceLiveData.fromUri(mContext, mKeyguardSliceUri);

        if (wasObserving) {
            mLiveData.observeForever(this);
        }
    }

    @VisibleForTesting
    int getTextColor() {
        return ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
    }

    @VisibleForTesting
    void setTextColor(@ColorInt int textColor) {
        mTextColor = textColor;
        updateTextColors();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        mIconSize = mRowTextSize;
        mIconSizeWithHeader = (int) mContext.getResources().getDimension(R.dimen.header_icon_size);
        mRowTextSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.lock_date_font_size_18);
        mRowWithHeaderTextSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.header_row_font_size);
    }

    public void refresh() {
        Slice slice;
        Trace.beginSection("KeyguardSliceView#refresh");
        // We can optimize performance and avoid binder calls when we know that we're bound
        // to a Slice on the same process.
        if (KeyguardSliceProvider.KEYGUARD_SLICE_URI.equals(mKeyguardSliceUri.toString())) {
            KeyguardSliceProvider instance = KeyguardSliceProviderGoogle.getAttachedInstance();
            if (instance != null) {
                slice = instance.onBindSlice(mKeyguardSliceUri);
            } else {
                Log.w(TAG, "Keyguard slice not bound yet?");
                slice = null;
            }
        } else {
            slice = SliceViewManager.getInstance(getContext()).bindSlice(mKeyguardSliceUri);
        }
        onChanged(slice);
        Trace.endSection();
    }

    public void refreshdatesize() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockDateSize = isPrimary ? getLockDateSize() : 18;

        switch (lockDateSize) {
            case 10:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_10);
                break;
            case 11:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_11);
                break;
            case 12:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_12);
                break;
            case 13:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_13);
                break;
            case 14:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_14);
                break;
            case 15:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_15);
                break;
            case 16:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_16);
                break;
            case 17:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_17);
                break;
            case 18:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_18);
                break;
            case 19:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_19);
                break;
            case 20:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_20);
                break;
            case 21:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_21);
                break;
            case 22:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_22);
                break;
            case 23:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_23);
                break;
            case 24:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_24);
                break;
            case 25:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_25);
                break;
            case 26:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_26);
                break;
            case 27:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_27);
                break;
            case 28:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_28);
                break;
            case 29:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_29);
                break;
            case 30:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_30);
                break;
            case 31:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_31);
                break;
            case 32:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_32);
                break;
            case 33:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_33);
                break;
            case 34:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_34);
                break;
            case 35:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_35);
                break;
            case 36:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_36);
                break;
            case 37:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_37);
                break;
            case 38:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_38);
                break;
            case 39:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_39);
                break;
            case 40:
                mRowTextSize = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_40);
                break;
        }
    }

    private void refreshLockDateFont(KeyguardSliceButton button) {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockDateFont = isPrimary ? getLockDateFont() : -1;
        switch (lockDateFont) {
            case 0:
                button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
            case 1:
                button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case 2:
                button.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                break;
            case 3:
                button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                break;
            case 4:
                button.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case 5:
                button.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case 6:
                button.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                break;
            case 7:
                button.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case 8:
                button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case 9:
                button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                break;
            case 10:
                button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case 11:
                button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;
            case 12:
                button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case 13:
                button.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                break;
            case 14:
                button.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                break;
            case 15:
                button.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                break;
            case 16:
                button.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                break;
            case 17:
                button.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                break;
            case 18:
                button.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
                break;
            case 19:
                button.setTypeface(Typeface.create("cursive", Typeface.BOLD));
                break;
            case 20:
                button.setTypeface(Typeface.create("casual", Typeface.NORMAL));
                break;
            case 21:
                button.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                break;
            case 22:
                button.setTypeface(Typeface.create("serif", Typeface.ITALIC));
                break;
            case 23:
                button.setTypeface(Typeface.create("serif", Typeface.BOLD));
                break;
            case 24:
                button.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                break;
            case 25:
                button.setTypeface(Typeface.create("gobold-light-sys", Typeface.NORMAL));
                break;
            case 26:
                button.setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
                break;
            case 27:
                button.setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
                break;
            case 28:
                button.setTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
                break;
            case 29:
                button.setTypeface(Typeface.create("neoneon-sys", Typeface.NORMAL));
                break;
            case 30:
                button.setTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
                break;
            case 31:
                button.setTypeface(Typeface.create("samsung-sys", Typeface.NORMAL));
                break;
            case 32:
                button.setTypeface(Typeface.create("mexcellent-sys", Typeface.NORMAL));
                break;
            case 33:
                button.setTypeface(Typeface.create("burnstown-sys", Typeface.NORMAL));
                break;
            case 34:
                button.setTypeface(Typeface.create("dumbledor-sys", Typeface.NORMAL));
                break;
            case 35:
                button.setTypeface(Typeface.create("phantombold-sys", Typeface.NORMAL));
                break;
            default: // set desired default font here
                button.setTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
                break;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardSliceView:");
        pw.println("  mClickActions: " + mClickActions);
        pw.println("  mTitle: " + (mTitle == null ? "null" : mTitle.getVisibility() == VISIBLE));
        pw.println("  mRow: " + (mRow == null ? "null" : mRow.getVisibility() == VISIBLE));
        pw.println("  mTextColor: " + Integer.toHexString(mTextColor));
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mSlice: " + mSlice);
        pw.println("  mHasHeader: " + mHasHeader);
    }

    public static class Row extends LinearLayout {

        /**
         * This view is visible in AOD, which means that the device will sleep if we
         * don't hold a wake lock. We want to enter doze only after all views have reached
         * their desired positions.
         */
        private final Animation.AnimationListener mKeepAwakeListener;
        private LayoutTransition mLayoutTransition;
        private float mDarkAmount;

        public Row(Context context) {
            this(context, null);
        }

        public Row(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
            mKeepAwakeListener = new KeepAwakeAnimationListener(mContext);
        }

        @Override
        protected void onFinishInflate() {
            mLayoutTransition = new LayoutTransition();
            mLayoutTransition.setDuration(DEFAULT_ANIM_DURATION);

            PropertyValuesHolder left = PropertyValuesHolder.ofInt("left", 0, 1);
            PropertyValuesHolder right = PropertyValuesHolder.ofInt("right", 0, 1);
            ObjectAnimator changeAnimator = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                    left, right);
            mLayoutTransition.setAnimator(LayoutTransition.CHANGE_APPEARING, changeAnimator);
            mLayoutTransition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, changeAnimator);
            mLayoutTransition.setInterpolator(LayoutTransition.CHANGE_APPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            mLayoutTransition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            mLayoutTransition.setStartDelay(LayoutTransition.CHANGE_APPEARING,
                    DEFAULT_ANIM_DURATION);
            mLayoutTransition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING,
                    DEFAULT_ANIM_DURATION);

            ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
            mLayoutTransition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
            mLayoutTransition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

            ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
            mLayoutTransition.setInterpolator(LayoutTransition.DISAPPEARING,
                    Interpolators.ALPHA_OUT);
            mLayoutTransition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 4);
            mLayoutTransition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

            mLayoutTransition.setAnimateParentHierarchy(false);
        }

        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            setLayoutTransition(isVisible ? mLayoutTransition : null);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child instanceof KeyguardSliceButton && childCount > 5) {
                    ((KeyguardSliceButton) child).setMaxWidth(width / childCount);
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        public void setDarkAmount(float darkAmount) {
            boolean isAwake = darkAmount != 0;
            boolean wasAwake = mDarkAmount != 0;
            if (isAwake == wasAwake) {
                return;
            }
            mDarkAmount = darkAmount;
            setLayoutAnimationListener(isAwake ? null : mKeepAwakeListener);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    /**
     * Representation of an item that appears under the clock on main keyguard message.
     */
    @VisibleForTesting
    static class KeyguardSliceButton extends Button implements
            ConfigurationController.ConfigurationListener {

        @StyleRes
        private static int sStyleId = R.style.TextAppearance_Keyguard_Secondary;

        public KeyguardSliceButton(Context context) {
            super(context, null /* attrs */, 0 /* styleAttr */, sStyleId);
            onDensityOrFontScaleChanged();
            setEllipsize(TruncateAt.END);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Dependency.get(ConfigurationController.class).addCallback(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            Dependency.get(ConfigurationController.class).removeCallback(this);
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            updatePadding();
        }

        @Override
        public void onOverlayChanged() {
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            updatePadding();
        }

        private void updatePadding() {
            boolean hasText = !TextUtils.isEmpty(getText());
            int horizontalPadding = (int) getContext().getResources()
                    .getDimension(R.dimen.widget_horizontal_padding) / 2;
            setPadding(horizontalPadding, 0, horizontalPadding * (hasText ? 1 : -1), 0);
            setCompoundDrawablePadding((int) mContext.getResources()
                    .getDimension(R.dimen.widget_icon_padding));
        }

        @Override
        public void setTextColor(int color) {
            super.setTextColor(color);
            updateDrawableColors();
        }

        @Override
        public void setCompoundDrawables(Drawable left, Drawable top, Drawable right,
                Drawable bottom) {
            super.setCompoundDrawables(left, top, right, bottom);
            updateDrawableColors();
            updatePadding();
        }

        private void updateDrawableColors() {
            final int color = getCurrentTextColor();
            for (Drawable drawable : getCompoundDrawables()) {
                if (drawable != null) {
                    drawable.setTint(color);
                }
            }
        }
    }
}
