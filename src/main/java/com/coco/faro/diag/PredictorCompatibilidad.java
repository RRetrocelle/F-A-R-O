package com.coco.faro.diag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Predictor: que va a pasar si agregas ESTE mod a ESTA instalacion, antes de
 * ponerlo en la carpeta.
 *
 * La idea es dar vuelta el flujo habitual. Hoy uno copia el .jar, arranca el
 * juego, revienta, lee el log y recien ahi entiende. Aca se lee el .jar candidato
 * y se lo confronta contra lo que ya hay instalado, sin arrancar nada.
 *
 * Que se puede predecir con CERTEZA (sale de los metadatos, es verificable):
 *   - Que el modId ya este ocupado por otro jar. Eso es crash garantizado.
 *   - Que sea de otro modloader. No va a cargar, punto.
 *   - Que le falte una dependencia obligatoria, o que la instalada este fuera del
 *     rango que pide.
 *   - Que ROMPA una dependencia existente: el caso que nadie mira. Si el candidato
 *     trae una version distinta de una libreria que otros mods ya usan, los que
 *     pedian la vieja se caen. Esto es lo que hace que "agregue un mod y se
 *     rompieron otros tres".
 *   - Que declare incompatibilidad explicita con algo instalado.
 *
 * Que se predice con CERTEZA MEDIA (deduccion razonable, revisable):
 *   - Solapamiento funcional contra la lista curada de {@link BaseConflictos}.
 *   - Clases del juego que el candidato parchea y otro mod tambien. Los objetivos
 *     son dato duro, pero que dos mixins sobre la misma clase choquen no lo es.
 *   - Impacto de rendimiento estimado por categoria y peso del jar.
 *
 * Que NO se puede predecir, y se dice sin vueltas: conflictos funcionales que solo
 * aparecen al jugar. Dos mods que registran el mismo bloque con distinto
 * comportamiento, o que se pisan una receta, no se deducen de los metadatos. Para
 * eso hay que instalarlo y probarlo — y ahi entra el resto de Faro.
 */
public final class PredictorCompatibilidad {

    public enum Veredicto {
        SEGURO("Se puede instalar", "No encontre nada que lo impida."),
        REVISAR("Instalable, con avisos", "Va a cargar, pero hay cosas que conviene mirar."),
        RIESGOSO("Riesgoso", "Es probable que rompa algo. Leé los avisos antes."),
        NO("No va a funcionar", "Tiene un impedimento que garantiza que falle.");

        public final String etiqueta;
        public final String resumen;

        Veredicto(String etiqueta, String resumen) {
            this.etiqueta = etiqueta;
            this.resumen = resumen;
        }
    }

    /** Un hallazgo concreto del analisis. */
    public record Hallazgo(Severidad severidad, Certeza certeza, String titulo,
                           String detalle, String queHacer) {
    }

    public record Prediccion(MetadatosJar candidato, Veredicto veredicto,
                             List<Hallazgo> hallazgos, String error) {

        public boolean valido() {
            return error == null;
        }

        public List<Hallazgo> por(Severidad s) {
            return hallazgos.stream().filter(h -> h.severidad() == s).toList();
        }

        public static Prediccion fallo(String motivo) {
            return new Prediccion(null, Veredicto.NO, List.of(), motivo);
        }
    }

    private PredictorCompatibilidad() {
    }

    /** Carpeta donde el usuario deja los .jar que quiere evaluar sin instalarlos. */
    public static Path carpetaPruebas(Path carpetaJuego) {
        return carpetaJuego.resolve("faro").resolve("probar");
    }

    /** Prepara la carpeta y deja un LEEME, para que se entienda sin abrir el juego. */
    public static Path prepararCarpeta(Path carpetaJuego) {
        Path carpeta = carpetaPruebas(carpetaJuego);
        try {
            Files.createDirectories(carpeta);
            Path leeme = carpeta.resolve("LEEME.txt");
            if (!Files.exists(leeme)) {
                Files.writeString(leeme, """
                        Poné acá los .jar que querés evaluar ANTES de instalarlos.

                        Faro los lee, los compara contra lo que ya tenés en mods/ y te dice si
                        van a funcionar — sin arrancar el juego y sin tocar tu carpeta de mods.

                        Los archivos que están acá NO se cargan. Forge ni los mira.
                        Cuando decidas instalarlo, Faro te ofrece moverlo a mods/ con un botón.
                        """, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return carpeta;
    }

    /** Los .jar que hay esperando evaluacion. */
    public static List<Path> candidatos(Path carpetaJuego) {
        List<Path> out = new ArrayList<>();
        Path carpeta = carpetaPruebas(carpetaJuego);
        if (!Files.isDirectory(carpeta)) {
            return out;
        }
        try (var flujo = Files.newDirectoryStream(carpeta, "*.jar")) {
            for (Path p : flujo) {
                out.add(p);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Analiza un jar candidato contra la instalacion actual.
     *
     * @param jarCandidato el .jar a evaluar, que NO esta en mods/
     * @param instalados   lo que ya hay en mods/
     * @param mixins       reporte de mixins ya calculado, o null si no esta listo
     */
    public static Prediccion analizar(Path jarCandidato, List<MetadatosJar> instalados,
                                      AnalizadorMixins.Reporte mixins) {
        if (jarCandidato == null || !Files.isRegularFile(jarCandidato)) {
            return Prediccion.fallo("No encuentro el archivo.");
        }

        List<MetadatosJar> leido = EscanerJars.escanear(jarCandidato.getParent());
        MetadatosJar cand = leido.stream()
                .filter(m -> m.archivo().equals(jarCandidato))
                .findFirst().orElse(null);
        if (cand == null) {
            return Prediccion.fallo("No pude leer los metadatos del .jar.");
        }

        List<Hallazgo> hallazgos = new ArrayList<>();

        // Indice de lo instalado.
        Set<String> idsInstalados = new LinkedHashSet<>();
        for (MetadatosJar j : instalados) {
            for (String id : j.todosLosModIds()) {
                idsInstalados.add(id.toLowerCase(Locale.ROOT));
            }
        }

        verificarLoader(cand, hallazgos);
        verificarDuplicado(cand, instalados, hallazgos);
        verificarDependenciasDelCandidato(cand, instalados, idsInstalados, hallazgos);
        verificarQueNoRompaLoInstalado(cand, instalados, hallazgos);
        verificarIncompatibilidadesDeclaradas(cand, instalados, idsInstalados, hallazgos);
        verificarSolapamiento(cand, idsInstalados, hallazgos);
        verificarMixins(cand, mixins, hallazgos);
        estimarRendimiento(cand, instalados, hallazgos);

        return new Prediccion(cand, decidir(hallazgos), hallazgos, null);
    }

    private static Veredicto decidir(List<Hallazgo> hallazgos) {
        boolean critico = hallazgos.stream().anyMatch(h -> h.severidad() == Severidad.CRITICA);
        if (critico) {
            return Veredicto.NO;
        }
        long altos = hallazgos.stream().filter(h -> h.severidad() == Severidad.ALTA).count();
        if (altos >= 2) {
            return Veredicto.RIESGOSO;
        }
        if (altos == 1) {
            return Veredicto.REVISAR;
        }
        boolean medios = hallazgos.stream().anyMatch(h -> h.severidad() == Severidad.MEDIA);
        return medios ? Veredicto.REVISAR : Veredicto.SEGURO;
    }

    // ------------------------------------------------------------- chequeos

    private static void verificarLoader(MetadatosJar c, List<Hallazgo> out) {
        switch (c.loader()) {
            case FABRIC -> out.add(new Hallazgo(Severidad.CRITICA, Certeza.ALTA,
                    "Es un mod de Fabric",
                    "El .jar trae fabric.mod.json y no trae mods.toml. Forge no lo va a cargar nunca.",
                    "Buscá la version para Forge 1.20.1 del mismo mod."));
            case NEOFORGE -> out.add(new Hallazgo(Severidad.CRITICA, Certeza.ALTA,
                    "Es un mod de NeoForge",
                    "Declara neoforge.mods.toml. NeoForge y Forge se parecen pero no son "
                            + "intercambiables.",
                    "Buscá la build de Forge."));
            case NINGUNO -> out.add(new Hallazgo(Severidad.MEDIA, Certeza.ALTA,
                    "No declara ningun mod",
                    "El .jar no tiene mods.toml. Puede ser una libreria, un coremod o un "
                            + "archivo que no es un mod.",
                    "Si lo pide otro mod como dependencia, esta bien. Si lo bajaste como "
                            + "mod de contenido, revisá que sea el archivo correcto."));
            case MIXTO -> out.add(new Hallazgo(Severidad.INFO, Certeza.ALTA,
                    "Es un jar multiplataforma",
                    "Trae metadatos de Forge y de Fabric a la vez. En Forge se usa la parte "
                            + "de Forge.",
                    "No hay nada que hacer."));
            case FORGE -> {
                // Lo esperado; no genera hallazgo.
            }
        }
    }

    private static void verificarDuplicado(MetadatosJar c, List<MetadatosJar> instalados,
                                           List<Hallazgo> out) {
        for (String id : c.modIds()) {
            for (MetadatosJar j : instalados) {
                if (!j.todosLosModIds().stream().anyMatch(x -> x.equalsIgnoreCase(id))) {
                    continue;
                }
                boolean mismaVersion = j.version().equalsIgnoreCase(c.version());
                out.add(new Hallazgo(Severidad.CRITICA, Certeza.ALTA,
                        "El modId '" + id + "' ya esta instalado",
                        "Lo provee " + j.nombreArchivo()
                                + (j.version().isBlank() ? "" : " (version " + j.version() + ")")
                                + (mismaVersion ? ". Es la misma version."
                                : ". El candidato es la " + (c.version().isBlank() ? "?" : c.version()) + ".")
                                + " Dos jars con el mismo modId hacen que Forge aborte el arranque.",
                        mismaVersion
                                ? "Ya lo tenes. No hace falta instalarlo."
                                : "Si querés actualizar, sacá el viejo primero. Faro puede "
                                  + "hacerlo desde la pestaña Mods con el boton Actualizar."));
            }
        }
    }

    private static void verificarDependenciasDelCandidato(MetadatosJar c,
                                                          List<MetadatosJar> instalados,
                                                          Set<String> ids, List<Hallazgo> out) {
        for (MetadatosJar.Dependencia d : c.dependencias()) {
            String id = d.modId();
            if (id.equals("forge") || id.equals("minecraft") || id.equals("neoforge")) {
                continue;
            }
            if (d.esIncompatible()) {
                continue; // se maneja en verificarIncompatibilidadesDeclaradas
            }
            // Lo que trae adentro cuenta como presente.
            boolean anidada = c.modIdsAnidados().stream().anyMatch(x -> x.equalsIgnoreCase(id));
            if (anidada) {
                continue;
            }
            boolean presente = ids.contains(id);

            if (!presente) {
                if (!d.obligatoria()) {
                    out.add(new Hallazgo(Severidad.INFO, Certeza.ALTA,
                            "Dependencia opcional ausente: " + id,
                            "El mod funciona sin ella; solo pierde la integracion con ese mod.",
                            "No hay nada que hacer salvo que quieras esa integracion."));
                    continue;
                }
                BaseFixesLocales.Nota nota = BaseFixesLocales.buscar(id);
                out.add(new Hallazgo(Severidad.CRITICA, Certeza.ALTA,
                        "Le falta la dependencia obligatoria '" + id + "'",
                        "Pide el rango " + d.rango().original()
                                + (nota == null ? "" : ". " + nota.advertencia()),
                        "Instalá " + (nota == null ? id : nota.nombreLindo())
                                + (nota == null ? "" : " " + nota.versionRecomendada())
                                + " ANTES que este mod. Faro puede buscarla desde la "
                                + "pestaña Problemas una vez instalado."));
                continue;
            }

            // Esta, pero ¿en la version que pide?
            String versionInstalada = versionDe(instalados, id);
            if (versionInstalada == null || versionInstalada.isBlank()) {
                continue; // sin version legible no se puede afirmar nada
            }
            if (!d.rango().acepta(versionInstalada)) {
                out.add(new Hallazgo(Severidad.CRITICA, Certeza.ALTA,
                        "'" + id + "' esta en una version que no le sirve",
                        "Tenes la " + versionInstalada + " y pide " + d.rango().original() + ".",
                        "Habria que cambiar la version de " + id + ". Ojo: puede que otros "
                                + "mods dependan de la que tenes ahora."));
            }
        }
    }

    /**
     * El chequeo que nadie hace: ¿este mod ROMPE algo que ya funciona?
     *
     * Pasa cuando el candidato provee una libreria que ya esta instalada en otra
     * version. Al reemplazarla, los mods que pedian la anterior dejan de cargar.
     * Es el origen tipico del "instale un mod y se rompieron otros tres".
     */
    private static void verificarQueNoRompaLoInstalado(MetadatosJar c, List<MetadatosJar> instalados,
                                                       List<Hallazgo> out) {
        Set<String> aporta = c.todosLosModIds();
        if (aporta.isEmpty() || c.version().isBlank()) {
            return;
        }

        for (String id : aporta) {
            List<String> afectados = new ArrayList<>();
            for (MetadatosJar j : instalados) {
                for (MetadatosJar.Dependencia d : j.dependencias()) {
                    if (!d.modId().equalsIgnoreCase(id) || !d.obligatoria()) {
                        continue;
                    }
                    if (!d.rango().acepta(c.version())) {
                        afectados.add(j.nombreVisible() + " (pide " + d.rango().original() + ")");
                    }
                }
            }
            if (!afectados.isEmpty()) {
                out.add(new Hallazgo(Severidad.ALTA, Certeza.ALTA,
                        "Este mod provee '" + id + " " + c.version() + "' y hay mods que piden otra",
                        "Dejarian de cargar: " + String.join(", ", afectados)
                                + ". Esto es lo que hace que instalar un mod rompa otros que "
                                + "funcionaban bien.",
                        "Verificá si esos mods tienen una version compatible antes de instalar este."));
            }
        }
    }

    private static void verificarIncompatibilidadesDeclaradas(MetadatosJar c,
                                                              List<MetadatosJar> instalados,
                                                              Set<String> ids, List<Hallazgo> out) {
        for (MetadatosJar.Dependencia d : c.dependencias()) {
            if (d.esIncompatible() && ids.contains(d.modId())) {
                out.add(new Hallazgo(Severidad.CRITICA, Certeza.ALTA,
                        "Declara que no puede convivir con '" + d.modId() + "'",
                        "El propio mods.toml del candidato lo marca como incompatible. "
                                + "Cuando un mod lo dice asi, no hay margen de duda.",
                        "Hay que elegir uno de los dos. No se pueden tener ambos."));
            }
        }
        // Y al reves: alguno de los instalados puede declararlo incompatible a el.
        for (MetadatosJar j : instalados) {
            for (MetadatosJar.Dependencia d : j.dependencias()) {
                if (!d.esIncompatible()) {
                    continue;
                }
                if (c.todosLosModIds().stream().anyMatch(x -> x.equalsIgnoreCase(d.modId()))) {
                    out.add(new Hallazgo(Severidad.CRITICA, Certeza.ALTA,
                            "'" + j.nombreVisible() + "' lo declara incompatible",
                            "Un mod que ya tenes instalado marca a este como incompatible en "
                                    + "su propio mods.toml.",
                            "Hay que elegir uno de los dos."));
                }
            }
        }
    }

    private static void verificarSolapamiento(MetadatosJar c, Set<String> ids, List<Hallazgo> out) {
        for (String id : c.modIds()) {
            for (String instalado : ids) {
                BaseConflictos.Solapamiento s = BaseConflictos.solapamientoEntre(id, instalado);
                if (s != null) {
                    out.add(new Hallazgo(Severidad.MEDIA, Certeza.MEDIA,
                            "Probablemente se solape con '" + instalado + "'",
                            s.motivo() + " Esto sale de una lista armada a mano: puede fallar, "
                                    + "revisalo vos.",
                            "No rompe nada, pero podés terminar con contenido duplicado o dos "
                                    + "mods peleando por lo mismo."));
                }
            }
            BaseConflictos.Alternativa alt = BaseConflictos.alternativaPara(id);
            if (alt != null) {
                out.add(new Hallazgo(Severidad.MEDIA, Certeza.MEDIA,
                        "Hay un reemplazo mas actual",
                        alt.motivo(),
                        "Considerá instalar " + alt.reemplazo() + " en lugar de este."));
            }
        }
    }

    private static void verificarMixins(MetadatosJar c, AnalizadorMixins.Reporte reporte,
                                        List<Hallazgo> out) {
        if (reporte == null || reporte.parches().isEmpty()) {
            return;
        }
        // Se analiza el candidato solo, y se cruza con lo ya mapeado.
        AnalizadorMixins.Reporte suyo = AnalizadorMixins.analizar(List.of(c));
        if (suyo.parches().isEmpty()) {
            return;
        }

        Set<String> objetivosCandidato = new LinkedHashSet<>();
        for (AnalizadorMixins.Parche p : suyo.parches()) {
            objetivosCandidato.add(p.claseObjetivo());
        }

        List<String> compartidas = new ArrayList<>();
        for (AnalizadorMixins.Objetivo o : reporte.objetivos()) {
            if (objetivosCandidato.contains(o.claseObjetivo())) {
                compartidas.add(o.nombreCorto() + " (con " + String.join(", ", o.mods()) + ")");
            }
        }

        if (compartidas.isEmpty()) {
            out.add(new Hallazgo(Severidad.INFO, Certeza.ALTA,
                    "Parchea " + objetivosCandidato.size() + " clases, ninguna compartida",
                    "Ningun mod instalado toca las mismas clases del juego que este.",
                    "Nada que revisar por este lado."));
            return;
        }
        int mostrar = Math.min(6, compartidas.size());
        out.add(new Hallazgo(Severidad.MEDIA, Certeza.MEDIA,
                "Parchea " + compartidas.size() + " clases que otros mods tambien tocan",
                String.join("; ", compartidas.subList(0, mostrar))
                        + (compartidas.size() > mostrar ? "; y " + (compartidas.size() - mostrar) + " mas" : "")
                        + ". Los objetivos son dato duro (salen del bytecode), pero que dos "
                        + "mixins sobre la misma clase choquen NO se puede afirmar de antemano: "
                        + "lo normal es que convivan.",
                "Si despues del install aparece un crash en alguna de esas clases, ya sabes "
                        + "por donde empezar."));
    }

    private static void estimarRendimiento(MetadatosJar c, List<MetadatosJar> instalados,
                                           List<Hallazgo> out) {
        long mb = c.tamano() / (1024 * 1024);
        EtiquetadorMods.Etiqueta cat = EtiquetadorMods.clasificar(c);

        double tickP95 = 0;
        try {
            MotorDiagnostico m = MotorDiagnostico.get();
            if (m != null) {
                tickP95 = m.rendimiento().p95Ms();
            }
        } catch (Throwable ignored) {
        }

        if (tickP95 >= 45) {
            out.add(new Hallazgo(Severidad.ALTA, Certeza.MEDIA,
                    "Tu equipo ya esta al limite",
                    String.format(Locale.ROOT,
                            "El p95 del tick esta en %.0f ms sobre un presupuesto de 50. Sumar "
                                    + "contenido con %d mods ya cargados va a empeorarlo.",
                            tickP95, instalados.size()),
                    "Si igual lo instalas, medí antes y despues con el benchmark de Faro para "
                            + "ver cuanto costo de verdad."));
        }

        if (mb >= 40) {
            out.add(new Hallazgo(Severidad.MEDIA, Certeza.MEDIA,
                    "Es un mod pesado (" + mb + " MB)",
                    "Los jars grandes suelen traer muchas texturas, modelos y datos. Eso pega "
                            + "en la memoria y en el tiempo de arranque, no tanto en el tick.",
                    "Si andas justo de RAM, mirá el asistente de memoria en la pestaña "
                            + "Rendimiento antes de instalarlo."));
        }

        if (cat == EtiquetadorMods.Etiqueta.LIBRERIA) {
            out.add(new Hallazgo(Severidad.INFO, Certeza.ALTA,
                    "Es una libreria",
                    "No agrega contenido por si misma: la necesitan otros mods.",
                    "Instalala solo si algo la pide. Una libreria suelta no hace nada."));
        }
    }

    private static String versionDe(List<MetadatosJar> instalados, String modId) {
        for (MetadatosJar j : instalados) {
            for (String id : j.todosLosModIds()) {
                if (id.equalsIgnoreCase(modId)) {
                    return j.version();
                }
            }
        }
        return null;
    }
}
