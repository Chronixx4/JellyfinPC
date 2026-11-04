package de.marvin.jellyfin.pc;

import javafx.scene.control.*;
import javafx.stage.Stage;

import java.util.function.Consumer;

/** Kleiner Dialog zur PIN-Eingabe (Eingabe wird verdeckt). */
final class PasswordDialogHelper {
    private PasswordDialogHelper() { }

    static void ask(Stage owner, String title, Consumer<String> done) {
        Dialog<String> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(title);
        PasswordField pf = new PasswordField();
        pf.setPromptText("PIN");
        d.getDialogPane().setContent(pf);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().getStylesheets().add(PasswordDialogHelper.class.getResource("/style.css").toExternalForm());
        d.getDialogPane().setStyle("-fx-background-color:#121722;");
        d.setOnShown(e -> pf.requestFocus());
        d.setResultConverter(bt -> bt == ButtonType.OK ? pf.getText() : null);
        d.showAndWait().ifPresent(done);
    }
}
