package com.mooviies.scaleruler

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage

class SRApplication : Application() {
    override fun start(stage: Stage) {
        val fxmlLoader = FXMLLoader(SRApplication::class.java.getResource("sr-view.fxml"))
        val root = fxmlLoader.load<javafx.scene.Parent>()
        val scene = Scene(root, 320.0, 240.0)
        stage.title = "Scale Ruler"
        stage.scene = scene
        // Start maximized so the image can take the full screen
        stage.isMaximized = true
        stage.show()

        // Try opening the last image if available
        val controller = fxmlLoader.getController<SRController>()
        controller.openLastImageIfAny()
    }
}
  
