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

package com.android.tests.rollback;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test system Rollback APIs.
 * TODO: Should this be a cts test instead? Where should it live?
 */
@RunWith(JUnit4.class)
public class RollbackTest {

    private static final String TAG = "RollbackTest";

    private static final String TEST_APP_A = "com.android.tests.rollback.testapp.A";
    private static final String TEST_APP_B = "com.android.tests.rollback.testapp.B";

    /**
     * Test basic rollbacks.
     */
    @Test
    public void testBasic() throws Exception {
        // Make sure an app can't listen to or disturb the internal
        // ACTION_PACKAGE_ENABLE_ROLLBACK broadcast.
        Context context = InstrumentationRegistry.getContext();
        IntentFilter enableRollbackFilter = new IntentFilter();
        enableRollbackFilter.addAction("android.intent.action.PACKAGE_ENABLE_ROLLBACK");
        enableRollbackFilter.addDataType("application/vnd.android.package-archive");
        enableRollbackFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        BroadcastReceiver enableRollbackReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                abortBroadcast();
            }
        };
        context.registerReceiver(enableRollbackReceiver, enableRollbackFilter);

        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            // Register a broadcast receiver for notification when the rollback is
            // done executing.
            RollbackBroadcastReceiver broadcastReceiver = new RollbackBroadcastReceiver();
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Uninstall TEST_APP_A
            RollbackTestUtils.uninstall(TEST_APP_A);
            assertEquals(-1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // TODO: There is currently a race condition between when the app is
            // uninstalled and when rollback manager deletes the rollback. Fix it
            // so that's not the case!
            for (int i = 0; i < 5; ++i) {
                for (RollbackInfo info : rm.getRecentlyExecutedRollbacks()) {
                    if (TEST_APP_A.equals(info.targetPackage.packageName)) {
                        Log.i(TAG, "Sleeping 1 second to wait for uninstall to take effect.");
                        Thread.sleep(1000);
                        break;
                    }
                }
            }

            // The app should not be available for rollback.
            assertNull(rm.getAvailableRollback(TEST_APP_A));
            assertFalse(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_A));

            // There should be no recently executed rollbacks for this package.
            for (RollbackInfo info : rm.getRecentlyExecutedRollbacks()) {
                assertNotEquals(TEST_APP_A, info.targetPackage.packageName);
            }

            // Install v1 of the app (without rollbacks enabled).
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Upgrade from v1 to v2, with rollbacks enabled.
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // The app should now be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_A));
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_A);
            assertNotNull(rollback);
            assertEquals(TEST_APP_A, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // We should not have received any rollback requests yet.
            // TODO: Possibly flaky if, by chance, some other app on device
            // happens to be rolled back at the same time?
            assertNull(broadcastReceiver.poll(0, TimeUnit.SECONDS));

            // Roll back the app.
            RollbackTestUtils.rollback(rollback);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Verify we received a broadcast for the rollback.
            // TODO: Race condition between the timeout and when the broadcast is
            // received could lead to test flakiness.
            Intent broadcast = broadcastReceiver.poll(5, TimeUnit.SECONDS);
            assertNotNull(broadcast);
            assertEquals(TEST_APP_A, broadcast.getData().getSchemeSpecificPart());
            assertNull(broadcastReceiver.poll(0, TimeUnit.SECONDS));

            // Verify the recent rollback has been recorded.
            rollback = null;
            for (RollbackInfo r : rm.getRecentlyExecutedRollbacks()) {
                if (TEST_APP_A.equals(r.targetPackage.packageName)) {
                    assertNull(rollback);
                    rollback = r;
                }
            }
            assertNotNull(rollback);
            assertEquals(TEST_APP_A, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            broadcastReceiver.unregister();
            context.unregisterReceiver(enableRollbackReceiver);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that rollback data is properly persisted.
     */
    @Test
    public void testRollbackDataPersistence() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Prep installation of TEST_APP_A
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // The app should now be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_A));
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_A);
            assertNotNull(rollback);
            assertEquals(TEST_APP_A, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // The app should still be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_A));
            rollback = rm.getAvailableRollback(TEST_APP_A);
            assertNotNull(rollback);
            assertEquals(TEST_APP_A, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Roll back the app.
            RollbackTestUtils.rollback(rollback);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Verify the recent rollback has been recorded.
            rollback = null;
            for (RollbackInfo r : rm.getRecentlyExecutedRollbacks()) {
                if (TEST_APP_A.equals(r.targetPackage.packageName)) {
                    assertNull(rollback);
                    rollback = r;
                }
            }
            assertNotNull(rollback);
            assertEquals(TEST_APP_A, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // Verify the recent rollback is still recorded.
            rollback = null;
            for (RollbackInfo r : rm.getRecentlyExecutedRollbacks()) {
                if (TEST_APP_A.equals(r.targetPackage.packageName)) {
                    assertNull(rollback);
                    rollback = r;
                }
            }
            assertNotNull(rollback);
            assertEquals(TEST_APP_A, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test explicit expiration of rollbacks.
     * Does not test the scheduling aspects of rollback expiration.
     */
    @Test
    public void testRollbackExpiration() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // The app should now be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_A));
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_A);
            assertNotNull(rollback);
            assertEquals(TEST_APP_A, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Expire the rollback.
            rm.expireRollbackForPackage(TEST_APP_A);

            // The rollback should no longer be available.
            assertNull(rm.getAvailableRollback(TEST_APP_A));
            assertFalse(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_A));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    private static final String NO_RESPONSE = "NO RESPONSE";

    // Calls into the test app to process user data.
    // Asserts if the user data could not be processed or was version
    // incompatible with the previously processed user data.
    private void processUserData(String packageName) throws Exception {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName,
                    "com.android.tests.rollback.testapp.ProcessUserData"));
        Context context = InstrumentationRegistry.getContext();

        HandlerThread handlerThread = new HandlerThread("RollbackTestHandlerThread");
        handlerThread.start();

        // It can sometimes take a while after rollback before the app will
        // receive this broadcast, so try a few times in a loop.
        String result = NO_RESPONSE;
        for (int i = 0; result.equals(NO_RESPONSE) && i < 5; ++i) {
            BlockingQueue<String> resultQueue = new LinkedBlockingQueue<>();
            context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (getResultCode() == 1) {
                        resultQueue.add("OK");
                    } else {
                        // If the test app doesn't receive the broadcast or
                        // fails to set the result data, then getResultData
                        // here returns the initial NO_RESPONSE data passed to
                        // the sendOrderedBroadcast call.
                        resultQueue.add(getResultData());
                    }
                }
            }, new Handler(handlerThread.getLooper()), 0, NO_RESPONSE, null);

            result = resultQueue.poll(10, TimeUnit.SECONDS);
            if (result == null) {
                result = "ProcessUserData broadcast timed out";
            }
        }

        handlerThread.quit();
        if (!"OK".equals(result)) {
            fail(result);
        }
    }

    /**
     * Test that app user data is rolled back.
     * TODO: Stop ignoring this test once user data rollback is supported.
     */
    @Ignore @Test
    public void testUserDataRollback() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppV1.apk", false);
            processUserData(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppV2.apk", true);
            processUserData(TEST_APP_A);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_A);
            RollbackTestUtils.rollback(rollback);
            processUserData(TEST_APP_A);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test restrictions on rollback broadcast sender.
     * A random app should not be able to send a PACKAGE_ROLLBACK_EXECUTED broadcast.
     */
    @Test
    public void testRollbackBroadcastRestrictions() throws Exception {
        RollbackBroadcastReceiver broadcastReceiver = new RollbackBroadcastReceiver();
        Intent broadcast = new Intent(Intent.ACTION_PACKAGE_ROLLBACK_EXECUTED,
                Uri.fromParts("package", "com.android.tests.rollback.bogus", null));
        try {
            InstrumentationRegistry.getContext().sendBroadcast(broadcast);
            fail("Succeeded in sending restricted broadcast from app context.");
        } catch (SecurityException se) {
            // Expected behavior.
        }

        // Confirm that we really haven't received the broadcast.
        // TODO: How long to wait for the expected timeout?
        assertNull(broadcastReceiver.poll(5, TimeUnit.SECONDS));

        // TODO: Do we need to do this? Do we need to ensure this is always
        // called, even when the test fails?
        broadcastReceiver.unregister();
    }

    /**
     * Regression test for rollback in the case when multiple apps are
     * available for rollback at the same time.
     */
    @Test
    public void testMultipleRollbackAvailable() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Prep installation of the test apps.
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.install("RollbackTestAppBv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppBv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // Both test apps should now be available for rollback, and the
            // targetPackage returned for rollback should be correct.
            RollbackInfo rollbackA = rm.getAvailableRollback(TEST_APP_A);
            assertNotNull(rollbackA);
            assertEquals(TEST_APP_A, rollbackA.targetPackage.packageName);

            RollbackInfo rollbackB = rm.getAvailableRollback(TEST_APP_B);
            assertNotNull(rollbackB);
            assertEquals(TEST_APP_B, rollbackB.targetPackage.packageName);

            // Executing rollback should roll back the correct package.
            RollbackTestUtils.rollback(rollbackA);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.rollback(rollbackB);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_B));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that the MANAGE_ROLLBACKS permission is required to call
     * RollbackManager APIs.
     */
    @Test
    public void testManageRollbacksPermission() throws Exception {
        // We shouldn't be allowed to call any of the RollbackManager APIs
        // without the MANAGE_ROLLBACKS permission.
        RollbackManager rm = RollbackTestUtils.getRollbackManager();

        try {
            rm.getAvailableRollback(TEST_APP_A);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.getPackagesWithAvailableRollbacks();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.getRecentlyExecutedRollbacks();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            // TODO: What if the implementation checks arguments for non-null
            // first? Then this test isn't valid.
            rm.executeRollback(null, null);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.reloadPersistedData();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.expireRollbackForPackage(TEST_APP_A);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }
    }

    /**
     * Test rollback of multi-package installs.
     * TODO: Stop ignoring this test once support for multi-package rollback
     * is implemented.
     */
    @Ignore @Test
    public void testMultiPackage() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Prep installation of the test apps.
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.installMultiPackage(false,
                    "RollbackTestAppAv1.apk",
                    "RollbackTestAppBv1.apk");
            RollbackTestUtils.installMultiPackage(true,
                    "RollbackTestAppAv2.apk",
                    "RollbackTestAppBv2.apk");
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // TEST_APP_A should now be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_A));
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_A);
            assertNotNull(rollback);

            // TODO: Test the dependent apps for rollback are correct once we
            // support that in the RollbackInfo API.

            // Rollback the app. It should cause both test apps to be rolled
            // back.
            RollbackTestUtils.rollback(rollback);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // We should not see a recent rollback listed for TEST_APP_B
            for (RollbackInfo r : rm.getRecentlyExecutedRollbacks()) {
                assertNotEquals(TEST_APP_B, r.targetPackage.packageName);
            }

            // TODO: Test the listed dependent apps for the recently executed
            // rollback are correct once we support that in the RollbackInfo
            // API.
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }
}