package com.shilapi.xcertplay.hud

import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BluetoothSuspendLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val leaseField = BydBluetoothSuspend::class.java.getDeclaredField("lease").apply { isAccessible = true }
    private val original = leaseField.get(null)
    private val worker = BydBluetoothSuspend::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(null) as ScheduledExecutorService
    private val controllers = mutableListOf<CarPlayController>()

    @After fun tearDown() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        // Finish queued recovery before restoring the production dependency for other tests.
        worker.submit {}.get(2, TimeUnit.SECONDS)
        leaseField.set(null, original)
    }

    @Test fun realControllerCloseCancelsDelayedDisableWithoutSessionEndedCallback() {
        val journal = Journal(); var enabled: Boolean? = true
        val commands = mutableListOf<Boolean>()
        val lease = BluetoothSuspendLease(journal, { enabled }, { commands += it; enabled = it }, { enabled == it })
        leaseField.set(null, lease)
        val controller = controller()
        BydBluetoothSuspend.suspend(app, controller, 60_000)
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(commands.isEmpty())
        assertFalse(journal.recorded)
        assertNotNull("the controller must have retired its active lease", lease.recoveryOnAppOpen())
    }

    @Test fun realControllerCloseRestoresPersistedJournalWithoutAnInMemoryOwner() {
        val journal = Journal().apply { recorded = true }; var enabled: Boolean? = false
        val restored = CountDownLatch(1)
        val lease = BluetoothSuspendLease(journal, { enabled }, {
            enabled = it; if (it) restored.countDown()
        }, { enabled == it })
        leaseField.set(null, lease)
        controller().close()
        assertTrue(restored.await(2, TimeUnit.SECONDS))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
        assertEquals(true, enabled)
    }

    @Test fun realControllerCloseDoesNotWaitForARunningDisableAndRestoresAfterIt() {
        val journal = Journal(); var enabled: Boolean? = true
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val restored = CountDownLatch(1)
        leaseField.set(null, BluetoothSuspendLease(journal, { enabled }, {
            if (!it) { entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)) }
            enabled = it
            if (it) restored.countDown()
        }, { enabled == it }))
        val controller = controller()
        BydBluetoothSuspend.suspend(app, controller, 0)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.close()
            assertTrue("controller close waited for the Bluetooth command", controller.awaitClosed(1_000))
        } finally { proceed.countDown() }
        assertTrue(restored.await(2, TimeUnit.SECONDS))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
        assertEquals(true, enabled)
    }

    @Test fun appOpenRunsCrashRecoveryWithoutAConnectionOrApprovalPrompt() {
        val journal = Journal().apply { recorded = true }; var enabled: Boolean? = false
        val restored = CountDownLatch(1)
        leaseField.set(null, BluetoothSuspendLease(journal, { enabled }, {
            enabled = it; if (it) restored.countDown()
        }, { enabled == it }))
        BydBluetoothSuspend.onAppOpened(app)
        assertTrue(restored.await(2, TimeUnit.SECONDS))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
    }

    @Test fun handshakeRecoveryTimeoutDoesNotClaimReadinessWhileDisableIsRunning() {
        val journal = Journal(); var enabled: Boolean? = true
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        leaseField.set(null, BluetoothSuspendLease(journal, { enabled }, {
            if (!it) { entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)) }
            enabled = it
        }, { enabled == it }))
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        BydBluetoothSuspend.suspend(app, Any(), 0)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(BydBluetoothSuspend.resumeAndWait(app, adapter, 25))
            assertTrue("the still-ON snapshot alone cannot prove recovery", adapter.isEnabled)
        } finally { proceed.countDown() }
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
        assertEquals(true, enabled)
    }

    @Test fun oldControllerCloseCannotCancelNewControllerDelay() {
        val journal = Journal(); var enabled: Boolean? = true
        val lease = BluetoothSuspendLease(journal, { enabled }, { enabled = it }, { enabled == it })
        leaseField.set(null, lease)
        val old = controller(); val current = controller()
        BydBluetoothSuspend.suspend(app, old, 60_000)
        BydBluetoothSuspend.suspend(app, current, 60_000)
        old.close()
        assertTrue(old.awaitClosed(2_000))
        assertNull("current owner must survive the old controller close", lease.recoveryOnAppOpen())
        current.close()
        assertNotNull(lease.recoveryOnAppOpen())
    }

    private class Journal : BluetoothRestoreJournal {
        @Volatile var recorded = false
        override fun pending() = recorded
        override fun write(pending: Boolean): Boolean { recorded = pending; return true }
    }

    private fun controller(): CarPlayController {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int) = false
        }
        return CarPlayController(context, CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {}).also(controllers::add)
    }
}
