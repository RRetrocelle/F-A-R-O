package com.coco.faro.client;

import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.repair.RegistroAcciones;
import com.coco.faro.repair.ServicioReparacion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confirmacion para desactivar un .jar puntual.
 *
 * Distinta de {@link PantallaConfirmarReparacion}, que se usa cuando el culpable
 * sale del analisis de un crash. Esta se abre desde un problema concreto de la
 * pestana Problemas (un duplicado, un jar de otro loader) donde la causa ya es
 * indiscutible y no hace falta hablar de puntajes ni confianza.
 *
 * Como siempre: se muestra el archivo exacto, adonde va, y no se toca nada hasta
 * que el usuario aprieta el boton.
 */
public class PantallaConfirmarDesactivacion extends Screen {

    private enum Fase { CONFIRMAR, APLICANDO, RESULTADO }

    private static final int TICKS_ANIMACION = 24;

    private final Screen anterior;
    private final Path jar;
    private final String modId;
    private final String motivo;

    private Fase fase = Fase.CONFIRMAR;
    private int ticks = 0;
    private ServicioReparacion.Resultado resultado;
    private long tamano = -1L;

    public PantallaConfirmarDesactivacion(Screen anterior, Path jar, String modId, String motivo) {
        super(Component.literal("Faro — deshabilitar mod"));
        this.anterior = anterior;
        this.jar = jar;
        this.modId = modId;
        this.motivo = motivo;
        try {
            this.tamano = Files.size(jar);
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        int ancho = 150;
        int y = this.height - 34;

        switch (fase) {
            case CONFIRMAR -> {
                boolean protegido = ServicioReparacion.esProtegido(modId);
                Button si = Button.builder(Component.literal("Si, deshabilitar"), b -> aplicar())
                        .bounds(cx - ancho - 4, y, ancho, 20).build();
                si.active = !protegido;
                addRenderableWidget(si);
                addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                        .bounds(cx + 4, y, ancho, 20).build());
            }
            case APLICANDO -> {
                // Sin botones mientras se mueve el archivo.
            }
            case RESULTADO -> {
                addRenderableWidget(Button.builder(Component.literal("Cerrar Minecraft"),
                                b -> cerrarJuego())
                        .bounds(cx - ancho - 4, y, ancho, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Seguir aca"), b -> onClose())
                        .bounds(cx + 4, y, ancho, 20).build());
            }
        }
    }

    private void aplicar() {
        fase = Fase.APLICANDO;
        ticks = 0;
        init();
    }

    private void ejecutar() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        RegistroAcciones registro = motor != null ? motor.registro()
                : new RegistroAcciones(Minecraft.getInstance().gameDirectory.toPath().resolve("faro"));
        ServicioReparacion servicio = motor != null ? motor.reparacion()
                : new ServicioReparacion(registro);
        resultado = servicio.desactivar(jar, modId, motivo);
    }

    private void cerrarJuego() {
        try {
            this.minecraft.stop();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void tick() {
        if (fase != Fase.APLICANDO) {
            return;
        }
        ticks++;
        if (ticks == TICKS_ANIMACION / 2) {
            ejecutar();
        }
        if (ticks >= TICKS_ANIMACION) {
            fase = Fase.RESULTADO;
            init();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int cx = this.width / 2;
        int ancho = Math.min(this.width - 40, 420);
        int x = cx - ancho / 2;

        g.drawCenteredString(this.font, "Deshabilitar un mod", cx, 14, Paleta.TEXTO_TITULO);

        switch (fase) {
            case CONFIRMAR -> renderConfirmar(g, x, ancho, cx);
            case APLICANDO -> {
                g.drawCenteredString(this.font, "Aplicando cambios...", cx,
                        this.height / 2 - 10, Paleta.TEXTO_TITULO);
                Widgets.barra(g, cx - 90, this.height / 2 + 4, 180, 6,
                        ticks / (float) TICKS_ANIMACION, Paleta.BORDE_ACENTO);
            }
            case RESULTADO -> renderResultado(g, x, ancho, cx);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderConfirmar(GuiGraphics g, int x, int ancho, int cx) {
        int y = 34;

        if (ServicioReparacion.esProtegido(modId)) {
            y = Widgets.parrafo(g, this.font,
                    "'" + modId + "' es una libreria de la que dependen otros mods. "
                            + "Sacarla romperia varias cosas a la vez, asi que Faro no la "
                            + "deshabilita. Si estas seguro, moveé el archivo a mano.",
                    x, y, ancho, Paleta.ADVERTENCIA, 5);
            return;
        }

        y = Widgets.parrafo(g, this.font, "Motivo: " + motivo, x, y, ancho, Paleta.TEXTO_TENUE, 3);
        y += 6;

        int alto = 40;
        Widgets.tarjeta(g, x, y, ancho, alto, Paleta.ADVERTENCIA);
        g.drawString(this.font, "Archivo", x + 8, y + 6, Paleta.TEXTO_APAGADO, false);
        Widgets.lineaRecortada(g, this.font, jar.getFileName().toString(),
                x + 56, y + 6, ancho - 68, Paleta.TEXTO);
        g.drawString(this.font, "Mod", x + 8, y + 17, Paleta.TEXTO_APAGADO, false);
        Widgets.lineaRecortada(g, this.font, modId, x + 56, y + 17, ancho - 68, Paleta.TEXTO);
        g.drawString(this.font, "Tamano", x + 8, y + 28, Paleta.TEXTO_APAGADO, false);
        g.drawString(this.font, tamano < 0 ? "?" : (tamano / 1024) + " KB",
                x + 56, y + 28, Paleta.TEXTO, false);
        y += alto + 8;

        g.drawString(this.font, "Que va a cambiar", x, y, Paleta.TEXTO_TITULO, false);
        y += 11;
        Widgets.parrafo(g, this.font,
                "El archivo se MUEVE a mods/" + ServicioReparacion.CARPETA_DESTINO + "/\n"
                        + "No se borra nada. Ningun otro archivo se toca. Para revertirlo, "
                        + "movelo de vuelta a mods/ — no hace falta Faro para eso.",
                x, y, ancho, Paleta.TEXTO_TENUE, 5);
    }

    private void renderResultado(GuiGraphics g, int x, int ancho, int cx) {
        if (resultado == null) {
            g.drawCenteredString(this.font, "No se realizo ningun cambio.", cx, 40, Paleta.ADVERTENCIA);
            return;
        }
        int y = 36;
        g.drawCenteredString(this.font, resultado.exito() ? "Listo" : "No se pudo",
                cx, y, resultado.exito() ? Paleta.OK : Paleta.ERROR);
        y += 20;
        y = Widgets.parrafo(g, this.font, resultado.mensaje(), x, y, ancho, Paleta.TEXTO, 4);

        if (resultado.exito()) {
            y += 8;
            y = Widgets.parrafo(g, this.font,
                    "Forge solo lee la carpeta mods al iniciar: hay que reabrir el juego "
                            + "para que tome efecto.",
                    x, y, ancho, Paleta.NEUTRO, 3);
            y += 6;
            Widgets.parrafo(g, this.font, "Anotado en faro/acciones.log.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 2);
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
