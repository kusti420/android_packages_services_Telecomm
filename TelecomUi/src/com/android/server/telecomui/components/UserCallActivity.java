/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.telecomui.components;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Activity in TelecomUi responsible for handling call initiation requests.
 *
 * <p>This activity serves as the entry point for outgoing calls within the TelecomUi module.
 * It is designed to handle the {@code com.android.internal.telecom.action.CALL_TRAMPOLINE} intent,
 * which is used by the system (via {@code TelecomShim}) to bridge {@link Intent#ACTION_CALL}
 * requests from applications into the Mainline module context.
 *
 * <p>Upon receiving the request, this activity initiates the call via
 * {@link TelecomManager#placeCall(android.net.Uri, Bundle)}.
 */
public class UserCallActivity extends Activity {
    private static final String TAG = "UserCallActivity TelecomUi";
    // IchthysOS: CALL_PHONE shield extras, see com.android.server.telecom.IchthysShield.
    private static final String EXTRA_CALL_CONFIRMED = "org.ichthys.telecom.extra.CALL_CONFIRMED";
    private static final String EXTRA_ORIGIN_PACKAGE = "org.ichthys.telecom.extra.ORIGIN_PACKAGE";
    private static final String EXTRA_ORIGIN_UID = "org.ichthys.telecom.extra.ORIGIN_UID";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Log.i(TAG, "Handling call in TelecomUi");
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "UserCallActivity");
        wakelock.acquire();

        try {
            Intent intent = getIntent();
            verifyCallAction(intent);

            TelecomManager tm = getSystemService(TelecomManager.class);
            if (tm != null && intent != null) {
                tm.placeCall(intent.getData(), getShieldedExtras(intent));
            }
        } finally {
            wakelock.release();
        }
        finish();
    }

    /**
     * IchthysOS: the CALL intent's extras, made safe for the CALL_PHONE shield. This activity is
     * exported, so an app could launch it directly and claim the call was already confirmed or
     * came from the dialer. The "confirmed" mark is never accepted from an intent, and the origin
     * is only kept when TelecomShim (system UID, which shares its identity) launched us.
     */
    private Bundle getShieldedExtras(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        extras.remove(EXTRA_CALL_CONFIRMED);
        if (getLaunchedFromUid() != Process.SYSTEM_UID) {
            extras.remove(EXTRA_ORIGIN_PACKAGE);
            extras.remove(EXTRA_ORIGIN_UID);
        }
        return extras;
    }

    private void verifyCallAction(Intent intent) {
        if (getClass().getName().equals(intent.getComponent().getClassName())) {
            // If we were launched directly from the CallActivity, not one of its more privileged
            // aliases, then make sure that only the non-privileged actions are allowed.
            if (!Intent.ACTION_CALL.equals(intent.getAction())) {
                Log.w(TAG, "Attempt to deliver non-CALL action; forcing to CALL");
                intent.setAction(Intent.ACTION_CALL);
            }
        }
    }
}
