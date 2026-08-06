package com.coco.faro.diag;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Resultado de analizar un crash report. Inmutable una vez construido.
 *
 * Nota de honestidad: {@link #modSospechoso()} puede venir vacio. Eso es un
 * resultado valido y esperado, no un bug. La UI debe mostrar "no pude determinar
 * la causa" en ese caso, nunca rellenar con un mod al azar.
 */
public final class Diagnostico {

    private final boolean huboCrash;
    private final Path archivoAnalizado;
    private final Instant fechaCrash;
    private final String descripcion;
    private final String excepcionPrincipal;
    private final TipoProblema tipo;
    private final Confianza confianza;
    private final String modSospechoso;
    private final Path jarSospechoso;
    private final List<String> evidencia;
    private final List<String> lineasStack;
    private final List<Firma.Coincidencia> firmas;
    private final List<Sospechoso> ranking;
    private final String sugerencia;

    private Diagnostico(Builder b) {
        this.huboCrash = b.huboCrash;
        this.archivoAnalizado = b.archivoAnalizado;
        this.fechaCrash = b.fechaCrash;
        this.descripcion = b.descripcion;
        this.excepcionPrincipal = b.excepcionPrincipal;
        this.tipo = b.tipo == null ? TipoProblema.DESCONOCIDO : b.tipo;
        this.confianza = b.confianza == null ? Confianza.NINGUNA : b.confianza;
        this.modSospechoso = b.modSospechoso;
        this.jarSospechoso = b.jarSospechoso;
        this.evidencia = Collections.unmodifiableList(new ArrayList<>(b.evidencia));
        this.lineasStack = Collections.unmodifiableList(new ArrayList<>(b.lineasStack));
        this.firmas = Collections.unmodifiableList(new ArrayList<>(b.firmas));
        this.ranking = Collections.unmodifiableList(new ArrayList<>(b.ranking));
        this.sugerencia = b.sugerencia;
    }

    /** Firmas conocidas que coincidieron con este crash, de mayor a menor peso. */
    public List<Firma.Coincidencia> firmas() {
        return firmas;
    }

    /** Todos los candidatos con su puntaje, no solo el primero. */
    public List<Sospechoso> ranking() {
        return ranking;
    }

    /**
     * El crash contado en castellano simple, para alguien que no programa.
     *
     * Responde las tres preguntas que uno se hace cuando el juego se cierra:
     * que paso, quien parece haberlo causado, y en que archivo esta.
     *
     * Nunca afirma mas de lo que se sabe: si no hay culpable identificado, lo
     * dice con esas palabras en vez de dejar la frase a medias o inventar uno.
     */
    public String explicacionSimple() {
        StringBuilder sb = new StringBuilder();

        sb.append("El juego se cerro solo. ");
        sb.append(switch (tipo) {
            case FALTA_MEMORIA ->
                    "Se quedo sin memoria: no es culpa de un mod puntual, el pack entero "
                            + "necesita mas RAM de la que tiene asignada.";
            case DEPENDENCIA_FALTANTE ->
                    "Un mod necesitaba otro mod que no esta instalado.";
            case CONFLICTO_ENTRE_MODS ->
                    "Dos mods se pisaron entre si.";
            case MIXIN_FALLIDO ->
                    "Un mod intento modificar el juego por dentro y no encontro lo que "
                            + "esperaba. Suele pasar cuando esta hecho para otra version.";
            case VERSION_INCORRECTA ->
                    "Hay un mod hecho para otra version de Minecraft, de Forge o de una "
                            + "libreria.";
            case ERROR_DE_MUNDO ->
                    "El mundo guardado tiene cosas de un mod que ya no esta instalado.";
            case EXCEPCION_GENERICA, DESCONOCIDO ->
                    "El error no dice con claridad que lo provoco.";
        });

        if (modSospechoso != null && confianza != Confianza.NINGUNA) {
            sb.append("\n\nEl mod que parece responsable es '").append(modSospechoso).append("'");
            if (jarSospechoso != null) {
                sb.append(", en el archivo ").append(jarSospechoso.getFileName());
            }
            sb.append(". ");
            sb.append(confianza == Confianza.ALTA
                    ? "Esto lo dijo Forge directamente, asi que es bastante seguro."
                    : "Esto lo deduje del error, asi que puede estar equivocado.");
        } else {
            sb.append("\n\nNo pude identificar que mod lo causo: el error solo pasa por "
                    + "codigo del juego, o el texto no alcanza para atribuirlo a nadie.");
        }

        return sb.toString();
    }

    /** Certeza de esta explicacion, para mostrarla siempre etiquetada. */
    public Certeza certeza() {
        return Certeza.de(confianza);
    }

    /** Que hacer al respecto, en criollo. Sale de la firma de mayor peso. */
    public String sugerencia() {
        if (sugerencia != null && !sugerencia.isBlank()) {
            return sugerencia;
        }
        return "Abri el detalle tecnico y revisá el stacktrace, o mandaselo a alguien "
                + "que pueda leerlo.";
    }

    /** Estado para cuando no hay ningun crash report en la carpeta. */
    public static Diagnostico sinCrash() {
        return new Builder().huboCrash(false).tipo(TipoProblema.DESCONOCIDO)
                .confianza(Confianza.NINGUNA).build();
    }

    public boolean huboCrash() {
        return huboCrash;
    }

    public Optional<Path> archivoAnalizado() {
        return Optional.ofNullable(archivoAnalizado);
    }

    public Optional<Instant> fechaCrash() {
        return Optional.ofNullable(fechaCrash);
    }

    public String descripcion() {
        return descripcion == null ? "" : descripcion;
    }

    public String excepcionPrincipal() {
        return excepcionPrincipal == null ? "" : excepcionPrincipal;
    }

    public TipoProblema tipo() {
        return tipo;
    }

    public Confianza confianza() {
        return confianza;
    }

    /** Mod id sospechoso, o vacio si no se pudo determinar. */
    public Optional<String> modSospechoso() {
        return Optional.ofNullable(modSospechoso);
    }

    /** Ruta al .jar del mod sospechoso, o vacio si no se pudo resolver. */
    public Optional<Path> jarSospechoso() {
        return Optional.ofNullable(jarSospechoso);
    }

    /** Lineas concretas del reporte que justifican la conclusion. */
    public List<String> evidencia() {
        return evidencia;
    }

    public List<String> lineasStack() {
        return lineasStack;
    }

    /**
     * Unica fuente de verdad para habilitar el boton de reparar.
     * Exige las tres cosas a la vez: confianza suficiente, que desactivar
     * sirva para este tipo de problema, y que tengamos un jar concreto que mover.
     */
    public boolean puedeRepararse() {
        return huboCrash
                && confianza.permiteReparar()
                && tipo.reparablePorDesactivacion()
                && jarSospechoso != null;
    }

    /** Mensaje que explica por que NO se puede reparar. Para mostrar en el boton deshabilitado. */
    public String motivoNoReparable() {
        if (!huboCrash) {
            return "No hay ningun crash reciente para reparar.";
        }
        if (!confianza.permiteReparar()) {
            return "No encontre una causa clara para arreglar automaticamente.";
        }
        if (!tipo.reparablePorDesactivacion()) {
            if (tipo == TipoProblema.DEPENDENCIA_FALTANTE) {
                return "Falta un mod, no sobra. Hay que instalar la dependencia, no desactivar nada.";
            }
            if (tipo == TipoProblema.FALTA_MEMORIA) {
                return "Es falta de RAM. Subi la memoria asignada en el launcher.";
            }
            return "Desactivar un mod no resuelve este tipo de problema.";
        }
        return "No pude ubicar el archivo .jar del mod sospechoso.";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean huboCrash = true;
        private Path archivoAnalizado;
        private Instant fechaCrash;
        private String descripcion;
        private String excepcionPrincipal;
        private TipoProblema tipo;
        private Confianza confianza;
        private String modSospechoso;
        private Path jarSospechoso;
        private final List<String> evidencia = new ArrayList<>();
        private final List<String> lineasStack = new ArrayList<>();
        private final List<Firma.Coincidencia> firmas = new ArrayList<>();
        private final List<Sospechoso> ranking = new ArrayList<>();
        private String sugerencia;

        public Builder firmas(List<Firma.Coincidencia> v) {
            this.firmas.clear();
            if (v != null) {
                this.firmas.addAll(v);
            }
            return this;
        }

        public Builder ranking(List<Sospechoso> v) {
            this.ranking.clear();
            if (v != null) {
                this.ranking.addAll(v);
            }
            return this;
        }

        public Builder sugerencia(String v) {
            this.sugerencia = v;
            return this;
        }

        public Builder huboCrash(boolean v) {
            this.huboCrash = v;
            return this;
        }

        public Builder archivoAnalizado(Path v) {
            this.archivoAnalizado = v;
            return this;
        }

        public Builder fechaCrash(Instant v) {
            this.fechaCrash = v;
            return this;
        }

        public Builder descripcion(String v) {
            this.descripcion = v;
            return this;
        }

        public Builder excepcionPrincipal(String v) {
            this.excepcionPrincipal = v;
            return this;
        }

        public Builder tipo(TipoProblema v) {
            this.tipo = v;
            return this;
        }

        public Builder confianza(Confianza v) {
            this.confianza = v;
            return this;
        }

        public Builder modSospechoso(String v) {
            this.modSospechoso = v;
            return this;
        }

        public Builder jarSospechoso(Path v) {
            this.jarSospechoso = v;
            return this;
        }

        public Builder agregarEvidencia(String v) {
            if (v != null && !v.isBlank()) {
                this.evidencia.add(v.trim());
            }
            return this;
        }

        public Builder agregarLineaStack(String v) {
            if (v != null && !v.isBlank()) {
                this.lineasStack.add(v.trim());
            }
            return this;
        }

        public Diagnostico build() {
            return new Diagnostico(this);
        }
    }
}
