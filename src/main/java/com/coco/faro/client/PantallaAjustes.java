package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MotorDiagnostico;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Ajustes de Faro dentro del juego.
 *
 * Todo lo que se puede tocar aca se guarda al instante en el .toml: la idea es
 * que nadie tenga que abrir un archivo de configuracion a mano para prender el
 * HUD o mover un aviso de esquina.
 */
public class PantallaAjustes extends Screen {

    private final Screen anterior;

    public PantallaAjustes(Screen anterior) {
        super(Component.literal("Faro — ajustes"));
        this.anterior = anterior;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        ConfigFaro c = ConfigFaro.INSTANCIA;

        int ancho = Math.min(this.width - 40, 300);
        int x = (this.width - ancho) / 2;
        int y = 44;
        int paso = 24;

        y = alternar(x, y, ancho, "HUD de ticks / TPS", c.hudTicksActivo);
        y = alternarEsquina(x, y, ancho, "  esquina del HUD", c.hudTicksEsquina);
        y = alternar(x, y, ancho, "  HUD detallado (p95 y RAM)", c.hudTicksDetallado);
        y += 6;

        y = alternar(x, y, ancho, "Aviso de errores en pantalla", c.overlayActivo);
        y = alternarEsquina(x, y, ancho, "  esquina del aviso", c.overlayEsquina);
        y = alternar(x, y, ancho, "  solo errores (ignorar avisos)", c.overlaySoloErrores);
        y += 6;

        y = alternar(x, y, ancho, "Notificaciones estilo logro", c.notificacionesActivas);
        y = alternar(x, y, ancho, "Re-analizar al abrir Faro", c.autoAnalizarAlAbrir);
        y = alternar(x, y, ancho, "Boton de Faro en los menus", c.botonEnMenuPrincipal);
        y += 10;

        // Recargar recursos: la unica parte del "arreglar sin reiniciar" que es
        // realmente posible. Los mods no se pueden recargar en caliente porque
        // la JVM no permite reemplazar clases ya cargadas, pero las texturas y
        // modelos si: es lo mismo que hace F3+T.
        addRenderableWidget(Button.builder(
                        Component.literal("Recargar texturas y modelos (sin reiniciar)"),
                        b -> {
                            Minecraft.getInstance().reloadResourcePacks();
                            onClose();
                        })
                .bounds(x, y, ancho, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Volver a analizar la instalacion"),
                        b -> {
                            MotorDiagnostico m = MotorDiagnostico.get();
                            if (m != null) {
                                m.reanalizar();
                            }
                            onClose();
                        })
                .bounds(x, y, ancho, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Abrir carpeta de config"), b -> {
                    MotorDiagnostico m = MotorDiagnostico.get();
                    if (m != null) {
                        Util.getPlatform().openFile(
                                m.carpetaJuego().resolve("config").toFile());
                    }
                })
                .bounds(x, y, ancho, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                .bounds(this.width / 2 - 60, this.height - 28, 120, 20).build());
    }

    /** Fila de si/no que persiste el cambio al instante. */
    private int alternar(int x, int y, int ancho, String etiqueta,
                         ForgeConfigSpec.BooleanValue valor) {
        boolean activo = valor.get();
        addRenderableWidget(Button.builder(
                        Component.literal(etiqueta + ": " + (activo ? "si" : "no")),
                        b -> {
                            valor.set(!valor.get());
                            valor.save();
                            this.clearWidgets();
                            init();
                        })
                .bounds(x, y, ancho, 20).build());
        return y + 24;
    }

    /** Rota entre las cuatro esquinas. */
    private int alternarEsquina(int x, int y, int ancho, String etiqueta,
                                ForgeConfigSpec.EnumValue<ConfigFaro.Esquina> valor) {
        ConfigFaro.Esquina actual = valor.get();
        addRenderableWidget(Button.builder(
                        Component.literal(etiqueta + ": " + nombreLindo(actual)),
                        b -> {
                            ConfigFaro.Esquina[] todas = ConfigFaro.Esquina.values();
                            valor.set(todas[(valor.get().ordinal() + 1) % todas.length]);
                            valor.save();
                            this.clearWidgets();
                            init();
                        })
                .bounds(x, y, ancho, 20).build());
        return y + 24;
    }

    private static String nombreLindo(ConfigFaro.Esquina e) {
        return switch (e) {
            case ARRIBA_IZQUIERDA -> "arriba izq.";
            case ARRIBA_DERECHA -> "arriba der.";
            case ABAJO_IZQUIERDA -> "abajo izq.";
            case ABAJO_DERECHA -> "abajo der.";
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, "Ajustes de Faro", this.width / 2, 16, Paleta.TEXTO_TITULO);
        g.drawCenteredString(this.font, "los cambios se guardan al instante",
                this.width / 2, 28, Paleta.TEXTO_APAGADO);
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
