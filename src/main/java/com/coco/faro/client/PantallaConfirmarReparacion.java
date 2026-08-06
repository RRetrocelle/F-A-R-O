package com.coco.faro.client;

import com.coco.faro.Faro;
import com.coco.faro.diag.Diagnostico;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.repair.ServicioReparacion;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Path;
import java.util.List;

/**
 * Confirmacion, aplicacion y resultado de una desactivacion.
 *
 * Nada se mueve hasta que el usuario confirma en esta pantalla, y el texto le
 * dice exactamente que archivo se va a mover, adonde, y con que nivel de
 * confianza fue senalado. Si el diagnostico se equivoco, el mensaje final
 * explica como deshacerlo a mano.
 */
public class PantallaConfirmarReparacion extends Screen {

    private enum Fase { CONFIRMAR, APLICANDO, RESULTADO }

    private static final int TICKS_ANIMACION = 30;      // ~1,5 s
    private static final int TICKS_HASTA_CERRAR = 100;  // ~5 s

    private final Screen anterior;
    private final Diagnostico diag;

    private Fase fase = Fase.CONFIRMAR;
    private int ticks = 0;
    private int ticksResultado = 0;
    private boolean cierreAutomatico = true;

    private ServicioReparacion.Resultado resultado;

    public PantallaConfirmarReparacion(Screen anterior, Diagnostico diag) {
        super(Component.literal("Faro — reparar"));
        this.anterior = anterior;
        this.diag = diag;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int ancho = 150;
        int cx = this.width / 2;

        switch (fase) {
            case CONFIRMAR -> {
                addRenderableWidget(Button.builder(Component.literal("Si, desactivar"), b -> aplicar())
                        .bounds(cx - ancho - 4, this.height - 40, ancho, 20)
                        .build());
                addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                        .bounds(cx + 4, this.height - 40, ancho, 20)
                        .build());
            }
            case APLICANDO -> {
                // Sin botones: la operacion de disco es breve y no se interrumpe.
            }
            case RESULTADO -> {
                addRenderableWidget(Button.builder(Component.literal("Cerrar Minecraft ahora"), b -> cerrarJuego())
                        .bounds(cx - ancho - 4, this.height - 40, ancho, 20)
                        .build());
                addRenderableWidget(Button.builder(Component.literal("Cancelar cierre"), b -> {
                            cierreAutomatico = false;
                            this.clearWidgets();
                            init();
                        })
                        .bounds(cx + 4, this.height - 40, ancho, 20)
                        .build());
            }
        }
    }

    private void aplicar() {
        fase = Fase.APLICANDO;
        ticks = 0;
        init();
    }

    private void ejecutarMovimiento() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return;
        }
        Path jar = diag.jarSospechoso().orElse(null);
        String modId = diag.modSospechoso().orElse("?");
        String motivo = diag.tipo().titulo() + " / confianza " + diag.confianza().etiqueta();

        resultado = motor.reparacion().desactivar(jar, modId, motivo);
        Faro.LOG.info("[Faro] Reparacion: {} -> {}", modId, resultado.mensaje());
    }

    private void cerrarJuego() {
        try {
            this.minecraft.stop();
        } catch (Throwable t) {
            Faro.LOG.error("[Faro] No pude cerrar el juego limpiamente", t);
        }
    }

    @Override
    public void tick() {
        if (fase == Fase.APLICANDO) {
            ticks++;
            if (ticks == TICKS_ANIMACION / 2) {
                ejecutarMovimiento();
            }
            if (ticks >= TICKS_ANIMACION) {
                fase = Fase.RESULTADO;
                ticksResultado = 0;
                init();
            }
        } else if (fase == Fase.RESULTADO && cierreAutomatico
                && resultado != null && resultado.exito()) {
            ticksResultado++;
            if (ticksResultado >= TICKS_HASTA_CERRAR) {
                cerrarJuego();
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int cx = this.width / 2;
        int ancho = Math.min(this.width - 40, 400);

        switch (fase) {
            case CONFIRMAR -> renderConfirmar(g, cx, ancho);
            case APLICANDO -> renderAplicando(g, cx);
            case RESULTADO -> renderResultado(g, cx, ancho);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderConfirmar(GuiGraphics g, int cx, int ancho) {
        g.drawCenteredString(this.font, "¿Desactivar este mod?", cx, 30, Paleta.TEXTO_TITULO);

        String mod = diag.modSospechoso().orElse("?");
        String archivo = diag.jarSospechoso().map(p -> p.getFileName().toString()).orElse("?");

        int y = 54;
        g.drawCenteredString(this.font, "Mod: " + mod, cx, y, Paleta.TEXTO);
        y += 12;
        g.drawCenteredString(this.font, "Archivo: " + archivo, cx, y, Paleta.TEXTO_TENUE);
        y += 12;
        g.drawCenteredString(this.font, "Confianza del diagnostico: " + diag.confianza().etiqueta(),
                cx, y, Paleta.porConfianza(diag.confianza()));
        y += 18;

        String aviso = diag.confianza().explicacion();
        for (FormattedCharSequence l : this.font.split(Component.literal(aviso), ancho)) {
            g.drawCenteredString(this.font, l, cx, y, Paleta.TEXTO_TENUE);
            y += 10;
        }

        y += 8;
        String queHace = "El .jar se mueve a mods/" + ServicioReparacion.CARPETA_DESTINO
                + "/. No se borra nada y podes devolverlo a mano cuando quieras.";
        for (FormattedCharSequence l : this.font.split(Component.literal(queHace), ancho)) {
            g.drawCenteredString(this.font, l, cx, y, Paleta.NEUTRO);
            y += 10;
        }

        if (diag.confianza() == com.coco.faro.diag.Confianza.MEDIA) {
            y += 6;
            String extra = "Ojo: esta conclusion sale del stacktrace, no de una confirmacion de Forge. "
                    + "Puede estar equivocada.";
            for (FormattedCharSequence l : this.font.split(Component.literal(extra), ancho)) {
                g.drawCenteredString(this.font, l, cx, y, Paleta.ADVERTENCIA);
                y += 10;
            }
        }
    }

    private void renderAplicando(GuiGraphics g, int cx) {
        g.drawCenteredString(this.font, "Aplicando cambios...", cx, this.height / 2 - 20, Paleta.TEXTO_TITULO);

        // Barra de progreso ligada al avance real de la animacion.
        int anchoBarra = 180;
        int x0 = cx - anchoBarra / 2;
        int y0 = this.height / 2;
        int avance = (int) (anchoBarra * Math.min(1.0, ticks / (double) TICKS_ANIMACION));

        g.fill(x0 - 1, y0 - 1, x0 + anchoBarra + 1, y0 + 7, Paleta.BORDE);
        g.fill(x0, y0, x0 + anchoBarra, y0 + 6, Paleta.FONDO_TARJETA);
        g.fill(x0, y0, x0 + avance, y0 + 6, Paleta.BORDE_ACENTO);

        g.drawCenteredString(this.font, "moviendo el archivo", cx, y0 + 16, Paleta.TEXTO_TENUE);
    }

    private void renderResultado(GuiGraphics g, int cx, int ancho) {
        if (resultado == null) {
            g.drawCenteredString(this.font, "No se realizo ningun cambio.", cx, 40, Paleta.ADVERTENCIA);
            return;
        }

        boolean ok = resultado.exito();
        g.drawCenteredString(this.font, ok ? "Listo" : "No se pudo",
                cx, 30, ok ? Paleta.OK : Paleta.ERROR);

        int y = 52;
        for (FormattedCharSequence l : this.font.split(Component.literal(resultado.mensaje()), ancho)) {
            g.drawCenteredString(this.font, l, cx, y, Paleta.TEXTO);
            y += 10;
        }

        if (ok) {
            y += 10;
            String nota = "Forge solo carga mods al iniciar, asi que el cambio recien toma efecto "
                    + "cuando vuelvas a abrir el juego.";
            for (FormattedCharSequence l : this.font.split(Component.literal(nota), ancho)) {
                g.drawCenteredString(this.font, l, cx, y, Paleta.NEUTRO);
                y += 10;
            }

            y += 8;
            String deshacer = "¿Me equivoque? Devolve el .jar de mods/"
                    + ServicioReparacion.CARPETA_DESTINO + "/ a mods/ y listo.";
            for (FormattedCharSequence l : this.font.split(Component.literal(deshacer), ancho)) {
                g.drawCenteredString(this.font, l, cx, y, Paleta.TEXTO_TENUE);
                y += 10;
            }

            if (cierreAutomatico) {
                int restante = Math.max(0, (TICKS_HASTA_CERRAR - ticksResultado) / 20 + 1);
                g.drawCenteredString(this.font, "Cerrando en " + restante + "...",
                        cx, this.height - 58, Paleta.ADVERTENCIA);
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(anterior);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
