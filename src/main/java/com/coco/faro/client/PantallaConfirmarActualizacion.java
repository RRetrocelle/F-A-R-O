package com.coco.faro.client;

import com.coco.faro.diag.ActualizadorMods;
import com.coco.faro.diag.MetadatosJar;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.repair.InstaladorMods;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Confirmacion de una actualizacion: mostrar exactamente que va a pasar y esperar.
 *
 * Misma regla que la instalacion de dependencias: no existe ningun camino de
 * codigo que descargue algo sin que el usuario haya visto el nombre del archivo,
 * la version, el tamano, el hash y —lo que es propio de esta pantalla— la lista
 * de mods que la actualizacion deja sin cargar.
 *
 * Ese ultimo dato es el que convierte una actualizacion a ciegas en una decision.
 * Se muestra en rojo y arriba, no escondido al final.
 */
public class PantallaConfirmarActualizacion extends Screen {

    private enum Fase { CONFIRMAR, DESCARGANDO, RESULTADO }

    private final Screen anterior;
    private final MetadatosJar jarViejo;
    private final ActualizadorMods.Info info;
    private final List<String> rompe;

    private volatile Fase fase = Fase.CONFIRMAR;
    private volatile InstaladorMods.Resultado resultado;

    private final AnimacionFaro animacion = new AnimacionFaro();

    public PantallaConfirmarActualizacion(Screen anterior, MetadatosJar jarViejo,
                                          ActualizadorMods.Info info, List<String> rompe) {
        super(Component.literal("Faro — actualizar mod"));
        this.anterior = anterior;
        this.jarViejo = jarViejo;
        this.info = info;
        this.rompe = rompe == null ? List.of() : rompe;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        int ancho = 150;
        int y = this.height - 30;

        switch (fase) {
            case CONFIRMAR -> {
                addRenderableWidget(Button.builder(
                                Component.literal(rompe.isEmpty()
                                        ? "Si, actualizar" : "Actualizar de todos modos"),
                                b -> descargar())
                        .bounds(cx - ancho - 4, y, ancho, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                        .bounds(cx + 4, y, ancho, 20).build());

                if (info.candidato() != null) {
                    addRenderableWidget(Button.builder(
                                    Component.literal("Prefiero bajarlo yo (abrir navegador)"),
                                    b -> Util.getPlatform().openUri("https://modrinth.com/mod/"
                                            + info.candidato().slug() + "/versions"))
                            .bounds(cx - 140, y - 24, 280, 20).build());
                }
            }
            case DESCARGANDO -> {
                // Sin botones mientras hay una descarga en curso.
            }
            case RESULTADO -> addRenderableWidget(
                    Button.builder(Component.literal("Volver"), b -> onClose())
                            .bounds(cx - 80, y, 160, 20).build());
        }
    }

    private void descargar() {
        fase = Fase.DESCARGANDO;
        init();

        Thread t = new Thread(() -> {
            MotorDiagnostico motor = MotorDiagnostico.get();
            try {
                // reemplazar = true: aparta TODAS las versiones viejas del mismo
                // mod al backup antes de instalar. Dejar dos jars del mismo mod
                // en la carpeta es un crash garantizado al arrancar.
                resultado = new InstaladorMods(motor.registro())
                        .instalar(info.candidato(), motor.carpetaMods(), true);
                if (resultado.exito()) {
                    AlertasSonoras.listo();
                } else {
                    AlertasSonoras.fallo();
                }
            } catch (Throwable e) {
                resultado = new InstaladorMods.Resultado(
                        InstaladorMods.Estado.ERROR_IO, "Fallo: " + e, null);
                AlertasSonoras.fallo();
            } finally {
                fase = Fase.RESULTADO;
                Minecraft.getInstance().execute(this::init);
            }
        }, "Faro-Actualizacion-Descarga");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int cx = this.width / 2;
        int ancho = Math.min(this.width - 40, 420);
        int x = cx - ancho / 2;

        g.drawCenteredString(this.font, "Actualizar " + jarViejo.nombreVisible(),
                cx, 12, Paleta.TEXTO_TITULO);

        switch (fase) {
            case CONFIRMAR -> renderConfirmar(g, x, ancho);
            case DESCARGANDO -> {
                animacion.dibujar(g, cx, this.height / 2 - 30, 2);
                g.drawCenteredString(this.font, "Descargando y verificando el hash...",
                        cx, this.height / 2 + 26, Paleta.NEUTRO);
            }
            case RESULTADO -> renderResultado(g, x, ancho, cx);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderConfirmar(GuiGraphics g, int x, int ancho) {
        var c = info.candidato();
        int y = 30;

        // Lo que rompe va PRIMERO. Si esta abajo, nadie lo lee.
        if (!rompe.isEmpty()) {
            int alto = 14 + this.font.split(Component.literal(String.join(", ", rompe)),
                    ancho - 14).size() * 10;
            Widgets.tarjeta(g, x, y, ancho, alto, Paleta.ERROR);
            g.drawString(this.font, "Esta actualizacion rompe otros mods", x + 7, y + 4,
                    Paleta.ERROR, false);
            Widgets.parrafo(g, this.font, String.join(", ", rompe), x + 7, y + 15,
                    ancho - 14, Paleta.TEXTO, 5);
            y += alto + 6;

            y = Widgets.parrafo(g, this.font,
                    "Esos mods declaran que necesitan una version que la nueva ya no cumple. "
                            + "Si actualizas igual, Forge no los va a cargar y vas a perder su "
                            + "contenido. Faro te lo dice antes, no despues.",
                    x, y, ancho, Paleta.ADVERTENCIA, 5) + 6;
        }

        Widgets.tarjeta(g, x, y, ancho, 74, rompe.isEmpty() ? Paleta.OK : Paleta.ADVERTENCIA);
        int fx = x + 8;
        int fy = y + 6;
        fy = fila(g, fx, fy, ancho, "Version actual", info.versionInstalada());
        fy = fila(g, fx, fy, ancho, "Version nueva", c.versionNumero() + "  (" + c.tipoVersion() + ")");
        fy = fila(g, fx, fy, ancho, "Archivo", c.nombreArchivo());
        fy = fila(g, fx, fy, ancho, "Tamano", c.tamanoLegible());
        fy = fila(g, fx, fy, ancho, "Fuente", c.fuente());
        fila(g, fx, fy, ancho, "SHA-1", c.sha1().isEmpty() ? "(no informado)" : c.sha1());
        y += 80;

        if (!"release".equals(c.tipoVersion())) {
            y = Widgets.parrafo(g, this.font,
                    "OJO: es una " + c.tipoVersion() + ", no una version estable. En un pack que "
                            + "funciona, cambiar a una pre-release por ser mas nueva suele ser "
                            + "mal negocio.",
                    x, y, ancho, Paleta.ADVERTENCIA, 4) + 4;
        }

        g.drawString(this.font, "Que va a pasar exactamente", x, y, Paleta.TEXTO_TITULO, false);
        y += 11;
        Widgets.parrafo(g, this.font,
                "1) Se descarga el archivo a un temporal y se verifica su SHA-1. Si no coincide, "
                        + "se descarta y no se instala nada.\n"
                        + "2) TODOS los .jar de este mismo mod que haya en la carpeta se MUEVEN a "
                        + "mods/faro_backup/ — no se borran.\n"
                        + "3) Se instala el nuevo.\n"
                        + "Para deshacerlo: borrá el nuevo y devolvé el viejo desde faro_backup.",
                x, y, ancho, Paleta.TEXTO_TENUE, 8);
    }

    private int fila(GuiGraphics g, int x, int y, int ancho, String etiqueta, String valor) {
        g.drawString(this.font, etiqueta, x, y, Paleta.TEXTO_APAGADO, false);
        Widgets.lineaRecortada(g, this.font, valor, x + 78, y, ancho - 92, Paleta.TEXTO);
        return y + 11;
    }

    private void renderResultado(GuiGraphics g, int x, int ancho, int cx) {
        if (resultado == null) {
            return;
        }
        int y = 40;
        g.drawCenteredString(this.font, resultado.exito() ? "Actualizado" : "No se actualizo",
                cx, y, resultado.exito() ? Paleta.OK : Paleta.ERROR);
        y += 20;
        y = Widgets.parrafo(g, this.font, resultado.mensaje(), x, y, ancho, Paleta.TEXTO, 5) + 8;

        if (resultado.exito()) {
            Widgets.parrafo(g, this.font,
                    "La version vieja quedo en mods/faro_backup/. Reinicia el juego para que "
                            + "tome efecto: Forge solo lee la carpeta mods al arrancar.\n\n"
                            + "Si algo se rompe, devolvé el .jar viejo desde faro_backup y borrá "
                            + "el nuevo. Todo quedo anotado en faro/acciones.log.",
                    x, y, ancho, Paleta.NEUTRO, 8);
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
