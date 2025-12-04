module com.mooviies.scaleruler {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;

    requires org.controlsfx.controls;
    requires org.kordamp.bootstrapfx.core;

    opens com.mooviies.scaleruler to javafx.fxml;
    exports com.mooviies.scaleruler;
}