package com.coco.faro.diag;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Un problema concreto detectado en la instalacion, con nombre y apellido.
 *
 * A diferencia de {@link Diagnostico} (que analiza un crash ya ocurrido), estos
 * se detectan de forma preventiva leyendo los metadatos de los jars. Es la parte
 * mas util del mod: avisa antes de que el juego reviente, no despues.
 */
public record Problema(
        Severidad severidad,
        Categoria categoria,
        String titulo,
        String detalle,
        String sugerencia,
        String modAfectado,
        Path jarAfectado) {

    public enum Categoria {
        DEPENDENCIA_AUSENTE("Dependencia ausente"),
        DEPENDENCIA_VERSION("Version de dependencia"),
        MOD_DUPLICADO("Mod duplicado"),
        LOADER_INCORRECTO("Loader incorrecto"),
        SIN_METADATOS("Sin metadatos"),
        RANGO_BLANDO("Compatibilidad no verificable"),
        CONFLICTO_DECLARADO("Conflicto declarado"),
        POSIBLE_SOLAPAMIENTO("Posible solapamiento"),
        ALTERNATIVA_SUGERIDA("Alternativa sugerida"),
        RENDIMIENTO("Rendimiento"),
        LOG("Errores en el log");

        private final String etiqueta;

        Categoria(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    public Optional<Path> jar() {
        return Optional.ofNullable(jarAfectado);
    }

    public Optional<String> mod() {
        return Optional.ofNullable(modAfectado);
    }
}
