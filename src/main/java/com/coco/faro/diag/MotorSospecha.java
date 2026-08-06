package com.coco.faro.diag;

import com.coco.faro.repair.ServicioReparacion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Motor de sospecha por evidencia acumulada.
 *
 * La primera version de Faro se quedaba con el primer mod que aparecia en el
 * stacktrace. Eso funciona a veces y falla feo el resto: el primer frame suele
 * ser el mod que recibio el golpe, no el que lo causo.
 *
 * Aca cada mod junta puntos de varias senales independientes (Forge lo nombro,
 * aparece arriba en el stack, mete ruido en el log, tiene una dependencia rota,
 * lo agregaste recien) y despues se ordenan. La confianza sale del puntaje del
 * primero Y de cuanta ventaja le saca al segundo: si dos mods empatan, no hay
 * ganador y lo decimos.
 *
 * Todo indicio queda guardado con su puntaje, asi que la conclusion siempre se
 * puede auditar en la pantalla de detalle.
 */
public final class MotorSospecha {

    // Pesos. Estan juntos a proposito para poder razonar sobre el balance.
    private static final int P_FORGE_NOMBRO      = 100;
    private static final int P_FIRMA_CAPTURO     = 60;
    private static final int P_STACK_CIMA        = 45;   // frames 0-4
    private static final int P_STACK_MEDIO       = 25;   // frames 5-14
    private static final int P_STACK_FONDO       = 10;   // resto
    private static final int P_LOG_POR_EVENTO    = 8;
    private static final int P_LOG_TOPE          = 32;
    private static final int P_JAR_RECIENTE      = 12;
    private static final int P_DEPENDENCIA_ROTA  = 30;
    private static final int P_LIBRERIA_BASE     = -40;  // castigo, ver abajo

    private static final int UMBRAL_ALTA   = 100;
    private static final int UMBRAL_MEDIA  = 55;
    private static final int UMBRAL_BAJA   = 30;
    private static final int VENTAJA_MINIMA = 20;

    private MotorSospecha() {
    }

    /** Todo lo que el motor necesita para razonar. */
    public record Entrada(
            String modNombradoPorForge,
            List<String> clasesDelStack,
            List<Firma.Coincidencia> coincidencias,
            List<MetadatosJar> jars,
            List<Problema> problemas,
            List<Map.Entry<String, Integer>> ruidoEnLog) {
    }

    public record Resultado(List<Sospechoso> ranking, Confianza confianza) {
        public Sospechoso principal() {
            return ranking.isEmpty() ? null : ranking.get(0);
        }
    }

    public static Resultado evaluar(Entrada in) {
        Map<String, Sospechoso> mapa = new LinkedHashMap<>();
        Map<String, MetadatosJar> porId = new LinkedHashMap<>();
        long masReciente = 0L;

        for (MetadatosJar j : in.jars()) {
            for (String id : j.modIds()) {
                porId.put(id, j);
            }
            masReciente = Math.max(masReciente, j.modificado());
        }

        // ---- Senal 1: Forge nombro el mod. Es la mas fuerte que existe.
        if (in.modNombradoPorForge() != null) {
            obtener(mapa, porId, in.modNombradoPorForge())
                    .sumar("Forge lo marco con el bloque \"-- MOD ... --\" del crash report",
                            P_FORGE_NOMBRO);
        }

        // ---- Senal 2: una firma conocida capturo un modId en su patron.
        for (Firma.Coincidencia c : in.coincidencias()) {
            String capturado = c.modCapturado();
            if (capturado != null && porId.containsKey(capturado.toLowerCase(Locale.ROOT))) {
                obtener(mapa, porId, capturado)
                        .sumar("La firma '" + c.firma().id() + "' lo nombro directamente",
                                P_FIRMA_CAPTURO);
            }
        }

        // ---- Senal 3: posicion en el stacktrace. Mas arriba, mas sospechoso.
        for (int i = 0; i < in.clasesDelStack().size(); i++) {
            String clase = in.clasesDelStack().get(i);
            String id = modDeClase(clase, porId.keySet());
            if (id == null) {
                continue;
            }
            int puntos = i < 5 ? P_STACK_CIMA : (i < 15 ? P_STACK_MEDIO : P_STACK_FONDO);
            Sospechoso s = obtener(mapa, porId, id);
            // Solo el frame mas alto de cada mod suma, para que un mod con 40
            // frames no gane por acumulacion tonta.
            boolean yaSumoPorStack = s.indicios().stream()
                    .anyMatch(x -> x.descripcion().startsWith("Aparece en el stacktrace"));
            if (!yaSumoPorStack) {
                s.sumar("Aparece en el stacktrace (frame " + i + "): " + acortar(clase), puntos);
            }
        }

        // ---- Senal 4: ruido en el log de esta sesion.
        for (Map.Entry<String, Integer> e : in.ruidoEnLog()) {
            String id = e.getKey().toLowerCase(Locale.ROOT);
            if (!porId.containsKey(id)) {
                continue;
            }
            int puntos = Math.min(P_LOG_TOPE, e.getValue() * P_LOG_POR_EVENTO);
            obtener(mapa, porId, id)
                    .sumar("Genero " + e.getValue() + " errores/avisos en el log de esta sesion", puntos);
        }

        // ---- Senal 5: dependencia rota detectada de forma preventiva.
        for (Problema p : in.problemas()) {
            if (p.categoria() != Problema.Categoria.DEPENDENCIA_AUSENTE
                    && p.categoria() != Problema.Categoria.DEPENDENCIA_VERSION
                    && p.categoria() != Problema.Categoria.RANGO_BLANDO) {
                continue;
            }
            p.mod().ifPresent(id -> obtener(mapa, porId, id)
                    .sumar("Tiene un problema de dependencia detectado: " + p.titulo(),
                            P_DEPENDENCIA_ROTA));
        }

        // ---- Senal 6: lo agregaste hace poco. Lo nuevo rompe mas seguido.
        if (masReciente > 0) {
            long ventana = 24L * 60 * 60 * 1000; // 24 h
            for (Sospechoso s : mapa.values()) {
                MetadatosJar j = porId.get(s.modId());
                if (j != null && masReciente - j.modificado() < ventana) {
                    s.sumar("El .jar se agrego o cambio en las ultimas 24 h", P_JAR_RECIENTE);
                }
            }
        }

        // ---- Ajuste: las librerias base aparecen en TODOS los stacktraces.
        // Que Curios o GeckoLib figuren no significa nada, y ademas nunca las
        // vamos a desactivar. Las hundimos para que no tapen al culpable real.
        for (Sospechoso s : mapa.values()) {
            if (ServicioReparacion.esProtegido(s.modId())) {
                s.sumar("Es una libreria compartida: aparece en casi todos los fallos", P_LIBRERIA_BASE);
            }
        }

        List<Sospechoso> ranking = new ArrayList<>(mapa.values());
        Collections.sort(ranking);
        ranking.removeIf(s -> s.puntaje() <= 0);

        return new Resultado(ranking, calcularConfianza(ranking));
    }

    private static Confianza calcularConfianza(List<Sospechoso> ranking) {
        if (ranking.isEmpty()) {
            return Confianza.NINGUNA;
        }
        int primero = ranking.get(0).puntaje();
        int segundo = ranking.size() > 1 ? ranking.get(1).puntaje() : 0;
        int ventaja = primero - segundo;

        if (primero >= UMBRAL_ALTA) {
            return Confianza.ALTA;
        }
        if (primero >= UMBRAL_MEDIA && ventaja >= VENTAJA_MINIMA) {
            return Confianza.MEDIA;
        }
        if (primero >= UMBRAL_BAJA) {
            return Confianza.BAJA;
        }
        return Confianza.NINGUNA;
    }

    private static Sospechoso obtener(Map<String, Sospechoso> mapa,
                                      Map<String, MetadatosJar> porId, String modId) {
        String id = modId.toLowerCase(Locale.ROOT);
        return mapa.computeIfAbsent(id, k -> {
            MetadatosJar j = porId.get(k);
            return new Sospechoso(k,
                    j == null ? k : j.nombreVisible(),
                    j == null ? null : j.archivo());
        });
    }

    /** Busca un modId conocido entre los segmentos del nombre de una clase. */
    private static String modDeClase(String clase, Iterable<String> idsConocidos) {
        if (clase == null || esDelJuego(clase)) {
            return null;
        }
        String[] segmentos = clase.toLowerCase(Locale.ROOT).split("\\.");
        for (String seg : segmentos) {
            String norm = seg.replace("_", "").replace("-", "");
            if (norm.length() < 3) {
                continue;
            }
            for (String id : idsConocidos) {
                if (id.replace("_", "").replace("-", "").equals(norm)) {
                    return id;
                }
            }
        }
        return null;
    }

    private static boolean esDelJuego(String clase) {
        return clase.startsWith("net.minecraft.")
                || clase.startsWith("net.minecraftforge.")
                || clase.startsWith("java.") || clase.startsWith("javax.")
                || clase.startsWith("sun.") || clase.startsWith("jdk.")
                || clase.startsWith("cpw.mods.")
                || clase.startsWith("org.spongepowered.")
                || clase.startsWith("com.mojang.");
    }

    private static String acortar(String clase) {
        int ultimo = clase.lastIndexOf('.');
        if (ultimo < 0) {
            return clase;
        }
        int anterior = clase.lastIndexOf('.', ultimo - 1);
        return anterior < 0 ? clase : "..." + clase.substring(anterior);
    }
}
