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

package com.android.server.infra;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.FgThread;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * Base class representing a remote service.
 *
 * <p>It abstracts away the binding and unbinding from the remote implementation, so clients can
 * call its methods without worrying about when and how to bind/unbind/timeout.
 *
 * <p>All state of this class is modified on a handler thread.
 *
 * <p><b>NOTE: </b>this class should not be extended directly, you should extend either
 * {@link AbstractSinglePendingRequestRemoteService} or
 * {@link AbstractMultiplePendingRequestsRemoteService}.
 *
 * <p>See {@code com.android.server.autofill.RemoteFillService} for a concrete
 * (no pun intended) example of how to use it.
 *
 * @param <S> the concrete remote service class
 * @param <I> the interface of the binder service
 *
 * @hide
 */
//TODO(b/117779333): improve javadoc above instead of using Autofill as an example
public abstract class AbstractRemoteService<S extends AbstractRemoteService<S, I>,
        I extends IInterface> implements DeathRecipient {
    private static final int MSG_UNBIND = 1;

    protected static final int LAST_PRIVATE_MSG = MSG_UNBIND;

    // TODO(b/117779333): convert all booleans into an integer / flags
    public final boolean mVerbose;

    protected final String mTag = getClass().getSimpleName();
    protected final Handler mHandler;
    protected final ComponentName mComponentName;

    private final Context mContext;
    private final Intent mIntent;
    private final VultureCallback<S> mVultureCallback;
    private final int mUserId;
    private final ServiceConnection mServiceConnection = new RemoteServiceConnection();
    private final boolean mBindInstantServiceAllowed;
    protected I mService;

    private boolean mBinding;
    private boolean mDestroyed;
    private boolean mServiceDied;
    private boolean mCompleted;

    /**
     * Callback called when the service dies.
     *
     * @param <T> service class
     */
    public interface VultureCallback<T> {
        /**
         * Called when the service dies.
         *
         * @param service service that died!
         */
        void onServiceDied(T service);
    }

    // NOTE: must be package-protected so this class is not extend outside
    AbstractRemoteService(@NonNull Context context, @NonNull String serviceInterface,
            @NonNull ComponentName componentName, int userId, @NonNull VultureCallback<S> callback,
            boolean bindInstantServiceAllowed, boolean verbose) {
        mContext = context;
        mVultureCallback = callback;
        mVerbose = verbose;
        mComponentName = componentName;
        mIntent = new Intent(serviceInterface).setComponent(mComponentName);
        mUserId = userId;
        mHandler = new Handler(FgThread.getHandler().getLooper());
        mBindInstantServiceAllowed = bindInstantServiceAllowed;
    }

    /**
     * Destroys this service.
     */
    public final void destroy() {
        mHandler.sendMessage(obtainMessage(AbstractRemoteService::handleDestroy, this));
    }

    /**
     * Checks whether this service is destroyed.
     */
    public final boolean isDestroyed() {
        return mDestroyed;
    }

    private void handleOnConnectedStateChangedInternal(boolean connected) {
        if (connected) {
            handlePendingRequests();
        }
        handleOnConnectedStateChanged(connected);
    }

    /**
     * Handles the pending requests when the connection it bounds to the remote service.
     */
    abstract void handlePendingRequests();

    /**
     * Callback called when the system connected / disconnected to the service and the pending
     * requests have been handled.
     *
     * @param state {@code true} when connected, {@code false} when disconnected.
     */
    protected void handleOnConnectedStateChanged(boolean state) {
    }

    /**
     * Gets the base Binder interface from the service.
     */
    @NonNull
    protected abstract I getServiceInterface(@NonNull IBinder service);

    /**
     * Defines How long after the last interaction with the service we would unbind.
     */
    protected abstract long getTimeoutIdleBindMillis();

    /**
     * Defines how long after we make a remote request to a fill service we timeout.
     */
    protected abstract long getRemoteRequestMillis();

    private void handleDestroy() {
        if (checkIfDestroyed()) return;
        handleOnDestroy();
        handleEnsureUnbound();
        mDestroyed = true;
    }

    /**
     * Clears the state when this object is destroyed.
     *
     * <p>Typically used to cancel the pending requests.
     */
    protected abstract void handleOnDestroy();

    @Override // from DeathRecipient
    public void binderDied() {
        mHandler.sendMessage(obtainMessage(AbstractRemoteService::handleBinderDied, this));
    }

    private void handleBinderDied() {
        if (checkIfDestroyed()) return;
        if (mService != null) {
            mService.asBinder().unlinkToDeath(this, 0);
        }
        mService = null;
        mServiceDied = true;
        @SuppressWarnings("unchecked") // TODO(b/117779333): fix this warning
        final S castService = (S) this;
        mVultureCallback.onServiceDied(castService);
    }

    // Note: we are dumping without a lock held so this is a bit racy but
    // adding a lock to a class that offloads to a handler thread would
    // mean adding a lock adding overhead to normal runtime operation.
    /**
     * Dump it!
     */
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        String tab = "  ";
        pw.append(prefix).append("service:").println();
        pw.append(prefix).append(tab).append("userId=")
                .append(String.valueOf(mUserId)).println();
        pw.append(prefix).append(tab).append("componentName=")
                .append(mComponentName.flattenToString()).println();
        pw.append(prefix).append(tab).append("destroyed=")
                .append(String.valueOf(mDestroyed)).println();
        pw.append(prefix).append(tab).append("bound=")
                .append(String.valueOf(handleIsBound())).println();
        pw.append(prefix).append("mBindInstantServiceAllowed=").println(mBindInstantServiceAllowed);
        pw.append(prefix).append("idleTimeout=")
            .append(Long.toString(getTimeoutIdleBindMillis() / 1000)).append("s").println();
        pw.append(prefix).append("requestTimeout=")
            .append(Long.toString(getRemoteRequestMillis() / 1000)).append("s").println();
        pw.println();
    }

    /**
     * Schedules a "sync" request.
     *
     * <p>This request must be responded by the service somehow (typically using a callback),
     * othewise it will trigger a {@link PendingRequest#onTimeout(AbstractRemoteService)} if the
     * service doesn't respond.
     */
    protected void scheduleRequest(@NonNull PendingRequest<S, I> pendingRequest) {
        cancelScheduledUnbind();
        mHandler.sendMessage(obtainMessage(
                AbstractRemoteService::handlePendingRequest, this, pendingRequest));
    }

    /**
     * Schedules an async request.
     *
     * <p>This request is not expecting a callback from the service, hence it's represented by
     * a simple {@link Runnable}.
     */
    protected void scheduleAsyncRequest(@NonNull AsyncRequest<I> request) {
        cancelScheduledUnbind();
        // TODO(b/117779333): fix generics below
        @SuppressWarnings({"unchecked", "rawtypes"})
        final MyAsyncPendingRequest<S, I> asyncRequest = new MyAsyncPendingRequest(this, request);
        mHandler.sendMessage(
                obtainMessage(AbstractRemoteService::handlePendingRequest, this, asyncRequest));
    }

    private void cancelScheduledUnbind() {
        mHandler.removeMessages(MSG_UNBIND);
    }

    protected void scheduleUnbind() {
        cancelScheduledUnbind();
        // TODO(b/111276913): implement "permanent binding"
        // TODO(b/117779333): make sure it's unbound if the service settings changing (right now
        // it's not)
        mHandler.sendMessageDelayed(obtainMessage(AbstractRemoteService::handleUnbind, this)
                .setWhat(MSG_UNBIND), getTimeoutIdleBindMillis());
    }

    private void handleUnbind() {
        if (checkIfDestroyed()) return;

        handleEnsureUnbound();
    }

    /**
     * Handles a request, either processing it right now when bound, or saving it to be handled when
     * bound.
     */
    protected final void handlePendingRequest(@NonNull PendingRequest<S, I> pendingRequest) {
        if (checkIfDestroyed() || mCompleted) return;

        if (!handleIsBound()) {
            if (mVerbose) Slog.v(mTag, "handlePendingRequest(): queuing " + pendingRequest);
            handlePendingRequestWhileUnBound(pendingRequest);
            handleEnsureBound();
        } else {
            if (mVerbose) Slog.v(mTag, "handlePendingRequest(): " + pendingRequest);
            pendingRequest.run();
            if (pendingRequest.isFinal()) {
                mCompleted = true;
            }
        }
    }

    /**
     * Defines what to do with a request that arrives while not bound to the service.
     */
    abstract void handlePendingRequestWhileUnBound(@NonNull PendingRequest<S, I> pendingRequest);

    private boolean handleIsBound() {
        return mService != null;
    }

    private void handleEnsureBound() {
        if (handleIsBound() || mBinding) return;

        if (mVerbose) Slog.v(mTag, "ensureBound()");
        mBinding = true;

        int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
        if (mBindInstantServiceAllowed) {
            flags |= Context.BIND_ALLOW_INSTANT;
        }

        final boolean willBind = mContext.bindServiceAsUser(mIntent, mServiceConnection, flags,
                mHandler, new UserHandle(mUserId));

        if (!willBind) {
            Slog.w(mTag, "could not bind to " + mIntent + " using flags " + flags);
            mBinding = false;

            if (!mServiceDied) {
                handleBinderDied();
            }
        }
    }

    private void handleEnsureUnbound() {
        if (!handleIsBound() && !mBinding) return;

        if (mVerbose) Slog.v(mTag, "ensureUnbound()");
        mBinding = false;
        if (handleIsBound()) {
            handleOnConnectedStateChangedInternal(false);
            if (mService != null) {
                mService.asBinder().unlinkToDeath(this, 0);
                mService = null;
            }
        }
        mContext.unbindService(mServiceConnection);
    }

    private class RemoteServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mVerbose) Slog.v(mTag, "onServiceConnected()");
            if (mDestroyed || !mBinding) {
                // This is abnormal. Unbinding the connection has been requested already.
                Slog.wtf(mTag, "onServiceConnected() was dispatched after unbindService.");
                return;
            }
            mBinding = false;
            mService = getServiceInterface(service);
            try {
                service.linkToDeath(AbstractRemoteService.this, 0);
            } catch (RemoteException re) {
                handleBinderDied();
                return;
            }
            handleOnConnectedStateChangedInternal(true);
            mServiceDied = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinding = true;
            mService = null;
        }
    }

    private boolean checkIfDestroyed() {
        if (mDestroyed) {
            if (mVerbose) {
                Slog.v(mTag, "Not handling operation as service for " + mComponentName
                        + " is already destroyed");
            }
        }
        return mDestroyed;
    }

    /**
     * Base class for the requests serviced by the remote service.
     *
     * <p><b>NOTE: </b> this class is typically used when the service needs to use a callback to
     * communicate back with the system server. For cases where that's not needed, you should use
     * {@link AbstractRemoteService#scheduleAsyncRequest(AsyncRequest)} instead.
     *
     * @param <S> the remote service class
     * @param <I> the interface of the binder service
     */
    public abstract static class PendingRequest<S extends AbstractRemoteService<S, I>,
            I extends IInterface> implements Runnable {
        protected final String mTag = getClass().getSimpleName();
        protected final Object mLock = new Object();

        private final WeakReference<S> mWeakService;
        private final Runnable mTimeoutTrigger;
        private final Handler mServiceHandler;

        @GuardedBy("mLock")
        private boolean mCancelled;

        @GuardedBy("mLock")
        private boolean mCompleted;

        protected PendingRequest(@NonNull S service) {
            mWeakService = new WeakReference<>(service);
            mServiceHandler = service.mHandler;
            mTimeoutTrigger = () -> {
                synchronized (mLock) {
                    if (mCancelled) {
                        return;
                    }
                    mCompleted = true;
                }

                final S remoteService = mWeakService.get();
                if (remoteService != null) {
                    // TODO(b/117779333): we should probably ignore it if service is destroyed.
                    Slog.w(mTag, "timed out after " + service.getRemoteRequestMillis() + " ms");
                    onTimeout(remoteService);
                } else {
                    Slog.w(mTag, "timed out (no service)");
                }
            };
            mServiceHandler.postAtTime(mTimeoutTrigger,
                    SystemClock.uptimeMillis() + service.getRemoteRequestMillis());
        }

        /**
         * Gets a reference to the remote service.
         */
        protected final S getService() {
            return mWeakService.get();
        }

        /**
         * Subclasses must call this method when the remote service finishes, i.e., when the service
         * finishes processing a request.
         *
         * @return {@code false} in the service is already finished, {@code true} otherwise.
         */
        protected final boolean finish() {
            synchronized (mLock) {
                if (mCompleted || mCancelled) {
                    return false;
                }
                mCompleted = true;
            }
            mServiceHandler.removeCallbacks(mTimeoutTrigger);
            return true;
        }

        /**
         * Checks whether this request was cancelled.
         */
        @GuardedBy("mLock")
        protected final boolean isCancelledLocked() {
            return mCancelled;
        }

        /**
         * Cancels the service.
         *
         * @return {@code false} if service is already canceled, {@code true} otherwise.
         */
        public boolean cancel() {
            synchronized (mLock) {
                if (mCancelled || mCompleted) {
                    return false;
                }
                mCancelled = true;
            }

            mServiceHandler.removeCallbacks(mTimeoutTrigger);
            return true;
        }

        /**
         * Called by the self-destruct timeout when the remote service didn't reply to the
         * request on time.
         */
        protected abstract void onTimeout(S remoteService);

        /**
         * Checks whether this request leads to a final state where no other requests can be made.
         */
        protected boolean isFinal() {
            return false;
        }
    }

    /**
     * Represents a request that does not expect a callback from the remote service.
     *
     * @param <I> the interface of the binder service
     */
    public interface AsyncRequest<I extends IInterface> {

        /**
         * Run Forrest, run!
         */
        void run(@NonNull I binder) throws RemoteException;
    }

    private static final class MyAsyncPendingRequest<S extends AbstractRemoteService<S, I>,
            I extends IInterface> extends PendingRequest<S, I> {
        private static final String TAG = MyAsyncPendingRequest.class.getSimpleName();

        private final AsyncRequest<I> mRequest;

        protected MyAsyncPendingRequest(@NonNull S service, @NonNull AsyncRequest<I> request) {
            super(service);

            mRequest = request;
        }

        @Override
        public void run() {
            final S remoteService = getService();
            if (remoteService == null) return;
            try {
                mRequest.run(remoteService.mService);
            } catch (RemoteException e) {
                Slog.w(TAG, "exception handling async request (" + this + "): " + e);
            } finally {
                finish();
            }
        }

        @Override
        protected void onTimeout(S remoteService) {
            // TODO(b/117779333): should not happen because we called finish() on run(), although
            // currently it might be called if the service is destroyed while showing it.
            Slog.w(TAG, "AsyncPending requested timed out");
        }
    }
}