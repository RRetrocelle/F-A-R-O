package com.coco.faro.client;

import com.coco.faro.diag.Diagnostico;
import com.coco.faro.diag.Firma;
import com.coco.faro.diag.InventarioMods;
import com.coco.faro.diag.MetadatosJar;
import com.coco.faro.diag.MonitorRendimiento;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.Problema;
import com.coco.faro.diag.Sospechoso;
import com.coco.faro.diag.VigilanteLog;
import com.coco.faro.repair.ServicioReparacion;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pantalla principal, con pestanas.
 *
 * Cada pestana responde una pregunta distinta que el usuario realmente se hace:
 *   Resumen      -> "¿esta todo bien?"
 *   Problemas    -> "¿que tengo que arreglar?"
 *   Mods         -> "¿que hay instalado y que no cargo?"
 *   Crash        -> "¿por que se cerro?"
 *   Rendimiento  -> "¿por que va lento?"
 *
 * Nada de lo que se dibuja aca hace I/O: todo sale del cache del motor.
 */
public class PantallaFaro extends Screen {

    private enum Pestana {
        RESUMEN("Resumen"),
        PROBLEMAS("Problemas"),
        MODS("Mods"),
        CRASH("Crash"),
        RENDIMIENTO("Rendimiento");

        final String etiqueta;

        Pestana(String etiqueta) {
            this.etiqueta = etiqueta;
        }
    }

    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final Screen anterior;
    private Pestana activa = Pestana.RESUMEN;
    private final Map<Pestana, Integer> scroll = new EnumMap<>(Pestana.class);
    private final Map<Pestana, Integer> altoContenido = new EnumMap<>(Pestana.class);

    private final AnimacionFaro animacion = new AnimacionFaro();
    private final List<Zona> zonas = new ArrayList<>();
    private Glosario.Termino tooltipPendiente;

    /** Posicion del cursor del frame actual, para no ensuciar las firmas de los render*(). */
    private int mx;
    private int my;

    private boolean arrastrandoBarra = false;
    private double agarreEnPulgar = 0;

    private boolean autoActualizar = false;
    private long ultimaAutoActualizacion = 0L;

    private int panelX;
    private int panelAncho;
    private int yTabs;
    private int yContenido;
    private int altoVisible;

    private Button botonReparar;
    private Button botonDetalle;

    /** Abre directo en la pestana que corresponde al aviso clickeado. */
    public PantallaFaro(Screen anterior, NotificacionLogro.Destino destino) {
        this(anterior);
        this.activa = switch (destino) {
            case PROBLEMAS -> Pestana.PROBLEMAS;
            case CRASH -> Pestana.CRASH;
            case RENDIMIENTO -> Pestana.RENDIMIENTO;
            case RESUMEN -> Pestana.RESUMEN;
        };
    }

    public PantallaFaro(Screen anterior) {
        super(Component.literal("Faro"));
        this.anterior = anterior;
        for (Pestana p : Pestana.values()) {
            scroll.put(p, 0);
            altoContenido.put(p, 0);
        }
    }

    @Override
    protected void init() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor != null) {
            motor.analizarEnSegundoPlano();
        }

        panelAncho = Math.min(this.width - 24, 460);
        panelX = (this.width - panelAncho) / 2;
        yTabs = 48;
        yContenido = yTabs + 20;
        altoVisible = Math.max(60, this.height - yContenido - 58);

        int anchoBoton = (panelAncho - 12) / 3;
        int yBotones = this.height - 50;

        botonReparar = Button.builder(Component.literal("Intentar reparar"), b -> alReparar())
                .bounds(panelX, yBotones, anchoBoton, 20).build();
        addRenderableWidget(botonReparar);

        botonDetalle = Button.builder(Component.literal("Detalle tecnico"), b -> alDetalle())
                .bounds(panelX + anchoBoton + 6, yBotones, anchoBoton, 20).build();
        addRenderableWidget(botonDetalle);

        addRenderableWidget(Button.builder(Component.literal("Consola en vivo"),
                        b -> this.minecraft.setScreen(new PantallaConsola(this)))
                .bounds(panelX + (anchoBoton + 6) * 2, yBotones, anchoBoton, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Ajustes"),
                        b -> this.minecraft.setScreen(new PantallaAjustes(this)))
                .bounds(panelX, yBotones + 24, anchoBoton, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Exportar reporte"), b -> exportar())
                .bounds(panelX + anchoBoton + 6, yBotones + 24, anchoBoton, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cerrar"), b -> onClose())
                .bounds(panelX + (anchoBoton + 6) * 2, yBotones + 24, anchoBoton, 20).build());

        actualizarBotones();
    }

    private void actualizarBotones() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        Diagnostico d = motor == null ? null : motor.diagnostico().orElse(null);
        boolean puede = d != null && d.puedeRepararse();

        botonReparar.active = puede;
        botonReparar.setMessage(Component.literal(puede ? "Intentar reparar" : "Sin causa clara"));
        botonDetalle.active = d != null && d.huboCrash();
    }

    private void alReparar() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) return;
        motor.diagnostico().filter(Diagnostico::puedeRepararse)
                .ifPresent(d -> this.minecraft.setScreen(new PantallaConfirmarReparacion(this, d)));
    }

    /**
     * Escribe el reporte a disco y ademas copia el resumen corto al portapapeles,
     * que es lo que uno realmente quiere pegar en un chat.
     */
    private void exportar() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null || !motor.listo()) {
            return;
        }
        try {
            java.nio.file.Path archivo = com.coco.faro.repair.ExportadorReporte.escribir(motor);
            this.minecraft.keyboardHandler.setClipboard(
                    com.coco.faro.repair.ExportadorReporte.resumenParaPegar(motor));
            net.minecraft.Util.getPlatform().openFile(archivo.getParent().toFile());
            com.coco.faro.Faro.LOG.info("[Faro] Reporte exportado a {}", archivo);
        } catch (Throwable t) {
            com.coco.faro.Faro.LOG.error("[Faro] No pude exportar el reporte", t);
        }
    }

    private void alDetalle() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) return;
        motor.diagnostico().ifPresent(d -> this.minecraft.setScreen(new PantallaDetalle(this, d)));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        actualizarBotones();

        this.mx = mouseX;
        this.my = mouseY;

        MotorDiagnostico motor = MotorDiagnostico.get();

        dibujarEncabezado(g, motor);
        dibujarTabs(g, mouseX, mouseY);

        if (motor == null) {
            g.drawCenteredString(this.font, "El motor de diagnostico no arranco.",
                    this.width / 2, yContenido + 20, Paleta.ERROR);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        if (!motor.listo()) {
            dibujarCargando(g);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // Las zonas se recalculan enteras cada frame: las filas se mueven con el
        // scroll, asi que guardar posiciones viejas seria una fuente de bugs.
        zonas.clear();
        tooltipPendiente = null;

        // Recorte para que el contenido no se salga del area.
        g.enableScissor(panelX, yContenido, panelX + panelAncho, yContenido + altoVisible);
        int y = yContenido + 4 - scroll.get(activa);
        int yFinal = y;
        try {
            yFinal = switch (activa) {
                case RESUMEN -> renderResumen(g, y, motor);
                case PROBLEMAS -> renderProblemas(g, y, motor);
                case MODS -> renderMods(g, y, motor);
                case CRASH -> renderCrash(g, y, motor);
                case RENDIMIENTO -> renderRendimiento(g, y, motor);
            };
        } catch (Throwable t) {
            // Regla de estabilidad: Faro nunca puede ser el que rompe el juego.
            // Si una pestaña falla, se muestra el fallo dentro de la propia
            // pestaña y el resto de la interfaz sigue funcionando.
            com.coco.faro.Faro.LOG.error("[Faro] Fallo al dibujar la pestaña {}", activa, t);
            yFinal = Widgets.parrafo(g, this.font,
                    "Esta pestaña de Faro fallo al dibujarse. El problema es de Faro, no de "
                            + "tu modpack. El detalle quedo en latest.log.\n\n" + t,
                    panelX + 8, y + 6, panelAncho - 20, Paleta.ERROR, 8);
        }
        g.disableScissor();

        altoContenido.put(activa, yFinal - (yContenido + 4 - scroll.get(activa)));
        dibujarBarraScroll(g);

        // El resaltado de la zona bajo el cursor y su tooltip van fuera del
        // recorte, para que el globo pueda salirse del panel si hace falta.
        for (Zona z : zonas) {
            if (!Widgets.dentro(mouseX, mouseY, z.x(), z.y(), z.ancho(), z.alto())) {
                continue;
            }
            if (z.y() < yContenido || z.y() + z.alto() > yContenido + altoVisible) {
                continue; // recortada: no es realmente clickeable
            }
            if (z.claveGlosario() != null) {
                tooltipPendiente = Glosario.buscar(z.claveGlosario());
            }
        }

        super.render(g, mouseX, mouseY, partialTick);

        if (tooltipPendiente != null) {
            Glosario.dibujar(g, this.font, tooltipPendiente, mouseX, mouseY, this.width, this.height);
        }
    }

    /** Boton chico de accion. Delega en el panel compartido para no duplicar el dibujo. */
    private void botonAccion(GuiGraphics g, int x, int y, String etiqueta, int color,
                             int mouseX, int mouseY, Runnable accion) {
        PanelProblemas.boton(g, this.font, x, y, etiqueta, color, mouseX, mouseY,
                yContenido, yContenido + altoVisible, zonas, accion);
    }

    /** Registra una palabra como zona con tooltip, sin dibujar nada extra. */
    private void conAyuda(int x, int y, int ancho, int alto, String clave) {
        if (clave != null) {
            zonas.add(Zona.ayuda(x, y, ancho, alto, clave));
        }
    }

    private void dibujarEncabezado(GuiGraphics g, MotorDiagnostico motor) {
        Widgets.degradadoVertical(g, panelX, 6, panelAncho, 36,
                Paleta.FONDO_PANEL, Paleta.conAlfa(Paleta.FONDO_PANEL, 0.4f));
        g.fill(panelX, 6, panelX + panelAncho, 7, Paleta.BORDE_ACENTO);

        g.drawString(this.font, "F A R O", panelX + 8, 13, Paleta.TEXTO_TITULO, false);
        g.drawString(this.font, "diagnostico del modpack", panelX + 60, 13, Paleta.TEXTO_APAGADO, false);

        if (motor != null) {
            String titular = motor.titular();
            int color = motor.colorTitular();
            // Las alertas laten suave para que el ojo vaya ahi sin ser molesto.
            if (color == Paleta.ERROR) {
                color = Paleta.mezclar(Paleta.ERROR, Paleta.TEXTO, Widgets.pulso(1600) * 0.35f);
            }
            Widgets.lineaRecortada(g, this.font, titular, panelX + 8, 28, panelAncho - 16, color);
        }
    }

    private void dibujarTabs(GuiGraphics g, int mouseX, int mouseY) {
        Pestana[] todas = Pestana.values();
        int anchoTab = panelAncho / todas.length;
        for (int i = 0; i < todas.length; i++) {
            Pestana p = todas[i];
            int x = panelX + i * anchoTab;
            int ancho = (i == todas.length - 1) ? panelAncho - i * anchoTab : anchoTab;
            boolean sel = p == activa;
            boolean hover = Widgets.dentro(mouseX, mouseY, x, yTabs, ancho, 16);

            g.fill(x, yTabs, x + ancho, yTabs + 16,
                    sel ? Paleta.FONDO_TARJETA : (hover ? Paleta.FONDO_HOVER : 0x00000000));
            if (sel) {
                g.fill(x, yTabs + 15, x + ancho, yTabs + 16, Paleta.BORDE_ACENTO);
            }
            int color = sel ? Paleta.TEXTO_TITULO : (hover ? Paleta.TEXTO : Paleta.TEXTO_TENUE);
            int tw = this.font.width(p.etiqueta);
            g.drawString(this.font, p.etiqueta, x + (ancho - tw) / 2, yTabs + 4, color, false);
        }
        Widgets.separador(g, panelX, yTabs + 16, panelAncho);
    }

    private void dibujarCargando(GuiGraphics g) {
        int cx = this.width / 2;
        int escala = altoVisible > 160 ? 3 : 2;
        int altoFaro = animacion.altoEn(escala);
        int y = yContenido + (altoVisible - altoFaro - 30) / 2;

        animacion.dibujar(g, cx, y, escala);

        int yTexto = y + altoFaro + 10;
        g.drawCenteredString(this.font, animacion.texto(), cx, yTexto, Paleta.TEXTO_TITULO);
        g.drawCenteredString(this.font, "leyendo mods, dependencias y crash reports",
                cx, yTexto + 12, Paleta.TEXTO_APAGADO);
    }

    /** Ancho de la barra. Mas gruesa que antes para poder agarrarla con el mouse. */
    private static final int ANCHO_BARRA = 6;

    private void dibujarBarraScroll(GuiGraphics g) {
        int total = altoContenido.getOrDefault(activa, 0);
        if (total <= altoVisible) {
            return;
        }
        int x = panelX + panelAncho - ANCHO_BARRA;
        g.fill(x, yContenido, x + ANCHO_BARRA, yContenido + altoVisible,
                Paleta.conAlfa(Paleta.BORDE, 0.4f));

        int altoPulgar = altoPulgar(total);
        int y = yPulgar(total, altoPulgar);
        g.fill(x, y, x + ANCHO_BARRA, y + altoPulgar,
                arrastrandoBarra ? Paleta.TEXTO_TITULO : Paleta.BORDE_ACENTO);
    }

    private int altoPulgar(int total) {
        return Math.max(20, (int) (altoVisible * (altoVisible / (float) total)));
    }

    private int yPulgar(int total, int altoPulgar) {
        int maxScroll = Math.max(1, total - altoVisible);
        float frac = scroll.get(activa) / (float) maxScroll;
        return yContenido + (int) ((altoVisible - altoPulgar) * Math.min(1f, frac));
    }

    /** Traduce una posicion vertical del mouse a un desplazamiento. */
    private void moverScrollDesdeMouse(double mouseY) {
        int total = altoContenido.getOrDefault(activa, 0);
        if (total <= altoVisible) {
            return;
        }
        int altoPulgar = altoPulgar(total);
        int recorrido = Math.max(1, altoVisible - altoPulgar);
        // El punto agarrado del pulgar se mantiene bajo el cursor.
        double y = mouseY - yContenido - agarreEnPulgar;
        float frac = (float) Math.max(0, Math.min(1, y / recorrido));
        scroll.put(activa, Math.round(frac * (total - altoVisible)));
    }

    // ------------------------------------------------------------- pestanas

    private int renderResumen(GuiGraphics g, int y, MotorDiagnostico motor) {
        int x = panelX + 6;
        int ancho = panelAncho - 16;
        InventarioMods inv = motor.inventario().orElse(null);

        // --- Estado de carga  (clickeable -> pestana Mods)
        boolean hoverCarga = tarjetaClickeable(g, x, y, ancho, 46, Paleta.OK, Pestana.MODS);
        g.drawString(this.font, "Estado de carga" + (hoverCarga ? "  >" : ""),
                x + 8, y + 6, hoverCarga ? Paleta.TEXTO : Paleta.TEXTO_TITULO, false);
        if (inv != null) {
            g.drawString(this.font, inv.cantidadCargados() + " mods cargados  ·  "
                            + inv.cantidadJarsEnCarpeta() + " jars en la carpeta  ·  "
                            + inv.tamanoTotalMB() + " MB",
                    x + 8, y + 20, Paleta.TEXTO, false);
            List<String> no = inv.jarsQueNoCargaron();
            g.drawString(this.font,
                    no.isEmpty() ? "todos los jars con mods cargaron bien"
                            : no.size() + " jars no cargaron (mira la pestana Mods)",
                    x + 8, y + 32, no.isEmpty() ? Paleta.TEXTO_TENUE : Paleta.ERROR, false);
        }
        y += 52;

        // --- Problemas
        List<Problema> serios = motor.problemasSerios();
        boolean hoverProb = tarjetaClickeable(g, x, y, ancho, 44,
                serios.isEmpty() ? Paleta.OK : Paleta.ERROR, Pestana.PROBLEMAS);
        g.drawString(this.font, "Chequeo preventivo" + (hoverProb ? "  >" : ""),
                x + 8, y + 6, hoverProb ? Paleta.TEXTO : Paleta.TEXTO_TITULO, false);
        if (serios.isEmpty()) {
            g.drawString(this.font, "Sin problemas de dependencias, duplicados ni loader.",
                    x + 8, y + 20, Paleta.OK, false);
            g.drawString(this.font, motor.problemas().size() + " avisos informativos",
                    x + 8, y + 31, Paleta.TEXTO_APAGADO, false);
        } else {
            g.drawString(this.font, serios.size() + " problemas serios detectados",
                    x + 8, y + 20, Paleta.ERROR, false);
            Widgets.lineaRecortada(g, this.font, "· " + serios.get(0).titulo(),
                    x + 8, y + 31, ancho - 16, Paleta.TEXTO_TENUE);
        }
        y += 50;

        // --- Ultimo crash
        Diagnostico d = motor.diagnostico().orElse(null);
        boolean hubo = d != null && d.huboCrash();
        boolean hoverCrash = tarjetaClickeable(g, x, y, ancho, hubo ? 52 : 32,
                hubo ? Paleta.ADVERTENCIA : Paleta.OK, Pestana.CRASH);
        g.drawString(this.font, "Ultimo crash" + (hoverCrash ? "  >" : ""),
                x + 8, y + 6, hoverCrash ? Paleta.TEXTO : Paleta.TEXTO_TITULO, false);
        if (!hubo) {
            g.drawString(this.font, "No hay crash reports.", x + 8, y + 19, Paleta.OK, false);
            y += 38;
        } else {
            g.drawString(this.font, d.tipo().titulo() + "  ·  "
                            + d.fechaCrash().map(FECHA::format).orElse("?"),
                    x + 8, y + 19, Paleta.porTipo(d.tipo()), false);
            String culpa = d.modSospechoso()
                    .map(m -> "sospechoso: " + m + " (confianza " + d.confianza().etiqueta().toLowerCase() + ")")
                    .orElse("sin culpable identificable");
            Widgets.lineaRecortada(g, this.font, culpa, x + 8, y + 31, ancho - 16,
                    Paleta.porConfianza(d.confianza()));
            Widgets.lineaRecortada(g, this.font, d.sugerencia(), x + 8, y + 41, ancho - 16,
                    Paleta.TEXTO_APAGADO);
            y += 58;
        }

        // --- Compañeros de diagnostico detectados
        List<com.coco.faro.diag.Integraciones.Companero> activos =
                com.coco.faro.diag.Integraciones.activos();
        List<com.coco.faro.diag.Integraciones.Companero> ausentes =
                com.coco.faro.diag.Integraciones.recomendadosAusentes();

        int altoComp = 22 + Math.min(4, activos.size()) * 10 + (ausentes.isEmpty() ? 0 : 12);
        Widgets.tarjeta(g, x, y, ancho, altoComp,
                activos.isEmpty() ? Paleta.TEXTO_APAGADO : Paleta.VIOLETA);
        g.drawString(this.font, "Compañeros de diagnostico", x + 8, y + 6,
                Paleta.TEXTO_TITULO, false);
        int yy = y + 19;
        if (activos.isEmpty()) {
            g.drawString(this.font, "ninguno detectado", x + 8, yy, Paleta.TEXTO_APAGADO, false);
        } else {
            for (var c : activos.subList(0, Math.min(4, activos.size()))) {
                Widgets.lineaRecortada(g, this.font, "+ " + c.nombre() + " — " + c.queAporta(),
                        x + 8, yy, ancho - 16, Paleta.TEXTO_TENUE);
                yy += 10;
            }
        }
        if (!ausentes.isEmpty()) {
            StringBuilder sb = new StringBuilder("recomendados sin activar: ");
            for (int i = 0; i < ausentes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(ausentes.get(i).nombre());
            }
            Widgets.lineaRecortada(g, this.font, sb.toString(), x + 8, yy,
                    ancho - 16, Paleta.ADVERTENCIA);
        }
        y += altoComp + 6;

        // --- Log en vivo
        VigilanteLog v = motor.vigilante();
        int color = v.errores() > 0 ? Paleta.ERROR
                : (v.advertencias() > 0 ? Paleta.ADVERTENCIA : Paleta.OK);
        boolean hoverLog = tarjetaClickeable(g, x, y, ancho, 34, color, Pestana.RENDIMIENTO);
        g.drawString(this.font, "Log de esta sesion" + (hoverLog ? "  >" : ""),
                x + 8, y + 6, hoverLog ? Paleta.TEXTO : Paleta.TEXTO_TITULO, false);
        g.drawString(this.font, v.errores() + " errores  ·  " + v.advertencias()
                        + " avisos  ·  " + v.texturasFaltantes() + " texturas faltantes",
                x + 8, y + 20, color, false);
        y += 40;

        return y;
    }

    /**
     * Tarjeta del resumen que lleva a otra pestana al clickearla.
     *
     * El resaltado y la flecha son la senal de que es un boton: en una interfaz
     * dibujada a mano no hay cursor que cambie, asi que si no se marca nadie
     * adivina que se puede clickear.
     */
    private boolean tarjetaClickeable(GuiGraphics g, int x, int y, int ancho, int alto,
                                      int acento, Pestana destino) {
        boolean visible = y >= yContenido && y + alto <= yContenido + altoVisible;
        boolean hover = visible && Widgets.dentro(mx, my, x, y, ancho, alto);

        g.fill(x, y, x + ancho, y + alto, hover ? Paleta.FONDO_HOVER : Paleta.FONDO_TARJETA);
        Widgets.borde(g, x, y, ancho, alto,
                hover ? Paleta.conAlfa(acento, 0.9f) : Paleta.BORDE_SUAVE);
        g.fill(x, y, x + (hover ? 3 : 2), y + alto, acento);

        zonas.add(Zona.boton(x, y, ancho, alto, "ir a " + destino.etiqueta,
                () -> activa = destino));
        return hover;
    }

    private int renderProblemas(GuiGraphics g, int y, MotorDiagnostico motor) {
        int x = panelX + 6;
        int ancho = panelAncho - 16;

        // Barra de auto-actualizacion: al arreglar cosas a mano (mover un jar,
        // instalar una dependencia) la lista se refresca sola y se ve el efecto
        // sin tener que salir y volver a entrar.
        String etiqueta = autoActualizar
                ? "Actualizando en tiempo real (cada 5 s)"
                : "Actualizar en tiempo real: apagado";
        PanelProblemas.boton(g, this.font, x, y, etiqueta,
                autoActualizar ? Paleta.OK : Paleta.TEXTO_TENUE,
                mx, my, yContenido, yContenido + altoVisible, zonas,
                () -> {
                    autoActualizar = !autoActualizar;
                    ultimaAutoActualizacion = System.currentTimeMillis();
                });

        if (autoActualizar) {
            long ahora = System.currentTimeMillis();
            // 5 segundos: el analisis abre ~180 zips, no puede correr por frame.
            if (ahora - ultimaAutoActualizacion > 5000L && motor.listo()) {
                ultimaAutoActualizacion = ahora;
                motor.reanalizar();
            }
            String estado = motor.listo() ? "" : "  ·  analizando...";
            if (!estado.isEmpty()) {
                g.drawString(this.font, estado,
                        x + this.font.width(etiqueta) + 16, y + 3, Paleta.NEUTRO, false);
            }
        }
        y += 19;

        // Si hay conflictos reales entre mods, se anuncian con los dos barcos
        // chocando antes de la lista: es lo mas grave que puede detectar Faro y
        // merece que se note.
        long conflictos = motor.problemas().stream()
                .filter(p -> p.categoria() == Problema.Categoria.CONFLICTO_DECLARADO
                        || p.categoria() == Problema.Categoria.POSIBLE_SOLAPAMIENTO)
                .count();
        if (conflictos > 0) {
            AnimacionBarcos.chocando(g, x + ancho / 2, y + 4, 2);
            y += 30;
            g.drawCenteredString(this.font,
                    conflictos + (conflictos == 1 ? " conflicto entre mods" : " conflictos entre mods"),
                    x + ancho / 2, y, Paleta.ERROR);
            y += 14;
        }

        // Mismo panel que usa la pantalla de rescate del arranque: un solo lugar
        // define como se dibuja un problema y que acciones ofrece.
        return PanelProblemas.render(g, this.font, this, motor.problemas(),
                x, y, ancho, mx, my,
                yContenido, yContenido + altoVisible, zonas);
    }

    private int renderMods(GuiGraphics g, int y, MotorDiagnostico motor) {
        int x = panelX + 6;
        int ancho = panelAncho - 16;
        InventarioMods inv = motor.inventario().orElse(null);
        if (inv == null) {
            g.drawString(this.font, "Sin datos.", x, y, Paleta.TEXTO_TENUE, false);
            return y + 20;
        }

        List<String> noCargaron = inv.jarsQueNoCargaron();
        if (!noCargaron.isEmpty()) {
            g.drawString(this.font, "No cargaron (" + noCargaron.size() + ")",
                    x, y, Paleta.ERROR, false);
            y += 12;
            for (String n : noCargaron) {
                Widgets.lineaRecortada(g, this.font, "· " + n, x + 4, y, ancho - 8, Paleta.TEXTO_TENUE);
                y += 10;
            }
            y += 8;
        }

        g.drawString(this.font, "Instalados (" + inv.jars().size() + ")", x, y, Paleta.TEXTO_TITULO, false);
        y += 12;

        int limiteSuperior = yContenido - 12;
        int limiteInferior = yContenido + altoVisible + 12;

        for (MetadatosJar j : inv.jars()) {
            // Con ~190 mods, dibujar los que quedan fuera del recorte es trabajo
            // tirado. Saltearlos mantiene la pantalla barata en CPU modesta.
            if (y < limiteSuperior || y > limiteInferior) {
                y += 11;
                continue;
            }
            int colorLoader = switch (j.loader()) {
                case FORGE, MIXTO -> Paleta.OK;
                case FABRIC, NEOFORGE -> Paleta.ERROR;
                case NINGUNO -> Paleta.TEXTO_APAGADO;
            };
            g.fill(x, y + 2, x + 2, y + 8, colorLoader);

            // Etiqueta de categoria: con 160+ mods, poder distinguir de un
            // vistazo una libreria de un mod de contenido cambia la lectura.
            com.coco.faro.diag.EtiquetadorMods.Etiqueta cat =
                    com.coco.faro.diag.EtiquetadorMods.clasificar(j);
            int anchoCat = this.font.width(cat.texto) + 6;
            g.fill(x + 6, y - 1, x + 6 + anchoCat, y + 9, Paleta.conAlfa(cat.color, 0.18f));
            g.drawString(this.font, cat.texto, x + 9, y, cat.color, false);

            int xTexto = x + 10 + anchoCat;
            String texto = j.nombreVisible();
            if (!j.version().isBlank()) {
                texto += "  " + j.version();
            }
            String der = (j.tamano() / 1024) + " KB";
            int dw = this.font.width(der);
            Widgets.lineaRecortada(g, this.font, texto, xTexto, y,
                    x + ancho - dw - 8 - xTexto, Paleta.TEXTO);
            g.drawString(this.font, der, x + ancho - dw - 4, y, Paleta.TEXTO_APAGADO, false);
            y += 11;
        }
        return y;
    }

    private int renderCrash(GuiGraphics g, int y, MotorDiagnostico motor) {
        int x = panelX + 6;
        int ancho = panelAncho - 16;
        Diagnostico d = motor.diagnostico().orElse(null);

        if (d == null || !d.huboCrash()) {
            g.drawString(this.font, "No hay ningun crash report para analizar.",
                    x, y + 6, Paleta.OK, false);
            return y + 24;
        }

        g.drawString(this.font, d.tipo().titulo(), x, y, Paleta.porTipo(d.tipo()), false);
        int anchoCert = Widgets.badge(g, this.font, d.certeza().etiqueta(),
                x + ancho - this.font.width(d.certeza().etiqueta()) - 10, y - 1,
                PanelProblemas.colorCerteza(d.certeza()));
        conAyuda(x + ancho - anchoCert, y - 1, anchoCert, 11, "certeza");
        y += 14;

        // Explicacion en castellano simple, antes que nada tecnico.
        Widgets.tarjeta(g, x, y, ancho, 4 + this.font.split(
                Component.literal(d.explicacionSimple()), ancho - 12).size() * 10 + 4,
                Paleta.NEUTRO);
        y = Widgets.parrafo(g, this.font, d.explicacionSimple(), x + 6, y + 5,
                ancho - 12, Paleta.TEXTO, 10);
        y += 10;

        g.drawString(this.font, "Que hacer", x, y, Paleta.TEXTO_TITULO, false);
        y += 11;
        y = Widgets.parrafo(g, this.font, d.sugerencia(), x, y, ancho, Paleta.NEUTRO, 6);
        y += 6;

        // Firmas reconocidas: hace auditable la conclusion.
        if (!d.firmas().isEmpty()) {
            g.drawString(this.font, "Firmas reconocidas (" + d.firmas().size() + ")",
                    x, y, Paleta.TEXTO_TITULO, false);
            y += 11;
            for (Firma.Coincidencia c : d.firmas()) {
                Widgets.lineaRecortada(g, this.font, "· " + c.firma().id() + " (peso "
                        + c.firma().peso() + ")", x + 4, y, ancho - 8, Paleta.VIOLETA);
                y += 10;
                y = Widgets.parrafo(g, this.font, c.firma().explicacion(), x + 10, y,
                        ancho - 14, Paleta.TEXTO_APAGADO, 3);
                y += 2;
            }
            y += 4;
        }

        // Integracion opcional: si spark esta, se manda ahi para lo que hace
        // mejor que Faro, en vez de fingir que Faro lo reemplaza.
        if (com.coco.faro.diag.Integraciones.haySpark()) {
            y += 4;
            y = Widgets.parrafo(g, this.font,
                    "Tenes spark instalado: para ir mas a fondo, corré /spark profiler start, "
                            + "jugá un rato y despues /spark profiler stop.",
                    x, y, ancho, Paleta.VIOLETA, 3);
            y += 4;
        }

        // Ranking de sospechosos con su puntaje.
        g.drawString(this.font, "Sospechosos por puntaje", x, y, Paleta.TEXTO_TITULO, false);
        y += 11;
        if (d.ranking().isEmpty() || d.confianza() == com.coco.faro.diag.Confianza.NINGUNA) {
            y = Widgets.parrafo(g, this.font,
                    "Ningun mod acumulo evidencia suficiente. El fallo pasa solo por codigo "
                            + "del juego o de Forge, o el texto no alcanza para atribuirlo.",
                    x + 4, y, ancho - 8, Paleta.TEXTO_TENUE, 4);
            y += 4;

            // Unico punto de todo el mod donde se ofrece la IA: cuando la
            // heuristica ya dijo que no sabe. Si sabe, consultarla seria gastar
            // plata para responder algo que ya tenemos con certeza.
            String bloque = d.excepcionPrincipal() + "\n" + String.join("\n", d.lineasStack());
            botonAccion(g, x + 4, y, "Consultar IA (opcional)", Paleta.VIOLETA, mx, my,
                    () -> this.minecraft.setScreen(new PantallaIA(this, bloque)));
            y += 17;
            y = Widgets.parrafo(g, this.font,
                    "Requiere tu propia API key. Sin configurarla, Faro funciona igual.",
                    x + 4, y, ancho - 8, Paleta.TEXTO_APAGADO, 2);
        }

        if (!d.ranking().isEmpty()) {
            int maximo = d.ranking().get(0).puntaje();
            java.util.Set<String> instalados = motor.inventario()
                    .map(InventarioMods::idsCargados).orElse(java.util.Set.of());

            for (Sospechoso s : d.ranking()) {
                boolean protegido = ServicioReparacion.esProtegido(s.modId());

                // Un crash viejo cuyo culpable ya no esta instalado no sigue
                // siendo un problema activo. Marcarlo en verde evita que el
                // usuario vuelva a preocuparse por algo que ya resolvio.
                boolean yaResuelto = !instalados.contains(s.modId().toLowerCase())
                        && (s.jar() == null || !java.nio.file.Files.exists(s.jar()));

                int color = yaResuelto ? Paleta.OK
                        : protegido ? Paleta.TEXTO_APAGADO
                        : (s.puntaje() >= 100 ? Paleta.ERROR
                        : s.puntaje() >= 55 ? Paleta.ADVERTENCIA : Paleta.TEXTO_TENUE);

                String nombre = yaResuelto
                        ? s.nombreVisible() + "  —  ya removido"
                        : s.nombreVisible();
                Widgets.lineaRecortada(g, this.font, nombre, x + 4, y, ancho - 100, color);
                if (yaResuelto) {
                    // Ya no se ofrece deshabilitarlo: no esta.
                    g.drawString(this.font, "resuelto", x + ancho - this.font.width("resuelto") - 4,
                            y, Paleta.OK, false);
                    y += 12;
                    continue;
                }
                String pts = s.puntaje() + " pts";
                g.drawString(this.font, pts, x + ancho - this.font.width(pts) - 4, y, color, false);
                conAyuda(x + ancho - this.font.width(pts) - 4, y, this.font.width(pts), 9, "confianza");
                y += 10;
                Widgets.barra(g, x + 4, y, ancho - 12, 4,
                        maximo <= 0 ? 0 : s.puntaje() / (float) maximo, color);
                y += 8;
                for (Sospechoso.Indicio ind : s.indicios()) {
                    Widgets.lineaRecortada(g, this.font,
                            "   " + (ind.puntos() > 0 ? "+" : "") + ind.puntos() + "  " + ind.descripcion(),
                            x + 4, y, ancho - 8, Paleta.TEXTO_APAGADO);
                    y += 9;
                }

                // Solo se ofrece deshabilitar lo que tiene sentido deshabilitar:
                // un jar concreto que no sea una libreria compartida.
                if (!protegido && s.jar() != null) {
                    botonAccion(g, x + 4, y + 2, "Deshabilitar " + s.modId(),
                            Paleta.ADVERTENCIA, mx, my,
                            () -> this.minecraft.setScreen(new PantallaConfirmarDesactivacion(
                                    this, s.jar(), s.modId(),
                                    "sospechoso de causar el ultimo crash ("
                                            + s.puntaje() + " pts)")));
                    y += 17;
                } else if (protegido) {
                    g.drawString(this.font, "   libreria compartida: no se ofrece deshabilitar",
                            x + 4, y, Paleta.TEXTO_APAGADO, false);
                    y += 10;
                }
                y += 4;
            }
        }
        return y;
    }

    private int renderRendimiento(GuiGraphics g, int y, MotorDiagnostico motor) {
        int x = panelX + 6;
        int ancho = panelAncho - 16;
        MonitorRendimiento r = motor.rendimiento();

        g.drawString(this.font, "Tiempo de tick del cliente", x, y, Paleta.TEXTO_TITULO, false);
        conAyuda(x, y, this.font.width("Tiempo de tick del cliente"), 9, "tiempo de tick");
        y += 12;
        y = Widgets.parrafo(g, this.font, r.veredicto(), x, y, ancho, r.colorVeredicto(), 3);
        y += 4;

        Widgets.grafico(g, x, y, ancho, 44, r.historia(), Math.max(r.peorMs(), 60.0), 50.0);
        y += 48;
        g.drawString(this.font, "linea roja = 50 ms (el limite de un tick)",
                x, y, Paleta.TEXTO_APAGADO, false);
        y += 14;

        String stats = String.format("promedio %.1f ms  ·  p95 %.1f ms  ·  peor %.1f ms",
                r.promedioMs(), r.p95Ms(), r.peorMs());
        g.drawString(this.font, stats, x, y, Paleta.TEXTO, false);
        conAyuda(x, y, this.font.width(stats), 9, "p95");
        y += 12;
        g.drawString(this.font, r.totalTicks() + " ticks medidos", x, y, Paleta.TEXTO_APAGADO, false);
        y += 18;

        // ---- CPU y GPU
        com.coco.faro.diag.MonitorHardware hw = com.coco.faro.diag.MonitorHardware.get();
        hw.actualizarGpuSiCorresponde();

        g.drawString(this.font, "Procesador", x, y, Paleta.TEXTO_TITULO, false);
        y += 12;

        int cpuJuego = hw.cpuDelJuego();
        int cpuSis = hw.cpuDelSistema();
        y = medidor(g, x, y, ancho, "Minecraft", cpuJuego, hw.nucleos() + " nucleos");
        y = medidor(g, x, y, ancho, "Sistema", cpuSis, "");
        conAyuda(x, y - 22, ancho, 20, "cpu");

        // Temperatura de CPU: experimental. Se intenta una sola vez por sesion.
        hw.intentarTemperaturaCpu();
        int tCpu = hw.temperaturaCpu();
        if (tCpu > 0) {
            int col = tCpu >= 90 ? Paleta.ERROR : (tCpu >= 80 ? Paleta.ADVERTENCIA : Paleta.OK);
            g.drawString(this.font, "Temperatura CPU", x, y, Paleta.TEXTO_TENUE, false);
            String txt = tCpu + " °C";
            g.drawString(this.font, txt, x + ancho - this.font.width(txt), y, col, false);
            y += 12;
        } else if (hw.temperaturaCpuProbada()) {
            g.drawString(this.font, "Temperatura CPU: no disponible en este equipo",
                    x, y, Paleta.TEXTO_APAGADO, false);
            y += 12;
        }
        if (cpuJuego < 0) {
            y = Widgets.parrafo(g, this.font,
                    "Esperando la primera medicion (se toma una por segundo).",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 2);
        }
        y += 6;

        // Auto-medicion: una herramienta de rendimiento tiene que poder mostrar
        // lo que cuesta ella misma, si no es solo una promesa.
        g.drawString(this.font, "Costo del propio Faro", x, y, Paleta.TEXTO_TITULO, false);
        conAyuda(x, y, this.font.width("Costo del propio Faro"), 9, "autoconsumo");
        y += 11;
        double msFaro = hw.milisegundosGastadosPorFaro();
        long muestras = hw.muestrasTomadas();
        String costo = String.format("%.1f ms de CPU en total  ·  %d muestras", msFaro, muestras);
        g.drawString(this.font, costo, x, y, Paleta.OK, false);
        y += 10;
        g.drawString(this.font, "todo el monitoreo corre en hilos aparte, fuera del render",
                x, y, Paleta.TEXTO_APAGADO, false);
        y += 14;

        g.drawString(this.font, "Placa de video", x, y, Paleta.TEXTO_TITULO, false);
        y += 12;
        com.coco.faro.diag.MonitorHardware.LecturaGpu gpu = hw.gpu();
        if (gpu.hayDato()) {
            Widgets.lineaRecortada(g, this.font, gpu.nombre(), x, y, ancho, Paleta.TEXTO_TENUE);
            y += 11;
            y = medidor(g, x, y, ancho, "Uso GPU", gpu.usoPorcentaje(), "");
            if (gpu.memoriaTotalMB() > 0) {
                int pctVram = gpu.memoriaUsadaMB() * 100 / gpu.memoriaTotalMB();
                y = medidor(g, x, y, ancho, "VRAM", pctVram,
                        gpu.memoriaUsadaMB() + " / " + gpu.memoriaTotalMB() + " MB");
            }
            if (gpu.hayTemperatura()) {
                int t = gpu.temperaturaC();
                int colorT = t >= 85 ? Paleta.ERROR : (t >= 75 ? Paleta.ADVERTENCIA : Paleta.OK);
                g.drawString(this.font, "Temperatura", x, y, Paleta.TEXTO_TENUE, false);
                String txt = t + " °C";
                g.drawString(this.font, txt, x + ancho - this.font.width(txt), y, colorT, false);
                y += 12;
            } else {
                g.drawString(this.font, "Temperatura: no disponible en este equipo",
                        x, y, Paleta.TEXTO_APAGADO, false);
                y += 12;
            }
        } else {
            // Nunca se inventa un porcentaje: se dice que no se puede leer.
            y = Widgets.parrafo(g, this.font,
                    "No disponible en esta GPU. Java no puede leer el uso de video por su "
                            + "cuenta; Faro lo intenta con nvidia-smi, que solo viene con "
                            + "drivers NVIDIA. Prefiero decirlo antes que mostrar un numero "
                            + "estimado.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 5);
        }
        y += 8;

        // ---- Memoria
        g.drawString(this.font, "Memoria", x, y, Paleta.TEXTO_TITULO, false);
        conAyuda(x, y, this.font.width("Memoria"), 9, "memoria");
        y += 12;
        int pct = MonitorRendimiento.porcentajeMemoria();
        y = medidor(g, x, y, ancho, "RAM del juego", pct,
                MonitorRendimiento.memoriaUsadaMB() + " / "
                        + MonitorRendimiento.memoriaMaximaMB() + " MB");

        // ---- Asistente de RAM y argumentos de JVM
        int mods = motor.inventario().map(InventarioMods::cantidadCargados).orElse(0);
        com.coco.faro.diag.AsistenteJVM.Recomendacion rec =
                com.coco.faro.diag.AsistenteJVM.analizar(mods);

        y += 4;
        g.drawString(this.font, "Asistente de RAM", x, y, Paleta.TEXTO_TITULO, false);
        y += 12;

        String fisica = rec.ramFisicaMB() > 0
                ? (rec.ramFisicaMB() / 1024) + " GB en el equipo" : "RAM fisica: sin dato";
        g.drawString(this.font, fisica + "  ·  asignada " + rec.ramAsignadaMB() + " MB"
                + "  ·  recomendada " + rec.ramRecomendadaMB() + " MB",
                x, y, Paleta.TEXTO_TENUE, false);
        y += 12;
        y = Widgets.parrafo(g, this.font, rec.veredicto(), x, y, ancho,
                rec.hayQueCambiar() ? Paleta.ADVERTENCIA : Paleta.OK, 5);

        if (rec.hayQueCambiar()) {
            y += 4;
            // Copiar al portapapeles, no aplicar: los argumentos viven en el
            // launcher y cambiarlos en caliente no haria nada.
            botonAccion(g, x, y, "Copiar argumentos recomendados", Paleta.NEUTRO, mx, my,
                    () -> {
                        this.minecraft.keyboardHandler.setClipboard(
                                String.join(" ", rec.argumentos()));
                        com.coco.faro.Faro.LOG.info("[Faro] Argumentos JVM copiados.");
                    });
            y += 18;
            // Si se pudo ubicar el archivo del launcher, se ofrece aplicarlo
            // con diff y respaldo. Si no, solo queda el texto explicativo.
            botonAccion(g, x, y, "Cambiar memoria a " + rec.ramRecomendadaMB() + " MB",
                    Paleta.ADVERTENCIA, mx, my,
                    () -> this.minecraft.setScreen(
                            new PantallaAplicarMemoria(this, rec.ramRecomendadaMB())));
            y += 18;
            y = Widgets.parrafo(g, this.font,
                    com.coco.faro.diag.AsistenteJVM.dondeAplicar(),
                    x, y, ancho, Paleta.TEXTO_APAGADO, 5);
        }
        y += 8;
        if (MonitorRendimiento.memoriaMaximaMB() < 4096) {
            y = Widgets.parrafo(g, this.font,
                    "Tenes menos de 4 GB asignados. Para ~190 mods eso es poco: subilo "
                            + "en los ajustes de la instancia en SKLauncher.",
                    x, y, ancho, Paleta.ADVERTENCIA, 3);
        }
        y += 8;

        // Picos
        List<MonitorRendimiento.Pico> picos = r.picos();
        g.drawString(this.font, "Tirones registrados (" + picos.size() + ")",
                x, y, Paleta.TEXTO_TITULO, false);
        y += 12;
        if (picos.isEmpty()) {
            g.drawString(this.font, "Ninguno por encima de 100 ms.", x, y, Paleta.OK, false);
            y += 12;
        } else {
            for (MonitorRendimiento.Pico p : picos) {
                g.drawString(this.font, String.format("· %.0f ms  —  %s", p.duracionMs(), p.contexto()),
                        x + 2, y, Paleta.ADVERTENCIA, false);
                y += 10;
            }
        }

        // Ranking de ruido en el log
        y += 8;
        g.drawString(this.font, "Quien mas reporta errores", x, y, Paleta.TEXTO_TITULO, false);
        y += 12;
        List<Map.Entry<String, Integer>> ranking = motor.vigilante().rankingOrigenes(8);
        if (ranking.isEmpty()) {
            g.drawString(this.font, "Nadie. El log esta limpio.", x, y, Paleta.OK, false);
            y += 12;
        } else {
            int max = ranking.get(0).getValue();
            for (Map.Entry<String, Integer> e : ranking) {
                Widgets.lineaRecortada(g, this.font, e.getKey(), x + 2, y, ancho - 60, Paleta.TEXTO);
                String n = String.valueOf(e.getValue());
                g.drawString(this.font, n, x + ancho - this.font.width(n) - 4, y, Paleta.TEXTO_TENUE, false);
                y += 10;
                Widgets.barra(g, x + 2, y, ancho - 10, 3, e.getValue() / (float) max, Paleta.ADVERTENCIA);
                y += 7;
            }
        }
        return y;
    }

    /**
     * Fila de medicion: etiqueta, barra y valor.
     *
     * Un valor negativo significa "no se pudo leer", y se dibuja como tal en vez
     * de como 0%. La diferencia importa: 0% dice "no esta trabajando", y eso
     * seria mentira.
     */
    private int medidor(GuiGraphics g, int x, int y, int ancho, String etiqueta,
                        int porcentaje, String extra) {
        g.drawString(this.font, etiqueta, x, y, Paleta.TEXTO_TENUE, false);

        String valor = porcentaje < 0 ? "sin dato" : porcentaje + "%";
        int color = porcentaje < 0 ? Paleta.TEXTO_APAGADO
                : (porcentaje > 90 ? Paleta.ERROR
                : (porcentaje > 75 ? Paleta.ADVERTENCIA : Paleta.OK));
        g.drawString(this.font, valor, x + ancho - this.font.width(valor), y, color, false);

        if (!extra.isEmpty()) {
            int anchoExtra = this.font.width(extra);
            g.drawString(this.font, extra,
                    x + ancho - this.font.width(valor) - anchoExtra - 8, y,
                    Paleta.TEXTO_APAGADO, false);
        }
        y += 10;

        // Barra con fondo mas oscuro que el panel: sube el contraste del relleno.
        g.fill(x, y, x + ancho, y + 6, 0xFF0A0D11);
        Widgets.borde(g, x, y, ancho, 6, Paleta.BORDE);
        if (porcentaje > 0) {
            int relleno = (int) ((ancho - 2) * Math.min(1f, porcentaje / 100f));
            g.fill(x + 1, y + 1, x + 1 + relleno, y + 5, color);
        }
        // Marcas cada 25% para poder leer la barra de un vistazo.
        for (int m = 25; m < 100; m += 25) {
            int mx = x + 1 + (ancho - 2) * m / 100;
            g.fill(mx, y + 1, mx + 1, y + 5, 0x40FFFFFF);
        }
        return y + 12;
    }

    // ------------------------------------------------------------ interaccion

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int boton) {
        // La barra de scroll tiene prioridad: ocupa el borde derecho del area y
        // no debe competir con las zonas que quedan debajo.
        int total = altoContenido.getOrDefault(activa, 0);
        if (total > altoVisible
                && mouseX >= panelX + panelAncho - ANCHO_BARRA && mouseX <= panelX + panelAncho
                && mouseY >= yContenido && mouseY <= yContenido + altoVisible) {

            int altoPulgar = altoPulgar(total);
            int yp = yPulgar(total, altoPulgar);
            arrastrandoBarra = true;
            // Si clickeaste sobre el pulgar, se conserva el punto agarrado.
            // Si clickeaste en el riel, el pulgar se centra donde apuntaste.
            agarreEnPulgar = (mouseY >= yp && mouseY <= yp + altoPulgar)
                    ? (mouseY - yp)
                    : altoPulgar / 2.0;
            moverScrollDesdeMouse(mouseY);
            return true;
        }

        // Zonas dibujadas a mano dentro del area con scroll. Se exige que la zona
        // este completamente visible: un boton a medio recortar no se clickea.
        for (Zona z : zonas) {
            if (z.accion() == null) {
                continue;
            }
            if (z.y() < yContenido || z.y() + z.alto() > yContenido + altoVisible) {
                continue;
            }
            if (Widgets.dentro((int) mouseX, (int) mouseY, z.x(), z.y(), z.ancho(), z.alto())) {
                z.accion().run();
                return true;
            }
        }

        if (mouseY >= yTabs && mouseY < yTabs + 16 && mouseX >= panelX && mouseX < panelX + panelAncho) {
            Pestana[] todas = Pestana.values();
            int anchoTab = panelAncho / todas.length;
            int idx = (int) ((mouseX - panelX) / anchoTab);
            if (idx >= 0 && idx < todas.length) {
                activa = todas[idx];
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, boton);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int boton, double dx, double dy) {
        if (arrastrandoBarra) {
            moverScrollDesdeMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, boton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int boton) {
        arrastrandoBarra = false;
        return super.mouseReleased(mouseX, mouseY, boton);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int total = altoContenido.getOrDefault(activa, 0);
        int max = Math.max(0, total - altoVisible);
        int actual = scroll.get(activa);
        scroll.put(activa, Math.max(0, Math.min(max, actual - (int) (delta * 18))));
        return true;
    }

    @Override
    public void onClose() {
        OverlayFaro.INSTANCIA.marcarComoVisto();
        this.minecraft.setScreen(anterior);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
