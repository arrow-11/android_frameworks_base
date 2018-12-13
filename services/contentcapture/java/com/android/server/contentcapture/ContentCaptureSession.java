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
package com.android.server.contentcapture;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.service.contentcapture.ContentCaptureService;
import android.service.contentcapture.InteractionContext;
import android.service.contentcapture.InteractionSessionId;
import android.service.contentcapture.SnapshotData;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.contentcapture.RemoteContentCaptureService.ContentCaptureServiceCallbacks;

import java.io.PrintWriter;
import java.util.List;

final class ContentCaptureSession implements ContentCaptureServiceCallbacks {

    private static final String TAG = "ContentCaptureSession";

    private final Object mLock;
    final IBinder mActivityToken;
    private final ContentCapturePerUserService mService;
    private final RemoteContentCaptureService mRemoteService;
    private final InteractionContext mInterationContext;
    private final String mId;

    ContentCaptureSession(@NonNull Context context, int userId, @NonNull Object lock,
            @NonNull IBinder activityToken, @NonNull ContentCapturePerUserService service,
            @NonNull ComponentName serviceComponentName, @NonNull ComponentName appComponentName,
            int taskId, int displayId, @NonNull String sessionId, int flags,
            boolean bindInstantServiceAllowed, boolean verbose) {
        mLock = lock;
        mActivityToken = activityToken;
        mService = service;
        mId = Preconditions.checkNotNull(sessionId);
        mRemoteService = new RemoteContentCaptureService(context,
                ContentCaptureService.SERVICE_INTERFACE, serviceComponentName, userId, this,
                bindInstantServiceAllowed, verbose);
        mInterationContext = new InteractionContext(appComponentName, taskId, displayId, flags);
    }

    /**
     * Returns whether this session is for the given activity.
     */
    boolean isActivitySession(@NonNull IBinder activityToken) {
        return mActivityToken.equals(activityToken);
    }

    /**
     * Notifies the {@link ContentCaptureService} that the service started.
     */
    @GuardedBy("mLock")
    public void notifySessionStartedLocked() {
        mRemoteService.onSessionLifecycleRequest(mInterationContext, mId);
    }

    /**
     * Notifies the {@link ContentCaptureService} of a batch of events.
     */
    public void sendEventsLocked(@NonNull List<ContentCaptureEvent> events) {
        mRemoteService.onContentCaptureEventsRequest(mId, events);
    }

    /**
     * Notifies the {@link ContentCaptureService} of a snapshot of an activity.
     */
    @GuardedBy("mLock")
    public void sendActivitySnapshotLocked(@NonNull SnapshotData snapshotData) {
        mRemoteService.onActivitySnapshotRequest(mId, snapshotData);
    }

    /**
     * Cleans up the session and removes it from the service.
     *
     * @param notifyRemoteService whether it should trigger a {@link
     * ContentCaptureService#onDestroyInteractionSession(InteractionSessionId)}
     * request.
     */
    @GuardedBy("mLock")
    public void removeSelfLocked(boolean notifyRemoteService) {
        try {
            destroyLocked(notifyRemoteService);
        } finally {
            mService.removeSessionLocked(mId);
        }
    }

    /**
     * Cleans up the session, but not removes it from the service.
     *
     * @param notifyRemoteService whether it should trigger a {@link
     * ContentCaptureService#onDestroyInteractionSession(InteractionSessionId)}
     * request.
     */
    @GuardedBy("mLock")
    public void destroyLocked(boolean notifyRemoteService) {
        if (mService.isVerbose()) {
            Slog.v(TAG, "destroyLocked(notifyRemoteService=" + notifyRemoteService + ")");
        }
        // TODO(b/111276913): must call client to set session as FINISHED_BY_SERVER
        if (notifyRemoteService) {
            mRemoteService.onSessionLifecycleRequest(/* context= */ null, mId);
        }
    }

    @Override // from RemoteContentCaptureServiceCallbacks
    public void onServiceDied(@NonNull RemoteContentCaptureService service) {
        // TODO(b/111276913): implement (remove session from PerUserSession?)
        if (mService.isDebug()) {
            Slog.d(TAG, "onServiceDied() for " + mId);
        }
        synchronized (mLock) {
            removeSelfLocked(/* notifyRemoteService= */ false);
        }
    }

    @GuardedBy("mLock")
    public void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("id: ");  pw.print(mId); pw.println();
        pw.print(prefix); pw.print("context: ");  mInterationContext.dump(pw); pw.println();
        pw.print(prefix); pw.print("activity token: "); pw.println(mActivityToken);
        pw.print(prefix); pw.print("has autofill callback: ");
    }

    String toShortString() {
        return mId  + ":" + mActivityToken;
    }

    @Override
    public String toString() {
        return "ContentCaptureSession[id=" + mId + ", act=" + mActivityToken + "]";
    }
}