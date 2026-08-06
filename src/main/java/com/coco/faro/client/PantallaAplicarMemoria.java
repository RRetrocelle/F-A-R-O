package com.coco.faro.client;

import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.repair.ConfigLauncher;
import com.coco.faro.repair.RegistroAcciones;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Cambia la memoria asignada en la configuracion del launcher.
 *
 * Muestra el diff exacto antes de tocar nada, hace copia del archivo original y
 * espera confirmacion. Mientras escribe, se ve el barco navegando: es una
 * operacion corta pero conviene que se note que algo paso.
 */
public class PantallaAplicarMemoria extends Screen {

    private enum Fase { CONFIRMAR, APLICANDO, RESULTADO }

    private static final int TICKS = 30;

    private final Screen anterior;
    private final long nuevaMB;

    private ConfigLauncher.Estado estado;
    private Fase fase = Fase.CONFIRMAR;
    private int ticks = 0;
    private String resultado = "";

    public PantallaAplicarMemoria(Screen anterior, long nuevaMB) {
        super(Component.literal("Faro — memoria"));
        this.anterior = anterior;
        this.nuevaMB = nuevaMB;
    }

    private Path carpetaJuego() {
        MotorDiagnostico m = MotorDiagnostico.get();
        return m != null ? m.carpetaJuego() : Minecraft.getInstance().gameDirectory.toPath();
    }

    @Override
    protected void init() {
        this.clearWidgets();
        if (estado == null) {
            estado = ConfigLauncher.detectar(carpetaJuego());
        }
        int cx = this.width / 2;
        int y = this.height - 34;

        switch (fase) {
            case CONFIRMAR -> {
                if (estado.encontrado()) {
                    addRenderableWidget(Button.builder(
                                    Component.literal("Si, cambiar a " + nuevaMB + " MB"),
                                    b -> {
                                        fase = Fase.APLICANDO;
                                        ticks = 0;
                                        init();
                                    })
                            .bounds(cx - 154, y, 150, 20).build());
                }
                addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                        .bounds(cx + 4, y, 150, 20).build());
            }
            case APLICANDO -> {
                // Sin botones mientras se escribe el archivo.
            }
            case RESULTADO -> addRenderableWidget(
                    Button.builder(Component.literal("Volver"), b -> onClose())
                            .bounds(cx - 80, y, 160, 20).build());
        }
    }

    @Override
    public void tick() {
        if (fase != Fase.APLICANDO) {
            return;
        }
        ticks++;
        if (ticks == TICKS / 2) {
            MotorDiagnostico m = MotorDiagnostico.get();
            RegistroAcciones reg = m != null ? m.registro()
                    : new RegistroAcciones(carpetaJuego().resolve("faro"));
            resultado = ConfigLauncher.aplicarMemoria(carpetaJuego(), nuevaMB, reg);
        }
        if (ticks >= TICKS) {
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

        g.drawCenteredString(this.font, "Cambiar memoria asignada", cx, 14, Paleta.TEXTO_TITULO);

        switch (fase) {
            case CONFIRMAR -> {
                int y = 34;
                if (!estado.encontrado()) {
                    Widgets.parrafo(g, this.font,
                            estado.detalle() + "\n\nPodes cambiar la memoria desde la interfaz "
                                    + "de tu launcher, en los ajustes de esta instancia.",
                            x, y, ancho, Paleta.ADVERTENCIA, 6);
                    return;
                }
                y = Widgets.parrafo(g, this.font,
                        "Esto edita el archivo de configuracion del launcher. "
                                + "Solo cambia el valor de memoria; nada mas se toca.",
                        x, y, ancho, Paleta.TEXTO_TENUE, 3);
                y += 8;
                int alto = 62;
                Widgets.tarjeta(g, x, y, ancho, alto, Paleta.NEUTRO);
                Widgets.parrafo(g, this.font, ConfigLauncher.diff(estado, nuevaMB),
                        x + 6, y + 5, ancho - 12, Paleta.TEXTO, 8);
                y += alto + 8;
                Widgets.parrafo(g, this.font,
                        "Despues hay que cerrar el juego Y el launcher: el valor se lee al "
                                + "arrancar, y si el launcher esta abierto puede pisar el archivo.",
                        x, y, ancho, Paleta.ADVERTENCIA, 4);
            }
            case APLICANDO -> {
                AnimacionBarcos.navegando(g, cx, this.height / 2 - 30, 2);
                g.drawCenteredString(this.font, "Guardando la configuracion...",
                        cx, this.height / 2 + 20, Paleta.TEXTO_TITULO);
            }
            case RESULTADO -> {
                boolean ok = resultado.startsWith("Listo");
                g.drawCenteredString(this.font, ok ? "Guardado" : "No se pudo",
                        cx, 40, ok ? Paleta.OK : Paleta.ERROR);
                Widgets.parrafo(g, this.font, resultado, x, 60, ancho, Paleta.TEXTO, 10);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(anterior);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
