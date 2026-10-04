/*
 * Copyright (C) 2026 IchthysOS
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

package com.android.server.telecomui.ui;

import com.android.server.telecomui.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.util.Log;

/**
 * IchthysOS: asks the user before an app's call goes out (the CALL_PHONE shield).
 *
 * <p>Started by Telecom ({@code TelecomServiceImpl#maybeConfirmAppCall}) in place of placing the
 * call. It shows which app wants to call which number; on "Call" it places the call again as
 * TelecomUi, marked confirmed, so Telecom lets it through. Cancelling, backing out or leaving the
 * dialog drops the call, which to the app looks like the user hung up before it connected.
 *
 * <p>Not exported: only Telecom can start it, so the "confirmed" mark it sets cannot be forged.
 * The modelled-on dialog is {@link ConfirmCallDialogActivity}.
 */
public class IchthysCallConfirmActivity extends Activity {
    private static final String TAG = IchthysCallConfirmActivity.class.getSimpleName();

    // Keep in sync with com.android.server.telecom.IchthysShield.
    private static final String EXTRA_CALL_CONFIRMED = "org.ichthys.telecom.extra.CALL_CONFIRMED";
    private static final String EXTRA_CONFIRM_HANDLE = "org.ichthys.telecom.extra.CONFIRM_HANDLE";
    private static final String EXTRA_CONFIRM_EXTRAS = "org.ichthys.telecom.extra.CONFIRM_EXTRAS";
    private static final String EXTRA_CONFIRM_APP_LABEL =
            "org.ichthys.telecom.extra.CONFIRM_APP_LABEL";

    private AlertDialog mDialog;
    private boolean mDecided;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Uri handle = getIntent().getParcelableExtra(EXTRA_CONFIRM_HANDLE, Uri.class);
        if (handle == null) {
            finish();
            return;
        }
        final Bundle extras = getIntent().getBundleExtra(EXTRA_CONFIRM_EXTRAS);
        final String appLabel = getIntent().getStringExtra(EXTRA_CONFIRM_APP_LABEL);
        showDialog(handle, extras, appLabel);
    }

    private void showDialog(final Uri handle, final Bundle extras, String appLabel) {
        Log.i(TAG, "showDialog: confirming a call from " + appLabel);
        final String number = BidiFormatter.getInstance().unicodeWrap(
                handle.getSchemeSpecificPart(), TextDirectionHeuristics.LTR);
        final CharSequence message = TextUtils.isEmpty(appLabel)
                ? getString(R.string.ichthys_confirm_app_call_unknown_app, number)
                : getString(R.string.ichthys_confirm_app_call, appLabel, number);
        mDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ichthys_confirm_app_call_title)
                .setMessage(message)
                .setPositiveButton(R.string.ichthys_confirm_app_call_button,
                        (dialog, which) -> {
                            mDecided = true;
                            placeConfirmedCall(handle, extras);
                            dialog.dismiss();
                            finish();
                        })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    mDecided = true;
                    dialog.dismiss();
                    finish();
                })
                .setOnCancelListener(dialog -> {
                    mDecided = true;
                    finish();
                })
                .create();
        mDialog.show();
    }

    private void placeConfirmedCall(Uri handle, Bundle extras) {
        final TelecomManager tm = getSystemService(TelecomManager.class);
        if (tm == null) {
            Log.w(TAG, "placeConfirmedCall: no TelecomManager");
            return;
        }
        Bundle callExtras;
        try {
            callExtras = extras != null ? new Bundle(extras) : new Bundle();
            callExtras.putBoolean(EXTRA_CALL_CONFIRMED, true);
        } catch (RuntimeException e) {
            // Extras we cannot read are dropped rather than the call.
            Log.w(TAG, "placeConfirmedCall: dropping unreadable extras: " + e);
            callExtras = new Bundle();
            callExtras.putBoolean(EXTRA_CALL_CONFIRMED, true);
        }
        try {
            tm.placeCall(handle, callExtras);
        } catch (SecurityException e) {
            Log.w(TAG, "placeConfirmedCall: " + e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the dialog without answering is a "no": the call must not wait in the background.
        if (!mDecided && !isChangingConfigurations()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
        super.onDestroy();
    }
}
