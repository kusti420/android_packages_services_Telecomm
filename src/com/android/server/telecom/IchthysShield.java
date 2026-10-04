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

package com.android.server.telecom;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.Log;
import android.text.TextUtils;

import java.util.List;

/**
 * IchthysOS: the call "shields" Ichthys Guard switches on and off.
 *
 * <p>Two features live here:
 * <ul>
 *     <li>{@link #FEATURE_CALL}: a call an app places with {@code CALL_PHONE} — through
 *     {@code TelecomManager#placeCall} or an {@code ACTION_CALL} intent — waits for the user to
 *     confirm the app and the number in a TelecomUi dialog. Toll fraud and silent calls need the
 *     call to go out without the user noticing; one dialog is enough to stop that.</li>
 *     <li>{@link #FEATURE_ANSWER_CALLS}: {@code ANSWER_PHONE_CALLS} is refused, so an app cannot
 *     covertly pick up (a live bug) or hang up calls. The app sees exactly what it sees when the
 *     app op is denied on a stock phone.</li>
 * </ul>
 *
 * <p><b>Who is never restricted.</b> The platform and the rest of the core system (any app ID below
 * {@link Process#FIRST_APPLICATION_UID}), preinstalled apps ({@link ApplicationInfo#FLAG_SYSTEM} or
 * {@link ApplicationInfo#FLAG_UPDATED_SYSTEM_APP}) and platform-signed apps (the rule
 * {@code com.android.bluetooth.ichthys.RadioPolicy} uses), the default and system dialer, the
 * default SMS app, and packages the user listed in the per-user {@link Settings.Secure} key
 * {@code ichthys_<feature>_allowed_pkgs} (comma-separated). That key is the hook Ichthys Guard uses
 * for per-app exceptions and is empty by default. Emergency numbers are never held back.
 *
 * <p><b>On unless switched off.</b> Each feature has a per-user {@link Settings.Secure} switch
 * {@code ichthys_<feature>_shield}; unset or non-zero is on, 0 is off.
 *
 * <p><b>Unreadable means allowed.</b> If a setting or the role holders cannot be read, the caller
 * is let through: a shield that fails closed would break the phone app, which is exactly what
 * must keep working.
 */
public final class IchthysShield {

    private static final String TAG = "IchthysShield";

    /** Feature name for confirming app-placed calls. */
    public static final String FEATURE_CALL = "call";

    /** Feature name for refusing {@code ANSWER_PHONE_CALLS}. */
    public static final String FEATURE_ANSWER_CALLS = "answer_calls";

    private static final String SHIELD_SETTING_PREFIX = "ichthys_";
    private static final String SHIELD_SETTING_SUFFIX = "_shield";
    private static final String ALLOWED_PACKAGES_SETTING_SUFFIX = "_allowed_pkgs";

    /**
     * Set by TelecomUi on a {@code placeCall} the user has just confirmed. Only trusted when the
     * caller is TelecomUi itself. Keep in sync with {@code IchthysCallConfirmActivity} and
     * TelecomUi's {@code UserCallActivity}.
     */
    public static final String EXTRA_CALL_CONFIRMED =
            "org.ichthys.telecom.extra.CALL_CONFIRMED";

    /**
     * Package and UID of the app that sent an {@code ACTION_CALL} intent. The intent reaches
     * Telecom as a {@code placeCall} from TelecomUi, so TelecomShim records who really launched it.
     * Only trusted when the caller is TelecomUi, which drops both unless TelecomShim started it.
     * Keep in sync with TelecomShim's and TelecomUi's {@code UserCallActivity}.
     */
    public static final String EXTRA_ORIGIN_PACKAGE = "org.ichthys.telecom.extra.ORIGIN_PACKAGE";
    public static final String EXTRA_ORIGIN_UID = "org.ichthys.telecom.extra.ORIGIN_UID";

    /** Extras for {@link #CONFIRM_ACTIVITY}. */
    public static final String EXTRA_CONFIRM_HANDLE = "org.ichthys.telecom.extra.CONFIRM_HANDLE";
    public static final String EXTRA_CONFIRM_EXTRAS = "org.ichthys.telecom.extra.CONFIRM_EXTRAS";
    public static final String EXTRA_CONFIRM_APP_LABEL =
            "org.ichthys.telecom.extra.CONFIRM_APP_LABEL";

    /** The TelecomUi activity that asks the user to confirm an app-placed call. */
    public static final String CONFIRM_ACTIVITY =
            "com.android.server.telecomui.ui.IchthysCallConfirmActivity";

    private IchthysShield() {}

    /** The per-user switch for {@code feature}, e.g. {@code ichthys_call_shield}. */
    public static String getShieldSetting(String feature) {
        return SHIELD_SETTING_PREFIX + feature + SHIELD_SETTING_SUFFIX;
    }

    /** The per-user allowlist for {@code feature}, e.g. {@code ichthys_call_allowed_pkgs}. */
    public static String getAllowedPackagesSetting(String feature) {
        return SHIELD_SETTING_PREFIX + feature + ALLOWED_PACKAGES_SETTING_SUFFIX;
    }

    /**
     * Whether {@code feature} must be applied to {@code packageName} running as {@code uid}.
     *
     * @param isDefaultOrSystemDialer whether the caller already resolved {@code packageName} as the
     *     default or system dialer of its user; Telecom keeps that in {@link DefaultDialerCache}
     */
    public static boolean isRestricted(Context context, String feature, int uid,
            String packageName, boolean isDefaultOrSystemDialer) {
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID || isDefaultOrSystemDialer) {
            return false;
        }
        final UserHandle user = UserHandle.getUserHandleForUid(uid);
        final long token = Binder.clearCallingIdentity();
        try {
            if (!isShieldOn(context, feature, user)) {
                return false;
            }
            if (TextUtils.isEmpty(packageName)) {
                // An app we cannot name is not one the user can have exempted, and its uid is a
                // stand-in that says nothing about whether it is a system app.
                return true;
            }
            return !isSystemApp(context, uid, user)
                    && !isDefaultSmsApp(context, packageName, user)
                    && !isUserAllowed(context, feature, packageName, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isShieldOn(Context context, String feature, UserHandle user) {
        try {
            return Settings.Secure.getInt(
                    context.createContextAsUser(user, 0).getContentResolver(),
                    getShieldSetting(feature), 1) != 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "isShieldOn: cannot read %s: %s", getShieldSetting(feature), e);
            return false;
        }
    }

    /**
     * Whether {@code uid} belongs to a preinstalled app ({@link ApplicationInfo#FLAG_SYSTEM} or
     * {@link ApplicationInfo#FLAG_UPDATED_SYSTEM_APP}) or to one signed with the platform key.
     * Call it with the calling identity cleared.
     */
    private static boolean isSystemApp(Context context, int uid, UserHandle user) {
        try {
            final PackageManager pm = context.createContextAsUser(user, 0).getPackageManager();
            if (pm.checkSignatures(Process.SYSTEM_UID, uid) == PackageManager.SIGNATURE_MATCH) {
                return true;
            }
            final String[] packages = pm.getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (String pkg : packages) {
                try {
                    final ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    if ((info.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // Raced with an uninstall; treat as not preinstalled.
                }
            }
            return false;
        } catch (RuntimeException e) {
            // Unreadable means allowed.
            Log.w(TAG, "isSystemApp: cannot resolve uid %d: %s", uid, e);
            return true;
        }
    }

    private static boolean isUserAllowed(Context context, String feature, String packageName,
            UserHandle user) {
        final String allowed;
        try {
            allowed = Settings.Secure.getString(
                    context.createContextAsUser(user, 0).getContentResolver(),
                    getAllowedPackagesSetting(feature));
        } catch (RuntimeException e) {
            Log.w(TAG, "isUserAllowed: cannot read %s: %s",
                    getAllowedPackagesSetting(feature), e);
            return true;
        }
        if (TextUtils.isEmpty(allowed)) {
            return false;
        }
        for (String pkg : allowed.split(",")) {
            if (packageName.equals(pkg.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDefaultSmsApp(Context context, String packageName, UserHandle user) {
        try {
            final RoleManager roleManager = context.getSystemService(RoleManager.class);
            if (roleManager == null) {
                return true;
            }
            final List<String> holders = roleManager.getRoleHoldersAsUser(
                    RoleManager.ROLE_SMS, user);
            return holders != null && holders.contains(packageName);
        } catch (RuntimeException e) {
            Log.w(TAG, "isDefaultSmsApp: cannot read role holders: %s", e);
            return true;
        }
    }
}
