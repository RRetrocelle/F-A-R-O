package com.coco.faro.client;

import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.PredictorCompatibilidad;
import com.coco.faro.diag.Severidad;
import com.coco.faro.repair.RegistroAcciones;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Predictor de compatibilidad: que pasa si agrego este mod, antes de agregarlo.
 *
 * Da vuelta el flujo habitual (copiar, arrancar, reventar, leer el log). El
 * usuario deja el .jar en faro/probar/, Faro lo lee sin cargarlo y lo confronta
 * contra la instalacion actual.
 *
 * La pantalla separa siempre lo que es certeza de lo que es deduccion, porque
 * son cosas distintas y mezclarlas haria que el usuario desconfie de ambas.
 */
public class PantallaPredictor extends PantallaHerramienta {

    private List<Path> candidatos = new ArrayList<>();
    private int elegido = -1;
    private PredictorCompatibilidad.Prediccion prediccion;
    private volatile boolean analizando = false;
    private String mensaje = "";

    public PantallaPredictor(Screen anterior) {
        super(anterior, "Predictor de compatibilidad",
                "evalua un mod ANTES de instalarlo, sin tocar la carpeta mods");
        recargarCandidatos();
    }

    private void recargarCandidatos() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return;
        }
        PredictorCompatibilidad.prepararCarpeta(motor.carpetaJuego());
        candidatos = PredictorCompatibilidad.candidatos(motor.carpetaJuego());
        if (elegido >= candidatos.size()) {
            elegido = -1;
            prediccion = null;
        }
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int tercio = (ancho - 12) / 3;

        addRenderableWidget(Button.builder(Component.literal("Abrir carpeta faro/probar"),
                        b -> {
                            MotorDiagnostico m = MotorDiagnostico.get();
                            if (m != null) {
                                Util.getPlatform().openFile(
                                        PredictorCompatibilidad.prepararCarpeta(
                                                m.carpetaJuego()).toFile());
                            }
                        })
                .bounds(x, y, tercio, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Buscar de nuevo"),
                        b -> {
                            recargarCandidatos();
                            mensaje = candidatos.isEmpty()
                                    ? "No hay ningun .jar en faro/probar/."
                                    : candidatos.size() + " archivo(s) encontrados.";
                            refrescar();
                        })
                .bounds(x + tercio + 6, y, tercio, 20).build());

        boolean puedeInstalar = prediccion != null && prediccion.valido()
                && prediccion.veredicto() != PredictorCompatibilidad.Veredicto.NO;

        Button instalar = Button.builder(Component.literal("Mover a mods/ e instalar"),
                        b -> instalar())
                .bounds(x + (tercio + 6) * 2, y, tercio, 20).build();
        instalar.active = puedeInstalar;
        addRenderableWidget(instalar);
    }

    private void instalar() {
        if (elegido < 0 || elegido >= candidatos.size()) {
            return;
        }
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return;
        }
        Path origen = candidatos.get(elegido);
        try {
            Path destino = motor.carpetaMods().resolve(origen.getFileName().toString());
            if (Files.exists(destino)) {
                mensaje = "Ya existe un archivo con ese nombre en mods/. No lo piso.";
                AlertasSonoras.fallo();
                refrescar();
                return;
            }
            Files.move(origen, destino);
            RegistroAcciones registro = motor.registro();
            registro.anotar("PREDICTOR  instalado " + destino.getFileName()
                    + " desde faro/probar tras el analisis de compatibilidad.");
            registro.anotar("           Para deshacerlo: borra ese archivo de mods/.");

            mensaje = "Instalado. Reinicia el juego: Forge solo lee la carpeta mods al arrancar.";
            AlertasSonoras.listo();
            elegido = -1;
            prediccion = null;
            recargarCandidatos();
        } catch (Throwable t) {
            mensaje = "No pude moverlo: " + t.getMessage();
            AlertasSonoras.fallo();
        }
        refrescar();
    }

    private void analizar(int indice) {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null || indice < 0 || indice >= candidatos.size()) {
            return;
        }
        elegido = indice;
        prediccion = null;
        analizando = true;
        mensaje = "";

        Path jar = candidatos.get(indice);
        Thread t = new Thread(() -> {
            try {
                prediccion = PredictorCompatibilidad.analizar(jar, motor.jars(), motor.mixins());
            } catch (Throwable e) {
                prediccion = PredictorCompatibilidad.Prediccion.fallo("Fallo el analisis: " + e);
            } finally {
                analizando = false;
                Minecraft.getInstance().execute(this::refrescar);
            }
        }, "Faro-Predictor");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        if (!mensaje.isEmpty()) {
            y = veredicto(g, x, y, ancho, mensaje, Paleta.NEUTRO);
        }

        // --- Como se usa
        if (candidatos.isEmpty()) {
            y = seccion(g, x, y, ancho, "Como se usa");
            return Widgets.parrafo(g, this.font,
                    "1. Poné el .jar que querés evaluar en la carpeta faro/probar/ "
                            + "(el boton de abajo la abre).\n\n"
                            + "2. Volvé acá y tocá 'Buscar de nuevo'.\n\n"
                            + "3. Faro lo lee y lo compara contra tus 190 mods sin cargarlo. "
                            + "Forge ni lo mira mientras este ahi.\n\n"
                            + "4. Si el resultado te convence, el boton lo mueve a mods/.\n\n"
                            + "Lo que se gana: enterarte de que un mod va a romper otros tres "
                            + "ANTES de que pase, en vez de despues de un crash y media hora "
                            + "leyendo el log.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 20);
        }

        // --- Lista de candidatos
        y = seccion(g, x, y, ancho, "Archivos para evaluar (" + candidatos.size() + ")");
        for (int i = 0; i < candidatos.size(); i++) {
            Path p = candidatos.get(i);
            boolean sel = i == elegido;
            final int indice = i;

            int alto = 16;
            g.fill(x, y, x + ancho, y + alto, sel ? Paleta.FONDO_HOVER : Paleta.FONDO_TARJETA);
            Widgets.borde(g, x, y, ancho, alto, sel ? Paleta.BORDE_ACENTO : Paleta.BORDE_SUAVE);
            Widgets.lineaRecortada(g, this.font, p.getFileName().toString(),
                    x + 6, y + 4, ancho - 70, sel ? Paleta.TEXTO_TITULO : Paleta.TEXTO);

            String peso = tamano(p);
            g.drawString(this.font, peso, x + ancho - this.font.width(peso) - 6, y + 4,
                    Paleta.TEXTO_APAGADO, false);

            zonas.add(Zona.boton(x, y, ancho, alto, "analizar", () -> analizar(indice)));
            y += alto + 3;
        }
        y += 6;

        if (analizando) {
            return veredicto(g, x, y, ancho,
                    "Analizando... se abre el jar, se lee su mods.toml y su bytecode de mixins, "
                            + "y se cruza contra los 190 instalados.", Paleta.NEUTRO);
        }
        if (prediccion == null) {
            return vacio(g, x, y, ancho, "Tocá un archivo de la lista para analizarlo.");
        }
        if (!prediccion.valido()) {
            return veredicto(g, x, y, ancho, prediccion.error(), Paleta.ERROR);
        }

        // --- Veredicto
        var v = prediccion.veredicto();
        int color = switch (v) {
            case SEGURO -> Paleta.OK;
            case REVISAR -> Paleta.NEUTRO;
            case RIESGOSO -> Paleta.ADVERTENCIA;
            case NO -> Paleta.ERROR;
        };

        Widgets.tarjeta(g, x, y, ancho, 30, color);
        g.drawString(this.font, v.etiqueta, x + 8, y + 6, color, false);
        Widgets.lineaRecortada(g, this.font, v.resumen, x + 8, y + 18, ancho - 16, Paleta.TEXTO);
        y += 36;

        var c = prediccion.candidato();
        y = fila(g, x, y, ancho, "Mod", c.nombreVisible(), Paleta.TEXTO);
        y = fila(g, x, y, ancho, "modId", String.join(", ", c.modIds()), Paleta.TEXTO_TENUE);
        y = fila(g, x, y, ancho, "Version",
                c.version().isBlank() ? "(no declarada)" : c.version(), Paleta.TEXTO_TENUE);
        y = fila(g, x, y, ancho, "Loader", c.loader().name(),
                c.loader() == com.coco.faro.diag.MetadatosJar.Loader.FORGE
                        ? Paleta.OK : Paleta.ERROR);
        y = fila(g, x, y, ancho, "Dependencias que declara",
                String.valueOf(c.dependencias().size()), Paleta.TEXTO_TENUE);
        if (!c.modIdsAnidados().isEmpty()) {
            y = fila(g, x, y, ancho, "Trae adentro (JarInJar)",
                    String.join(", ", c.modIdsAnidados()), Paleta.VIOLETA);
        }
        y += 8;

        // --- Hallazgos por gravedad
        y = seccionHallazgos(g, x, y, ancho, "Impedimentos", Severidad.CRITICA, Paleta.ERROR);
        y = seccionHallazgos(g, x, y, ancho, "Riesgos", Severidad.ALTA, Paleta.ADVERTENCIA);
        y = seccionHallazgos(g, x, y, ancho, "Para revisar", Severidad.MEDIA, Paleta.NEUTRO);
        y = seccionHallazgos(g, x, y, ancho, "Datos", Severidad.INFO, Paleta.TEXTO_TENUE);

        // --- El limite del metodo, siempre visible
        y += 4;
        y = seccion(g, x, y, ancho, "Lo que esto NO puede predecir");
        y = Widgets.parrafo(g, this.font,
                "Conflictos funcionales que solo aparecen al jugar. Dos mods que registran el "
                        + "mismo bloque con comportamiento distinto, que se pisan una receta, o "
                        + "que asumen cosas contradictorias del mundo — nada de eso esta escrito "
                        + "en los metadatos, y deducirlo sin ejecutar el codigo no se puede.\n\n"
                        + "Los conflictos DECLARADOS si son certeza: cuando un mod dice en su "
                        + "propio archivo que es incompatible con otro, no hay margen de error. "
                        + "Los funcionales, no. Esa diferencia esta marcada en cada hallazgo de "
                        + "arriba con su etiqueta de certeza.",
                x, y, ancho, Paleta.TEXTO_APAGADO, 14);
        return y + 8;
    }

    private int seccionHallazgos(GuiGraphics g, int x, int y, int ancho, String titulo,
                                 Severidad sev, int color) {
        var lista = prediccion.por(sev);
        if (lista.isEmpty()) {
            return y;
        }
        y = seccion(g, x, y, ancho, titulo + " (" + lista.size() + ")");

        for (var h : lista) {
            int anchoBadge = Widgets.badge(g, this.font, h.certeza().etiqueta(), x, y,
                    PanelProblemas.colorCerteza(h.certeza()));
            zonas.add(Zona.ayuda(x, y, anchoBadge, 11, "certeza"));

            Widgets.lineaRecortada(g, this.font, h.titulo(), x + anchoBadge + 6, y + 2,
                    ancho - anchoBadge - 8, color);
            y += 14;

            y = Widgets.parrafo(g, this.font, h.detalle(), x + 6, y, ancho - 10,
                    Paleta.TEXTO_TENUE, 6);
            y = Widgets.parrafo(g, this.font, "-> " + h.queHacer(), x + 6, y, ancho - 10,
                    Paleta.NEUTRO, 5);
            y += 6;
        }
        return y + 2;
    }

    private static String tamano(Path p) {
        try {
            long kb = Files.size(p) / 1024;
            return kb > 1024 ? String.format("%.1f MB", kb / 1024.0) : kb + " KB";
        } catch (Throwable t) {
            return "?";
        }
    }
}
