/*
 * Copyright 2020 The Android Open Source Project
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

@file:Suppress("NOTHING_TO_INLINE")

package androidx.ui.core

import androidx.ui.geometry.Rect
import androidx.ui.graphics.Canvas
import androidx.ui.graphics.Color
import androidx.ui.graphics.Paint
import androidx.ui.graphics.PaintingStyle
import androidx.ui.unit.Density
import androidx.ui.unit.IntPx
import androidx.ui.unit.IntPxPosition
import androidx.ui.unit.IntPxSize
import androidx.ui.unit.PxPosition
import androidx.ui.unit.round
import androidx.ui.unit.toPx
import androidx.ui.unit.toPxPosition
import androidx.ui.unit.toPxSize

private val Unmeasured = IntPxSize(IntPx.Zero, IntPx.Zero)

/**
 * Measurable and Placeable type that has a position.
 */
internal sealed class LayoutNodeWrapper(
    internal val layoutNode: LayoutNode
) : Placeable(), Measurable, LayoutCoordinates {
    protected open val wrapped: LayoutNodeWrapper? = null
    internal var wrappedBy: LayoutNodeWrapper? = null
    var position = IntPxPosition.Origin

    private var dirtySize: Boolean = false
    fun hasDirtySize(): Boolean = dirtySize || (wrapped?.hasDirtySize() ?: false)
    override var size: IntPxSize = Unmeasured
        protected set(value) {
            if (field != value) dirtySize = true
            field = value
        }

    override val parentCoordinates: LayoutCoordinates?
        get() {
            check(isAttached) { ExpectAttachedLayoutCoordinates }
            return wrappedBy
        }

    /**
     * Assigns a layout size to this [LayoutNodeWrapper] given the assigned innermost size
     * from the call to [MeasureScope.layout].
     * @return The size after adjusting for the modifier.
     */
    abstract fun layoutSize(innermostSize: IntPxSize): IntPxSize

    /**
     * Places the modified child.
     */
    abstract fun place(position: IntPxPosition)

    /**
     * Places the modified child.
     */
    final override fun performPlace(position: IntPxPosition) {
        place(position)
        dirtySize = false
    }

    /**
     * Draws the content of the LayoutNode
     */
    abstract fun draw(canvas: Canvas, density: Density)

    override fun childToLocal(child: LayoutCoordinates, childLocal: PxPosition): PxPosition {
        check(isAttached) { ExpectAttachedLayoutCoordinates }
        check(child.isAttached) { "Child $child is not attached!" }
        var wrapper = child as LayoutNodeWrapper
        var position = childLocal
        while (wrapper !== this) {
            position = wrapper.toParentPosition(position)

            val parent = wrapper.wrappedBy
            check(parent != null) {
                "childToLocal: child parameter is not a child of the LayoutCoordinates"
            }
            wrapper = parent
        }
        return position
    }

    override fun globalToLocal(global: PxPosition): PxPosition {
        check(isAttached) { ExpectAttachedLayoutCoordinates }
        val wrapper = wrappedBy ?: return fromParentPosition(
            global - layoutNode.requireOwner().calculatePosition().toPxPosition()
        )
        return fromParentPosition(wrapper.globalToLocal(global))
    }

    override fun localToGlobal(local: PxPosition): PxPosition {
        return localToRoot(local) + layoutNode.requireOwner().calculatePosition()
    }

    override fun localToRoot(local: PxPosition): PxPosition {
        check(isAttached) { ExpectAttachedLayoutCoordinates }
        var wrapper: LayoutNodeWrapper? = this
        var position = local
        while (wrapper != null) {
            position = wrapper.toParentPosition(position)
            wrapper = wrapper.wrappedBy
        }
        return position
    }

    protected inline fun withPositionTranslation(canvas: Canvas, block: (Canvas) -> Unit) {
        val x = position.x.value.toFloat()
        val y = position.y.value.toFloat()
        canvas.translate(x, y)
        block(canvas)
        canvas.translate(-x, -y)
    }

    /**
     * Converts [position] in the local coordinate system to a [PxPosition] in the
     * [parentCoordinates] coordinate system.
     */
    open fun toParentPosition(position: PxPosition): PxPosition = position + this.position

    /**
     * Converts [position] in the [parentCoordinates] coordinate system to a [PxPosition] in the
     * local coordinate system.
     */
    open fun fromParentPosition(position: PxPosition): PxPosition = position - this.position

    protected fun drawBorder(canvas: Canvas, paint: Paint) {
        val rect = Rect(
            left = 0.5f,
            top = 0.5f,
            right = size.width.value.toFloat() - 0.5f,
            bottom = size.height.value.toFloat() - 0.5f
        )
        canvas.drawRect(rect, paint)
    }

    /**
     * Detaches the LayoutNodeWrapper and its wrapped LayoutNodeWrapper from an active LayoutNode.
     * This will be called whenever the modifier chain is replaced and the LayoutNodeWrappers
     * are recreated.
     */
    abstract fun detach()

    internal companion object {
        const val ExpectAttachedLayoutCoordinates = "LayoutCoordinate operations are only valid " +
                "when isAttached is true"
    }
}

/**
 * [LayoutNodeWrapper] with default implementations for methods.
 */
internal sealed class DelegatingLayoutNodeWrapper(
    override val wrapped: LayoutNodeWrapper
) : LayoutNodeWrapper(wrapped.layoutNode) {
    override val providedAlignmentLines: Set<AlignmentLine>
        get() = wrapped.providedAlignmentLines

    private var _isAttached = true
    override val isAttached: Boolean
        get() = _isAttached && layoutNode.isAttached()

    init {
        wrapped.wrappedBy = this
    }

    override fun layoutSize(innermostSize: IntPxSize): IntPxSize {
        size = wrapped.layoutSize(innermostSize)
        return size
    }
    override fun draw(canvas: Canvas, density: Density) {
        withPositionTranslation(canvas) {
            wrapped.draw(canvas, density)
        }
    }
    override fun get(line: AlignmentLine): IntPx? {
        val value = wrapped[line] ?: return null
        val px = value.toPx()
        val pos = wrapped.toParentPosition(PxPosition(px, px))
        return if (line is HorizontalAlignmentLine) pos.y.round() else pos.y.round()
    }
    override fun place(position: IntPxPosition) {
        this.position = position
        wrapped.place(IntPxPosition.Origin)
    }
    override fun measure(constraints: Constraints): Placeable {
        wrapped.measure(constraints)
        return this
    }

    override fun minIntrinsicWidth(height: IntPx) = wrapped.minIntrinsicWidth(height)
    override fun maxIntrinsicWidth(height: IntPx) = wrapped.maxIntrinsicWidth(height)
    override fun minIntrinsicHeight(width: IntPx) = wrapped.minIntrinsicHeight(width)
    override fun maxIntrinsicHeight(width: IntPx) = wrapped.maxIntrinsicHeight(width)
    override val parentData: Any? get() = wrapped.parentData

    override fun detach() {
        _isAttached = false
        wrapped.detach()
    }
}

internal class InnerPlaceable(
    layoutNode: LayoutNode
) : LayoutNodeWrapper(layoutNode), Density by layoutNode.measureScope {
    override val providedAlignmentLines: Set<AlignmentLine>
        get() = layoutNode.providedAlignmentLines.keys
    override val isAttached: Boolean
        get() = layoutNode.isAttached()

    override fun measure(constraints: Constraints): Placeable {
        val layoutResult = layoutNode.measureBlocks.measure(
                layoutNode.measureScope,
                layoutNode.layoutChildren,
                constraints
            )
        layoutNode.handleLayoutResult(layoutResult)
        return this
    }

    override val parentData: Any?
        get() = layoutNode.parentDataNode?.value

    override fun minIntrinsicWidth(height: IntPx): IntPx =
        layoutNode.measureBlocks.minIntrinsicWidth(
            layoutNode.measureScope,
            layoutNode.layoutChildren,
            height
        )

    override fun minIntrinsicHeight(width: IntPx): IntPx =
        layoutNode.measureBlocks.minIntrinsicHeight(
            layoutNode.measureScope,
            layoutNode.layoutChildren,
            width
        )

    override fun maxIntrinsicWidth(height: IntPx): IntPx =
        layoutNode.measureBlocks.maxIntrinsicWidth(
            layoutNode.measureScope,
            layoutNode.layoutChildren,
            height
        )

    override fun maxIntrinsicHeight(width: IntPx): IntPx =
        layoutNode.measureBlocks.maxIntrinsicHeight(
            layoutNode.measureScope,
            layoutNode.layoutChildren,
            width
        )

    override fun place(position: IntPxPosition) {
        layoutNode.isPlaced = true
        val wasMoved = position != this.position
        this.position = position
        if (wasMoved) {
            layoutNode.owner?.onPositionChange(layoutNode)
        }
        layoutNode.layout()
    }

    override fun layoutSize(innermostSize: IntPxSize): IntPxSize {
        size = innermostSize
        return innermostSize
    }

    override operator fun get(line: AlignmentLine): IntPx? {
        return layoutNode.calculateAlignmentLines()[line]
    }

    override fun draw(canvas: Canvas, density: Density) {
        withPositionTranslation(canvas) {
            val owner = layoutNode.requireOwner()
            val sizePx = size.toPxSize()
            layoutNode.children.forEach { child -> owner.callDraw(canvas, child, sizePx) }
            if (owner.showLayoutBounds) {
                drawBorder(canvas, innerBoundsPaint)
            }
        }
    }

    override fun detach() {
        // Do nothing. InnerPlaceable only is detached when the LayoutNode is detached.
    }

    internal companion object {
        val innerBoundsPaint = Paint().also { paint ->
            paint.color = Color.Red
            paint.strokeWidth = 1f
            paint.style = PaintingStyle.stroke
        }
    }
}

internal class ModifiedParentDataNode(
    wrapped: LayoutNodeWrapper,
    val parentDataModifier: ParentDataModifier
) : DelegatingLayoutNodeWrapper(wrapped) {
    override val parentData: Any?
        get() = with(parentDataModifier) {
            /**
             * ParentData provided through the parentData node will override the data provided
             * through a modifier
             */
            layoutNode.parentDataNode?.value
                ?: layoutNode.measureScope.modifyParentData(wrapped.parentData)
        }
}

internal class ModifiedLayoutNode(
    wrapped: LayoutNodeWrapper,
    val layoutModifier: LayoutModifier
) : DelegatingLayoutNodeWrapper(wrapped) {
    /**
     * The [Placeable] returned by measuring [wrapped] in [measure].
     * Used to avoid creating more wrapper objects than necessary since [ModifiedLayoutNode]
     * also
     */
    private var measuredPlaceable: Placeable? = null

    /**
     * The [Constraints] used in the current measurement of this modified node wrapper.
     * See [withMeasuredConstraints]
     */
    private var measuredConstraints: Constraints? = null

    /**
     * Sets [measuredConstraints] for the duration of [block].
     */
    private inline fun <R> withMeasuredConstraints(
        constraints: Constraints,
        block: () -> R
    ): R = try {
        measuredConstraints = constraints
        block()
    } finally {
        measuredConstraints = null
    }

    override fun measure(constraints: Constraints): Placeable = with(layoutModifier) {
        val measureResult = withMeasuredConstraints(constraints) {
            wrapped.measure(layoutNode.measureScope.modifyConstraints(constraints))
        }
        measuredPlaceable = measureResult
        this@ModifiedLayoutNode
    }

    override fun minIntrinsicWidth(height: IntPx): IntPx = with(layoutModifier) {
        layoutNode.measureScope.minIntrinsicWidthOf(wrapped, height)
    }

    override fun maxIntrinsicWidth(height: IntPx): IntPx = with(layoutModifier) {
        layoutNode.measureScope.maxIntrinsicWidthOf(wrapped, height)
    }

    override fun minIntrinsicHeight(width: IntPx): IntPx = with(layoutModifier) {
        layoutNode.measureScope.minIntrinsicHeightOf(wrapped, width)
    }

    override fun maxIntrinsicHeight(width: IntPx): IntPx = with(layoutModifier) {
        layoutNode.measureScope.maxIntrinsicHeightOf(wrapped, width)
    }

    override fun place(position: IntPxPosition) {
        this.position = position
        val placeable = measuredPlaceable ?: error("Placeable not measured")
        val relativePosition = with(layoutModifier) {
            layoutNode.measureScope.modifyPosition(placeable.size, size)
        }
        placeable.place(relativePosition)
    }

    override operator fun get(line: AlignmentLine): IntPx? = with(layoutModifier) {
        return layoutNode.measureScope.modifyAlignmentLine(line, super.get(line))
    }

    override fun layoutSize(innermostSize: IntPxSize): IntPxSize = with(layoutModifier) {
        val constraints = measuredConstraints ?: error("must be called during measurement")
        layoutNode.measureScope.modifySize(constraints, wrapped.layoutSize(innermostSize)).also {
            size = it
        }
    }

    override fun draw(canvas: Canvas, density: Density) {
        withPositionTranslation(canvas) {
            wrapped.draw(canvas, density)
            if (layoutNode.requireOwner().showLayoutBounds) {
                drawBorder(canvas, modifierBoundsPaint)
            }
        }
    }

    internal companion object {
        val modifierBoundsPaint = Paint().also { paint ->
            paint.color = Color.Blue
            paint.strokeWidth = 1f
            paint.style = PaintingStyle.stroke
        }
    }
}

internal class ModifiedDrawNode(
    wrapped: LayoutNodeWrapper,
    val drawModifier: DrawModifier
) : DelegatingLayoutNodeWrapper(wrapped), () -> Unit {
    private var density: Density? = null
    private var canvas: Canvas? = null

    override fun draw(canvas: Canvas, density: Density) {
        withPositionTranslation(canvas) {
            this.density = density
            this.canvas = canvas
            val pxSize = size.toPxSize()
            drawModifier.draw(density, this, canvas, pxSize)
            this.density = null
            this.canvas = null
        }
    }

    // This is the implementation of drawContent()
    override fun invoke() {
        wrapped.draw(canvas!!, density!!)
    }
}