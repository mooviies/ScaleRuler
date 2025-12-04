package com.mooviies.scaleruler

import javafx.application.Platform
import javafx.event.EventHandler
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.GridPane
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import javafx.stage.FileChooser
import java.net.URL
import java.util.*

class SRController : Initializable {
    @FXML
    private lateinit var imageView: ImageView
    @FXML
    private lateinit var scrollPane: ScrollPane
    @FXML
    private lateinit var imageGroup: Group
    @FXML
    private lateinit var openButton: Button
    @FXML
    private lateinit var overlayPane: Pane
    @FXML
    private lateinit var totalLabel: Label

    private var scaleFactor: Double = 1.0
    private var unitsPerPixel: Double? = null
    private var currentImagePath: String? = null
    private var tempFirstPoint: Pair<Double, Double>? = null
    private var tempCalibLine: Line? = null
    private var tempPreviewLine: Line? = null
    private val measurements = mutableListOf<Measurement>()

    // Middle-mouse panning state
    private var panActive = false
    private var panLastX = 0.0
    private var panLastY = 0.0

    private data class Measurement(
        val line: Line,
        val rect: Rectangle,
        val text: Text,
        var length: Double
    )

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        // Make the image initially fit the viewport
        scrollPane.viewportBoundsProperty().addListener { _, _, newBounds ->
            imageView.fitWidth = newBounds.width
            imageView.fitHeight = newBounds.height
        }

        // Enable smooth rendering and keep ratio
        imageView.isSmooth = true
        imageView.isPreserveRatio = true

        // Zoom with mouse wheel
        scrollPane.addEventFilter(ScrollEvent.SCROLL) { event ->
            if (event.deltaY == 0.0) return@addEventFilter
            val zoomFactor = if (event.deltaY > 0) 1.1 else 0.9
            val newScale = (scaleFactor * zoomFactor).coerceIn(0.1, 10.0)
            applyScale(newScale)
            event.consume()
        }

        // Ensure overlay matches the imageView display area
        overlayPane.isPickOnBounds = true
        // Bind overlay size to imageView fitted size
        overlayPane.prefWidthProperty().bind(imageView.fitWidthProperty())
        overlayPane.prefHeightProperty().bind(imageView.fitHeightProperty())

        overlayPane.addEventHandler(MouseEvent.MOUSE_CLICKED) { evt ->
            // Handle left click for drawing steps, right click to cancel preview
            when (evt.button) {
                MouseButton.PRIMARY -> onOverlayClicked(evt)
                MouseButton.SECONDARY -> {
                    // Cancel in-progress drawing if any
                    if (tempFirstPoint != null) {
                        clearPreview()
                        tempFirstPoint = null
                        tempCalibLine = null
                        evt.consume()
                    } else {
                        // Right click on empty space: reset calibration to allow redo
                        resetCalibration()
                        evt.consume()
                    }
                }
                else -> { /* ignore */ }
            }
        }

        // Update preview line as the mouse moves after the first click
        overlayPane.addEventHandler(MouseEvent.MOUSE_MOVED) { evt ->
            if (panActive) return@addEventHandler
            val start = tempFirstPoint
            if (start != null) {
                ensurePreview(start.first, start.second)
                tempPreviewLine?.endX = evt.x
                tempPreviewLine?.endY = evt.y
            }
        }

        // Hide preview when mouse exits the drawing area
        overlayPane.addEventHandler(MouseEvent.MOUSE_EXITED) {
            clearPreview()
            if (panActive) {
                panActive = false
                overlayPane.cursor = Cursor.DEFAULT
            }
        }

        // Middle mouse press to begin panning
        overlayPane.addEventHandler(MouseEvent.MOUSE_PRESSED) { evt ->
            if (evt.button == MouseButton.MIDDLE) {
                panActive = true
                panLastX = evt.x
                panLastY = evt.y
                overlayPane.cursor = Cursor.CLOSED_HAND
                evt.consume()
            }
        }

        // Drag to pan
        overlayPane.addEventHandler(MouseEvent.MOUSE_DRAGGED) { evt ->
            if (panActive) {
                val dx = evt.x - panLastX
                val dy = evt.y - panLastY

                performPan(dx, dy)

                panLastX = evt.x
                panLastY = evt.y
                evt.consume()
            }
        }

        // Release to stop panning
        overlayPane.addEventHandler(MouseEvent.MOUSE_RELEASED) { evt ->
            if (evt.button == MouseButton.MIDDLE && panActive) {
                panActive = false
                overlayPane.cursor = Cursor.DEFAULT
                evt.consume()
            }
        }

        // Initialize total label
        updateTotalLabel()
    }

    fun openLastImageIfAny() {
        val last = SettingsManager.getLastPath()
        if (last != null) {
            val f = java.io.File(last)
            if (f.exists() && f.isFile && f.canRead()) {
                loadImageFromPath(f.absolutePath)
            }
        }
    }

    private fun applyScale(newScale: Double) {
        scaleFactor = newScale
        imageGroup.scaleX = scaleFactor
        imageGroup.scaleY = scaleFactor
    }

    @FXML
    private fun onOpenButtonClick() {
        val chooser = FileChooser()
        chooser.title = "Open Image"
        chooser.extensionFilters.addAll(
            FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.tiff")
        )

        val file = chooser.showOpenDialog(imageView.scene?.window)
        if (file != null) {
            SettingsManager.setLastPath(file.absolutePath)
            loadImageFromPath(file.absolutePath)
        }
    }

    private fun loadImageFromPath(path: String): Boolean {
        return try {
            val image = Image(java.io.File(path).toURI().toString())
            imageView.image = image
            currentImagePath = path
            // Reset zoom and make image fill the available area
            applyScale(1.0)
            // Hide the open button and let image take the full window
            openButton.isVisible = false
            openButton.isManaged = false

            // Reset overlays/measurements
            tempFirstPoint = null
            tempCalibLine = null
            tempPreviewLine = null
            overlayPane.children.clear()
            measurements.clear()

            // Load cached scale for this image if available
            unitsPerPixel = SettingsManager.getScale(path)

            updateTotalLabel()
            // Restore persisted measurements for this image (if any)
            restoreMeasurementsForCurrentImage()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onOverlayClicked(event: MouseEvent) {
        // Ignore clicks while panning
        if (panActive) {
            event.consume()
            return
        }
        val x = event.x
        val y = event.y

        if (imageView.image == null) return

        val currentFirst = tempFirstPoint
        if (currentFirst == null) {
            // store first point
            tempFirstPoint = Pair(x, y)
            // create preview line starting at the first point
            createPreviewAt(x, y)
            return
        }

        val (x1, y1) = currentFirst
        val x2 = x
        val y2 = y

        val dx = x2 - x1
        val dy = y2 - y1
        val distance = kotlin.math.hypot(dx, dy)

        // clear preview when committing the second point
        clearPreview()

        val scale = unitsPerPixel
        if (scale == null) {
            // Draw a temporary line for calibration feedback
            val line = Line(x1, y1, x2, y2)
            line.strokeWidth = 3.0
            overlayPane.children.add(line)
            tempCalibLine = line

            // Ask user for real length
            val result = showFeetInchesDialog()
            if (result != null) {
                val (feet, inches) = result
                val totalInches = feet * 12.0 + inches
                if (totalInches > 0.0 && distance > 0.0) {
                    // unitsPerPixel now represents inches per pixel
                    val inchesPerPixel = totalInches / distance
                    unitsPerPixel = inchesPerPixel
                    // Save scale for this image path
                    currentImagePath?.let { SettingsManager.setScale(it, inchesPerPixel) }
                    // Remove only the temporary calibration line
                    overlayPane.children.remove(line)
                    tempCalibLine = null
                    // Recalculate all existing measurements with the new scale
                    recalcAllMeasurements()
                } else {
                    // Invalid (zero) input; remove temp line and allow retry
                    overlayPane.children.remove(line)
                }
            } else {
                // Canceled; remove temp line
                overlayPane.children.remove(line)
            }

        } else {
            // Measurement: add and persist
            addMeasurement(x1, y1, x2, y2)
            saveAllMeasurements()
        }

        // Reset for next pair
        tempFirstPoint = null
        tempCalibLine = null
    }

    private fun performPan(dx: Double, dy: Double) {
        val content = scrollPane.content ?: return
        val contentBounds = content.boundsInParent
        val viewport = scrollPane.viewportBounds

        val cw = contentBounds.width
        val ch = contentBounds.height
        val vw = viewport.width
        val vh = viewport.height

        val hDen = (cw - vw).coerceAtLeast(1.0)
        val vDen = (ch - vh).coerceAtLeast(1.0)

        // Adjust scroll values so content follows the cursor direction
        val deltaH = -dx / hDen
        val deltaV = -dy / vDen

        val newH = (scrollPane.hvalue + deltaH).coerceIn(0.0, 1.0)
        val newV = (scrollPane.vvalue + deltaV).coerceIn(0.0, 1.0)

        scrollPane.hvalue = newH
        scrollPane.vvalue = newV
    }

    private fun resetCalibration() {
        // Clear current scale and persisted scale for this image
        unitsPerPixel = null
        currentImagePath?.let { SettingsManager.clearScale(it) }
        // Recalculate labels/lengths (will become 0′ 0″) and update total
        recalcAllMeasurements()
    }

    private fun recalcAllMeasurements() {
        measurements.forEach { m ->
            val x1 = m.line.startX
            val y1 = m.line.startY
            val x2 = m.line.endX
            val y2 = m.line.endY
            val distance = kotlin.math.hypot(x2 - x1, y2 - y1)
            val newLength = (unitsPerPixel ?: 0.0) * distance
            m.length = newLength

            val midX = (x1 + x2) / 2.0
            val midY = (y1 + y2) / 2.0
            val textX = midX + 6.0
            val textY = midY - 6.0

            m.text.text = formatFeetInches(newLength)
            m.text.x = textX
            m.text.y = textY

            val padding = 4.0
            val bounds = m.text.layoutBounds
            m.rect.x = textX - padding
            m.rect.y = textY - bounds.height - padding
            m.rect.width = bounds.width + padding * 2
            m.rect.height = bounds.height + padding * 2
        }
        updateTotalLabel()
    }

    private fun addMeasurement(x1: Double, y1: Double, x2: Double, y2: Double) {
        val line = Line(x1, y1, x2, y2)
        line.strokeWidth = 3.0
        overlayPane.children.add(line)

        val distance = kotlin.math.hypot(x2 - x1, y2 - y1)
        val lengthValue = (unitsPerPixel ?: 0.0) * distance // inches
        val midX = (x1 + x2) / 2.0
        val midY = (y1 + y2) / 2.0
        val labelText = formatFeetInches(lengthValue)
        val textNode = Text(labelText)
        // Position text relative to the midpoint; Text's y is baseline
        val textX = midX + 6.0
        val textY = midY - 6.0
        textNode.x = textX
        textNode.y = textY
        textNode.fill = Color.WHITE

        // Compute background rect from text bounds with padding
        val padding = 4.0
        val bounds = textNode.layoutBounds
        val rect = Rectangle(
            textX - padding,
            textY - bounds.height - padding,
            bounds.width + padding * 2,
            bounds.height + padding * 2
        )
        rect.arcWidth = 8.0
        rect.arcHeight = 8.0
        rect.fill = Color.color(0.0, 0.0, 0.0, 0.6)

        // Add rectangle first so it appears behind the text
        overlayPane.children.addAll(rect, textNode)

        // Track measurement and enable deletion on right-click
        val measurement = Measurement(line, rect, textNode, lengthValue)
        measurements.add(measurement)

        val deleteHandler = EventHandler<MouseEvent> { e ->
            if (e.button == MouseButton.SECONDARY) {
                removeMeasurement(measurement)
                e.consume()
            }
        }
        rect.onMouseClicked = deleteHandler
        textNode.onMouseClicked = deleteHandler

        updateTotalLabel()
    }

    private fun createPreviewAt(x: Double, y: Double) {
        val line = Line(x, y, x, y)
        line.strokeWidth = 3.0
        line.stroke = Color.color(1.0, 1.0, 0.0, 0.9) // bright yellow for visibility
        line.strokeDashArray.setAll(8.0, 6.0)
        overlayPane.children.add(line)
        tempPreviewLine = line
    }

    private fun ensurePreview(x: Double, y: Double) {
        if (tempPreviewLine == null) createPreviewAt(x, y)
    }

    private fun clearPreview() {
        tempPreviewLine?.let { overlayPane.children.remove(it) }
        tempPreviewLine = null
    }

    private fun formatLength(value: Double): String {
        // Deprecated in favor of formatFeetInches; keep for potential compat.
        return formatFeetInches(value)
    }

    private fun formatNumber(value: Double): String {
        // Not used for display anymore, but keep a safe numeric formatter if needed
        val v = if (value.isFinite()) value else 0.0
        val s = String.format(Locale.US, "%.3f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun updateTotalLabel() {
        val totalInches = measurements.sumOf { it.length }
        totalLabel.text = "Total: ${formatFeetInches(totalInches)}"
    }

    private fun removeMeasurement(m: Measurement) {
        overlayPane.children.removeAll(m.line, m.rect, m.text)
        measurements.remove(m)
        updateTotalLabel()
        saveAllMeasurements()
    }

    // ---- Feet/Inches helpers ----
    private fun showFeetInchesDialog(): Pair<Int, Int>? {
        val dialog: Dialog<Pair<Int, Int>> = Dialog()
        dialog.title = "Calibration"
        dialog.headerText = "Enter the real-world length of the drawn line (feet and inches)"
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 10.0

        val feetField = TextField()
        feetField.promptText = "feet"
        val inchesField = TextField()
        inchesField.promptText = "inches (0–11)"

        grid.add(Label("Feet:"), 0, 0)
        grid.add(feetField, 1, 0)
        grid.add(Label("Inches:"), 0, 1)
        grid.add(inchesField, 1, 1)

        dialog.dialogPane.content = grid

        dialog.setResultConverter { btn ->
            if (btn == ButtonType.OK) {
                val feet = feetField.text.trim().ifEmpty { "0" }.toIntOrNull()
                val inches = inchesField.text.trim().ifEmpty { "0" }.toIntOrNull()
                if (feet != null && feet >= 0 && inches != null && inches in 0..11) {
                    Pair(feet, inches)
                } else null
            } else null
        }

        val res = dialog.showAndWait()
        return if (res.isPresent) res.get() else null
    }

    private fun formatFeetInches(totalInches: Double): String {
        val inchesRounded = if (totalInches.isFinite() && totalInches >= 0) kotlin.math.floor(totalInches + 0.5) else 0.0
        var feet = (inchesRounded / 12.0).toInt()
        var inches = (inchesRounded % 12.0).toInt()
        if (inches == 12) {
            feet += 1
            inches = 0
        }
        return "${feet}′ ${inches}″"
    }

    // ---- Persistence helpers for measurements ----
    private fun saveAllMeasurements() {
        val path = currentImagePath ?: return
        val w = overlayPane.width
        val h = overlayPane.height
        if (w <= 0.0 || h <= 0.0) return
        val list = measurements.map { m ->
            val x1 = m.line.startX
            val y1 = m.line.startY
            val x2 = m.line.endX
            val y2 = m.line.endY
            doubleArrayOf(x1, y1, x2, y2)
        }
        SettingsManager.setMeasurements(path, list)
    }

    private fun restoreMeasurementsForCurrentImage() {
        val path = currentImagePath ?: return
        // If overlay size not ready, defer
        if (overlayPane.width <= 0.0 || overlayPane.height <= 0.0) {
            Platform.runLater { restoreMeasurementsForCurrentImage() }
            return
        }
        val items = SettingsManager.getMeasurements(path)
        if (items.isEmpty()) return
        items.forEach { arr ->
            if (arr.size >= 4) {
                val x1 = arr[0]
                val y1 = arr[1]
                val x2 = arr[2]
                val y2 = arr[3]
                addMeasurement(x1, y1, x2, y2)
            }
        }
        updateTotalLabel()
    }
}