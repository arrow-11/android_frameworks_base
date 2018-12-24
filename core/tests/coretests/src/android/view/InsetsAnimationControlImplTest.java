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
 * limitations under the License
 */

package android.view;

import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;
import static junit.framework.Assert.assertEquals;
import static org.mockito.Mockito.verify;

import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.FlakyTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseArray;
import android.view.SurfaceControl.Transaction;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class InsetsAnimationControlImplTest {

    private InsetsAnimationControlImpl mController;

    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mTopLeash;
    private SurfaceControl mNavLeash;

    @Mock Transaction mMockTransaction;
    @Mock InsetsController mMockController;
    @Mock WindowInsetsAnimationControlListener mMockListener;
    @Mock SyncRtSurfaceTransactionApplier mMockTransactionApplier;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTopLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        mNavLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        InsetsState state = new InsetsState();
        state.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 500, 100));
        state.getSource(TYPE_NAVIGATION_BAR).setFrame(new Rect(400, 0, 500, 500));
        InsetsSourceConsumer topConsumer = new InsetsSourceConsumer(TYPE_TOP_BAR, state,
                () -> mMockTransaction, mMockController);
        topConsumer.setControl(new InsetsSourceControl(TYPE_TOP_BAR, mTopLeash));

        InsetsSourceConsumer navConsumer = new InsetsSourceConsumer(TYPE_NAVIGATION_BAR, state,
                () -> mMockTransaction, mMockController);
        navConsumer.hide();
        navConsumer.setControl(new InsetsSourceControl(TYPE_NAVIGATION_BAR, mNavLeash));

        SparseArray<InsetsSourceConsumer> consumers = new SparseArray<>();
        consumers.put(TYPE_TOP_BAR, topConsumer);
        consumers.put(TYPE_NAVIGATION_BAR, navConsumer);
        mController = new InsetsAnimationControlImpl(consumers,
                new Rect(0, 0, 500, 500), state, mMockListener, WindowInsets.Type.systemBars(),
                () -> mMockTransactionApplier);
    }

    @Test
    public void testGetters() {
        assertEquals(Insets.of(0, 100, 100, 0), mController.getShownStateInsets());
        assertEquals(Insets.of(0, 0, 0, 0), mController.getHiddenStateInsets());
        assertEquals(Insets.of(0, 100, 0, 0), mController.getCurrentInsets());
        assertEquals(WindowInsets.Type.systemBars(), mController.getTypes());
    }

    @Test
    public void testChangeInsets() {
        mController.changeInsets(Insets.of(0, 30, 40, 0));
        assertEquals(Insets.of(0, 30, 40, 0), mController.getCurrentInsets());

        ArgumentCaptor<SurfaceParams> captor = ArgumentCaptor.forClass(SurfaceParams.class);
        verify(mMockTransactionApplier).scheduleApply(captor.capture());
        List<SurfaceParams> params = captor.getAllValues();
        assertEquals(2, params.size());
        SurfaceParams first = params.get(0);
        SurfaceParams second = params.get(1);
        SurfaceParams topParams = first.surface == mTopLeash ? first : second;
        SurfaceParams navParams = first.surface == mNavLeash ? first : second;
        assertPosition(topParams.matrix, new Rect(0, 0, 500, 100), new Rect(0, -70, 500, 30));
        assertPosition(navParams.matrix, new Rect(400, 0, 500, 500), new Rect(460, 0, 560, 500));
    }

    private void assertPosition(Matrix m, Rect original, Rect transformed) {
        RectF rect = new RectF(original);
        rect.offsetTo(0, 0);
        m.mapRect(rect);
        rect.round(original);
        assertEquals(original, transformed);
    }
}