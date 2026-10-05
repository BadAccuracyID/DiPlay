package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Gt6NightModeTest {
    @Test fun headlightsOverrideAndroidWhileManualModesOverrideHeadlights() {
        assertTrue(Gt6NightMode.resolve(Gt6NightMode.Mode.HEADLIGHTS, true, false))
        assertFalse(Gt6NightMode.resolve(Gt6NightMode.Mode.HEADLIGHTS, false, true))
        assertFalse(Gt6NightMode.resolve(Gt6NightMode.Mode.DAY, true, true))
        assertTrue(Gt6NightMode.resolve(Gt6NightMode.Mode.NIGHT, false, false))
        assertTrue(Gt6NightMode.resolve(Gt6NightMode.Mode.SYSTEM, false, true))
    }
    @Test fun unknownLightStateFallsBackToAndroidInsteadOfGuessingDay() {
        assertTrue(Gt6NightMode.resolve(Gt6NightMode.Mode.HEADLIGHTS, null, true))
        assertFalse(Gt6NightMode.resolve(Gt6NightMode.Mode.HEADLIGHTS, null, false))
        assertNull(Gt6NightMode.parse("true"))
        assertNull(Gt6NightMode.parse("2"))
        assertNull(Gt6NightMode.parse(null))
        assertEquals(true, Gt6NightMode.parse(" 1\n"))
        assertEquals(false, Gt6NightMode.parse("0"))
    }
    @Test fun gt6DefaultIsHeadlightsAndChoicePersists() {
        val app = RuntimeEnvironment.getApplication()
        ShadowBuild.setModel(" GT6-CAR ")
        assertTrue(Gt6NightMode.available())
        assertEquals(Gt6NightMode.Mode.HEADLIGHTS, Gt6NightMode.mode(app))
        Gt6NightMode.save(app, Gt6NightMode.Mode.NIGHT)
        assertEquals(Gt6NightMode.Mode.NIGHT, Gt6NightMode.mode(app))
        ShadowBuild.setModel("Other car")
        assertFalse(Gt6NightMode.available())
    }
    @Test fun monitorReadsOnlyTheOemLightRowAndRespondsToChanges() {
        val app = RuntimeEnvironment.getApplication()
        val provider = LightProvider()
        ShadowContentResolver.registerProviderInternal(Gt6NightMode.URI.authority, provider)
        val states = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        val monitor = Gt6NightMode.Monitor(app) { states.offer(it) }
        monitor.start()
        try {
            assertEquals(true, states.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            provider.value = "0"
            app.contentResolver.notifyChange(Gt6NightMode.URI, null)
            assertEquals(false, states.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            Gt6NightMode.save(app, Gt6NightMode.Mode.NIGHT)
            app.contentResolver.notifyChange(Gt6NightMode.URI, null)
            assertEquals(true, states.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, provider.writes)
        } finally { monitor.stop() }
    }
    private class LightProvider : ContentProvider() {
        @Volatile var value = "1"
        var writes = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            assertEquals(Gt6NightMode.URI, uri)
            assertEquals("keyname=?", selection)
            assertArrayEquals(arrayOf(Gt6NightMode.LIGHT_KEY), args)
            assertArrayEquals(arrayOf("keyvalue"), projection)
            return MatrixCursor(arrayOf("keyvalue")).apply { addRow(arrayOf(value)) }
        }
        override fun getType(uri: Uri) = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; error("Read only") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { writes++; error("Read only") }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes++; error("Read only") }
    }
}
