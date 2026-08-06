package com.coco.faro.client;

import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.RangoVersion;
import com.coco.faro.net.ClienteModrinth;
import com.coco.faro.repair.InstaladorMods;
import com.coco.faro.repair.RegistroAcciones;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Buscar -> mostrar exactamente que se va a bajar -> esperar confirmacion -> instalar.
 *
 * El paso de confirmacion no se puede saltear ni automatizar: no existe ningun
 * camino de codigo que llegue a {@link #descargar()} sin que el usuario haya
 * apretado el boton viendo el nombre del archivo, la version, el tamano y la
 * fuente. Aunque haya diez dependencias faltantes, cada una pasa por esta
 * pantalla por separado.
 */
public class PantallaConfirmarInstalacion extends Screen {

    private enum Fase { BUSCANDO, ENCONTRADO, NO_ENCONTRADO, DESCARGANDO, RESULTADO }

    private final Screen anterior;
    private final String modId;
    private final RangoVersion rango;
    private final String pedidoPor;

    private volatile Fase fase = Fase.BUSCANDO;
    private volatile ClienteModrinth.Candidato candidato;
    private volatile InstaladorMods.Resultado resultado;
    private volatile boolean reemplazar = false;

    private final AnimacionFaro animacion = new AnimacionFaro();

    public PantallaConfirmarInstalacion(Screen anterior, String modId, RangoVersion rango,
                                        String pedidoPor) {
        super(Component.literal("Faro — instalar dependencia"));
        this.anterior = anterior;
        this.modId = modId;
        this.rango = rango;
        this.pedidoPor = pedidoPor;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        int ancho = 150;
        int y = this.height - 34;

        switch (fase) {
            case BUSCANDO -> lanzarBusqueda();

            case ENCONTRADO -> {
                addRenderableWidget(Button.builder(
                                Component.literal("Si, descargar e instalar"), b -> descargar())
                        .bounds(cx - ancho - 4, y, ancho, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                        .bounds(cx + 4, y, ancho, 20).build());
                // Descarga manual como alternativa siempre presente, no como
                // premio de consuelo cuando la automatica ya fallo.
                addRenderableWidget(Button.builder(
                                Component.literal("Prefiero bajarlo yo (abrir navegador)"),
                                b -> Util.getPlatform().openUri(
                                        "https://modrinth.com/mod/" + candidato.slug() + "/versions"))
                        .bounds(cx - 130, y - 24, 260, 20).build());
            }

            case RESULTADO -> {
                boolean yaExiste = resultado != null
                        && resultado.estado() == InstaladorMods.Estado.YA_EXISTE;

                if (yaExiste) {
                    // La salida real del bucle: apartar la version vieja al
                    // backup e instalar la correcta. Es reversible y se avisa.
                    addRenderableWidget(Button.builder(
                                    Component.literal("Reemplazar la version instalada"),
                                    b -> descargar(true))
                            .bounds(cx - 150, y - 24, 300, 20).build());
                }
                if (candidato != null) {
                    addRenderableWidget(Button.builder(
                                    Component.literal("Abrir en el navegador"),
                                    b -> Util.getPlatform().openUri(
                                            "https://modrinth.com/mod/" + candidato.slug() + "/versions"))
                            .bounds(cx - ancho - 4, y, ancho, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                            .bounds(cx + 4, y, ancho, 20).build());
                } else {
                    addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                            .bounds(cx - 80, y, 160, 20).build());
                }
            }

            case NO_ENCONTRADO -> {
                // Salida manual siempre disponible. Si el instalador automatico
                // no pudo (archivo ya existente, hash distinto, sin conexion),
                // el usuario tiene que poder resolverlo igual en vez de quedar
                // trabado viendo el mismo error una y otra vez.
                if (candidato != null) {
                    addRenderableWidget(Button.builder(
                                    Component.literal("Abrir en el navegador"),
                                    b -> Util.getPlatform().openUri(
                                            "https://modrinth.com/mod/" + candidato.slug() + "/versions"))
                            .bounds(cx - ancho - 4, y, ancho, 20).build());
                    addRenderableWidget(Button.builder(
                                    Component.literal("Abrir carpeta mods"),
                                    b -> {
                                        MotorDiagnostico m = MotorDiagnostico.get();
                                        if (m != null) {
                                            Util.getPlatform().openFile(m.carpetaMods().toFile());
                                        }
                                    })
                            .bounds(cx + 4, y, ancho, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                            .bounds(cx - 60, y - 24, 120, 20).build());
                } else {
                    addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                            .bounds(cx - 80, y, 160, 20).build());
                }
            }

            case DESCARGANDO -> {
                // Sin botones mientras hay una descarga en curso.
            }
        }
    }

    private void lanzarBusqueda() {
        Thread t = new Thread(() -> {
            ClienteModrinth.Candidato c = ClienteModrinth.buscar(modId, rango).orElse(null);
            this.candidato = c;
            this.fase = (c == null) ? Fase.NO_ENCONTRADO : Fase.ENCONTRADO;
            Minecraft.getInstance().execute(this::init);
        }, "Faro-BusquedaModrinth");
        t.setDaemon(true);
        t.start();
    }

    private void descargar() {
        descargar(false);
    }

    private void descargar(boolean reemplazando) {
        this.reemplazar = reemplazando;
        fase = Fase.DESCARGANDO;
        init();

        Thread t = new Thread(() -> {
            MotorDiagnostico motor = MotorDiagnostico.get();
            Path mods = motor != null ? motor.carpetaMods()
                    : Minecraft.getInstance().gameDirectory.toPath().resolve("mods");
            RegistroAcciones registro = motor != null ? motor.registro()
                    : new RegistroAcciones(Minecraft.getInstance().gameDirectory.toPath().resolve("faro"));

            this.resultado = new InstaladorMods(registro).instalar(candidato, mods, reemplazar);
            this.fase = Fase.RESULTADO;
            Minecraft.getInstance().execute(this::init);
        }, "Faro-Descarga");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int cx = this.width / 2;
        int ancho = Math.min(this.width - 40, 420);
        int x = cx - ancho / 2;

        g.drawCenteredString(this.font, "Instalar dependencia faltante", cx, 14, Paleta.TEXTO_TITULO);

        switch (fase) {
            case BUSCANDO -> {
                animacion.dibujar(g, cx, this.height / 2 - 30, 2);
                g.drawCenteredString(this.font, "Buscando '" + modId + "' en Modrinth...",
                        cx, this.height / 2 + 26, Paleta.NEUTRO);
                g.drawCenteredString(this.font, "lo unico que sale de tu PC es ese nombre",
                        cx, this.height / 2 + 38, Paleta.TEXTO_APAGADO);
            }
            case ENCONTRADO -> renderEncontrado(g, x, ancho, cx);
            case NO_ENCONTRADO -> renderNoEncontrado(g, x, ancho, cx);
            case DESCARGANDO -> {
                animacion.dibujar(g, cx, this.height / 2 - 30, 2);
                g.drawCenteredString(this.font, "Descargando...", cx, this.height / 2 + 26, Paleta.NEUTRO);
            }
            case RESULTADO -> renderResultado(g, x, ancho, cx);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderEncontrado(GuiGraphics g, int x, int ancho, int cx) {
        ClienteModrinth.Candidato c = candidato;
        if (c == null) {
            return;
        }
        int y = 32;

        y = Widgets.parrafo(g, this.font,
                "'" + pedidoPor + "' necesita el mod '" + modId + "' y no esta instalado. "
                        + "Esto es lo que encontre:",
                x, y, ancho, Paleta.TEXTO_TENUE, 3);
        y += 6;

        int alto = 74;
        Widgets.tarjeta(g, x, y, ancho, alto, c.cumpleRango() ? Paleta.OK : Paleta.ADVERTENCIA);
        int fx = x + 8;
        int fy = y + 6;

        fy = fila(g, fx, fy, ancho, "Proyecto", c.tituloProyecto());
        fy = fila(g, fx, fy, ancho, "Archivo", c.nombreArchivo());
        fy = fila(g, fx, fy, ancho, "Version", c.versionNumero() + "  (" + c.tipoVersion() + ")");
        fy = fila(g, fx, fy, ancho, "Tamano", c.tamanoLegible());
        fy = fila(g, fx, fy, ancho, "Fuente", c.fuente());
        fila(g, fx, fy, ancho, "SHA-1", c.sha1().isEmpty() ? "(no informado)" : c.sha1());

        y += alto + 6;

        if (c.cumpleRango()) {
            g.drawString(this.font, "Cumple el rango de version pedido: " + rango.original(),
                    x, y, Paleta.OK, false);
        } else {
            y = Widgets.parrafo(g, this.font,
                    "OJO: esta version NO cumple el rango pedido (" + rango.original() + "). "
                            + "Es la mas cercana que hay para 1.20.1 Forge. Puede no funcionar.",
                    x, y, ancho, Paleta.ADVERTENCIA, 3);
        }
        y += 14;

        // Vista previa de cambios: que se toca exactamente y que no.
        g.drawString(this.font, "Que va a cambiar", x, y, Paleta.TEXTO_TITULO, false);
        y += 11;
        Widgets.parrafo(g, this.font,
                "+ Se crea un archivo nuevo: mods/" + c.nombreArchivo() + "\n"
                        + "No se modifica ni se borra ningun otro archivo. Se verifica el SHA-1 "
                        + "antes de instalarlo, y si no coincide se descarta.",
                x, y, ancho, Paleta.TEXTO_TENUE, 4);
    }

    private int fila(GuiGraphics g, int x, int y, int ancho, String etiqueta, String valor) {
        g.drawString(this.font, etiqueta, x, y, Paleta.TEXTO_APAGADO, false);
        Widgets.lineaRecortada(g, this.font, valor, x + 52, y, ancho - 68, Paleta.TEXTO);
        return y + 11;
    }

    private void renderNoEncontrado(GuiGraphics g, int x, int ancho, int cx) {
        int y = 40;
        g.drawCenteredString(this.font, "No lo encontre en Modrinth", cx, y, Paleta.ADVERTENCIA);
        y += 20;
        y = Widgets.parrafo(g, this.font,
                "Busque '" + modId + "' para 1.20.1 Forge y no di con una coincidencia exacta. "
                        + "Prefiero no ofrecerte un mod parecido: instalar el equivocado es peor "
                        + "que no instalar nada.",
                x, y, ancho, Paleta.TEXTO_TENUE, 5);
        y += 8;
        Widgets.parrafo(g, this.font,
                "Puede estar solo en CurseForge, o llamarse distinto ahi. Buscalo a mano como '"
                        + modId + "' y poné el .jar en la carpeta mods.",
                x, y, ancho, Paleta.NEUTRO, 4);
    }

    private void renderResultado(GuiGraphics g, int x, int ancho, int cx) {
        InstaladorMods.Resultado r = resultado;
        if (r == null) {
            return;
        }
        int y = 40;
        g.drawCenteredString(this.font, r.exito() ? "Instalado" : "No se instalo",
                cx, y, r.exito() ? Paleta.OK : Paleta.ERROR);
        y += 20;
        y = Widgets.parrafo(g, this.font, r.mensaje(), x, y, ancho, Paleta.TEXTO, 4);
        y += 8;

        // El caso "ya existe" no es un fracaso del usuario ni un callejon sin
        // salida: significa que el archivo esta pero con otra version, o que el
        // mod que lo pide quiere un rango distinto. Se explica y se ofrece salida.
        if (r.estado() == com.coco.faro.repair.InstaladorMods.Estado.YA_EXISTE) {
            y = Widgets.parrafo(g, this.font,
                    "No lo piso por las dudas. Si el problema sigue apareciendo, es que la "
                            + "version instalada no sirve y hay que cambiarla.",
                    x, y, ancho, Paleta.ADVERTENCIA, 4);
            y += 8;
            g.drawString(this.font, "Que hace el boton de reemplazar", x, y,
                    Paleta.TEXTO_TITULO, false);
            y += 11;
            Widgets.parrafo(g, this.font,
                    "1) Busca TODOS los .jar de la carpeta que sean de este mismo mod, "
                            + "aunque el nombre del archivo sea distinto.\n"
                            + "2) Los MUEVE a mods/faro_backup/ (no los borra).\n"
                            + "3) Descarga e instala la version correcta, verificando el SHA-1.\n"
                            + "Para deshacerlo: borrá el nuevo y devolvé el viejo desde faro_backup.",
                    x, y, ancho, Paleta.NEUTRO, 8);
            return;
        }

        if (r.exito()) {
            y = Widgets.parrafo(g, this.font,
                    "Forge solo lee la carpeta mods al iniciar: cerra y volve a abrir el juego "
                            + "para que tome efecto.",
                    x, y, ancho, Paleta.NEUTRO, 3);
            y += 6;
            Widgets.parrafo(g, this.font,
                    "Quedo anotado en faro/acciones.log. Para deshacerlo, borra el archivo "
                            + "de la carpeta mods.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 3);
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
