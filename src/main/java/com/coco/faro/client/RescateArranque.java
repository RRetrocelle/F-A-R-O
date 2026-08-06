package com.coco.faro.client;

import com.coco.faro.diag.AnalizadorDependencias;
import com.coco.faro.diag.EscanerJars;
import com.coco.faro.diag.MetadatosJar;
import com.coco.faro.diag.ParserErrorForge;
import com.coco.faro.diag.Problema;
import com.coco.faro.diag.RangoVersion;
import com.coco.faro.diag.Severidad;
import com.coco.faro.repair.RegistroAcciones;
import com.coco.faro.repair.ServicioReparacion;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rescate de arranque: lo que corre cuando Forge ni siquiera llego a cargar los mods.
 *
 * Detalle importante que condiciona todo este archivo: cuando faltan dependencias,
 * Forge aborta ANTES de construir un solo mod. En ese momento no existe
 * MotorDiagnostico, ni ModList, ni nada del ciclo de vida normal. Por eso esta
 * clase es completamente autonoma: se arma sus propias rutas desde
 * Minecraft.getInstance().gameDirectory y vuelve a escanear la carpeta a mano.
 *
 * No depende de ninguna parte del resto de Faro que necesite estar inicializada.
 */
public final class RescateArranque {

    /** Un mod que se propone desactivar, con el motivo en criollo. */
    public record Candidato(String modId, String archivo, Path jar, String motivo) {
    }

    private RescateArranque() {
    }

    public static Path carpetaMods() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("mods");
    }

    /**
     * Arma la lista de problemas que se muestra en la pantalla de rescate,
     * en el mismo formato que usa la pestana Problemas.
     *
     * Combina dos fuentes que se complementan:
     *   1. El texto de error de Forge (o latest.log), que nombra las dependencias
     *      faltantes con nombre exacto y version minima. Parseo directo, sin
     *      heuristica: cuando Forge lo dice asi, no hay nada que interpretar.
     *   2. El escaneo propio de la carpeta, que ademas ve duplicados y jars de
     *      otro modloader.
     *
     * Si ambas detectan lo mismo, gana la de Forge y no se repite.
     */
    public static List<Problema> problemas() {
        List<Problema> out = new ArrayList<>();
        Set<String> yaReportados = new LinkedHashSet<>();

        for (ParserErrorForge.Faltante f : ParserErrorForge.desdeLog()) {
            if (!yaReportados.add(f.modIdFaltante())) {
                continue;
            }
            String detalle = f.estaInstalada()
                    ? "Instalada: " + f.versionActual() + "   |   Rango pedido: " + f.rango().original()
                    : "Rango pedido: " + f.rango().original();

            out.add(new Problema(
                    Severidad.CRITICA,
                    f.estaInstalada() ? Problema.Categoria.DEPENDENCIA_VERSION
                            : Problema.Categoria.DEPENDENCIA_AUSENTE,
                    f.modQueLaPide() + " necesita '" + f.modIdFaltante() + "' y no esta",
                    detalle,
                    f.estaInstalada()
                            ? "La version instalada no sirve. Hay que actualizarla o bajarla "
                              + "hasta que entre en el rango."
                            : "Instalá " + f.modIdFaltante() + " para 1.20.1 Forge. "
                              + "Sacar mods no arregla esto: falta uno, no sobra.",
                    f.modIdFaltante(),
                    null));
        }

        // El escaneo propio aporta lo que el texto de Forge no menciona.
        for (Problema p : AnalizadorDependencias.analizar(EscanerJars.escanear(carpetaMods()))) {
            if (p.severidad() == Severidad.INFO) {
                continue;
            }
            String clave = p.categoria() + "/" + p.mod().orElse(p.titulo());
            if (yaReportados.add(clave)) {
                out.add(p);
            }
        }

        // Si nada de lo anterior encontro algo, el fallo de arranque es de otro
        // tipo. En vez de mostrar una pantalla vacia, se corre el diagnostico
        // general de precarga, que como ultimo recurso adjunta el texto crudo.
        if (out.isEmpty()) {
            out.addAll(ParserErrorForge.diagnosticarPrecarga(leerColaDelLog()));
        }

        out.sort(Comparator.comparingInt((Problema p) -> -p.severidad().peso()));
        return out;
    }

    /** Ultimas lineas de latest.log, que es donde queda el error de arranque. */
    private static String leerColaDelLog() {
        try {
            java.nio.file.Path log = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("logs").resolve("latest.log");
            if (!java.nio.file.Files.isRegularFile(log)) {
                return "";
            }
            List<String> lineas = java.nio.file.Files.readAllLines(log,
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            int desde = Math.max(0, lineas.size() - 200);
            return String.join("\n", lineas.subList(desde, lineas.size()));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Analiza la carpeta mods y arma la lista de jars que impiden el arranque.
     *
     * Solo devuelve casos donde desactivar REALMENTE destraba el arranque:
     *   - mods a los que les falta una dependencia obligatoria;
     *   - copias duplicadas del mismo mod (se propone la de version mas baja);
     *   - jars de otro loader, que nunca van a cargar en Forge.
     *
     * Un mod al que le falta una dependencia no se "arregla" desactivandolo: se
     * arregla instalando lo que falta. Pero desactivarlo SI permite entrar al
     * juego, que es lo que hace falta para poder arreglar el resto con calma.
     * La pantalla lo explica con esas palabras.
     */
    public static List<Candidato> calcular() {
        List<Candidato> out = new ArrayList<>();
        Path mods = carpetaMods();

        List<MetadatosJar> jars = EscanerJars.escanear(mods);
        if (jars.isEmpty()) {
            return out;
        }
        List<Problema> problemas = AnalizadorDependencias.analizar(jars);

        Map<String, MetadatosJar> porId = new HashMap<>();
        for (MetadatosJar j : jars) {
            for (String id : j.modIds()) {
                porId.putIfAbsent(id, j);
            }
        }

        // Evita proponer el mismo archivo dos veces por motivos distintos.
        Map<Path, Candidato> unicos = new LinkedHashMap<>();

        for (Problema p : problemas) {
            switch (p.categoria()) {
                case DEPENDENCIA_AUSENTE -> p.jar().ifPresent(jar -> {
                    String id = p.mod().orElse(jar.getFileName().toString());
                    if (!ServicioReparacion.esProtegido(id)) {
                        unicos.putIfAbsent(jar, new Candidato(id, jar.getFileName().toString(), jar,
                                "le falta una dependencia obligatoria"));
                    }
                });

                case LOADER_INCORRECTO -> p.jar().ifPresent(jar ->
                        unicos.putIfAbsent(jar, new Candidato(
                                jar.getFileName().toString(), jar.getFileName().toString(), jar,
                                "es de otro modloader, en Forge no carga nunca")));

                case MOD_DUPLICADO -> {
                    String id = p.mod().orElse(null);
                    if (id == null) {
                        break;
                    }
                    Path aDesactivar = elegirDuplicadoMasViejo(jars, id);
                    if (aDesactivar != null && !ServicioReparacion.esProtegido(id)) {
                        unicos.putIfAbsent(aDesactivar, new Candidato(
                                id, aDesactivar.getFileName().toString(), aDesactivar,
                                "es la copia mas vieja de un mod duplicado"));
                    }
                }

                default -> {
                    // El resto no se resuelve desactivando: no lo proponemos.
                }
            }
        }

        out.addAll(unicos.values());
        return out;
    }

    /** De todas las copias de un modId, devuelve la de version mas baja. */
    private static Path elegirDuplicadoMasViejo(List<MetadatosJar> jars, String modId) {
        MetadatosJar peor = null;
        int cuantos = 0;
        for (MetadatosJar j : jars) {
            if (!j.modIds().contains(modId)) {
                continue;
            }
            cuantos++;
            if (peor == null || RangoVersion.comparar(j.version(), peor.version()) < 0) {
                peor = j;
            }
        }
        // Si no hay al menos dos, no hay duplicado que resolver.
        return (cuantos >= 2 && peor != null) ? peor.archivo() : null;
    }

    /** Mueve los candidatos elegidos. Devuelve cuantos se movieron bien. */
    public static int aplicar(List<Candidato> candidatos) {
        Path faro = Minecraft.getInstance().gameDirectory.toPath().resolve("faro");
        RegistroAcciones registro = new RegistroAcciones(faro);
        ServicioReparacion servicio = new ServicioReparacion(registro);

        registro.anotar("RESCATE  Se intenta destrabar el arranque desde la pantalla de error de Forge.");

        int movidos = 0;
        for (Candidato c : candidatos) {
            ServicioReparacion.Resultado r =
                    servicio.desactivar(c.jar(), c.modId(), "rescate de arranque: " + c.motivo());
            if (r.exito()) {
                movidos++;
            }
        }
        registro.anotar("RESCATE  Movidos " + movidos + " de " + candidatos.size() + " archivos.");
        return movidos;
    }
}
