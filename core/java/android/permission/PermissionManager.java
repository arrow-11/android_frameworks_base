/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.permission;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;

import com.android.internal.annotations.Immutable;

import java.util.Arrays;
import java.util.List;

/**
 * System level service for accessing the permission capabilities of the platform.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.PERMISSION_SERVICE)
public final class PermissionManager {
    /**
     * {@link android.content.pm.PackageParser} needs access without having a {@link Context}.
     *
     * @hide
     */
    public static final SplitPermissionInfo[] SPLIT_PERMISSIONS = new SplitPermissionInfo[]{
            // READ_EXTERNAL_STORAGE is always required when an app requests
            // WRITE_EXTERNAL_STORAGE, because we can't have an app that has
            // write access without read access.  The hack here with the target
            // target SDK version ensures that this grant is always done.
            new SplitPermissionInfo(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    android.os.Build.VERSION_CODES.CUR_DEVELOPMENT + 1),
            new SplitPermissionInfo(android.Manifest.permission.READ_CONTACTS,
                    new String[]{android.Manifest.permission.READ_CALL_LOG},
                    android.os.Build.VERSION_CODES.JELLY_BEAN),
            new SplitPermissionInfo(android.Manifest.permission.WRITE_CONTACTS,
                    new String[]{android.Manifest.permission.WRITE_CALL_LOG},
                    android.os.Build.VERSION_CODES.JELLY_BEAN),
            new SplitPermissionInfo(Manifest.permission.ACCESS_FINE_LOCATION,
                    new String[]{android.Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    android.os.Build.VERSION_CODES.P0),
            new SplitPermissionInfo(Manifest.permission.ACCESS_COARSE_LOCATION,
                    new String[]{android.Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    android.os.Build.VERSION_CODES.P0)};

    private final @NonNull Context mContext;

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @hide
     */
    public PermissionManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Get list of permissions that have been split into more granular or dependent permissions.
     *
     * <p>E.g. before {@link android.os.Build.VERSION_CODES#P0} an app that was granted
     * {@link Manifest.permission#ACCESS_COARSE_LOCATION} could access he location while it was in
     * foreground and background. On platforms after {@link android.os.Build.VERSION_CODES#P0}
     * the location permission only grants location access while the app is in foreground. This
     * would break apps that target before {@link android.os.Build.VERSION_CODES#P0}. Hence whenever
     * such an old app asks for a location permission (i.e. the
     * {@link SplitPermissionInfo#getRootPermission()}), then the
     * {@link Manifest.permission#ACCESS_BACKGROUND_LOCATION} permission (inside
     * {@{@link SplitPermissionInfo#getNewPermissions}) is added.
     *
     * <p>Note: Regular apps do not have to worry about this. The platform and permission controller
     * automatically add the new permissions where needed.
     *
     * @return All permissions that are split.
     */
    public @NonNull List<SplitPermissionInfo> getSplitPermissions() {
        return Arrays.asList(SPLIT_PERMISSIONS);
    }

    /**
     * A permission that was added in a previous API level might have split into several
     * permissions. This object describes one such split.
     */
    @Immutable
    public static final class SplitPermissionInfo {
        private final @NonNull String mRootPerm;
        private final @NonNull String[] mNewPerms;
        private final int mTargetSdk;

        /**
         * Get the permission that is split.
         */
        public @NonNull String getRootPermission() {
            return mRootPerm;
        }

        /**
         * Get the permissions that are added.
         */
        public @NonNull String[] getNewPermissions() {
            return mNewPerms;
        }

        /**
         * Get the target API level when the permission was split.
         */
        public int getTargetSdk() {
            return mTargetSdk;
        }

        private SplitPermissionInfo(@NonNull String rootPerm, @NonNull String[] newPerms,
                int targetSdk) {
            mRootPerm = rootPerm;
            mNewPerms = newPerms;
            mTargetSdk = targetSdk;
        }
    }
}