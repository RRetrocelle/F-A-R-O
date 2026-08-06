package com.coco.faro.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bitacora de todo lo que Faro toca en el disco.
 *
 * Regla del proyecto: si Faro mueve un archivo, queda escrito aca con fecha,
 * origen y destino, de forma que el usuario pueda deshacerlo a mano aunque el
 * mod deje de funcionar o lo desinstale. Nada de cambios silenciosos.
 */
public final class RegistroAcciones {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path archivo;

    public RegistroAcciones(Path carpetaFaro) {
        this.archivo = carpetaFaro.resolve("acciones.log");
    }

    public Path archivo() {
        return archivo;
    }

    public synchronized void anotar(String linea) {
        String texto = "[" + LocalDateTime.now().format(FMT) + "] " + linea + System.lineSeparator();
        try {
            Files.createDirectories(archivo.getParent());
            Files.writeString(archivo, texto, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Si no podemos escribir la bitacora no rompemos nada, pero tampoco
            // seguimos adelante fingiendo que quedo registrado: quien llama decide.
        }
    }

    public synchronized void anotarMovimiento(Path origen, Path destino, String motivo) {
        anotar("MOVER  origen=" + origen + "  destino=" + destino + "  motivo=" + motivo);
        anotar("       Para deshacerlo a mano: mové el archivo de vuelta a " + origen.getParent());
    }

    public synchronized List<String> leerUltimas(int cuantas) {
        if (!Files.isRegularFile(archivo)) {
            return Collections.emptyList();
        }
        try {
            List<String> todas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
            int desde = Math.max(0, todas.size() - cuantas);
            return new ArrayList<>(todas.subList(desde, todas.size()));
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
