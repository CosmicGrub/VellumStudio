package com.vellum.studio.testing

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Builders for the [MotionEvent] streams the drawing surface's input routing is tested with.
 *
 * Real [MotionEvent.obtain] events (not mocks), so the code under test reads them through the
 * same getToolType / getPointerId / findPointerIndex / getHistorical* / getAxisValue calls it makes
 * on a device. Everything is explicit and clock-free: [downTime]/[eventTime] are plain numbers the
 * test controls, because the view under test never reads a clock.
 *
 * What these events are NOT: they are synthesized, so the digitizer-shaped properties a JVM cannot
 * fake -- real hover reporting distance, real report rate, real palm-contact geometry, latency --
 * are not covered. The routing decisions (which pointer owns the stroke, what a finger may do, what
 * cancel rolls back) depend only on tool type, pointer id, action and coordinates, which is exactly
 * what is modelled here.
 */
internal object PointerEvents {

    /** One pointer's state in one event: id and tool type stay fixed for the pointer's lifetime. */
    data class P(
        val id: Int,
        val toolType: Int,
        val x: Float,
        val y: Float,
        val pressure: Float = 1f,
        val tiltRadians: Float = 0f,
        val orientationRadians: Float = 0f,
    ) {
        fun at(x: Float, y: Float, pressure: Float = this.pressure) = copy(x = x, y = y, pressure = pressure)
    }

    fun stylus(id: Int, x: Float, y: Float, pressure: Float = 1f) = P(id, MotionEvent.TOOL_TYPE_STYLUS, x, y, pressure)
    fun eraser(id: Int, x: Float, y: Float, pressure: Float = 1f) = P(id, MotionEvent.TOOL_TYPE_ERASER, x, y, pressure)
    fun finger(id: Int, x: Float, y: Float, pressure: Float = 1f) = P(id, MotionEvent.TOOL_TYPE_FINGER, x, y, pressure)
    fun mouse(id: Int, x: Float, y: Float) = P(id, MotionEvent.TOOL_TYPE_MOUSE, x, y, pressure = 1f)
    fun unknown(id: Int, x: Float, y: Float) = P(id, MotionEvent.TOOL_TYPE_UNKNOWN, x, y, pressure = 1f)

    private fun sourceFor(toolType: Int): Int = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> InputDevice.SOURCE_STYLUS
        MotionEvent.TOOL_TYPE_MOUSE -> InputDevice.SOURCE_MOUSE
        MotionEvent.TOOL_TYPE_FINGER -> InputDevice.SOURCE_TOUCHSCREEN
        else -> InputDevice.SOURCE_UNKNOWN
    }

    private fun props(pointers: List<P>): Array<MotionEvent.PointerProperties> =
        Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].id
                toolType = pointers[i].toolType
            }
        }

    private fun coords(pointers: List<P>): Array<MotionEvent.PointerCoords> =
        Array(pointers.size) { i ->
            val p = pointers[i]
            MotionEvent.PointerCoords().apply {
                x = p.x
                y = p.y
                pressure = p.pressure
                // AXIS_TILT / orientation are what the view reads for the InputSample; set them
                // through the axis API so getAxisValue(AXIS_TILT) and getOrientation() see them.
                setAxisValue(MotionEvent.AXIS_TILT, p.tiltRadians)
                orientation = p.orientationRadians
            }
        }

    /**
     * One event. [pointers] is the FULL pointer list at that instant, as on a device -- an event
     * during a two-pointer gesture carries both, and [actionIndex] names which one a POINTER_DOWN /
     * POINTER_UP is about (ignored for other actions).
     *
     * For an ACTION_MOVE, [history] are the older batched samples (oldest first), each a full
     * pointer list like [pointers]; [pointers] is then the newest ("current") sample, exactly the
     * getHistorical* / getX split the view consumes.
     */
    fun event(
        action: Int,
        pointers: List<P>,
        actionIndex: Int = 0,
        downTime: Long = DOWN_TIME,
        eventTime: Long = downTime,
        history: List<List<P>> = emptyList(),
    ): MotionEvent {
        require(pointers.isNotEmpty()) { "an event needs at least one pointer" }
        require(history.isEmpty() || action == MotionEvent.ACTION_MOVE) { "only ACTION_MOVE batches history" }
        val encoded = when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }
        // Batching model: MotionEvent.obtain fixes the FIRST sample, addBatch appends newer ones and
        // demotes everything before the last to history. So history + current is one ordered run.
        val run = history + listOf(pointers)
        val first = run.first()
        val ev = MotionEvent.obtain(
            downTime, eventTime, encoded, first.size, props(first), coords(first),
            0, 0, 1f, 1f, 0, 0, sourceFor(first[0].toolType), 0,
        )
        for (i in 1 until run.size) {
            ev.addBatch(eventTime + i, coords(run[i]), 0)
        }
        return ev
    }

    /** ACTION_DOWN for the first pointer of a gesture. */
    fun down(vararg pointers: P, downTime: Long = DOWN_TIME) =
        event(MotionEvent.ACTION_DOWN, pointers.toList(), downTime = downTime)

    /** ACTION_POINTER_DOWN: [pointers] is everything now down, [index] is the newcomer's position in it. */
    fun pointerDown(index: Int, vararg pointers: P) =
        event(MotionEvent.ACTION_POINTER_DOWN, pointers.toList(), actionIndex = index)

    fun move(vararg pointers: P, history: List<List<P>> = emptyList()) =
        event(MotionEvent.ACTION_MOVE, pointers.toList(), history = history)

    /** ACTION_POINTER_UP: [pointers] still includes the pointer that is lifting, at [index]. */
    fun pointerUp(index: Int, vararg pointers: P) =
        event(MotionEvent.ACTION_POINTER_UP, pointers.toList(), actionIndex = index)

    /** ACTION_UP for the last pointer left. */
    fun up(vararg pointers: P) = event(MotionEvent.ACTION_UP, pointers.toList())

    fun cancel(vararg pointers: P) = event(MotionEvent.ACTION_CANCEL, pointers.toList())

    /** A hover event (ACTION_HOVER_ENTER / MOVE / EXIT) -- delivered to onHoverEvent, never onTouchEvent. */
    fun hover(action: Int, pointer: P) = event(action, listOf(pointer))

    const val DOWN_TIME = 1_000L
}
