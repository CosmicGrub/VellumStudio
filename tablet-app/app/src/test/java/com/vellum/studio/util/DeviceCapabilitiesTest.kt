package com.vellum.studio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [DeviceCapabilities]' undo/canvas-size budget math directly against known heap-size
 * inputs, via the internal `...For(memoryClassMb, isLowRamDevice)` functions extracted from the
 * live-[android.app.ActivityManager]-backed public API specifically to make this possible without
 * a real device or a Robolectric fake ActivityManager.
 *
 * The 512MB case below reproduces the exact numbers this project already measured and documented
 * in both device branches' `DEVICE_PROFILE.md` (Galaxy Tab S9 FE / Galaxy Z Fold5, both a 512MB
 * `dalvik.vm.heapsize` large-heap grant, neither low-RAM): `undoBudgetBytes()` = 80,530,640 bytes
 * (~76.8MB) and `maxSafeCanvasPixels()` = 9,586,980 pixels.
 */
class DeviceCapabilitiesTest {

    @Test fun `512MB heap matches the numbers measured and documented on real devices`() {
        assertEquals(80_530_640L, DeviceCapabilities.undoBudgetBytesFor(512, isLowRamDevice = false))
        assertEquals(9_586_980L, DeviceCapabilities.maxSafeCanvasPixelsFor(512, isLowRamDevice = false))
    }

    @Test fun `undo budget is exactly 15 percent of the heap when neither clamp applies`() {
        // 1000MB * 0.15 = 150MB exactly, comfortably inside the 48..512MB clamp range.
        assertEquals(157_286_400L, DeviceCapabilities.undoBudgetBytesFor(1000, isLowRamDevice = false))
    }

    @Test fun `undo budget floor protects a small heap`() {
        // 15% of 300MB is 45MB, just under the 48MB floor -- the floor should win.
        assertEquals(50_331_648L, DeviceCapabilities.undoBudgetBytesFor(300, isLowRamDevice = false))
    }

    @Test fun `undo budget ceiling protects against a very generous heap`() {
        // 15% of 4096MB is 614.4MB, above the 512MB ceiling -- the ceiling should win.
        assertEquals(536_870_912L, DeviceCapabilities.undoBudgetBytesFor(4096, isLowRamDevice = false))
    }

    @Test fun `isLowRamDevice clamps the usable heap before either formula runs`() {
        // Same 512MB declared heap as the real devices above, but flagged low-RAM this time: the
        // usable heap is clamped to 128MB first, which pulls both formulas' inputs down with it.
        // 15% of 128MB is 19.2MB, under the 48MB floor, so the floor wins here too.
        assertEquals(50_331_648L, DeviceCapabilities.undoBudgetBytesFor(512, isLowRamDevice = true))
        assertEquals(2_396_745L, DeviceCapabilities.maxSafeCanvasPixelsFor(512, isLowRamDevice = true))
    }

    @Test fun `maxSafeCanvasPixels scales with the declared heap`() {
        val small = DeviceCapabilities.maxSafeCanvasPixelsFor(256, isLowRamDevice = false)
        val large = DeviceCapabilities.maxSafeCanvasPixelsFor(1024, isLowRamDevice = false)
        assertEquals(4_793_490L, small)
        assertEquals(19_173_961L, large)
        assertTrue("a 4x larger heap should allow a larger safe canvas", large > small)
    }
}
