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
package android.service.autofill.augmented;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillId;

import java.util.List;

/**
 * Response to a {@link FillRequest}.
 *
 * @hide
 */
@SystemApi
public final class FillResponse implements Parcelable {

    private final FillWindow mFillWindow;

    private FillResponse(@NonNull Builder builder) {
        mFillWindow = builder.mFillWindow;
    }

    /** @hide */
    @Nullable
    FillWindow getFillWindow() {
        return mFillWindow;
    }

    /**
     * Builder for {@link FillResponse} objects.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {

        private FillWindow mFillWindow;

        /**
         * Sets the {@link FillWindow} used to display the Autofill UI.
         *
         * <p>Must be called when the service is handling the request so the Android System can
         * properly synchronize the UI.
         *
         * @return this builder
         */
        public Builder setFillWindow(@NonNull FillWindow fillWindow) {
            // TODO(b/111330312): implement / check not null / unit test
            // TODO(b/111330312): throw exception if FillWindow not updated yet
            mFillWindow = fillWindow;
            return this;
        }

        /**
         * Tells the Android System that the given {@code ids} should not trigger further
         * {@link FillRequest requests} when focused.
         *
         * @param ids ids of the fields that should be ignored
         *
         * @return this builder
         */
        public Builder setIgnoredIds(@NonNull List<AutofillId> ids) {
            // TODO(b/111330312): implement / check not null / unit test
            return this;
        }

        /**
         * Builds a new {@link FillResponse} instance.
         *
         * @throws IllegalStateException if any of the following conditions occur:
         * <ol>
         *   <li>{@link #build()} was already called.
         *   <li>No call was made to {@link #setFillWindow(FillWindow)} or
         *   {@ling #setIgnoredIds(List<AutofillId>)}.
         * </ol>
         *
         * @return A built response.
         */
        public FillResponse build() {
            // TODO(b/111330312): check conditions / add unit test
            return new FillResponse(this);
        }

        // TODO(b/111330312): add methods to disable app / activity, either here or on manager
    }

    // TODO(b/111330312): implement to String

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        // TODO(b/111330312): implement
    }

    public static final Parcelable.Creator<FillResponse> CREATOR =
            new Parcelable.Creator<FillResponse>() {

                @Override
                public FillResponse createFromParcel(Parcel parcel) {
                    // TODO(b/111330312): implement
                    return null;
                }

                @Override
                public FillResponse[] newArray(int size) {
                    return new FillResponse[size];
                }
    };
}