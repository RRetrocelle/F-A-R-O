package com.coco.faro.diag;

import com.coco.faro.Faro;
import com.coco.faro.repair.RegistroAcciones;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Asistente de biseccion: busqueda binaria del mod culpable.
 *
 * Es el metodo que usa cualquiera que sepa, hecho a mano y de mala gana: apagas
 * la mitad de los mods, probas, y segun si el problema sigue o no, sabes en que
 * mitad esta. Repetis. Con 190 mods se llega al culpable en 8 arranques en vez de
 * 190.
 *
 * Por que hace falta que lo maneje un programa: hacerlo a mano es tediosisimo y
 * facil de arruinar. Hay que llevar la cuenta de que se movio, respetar las
 * dependencias (si apagas una libreria se caen diez mods y el resultado no dice
 * nada), y acordarse de devolver todo al final. Un error en cualquiera de esos
 * pasos invalida toda la sesion y hay que empezar de nuevo.
 *
 * Lo que hace Faro y a mano no se hace:
 *
 *   - CIERRE POR DEPENDENCIAS. Apagar un mod arrastra a los que lo necesitan. Se
 *     calcula el cierre transitivo y se mueve el grupo completo, asi el arranque
 *     no falla por una dependencia rota y confunde el resultado.
 *   - Nunca toca librerias compartidas ni el propio Faro.
 *   - El estado vive en disco (faro/biseccion.json), asi que sobrevive a los
 *     reinicios — que son justamente el punto de todo esto.
 *   - Cada movimiento queda en acciones.log y se puede revertir de una.
 *
 * El usuario solo responde una pregunta por vuelta: ¿el problema sigue pasando?
 */
public final class Biseccion {

    public enum Estado { INACTIVA, ESPERANDO_PRUEBA, TERMINADA }

    /** Una vuelta ya respondida, para poder mostrar el historial. */
    public record Paso(int numero, int candidatosAntes, int desactivados, boolean seguiaFallando) {
    }

    public record Situacion(Estado estado, int vuelta, int candidatos, int desactivadosAhora,
                            List<String> sospechosos, List<Paso> historial, String culpable) {
    }

    private static final String ARCHIVO = "biseccion.json";
    public static final String CARPETA_APARTE = "faro_biseccion";

    private final Path carpetaJuego;
    private final Path carpetaMods;
    private final Path archivoEstado;
    private final RegistroAcciones registro;

    /** Mods todavia bajo sospecha. Empieza siendo todo lo desactivable. */
    private final List<String> candidatos = new ArrayList<>();
    /** Los que estan apagados en esta vuelta, esperando el resultado de la prueba. */
    private final List<String> apagadosAhora = new ArrayList<>();
    private final List<Paso> historial = new ArrayList<>();

    private Estado estado = Estado.INACTIVA;
    private int vuelta = 0;
    private String culpable = null;

    public Biseccion(Path carpetaJuego, RegistroAcciones registro) {
        this.carpetaJuego = carpetaJuego;
        this.carpetaMods = carpetaJuego.resolve("mods");
        this.archivoEstado = carpetaJuego.resolve("faro").resolve(ARCHIVO);
        this.registro = registro;
        cargar();
    }

    public Situacion situacion() {
        return new Situacion(estado, vuelta, candidatos.size(), apagadosAhora.size(),
                new ArrayList<>(candidatos), new ArrayList<>(historial), culpable);
    }

    public Estado estado() {
        return estado;
    }

    // ------------------------------------------------------------- arranque

    /**
     * Cuantos arranques van a hacer falta, como maximo.
     *
     * log2(n) redondeado para arriba. Con 190 candidatos son 8. Decirlo de
     * entrada cambia la disposicion del usuario: "8 reinicios" es aceptable,
     * "no se cuantos" no.
     */
    public static int arranquesEstimados(int candidatos) {
        if (candidatos <= 1) {
            return 0;
        }
        return (int) Math.ceil(Math.log(candidatos) / Math.log(2));
    }

    /** Los mods que tiene sentido apagar: ni librerias compartidas ni Faro. */
    public List<String> candidatosIniciales(List<MetadatosJar> jars) {
        List<String> out = new ArrayList<>();
        for (MetadatosJar j : jars) {
            if (j.sinMetadatosDeMod() || j.esLibreria()) {
                continue;
            }
            String id = j.modIdPrincipal();
            if (id.equalsIgnoreCase("faro")
                    || com.coco.faro.repair.ServicioReparacion.esProtegido(id)) {
                continue;
            }
            if (EtiquetadorMods.clasificar(j) == EtiquetadorMods.Etiqueta.LIBRERIA) {
                continue;
            }
            out.add(id);
        }
        // Orden estable: si no, dos sesiones distintas darian pasos distintos y
        // el historial no se podria comparar.
        out.sort(String::compareTo);
        return out;
    }

    /** Arranca una sesion nueva. */
    public String iniciar(List<MetadatosJar> jars) {
        if (estado != Estado.INACTIVA) {
            return "Ya hay una biseccion en curso.";
        }
        candidatos.clear();
        candidatos.addAll(candidatosIniciales(jars));
        apagadosAhora.clear();
        historial.clear();
        vuelta = 0;
        culpable = null;

        if (candidatos.size() < 2) {
            return "Con menos de dos mods desactivables no hay nada que bisecar.";
        }

        registro.anotar("BISECCION  Sesion iniciada con " + candidatos.size()
                + " candidatos. Maximo " + arranquesEstimados(candidatos.size()) + " arranques.");
        estado = Estado.ESPERANDO_PRUEBA;
        return avanzar(jars);
    }

    /**
     * Prepara la siguiente vuelta: apaga la mitad de los candidatos.
     *
     * Se apaga la primera mitad. Si el problema DESAPARECE, el culpable estaba
     * entre los apagados. Si SIGUE, estaba entre los que quedaron. En ambos casos
     * el espacio de busqueda se reduce a la mitad exacta.
     */
    private String avanzar(List<MetadatosJar> jars) {
        vuelta++;
        int mitad = candidatos.size() / 2;
        List<String> aApagar = new ArrayList<>(candidatos.subList(0, mitad));

        Set<String> conDependientes = cerrarPorDependencias(aApagar, jars);
        apagadosAhora.clear();
        apagadosAhora.addAll(conDependientes);

        int movidos = mover(conDependientes, jars, true);
        guardar();

        registro.anotar("BISECCION  Vuelta " + vuelta + ": apagados " + movidos
                + " jars (" + mitad + " candidatos + " + (conDependientes.size() - mitad)
                + " dependientes). Quedan " + (candidatos.size() - mitad) + " candidatos activos.");

        return "Vuelta " + vuelta + ": aparté " + movidos + " archivos. "
                + "Cerrá el juego, abrilo de nuevo, probá si el problema sigue, y volvé acá.";
    }

    /**
     * Responde la pregunta de la vuelta y prepara la siguiente.
     *
     * @param seguiaFallando true si el problema se reprodujo con la mitad apagada
     */
    public String responder(boolean seguiaFallando, List<MetadatosJar> jars) {
        if (estado != Estado.ESPERANDO_PRUEBA) {
            return "No hay ninguna vuelta esperando respuesta.";
        }

        int mitad = candidatos.size() / 2;
        List<String> apagados = new ArrayList<>(candidatos.subList(0, mitad));
        List<String> activos = new ArrayList<>(candidatos.subList(mitad, candidatos.size()));

        historial.add(new Paso(vuelta, candidatos.size(), apagadosAhora.size(), seguiaFallando));

        // Devolver todo antes de decidir: cada vuelta parte de la carpeta completa.
        restaurarTodo();

        candidatos.clear();
        if (seguiaFallando) {
            // El culpable NO estaba entre los apagados: sigue entre los que quedaron.
            candidatos.addAll(activos);
        } else {
            candidatos.addAll(apagados);
        }

        if (candidatos.isEmpty()) {
            estado = Estado.TERMINADA;
            culpable = null;
            guardar();
            registro.anotar("BISECCION  Terminada sin culpable: el problema no viene de un mod "
                    + "de los que se podian apagar.");
            return "No quedo ningun candidato. El problema no viene de ninguno de los mods que "
                    + "se podian apagar: puede estar en una libreria compartida, en un config, "
                    + "o en Forge mismo.";
        }
        if (candidatos.size() == 1) {
            estado = Estado.TERMINADA;
            culpable = candidatos.get(0);
            guardar();
            registro.anotar("BISECCION  Culpable identificado: " + culpable
                    + " en " + vuelta + " vueltas.");
            return "Encontrado: " + culpable + ". Lo aislé en " + vuelta + " vueltas. "
                    + "Todos los demas mods estan de vuelta en su lugar.";
        }

        return avanzar(jars);
    }

    /** Cancela la sesion y deja todo como estaba. */
    public String cancelar() {
        int devueltos = restaurarTodo();
        estado = Estado.INACTIVA;
        candidatos.clear();
        apagadosAhora.clear();
        historial.clear();
        vuelta = 0;
        culpable = null;
        try {
            Files.deleteIfExists(archivoEstado);
        } catch (Throwable ignored) {
        }
        registro.anotar("BISECCION  Cancelada. Devueltos " + devueltos + " archivos.");
        return "Biseccion cancelada. Devolví " + devueltos + " archivos a la carpeta mods.";
    }

    // -------------------------------------------------------- dependencias

    /**
     * Cierre transitivo: si apago A, tambien hay que apagar todo lo que necesita A.
     *
     * Sin esto la biseccion no sirve. Apagar una libreria hace que 10 mods no
     * carguen, Forge muestra la pantalla de error, y el resultado de la prueba no
     * dice nada sobre el problema que estabas buscando.
     */
    private Set<String> cerrarPorDependencias(List<String> semilla, List<MetadatosJar> jars) {
        Set<String> apagar = new LinkedHashSet<>(semilla);
        boolean cambio = true;
        int vueltas = 0;

        while (cambio && vueltas++ < 20) {
            cambio = false;
            for (MetadatosJar j : jars) {
                String id = j.modIdPrincipal();
                if (apagar.contains(id)) {
                    continue;
                }
                if (id.equalsIgnoreCase("faro")) {
                    continue;
                }
                for (MetadatosJar.Dependencia d : j.dependencias()) {
                    if (!d.obligatoria() || d.esIncompatible()) {
                        continue;
                    }
                    if (apagar.stream().anyMatch(x -> x.equalsIgnoreCase(d.modId()))) {
                        apagar.add(id);
                        cambio = true;
                        break;
                    }
                }
            }
        }
        return apagar;
    }

    // ------------------------------------------------------------ archivos

    private Path carpetaAparte() {
        return carpetaMods.resolve(CARPETA_APARTE);
    }

    /** Mueve los jars de esos modIds. Devuelve cuantos se movieron. */
    private int mover(Set<String> modIds, List<MetadatosJar> jars, boolean apagar) {
        int movidos = 0;
        Path destino = apagar ? carpetaAparte() : carpetaMods;
        try {
            Files.createDirectories(destino);
        } catch (Throwable t) {
            return 0;
        }

        for (MetadatosJar j : jars) {
            if (!modIds.contains(j.modIdPrincipal())) {
                continue;
            }
            try {
                Path origen = j.archivo();
                if (!Files.exists(origen)) {
                    continue;
                }
                Path fin = destino.resolve(origen.getFileName().toString());
                Files.move(origen, fin, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                movidos++;
            } catch (Throwable t) {
                Faro.LOG.warn("[Faro] Biseccion: no pude mover {}: {}",
                        j.nombreArchivo(), t.toString());
            }
        }
        return movidos;
    }

    /**
     * Devuelve TODO lo que la biseccion aparto.
     *
     * No depende de la lista en memoria a proposito: vacia la carpeta entera. Asi
     * funciona aunque el estado se haya perdido, y el usuario nunca queda con
     * mods atrapados en una carpeta que no sabe que existe.
     */
    public int restaurarTodo() {
        Path aparte = carpetaAparte();
        if (!Files.isDirectory(aparte)) {
            return 0;
        }
        int devueltos = 0;
        try (var flujo = Files.newDirectoryStream(aparte, "*.jar")) {
            for (Path p : flujo) {
                try {
                    Files.move(p, carpetaMods.resolve(p.getFileName().toString()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    devueltos++;
                } catch (Throwable t) {
                    Faro.LOG.warn("[Faro] Biseccion: no pude devolver {}: {}",
                            p.getFileName(), t.toString());
                }
            }
        } catch (Throwable ignored) {
        }
        apagadosAhora.clear();
        return devueltos;
    }

    /** Cuantos jars quedaron apartados. Sirve para avisar al arrancar. */
    public int apartadosEnDisco() {
        Path aparte = carpetaAparte();
        if (!Files.isDirectory(aparte)) {
            return 0;
        }
        try (var flujo = Files.newDirectoryStream(aparte, "*.jar")) {
            int n = 0;
            for (Path ignored : flujo) {
                n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------- estado

    private void guardar() {
        try {
            Files.createDirectories(archivoEstado.getParent());
            JsonObject raiz = new JsonObject();
            raiz.addProperty("estado", estado.name());
            raiz.addProperty("vuelta", vuelta);
            if (culpable != null) {
                raiz.addProperty("culpable", culpable);
            }
            raiz.add("candidatos", arreglo(candidatos));
            raiz.add("apagadosAhora", arreglo(apagadosAhora));

            JsonArray pasos = new JsonArray();
            for (Paso p : historial) {
                JsonObject o = new JsonObject();
                o.addProperty("numero", p.numero());
                o.addProperty("candidatosAntes", p.candidatosAntes());
                o.addProperty("desactivados", p.desactivados());
                o.addProperty("seguiaFallando", p.seguiaFallando());
                pasos.add(o);
            }
            raiz.add("historial", pasos);

            Files.writeString(archivoEstado, raiz.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] No pude guardar el estado de la biseccion: {}", t.toString());
        }
    }

    private void cargar() {
        if (!Files.isRegularFile(archivoEstado)) {
            return;
        }
        try {
            JsonObject raiz = JsonParser.parseString(
                    Files.readString(archivoEstado, StandardCharsets.UTF_8)).getAsJsonObject();

            estado = Estado.valueOf(raiz.get("estado").getAsString());
            vuelta = raiz.get("vuelta").getAsInt();
            culpable = raiz.has("culpable") ? raiz.get("culpable").getAsString() : null;

            candidatos.clear();
            for (var el : raiz.getAsJsonArray("candidatos")) {
                candidatos.add(el.getAsString());
            }
            apagadosAhora.clear();
            if (raiz.has("apagadosAhora")) {
                for (var el : raiz.getAsJsonArray("apagadosAhora")) {
                    apagadosAhora.add(el.getAsString());
                }
            }
            historial.clear();
            if (raiz.has("historial")) {
                for (var el : raiz.getAsJsonArray("historial")) {
                    JsonObject o = el.getAsJsonObject();
                    historial.add(new Paso(o.get("numero").getAsInt(),
                            o.get("candidatosAntes").getAsInt(),
                            o.get("desactivados").getAsInt(),
                            o.get("seguiaFallando").getAsBoolean()));
                }
            }
            historial.sort(Comparator.comparingInt(Paso::numero));
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] Estado de biseccion ilegible, se ignora: {}", t.toString());
            estado = Estado.INACTIVA;
        }
    }

    private static JsonArray arreglo(List<String> lista) {
        JsonArray a = new JsonArray();
        for (String s : lista) {
            a.add(s);
        }
        return a;
    }

    /** Explicacion de la vuelta actual, en criollo. */
    public String instruccion() {
        return switch (estado) {
            case INACTIVA -> "Sin sesion activa. Empeza una cuando tengas un problema que se "
                    + "repite y no sepas de donde viene.";
            case ESPERANDO_PRUEBA -> "Aparté " + apagadosAhora.size() + " mods. Cerrá el juego, "
                    + "volvé a abrirlo, fijate si el problema sigue pasando, y contestá acá.";
            case TERMINADA -> culpable != null
                    ? "El culpable es " + culpable + ". Todo lo demas ya volvio a su lugar."
                    : "Terminada sin culpable identificado.";
        };
    }

    public String resumenProgreso() {
        if (estado == Estado.INACTIVA) {
            return "";
        }
        int restantes = arranquesEstimados(candidatos.size());
        return "Vuelta " + vuelta + "  ·  " + candidatos.size() + " candidatos  ·  "
                + (restantes == 0 ? "listo" : "faltan ~" + restantes + " arranques")
                + String.format(Locale.ROOT, "  ·  ya descartaste %d mods", descartados());
    }

    private int descartados() {
        if (historial.isEmpty()) {
            return 0;
        }
        return historial.get(0).candidatosAntes() - candidatos.size();
    }
}
