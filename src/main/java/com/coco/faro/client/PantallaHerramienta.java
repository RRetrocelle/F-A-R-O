package com.coco.faro.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Base de todas las pantallas de herramienta.
 *
 * Existe por una razon concreta: las quince herramientas nuevas necesitan lo
 * mismo —panel centrado, area recortada, scroll con barra arrastrable, zonas
 * clickeables dibujadas a mano, tooltips del glosario y un boton de volver—. Sin
 * esta clase, ese codigo estaria copiado quince veces y cualquier arreglo de
 * scroll habria que hacerlo quince veces.
 *
 * Cada herramienta solo implementa {@link #contenido}, que dibuja de arriba
 * hacia abajo devolviendo la Y final. El scroll, el recorte y los clicks los
 * maneja esta clase.
 *
 * La regla de interaccion se mantiene igual que en el resto de Faro: una zona
 * solo es clickeable si esta ENTERAMENTE visible. Un boton cortado por el borde
 * del recorte no se activa, porque no se puede ver que dice.
 */
public abstract class PantallaHerramienta extends Screen {

    private static final int ANCHO_BARRA = 6;

    private final Screen anterior;
    private final String subtitulo;

    protected final List<Zona> zonas = new ArrayList<>();
    private Glosario.Termino tooltip;

    protected int panelX;
    protected int panelAncho;
    protected int yContenido;
    protected int altoVisible;
    private int altoContenido;
    private int scroll = 0;

    private boolean arrastrando = false;
    private double agarre = 0;

    /** Posicion del mouse del cuadro actual, para no ensuciar las firmas. */
    protected int mx;
    protected int my;

    protected PantallaHerramienta(Screen anterior, String titulo, String subtitulo) {
        super(Component.literal(titulo));
        this.anterior = anterior;
        this.subtitulo = subtitulo;
    }

    /** Alto reservado abajo para los botones propios de cada herramienta. */
    protected int altoBotonera() {
        return 30;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        panelAncho = Math.min(this.width - 24, 460);
        panelX = (this.width - panelAncho) / 2;
        yContenido = 42;
        altoVisible = Math.max(60, this.height - yContenido - altoBotonera() - 26);

        botones(panelX, this.height - altoBotonera() - 2, panelAncho);

        addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                .bounds(this.width / 2 - 60, this.height - 24, 120, 20).build());
    }

    /**
     * Botones fijos de la herramienta. Se dibujan abajo, fuera del scroll.
     *
     * Van fuera del area con scroll a proposito: las acciones principales tienen
     * que estar siempre a la vista, no perdidas al final de una lista larga.
     */
    protected void botones(int x, int y, int ancho) {
    }

    /**
     * Dibuja el contenido de la herramienta.
     *
     * @param y la Y de arranque, ya desplazada por el scroll
     * @return la Y siguiente al ultimo elemento, para calcular el alto total
     */
    protected abstract int contenido(GuiGraphics g, int x, int y, int ancho);

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        this.mx = mouseX;
        this.my = mouseY;

        zonas.clear();
        tooltip = null;

        // Encabezado.
        Widgets.degradadoVertical(g, panelX, 4, panelAncho, 32,
                Paleta.FONDO_PANEL, Paleta.conAlfa(Paleta.FONDO_PANEL, 0.3f));
        g.fill(panelX, 4, panelX + panelAncho, 5, Paleta.BORDE_ACENTO);
        g.drawString(this.font, this.title.getString(), panelX + 8, 11,
                Paleta.TEXTO_TITULO, false);
        Widgets.lineaRecortada(g, this.font, subtitulo, panelX + 8, 24,
                panelAncho - 16, Paleta.TEXTO_APAGADO);
        Widgets.separador(g, panelX, yContenido - 3, panelAncho);

        g.enableScissor(panelX, yContenido, panelX + panelAncho, yContenido + altoVisible);
        int yInicio = yContenido + 4 - scroll;
        int yFin = yInicio;
        try {
            yFin = contenido(g, panelX + 6, yInicio, panelAncho - 16);
        } catch (Throwable t) {
            // Misma regla que en la pantalla principal: un fallo de dibujo se
            // muestra adentro y el resto de la interfaz sigue viva.
            com.coco.faro.Faro.LOG.error("[Faro] Fallo al dibujar {}", this.title.getString(), t);
            yFin = Widgets.parrafo(g, this.font,
                    "Esta herramienta fallo al dibujarse. El problema es de Faro, no de tu "
                            + "modpack. El detalle quedo en latest.log.\n\n" + t,
                    panelX + 8, yInicio + 6, panelAncho - 20, Paleta.ERROR, 8);
        }
        g.disableScissor();

        altoContenido = yFin - yInicio;
        dibujarBarra(g);

        for (Zona z : zonas) {
            if (z.claveGlosario() != null && z.contiene(mouseX, mouseY)
                    && z.visibleEntre(yContenido, yContenido + altoVisible)) {
                tooltip = Glosario.buscar(z.claveGlosario());
            }
        }

        super.render(g, mouseX, mouseY, partialTick);

        if (tooltip != null) {
            Glosario.dibujar(g, this.font, tooltip, mouseX, mouseY, this.width, this.height);
        }
    }

    // ------------------------------------------------------------- ayudantes

    /** Boton chico dentro del area con scroll. */
    protected void boton(GuiGraphics g, int x, int y, String etiqueta, int color, Runnable accion) {
        PanelProblemas.boton(g, this.font, x, y, etiqueta, color, mx, my,
                yContenido, yContenido + altoVisible, zonas, accion);
    }

    /** Titulo de seccion con linea debajo. Devuelve la Y siguiente. */
    protected int seccion(GuiGraphics g, int x, int y, int ancho, String texto) {
        g.drawString(this.font, texto, x, y, Paleta.TEXTO_TITULO, false);
        Widgets.separador(g, x, y + 10, ancho);
        return y + 15;
    }

    /** Fila de etiqueta a la izquierda y valor a la derecha. */
    protected int fila(GuiGraphics g, int x, int y, int ancho, String etiqueta,
                       String valor, int color) {
        Widgets.lineaRecortada(g, this.font, etiqueta, x, y,
                ancho - this.font.width(valor) - 8, Paleta.TEXTO_TENUE);
        g.drawString(this.font, valor, x + ancho - this.font.width(valor), y, color, false);
        return y + 11;
    }

    /** Tarjeta con un veredicto adentro. */
    protected int veredicto(GuiGraphics g, int x, int y, int ancho, String texto, int color) {
        List<net.minecraft.util.FormattedCharSequence> lineas =
                this.font.split(Component.literal(texto), ancho - 12);
        int alto = 8 + lineas.size() * 10;
        Widgets.tarjeta(g, x, y, ancho, alto, color);
        int yy = y + 4;
        for (var l : lineas) {
            g.drawString(this.font, l, x + 7, yy, Paleta.TEXTO, false);
            yy += 10;
        }
        return y + alto + 6;
    }

    /** Barra horizontal con etiqueta y valor, para rankings. */
    protected int barraDeRanking(GuiGraphics g, int x, int y, int ancho, String etiqueta,
                                 String valor, float fraccion, int color) {
        Widgets.lineaRecortada(g, this.font, etiqueta, x, y,
                ancho - this.font.width(valor) - 8, Paleta.TEXTO);
        g.drawString(this.font, valor, x + ancho - this.font.width(valor), y, color, false);
        Widgets.barra(g, x, y + 10, ancho, 4, fraccion, color);
        return y + 18;
    }

    protected void registrarAyuda(int x, int y, int ancho, int alto, String clave) {
        if (clave != null) {
            zonas.add(Zona.ayuda(x, y, ancho, alto, clave));
        }
    }

    /** Mensaje centrado para cuando no hay nada que mostrar. */
    protected int vacio(GuiGraphics g, int x, int y, int ancho, String texto) {
        return Widgets.parrafo(g, this.font, texto, x, y + 6, ancho, Paleta.TEXTO_APAGADO, 8) + 6;
    }

    // ----------------------------------------------------------- mecanica

    private void dibujarBarra(GuiGraphics g) {
        if (altoContenido <= altoVisible) {
            return;
        }
        int x = panelX + panelAncho - ANCHO_BARRA;
        g.fill(x, yContenido, x + ANCHO_BARRA, yContenido + altoVisible,
                Paleta.conAlfa(Paleta.BORDE, 0.4f));
        int altoPulgar = altoPulgar();
        g.fill(x, yPulgar(altoPulgar), x + ANCHO_BARRA, yPulgar(altoPulgar) + altoPulgar,
                arrastrando ? Paleta.TEXTO_TITULO : Paleta.BORDE_ACENTO);
    }

    private int altoPulgar() {
        return Math.max(20, (int) (altoVisible * (altoVisible / (float) altoContenido)));
    }

    private int yPulgar(int altoPulgar) {
        int max = Math.max(1, altoContenido - altoVisible);
        return yContenido + (int) ((altoVisible - altoPulgar) * Math.min(1f, scroll / (float) max));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int boton) {
        if (altoContenido > altoVisible
                && mouseX >= panelX + panelAncho - ANCHO_BARRA && mouseX <= panelX + panelAncho
                && mouseY >= yContenido && mouseY <= yContenido + altoVisible) {
            int alto = altoPulgar();
            int yp = yPulgar(alto);
            arrastrando = true;
            agarre = (mouseY >= yp && mouseY <= yp + alto) ? (mouseY - yp) : alto / 2.0;
            moverScroll(mouseY);
            return true;
        }
        for (Zona z : zonas) {
            if (z.accion() != null
                    && z.visibleEntre(yContenido, yContenido + altoVisible)
                    && z.contiene((int) mouseX, (int) mouseY)) {
                z.accion().run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, boton);
    }

    private void moverScroll(double mouseY) {
        int alto = altoPulgar();
        int recorrido = Math.max(1, altoVisible - alto);
        double y = mouseY - yContenido - agarre;
        float frac = (float) Math.max(0, Math.min(1, y / recorrido));
        scroll = Math.round(frac * Math.max(0, altoContenido - altoVisible));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int boton, double dx, double dy) {
        if (arrastrando) {
            moverScroll(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, boton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int boton) {
        arrastrando = false;
        return super.mouseReleased(mouseX, mouseY, boton);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, altoContenido - altoVisible);
        scroll = Math.max(0, Math.min(max, scroll - (int) (delta * 18)));
        return true;
    }

    /** Rearma los widgets sin perder el scroll. Para refrescar tras una accion. */
    protected void refrescar() {
        int guardado = scroll;
        this.init(Minecraft.getInstance(), this.width, this.height);
        this.scroll = guardado;
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
