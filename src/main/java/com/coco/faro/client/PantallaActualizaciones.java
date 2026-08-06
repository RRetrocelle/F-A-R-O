package com.coco.faro.client;

import com.coco.faro.diag.ActualizadorMods;
import com.coco.faro.diag.MetadatosJar;
import com.coco.faro.diag.MotorDiagnostico;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Actualizador integrado.
 *
 * Identifica cada .jar por su SHA-1 contra Modrinth, que es el unico camino sin
 * ambiguedad. Y antes de ofrecer cualquier actualizacion, chequea a quien la
 * romperia: subir una libreria de version deja sin cargar a los mods que pedian
 * la anterior, y ese aviso es la parte que ningun actualizador da.
 */
public class PantallaActualizaciones extends PantallaHerramienta {

    private boolean soloConActualizacion = false;

    public PantallaActualizaciones(Screen anterior) {
        super(anterior, "Actualizaciones", "que mods tienen version nueva para 1.20.1 Forge");
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int tercio = (ancho - 12) / 3;
        MotorDiagnostico motor = MotorDiagnostico.get();

        Button buscar = Button.builder(
                        Component.literal(ActualizadorMods.consultando()
                                ? "Consultando " + ActualizadorMods.progreso()
                                  + "/" + ActualizadorMods.total()
                                : "Buscar en todos"),
                        b -> {
                            if (motor != null) {
                                ActualizadorMods.consultarTodos(motor.jars(),
                                        () -> Minecraft.getInstance().execute(this::refrescar));
                                refrescar();
                            }
                        })
                .bounds(x, y, tercio, 20).build();
        buscar.active = motor != null && motor.listo() && !ActualizadorMods.consultando();
        addRenderableWidget(buscar);

        addRenderableWidget(Button.builder(
                        Component.literal(soloConActualizacion ? "Ver todos" : "Solo con novedad"),
                        b -> {
                            soloConActualizacion = !soloConActualizacion;
                            refrescar();
                        })
                .bounds(x + tercio + 6, y, tercio, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Olvidar lo consultado"),
                        b -> {
                            ActualizadorMods.limpiar();
                            refrescar();
                        })
                .bounds(x + (tercio + 6) * 2, y, tercio, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null || !motor.listo()) {
            return vacio(g, x, y, ancho, "El analisis de la instalacion todavia esta corriendo.");
        }

        y = veredicto(g, x, y, ancho, ActualizadorMods.veredicto(),
                ActualizadorMods.conActualizacion() > 0 ? Paleta.NEUTRO : Paleta.OK);

        if (ActualizadorMods.consultando()) {
            int total = Math.max(1, ActualizadorMods.total());
            Widgets.barra(g, x, y, ancho, 6,
                    ActualizadorMods.progreso() / (float) total, Paleta.NEUTRO);
            y += 12;
            y = Widgets.parrafo(g, this.font,
                    "Van de a una y con pausa, a proposito: 190 consultas en paralelo harian "
                            + "que Modrinth nos corte y ademas saturarian tu conexion mientras "
                            + "jugas. Podes seguir usando el resto de Faro mientras tanto.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 5) + 6;
        }

        // --- Como se identifica cada mod
        y = seccion(g, x, y, ancho, "Como se identifica cada mod");
        y = Widgets.parrafo(g, this.font,
                "Por el SHA-1 del archivo, no por el nombre. Buscar por nombre se equivoca "
                        + "seguido: hay mods homonimos, slugs que no coinciden con el modId, y "
                        + "forks. El hash identifica ESE archivo exacto o no lo identifica — no "
                        + "hay punto medio.\n\n"
                        + "Lo unico que sale de tu PC es ese hash. Ni la lista de mods, ni rutas, "
                        + "ni nada del sistema. Y ninguna consulta se hace sola: siempre la "
                        + "dispara un boton.",
                x, y, ancho, Paleta.TEXTO_TENUE, 12) + 8;

        // --- Lista
        List<MetadatosJar> jars = motor.jars().stream()
                .filter(j -> !j.sinMetadatosDeMod())
                .sorted((a, b) -> a.nombreVisible().compareToIgnoreCase(b.nombreVisible()))
                .toList();

        y = seccion(g, x, y, ancho, "Mods instalados (" + jars.size() + ")");

        int mostrados = 0;
        for (MetadatosJar j : jars) {
            ActualizadorMods.Info info = ActualizadorMods.estadoDe(j);
            if (soloConActualizacion
                    && info.estado() != ActualizadorMods.Estado.HAY_ACTUALIZACION) {
                continue;
            }
            mostrados++;
            y = dibujarFila(g, x, y, ancho, j, info, motor);
        }

        if (mostrados == 0) {
            y = vacio(g, x, y, ancho, soloConActualizacion
                    ? "Ningun mod consultado tiene version nueva."
                    : "No hay mods para mostrar.");
        }

        y += 6;
        y = seccion(g, x, y, ancho, "Antes de actualizar");
        return Widgets.parrafo(g, this.font,
                "Actualizá de a UNO y probá el juego entre medio. Con 190 mods, si actualizas "
                        + "diez de golpe y algo se rompe, volves a no saber cual fue — que es "
                        + "justamente el problema que Faro trata de evitar.\n\n"
                        + "Cada boton de actualizar avisa antes si esa version nueva deja sin "
                        + "cargar a otros mods que pedian la vieja. Ese chequeo es el que "
                        + "convierte una actualizacion a ciegas en una decision informada.",
                x, y, ancho, Paleta.TEXTO_TENUE, 12);
    }

    private int dibujarFila(GuiGraphics g, int x, int y, int ancho, MetadatosJar jar,
                            ActualizadorMods.Info info, MotorDiagnostico motor) {
        // Salteo de lo que queda fuera del recorte.
        boolean visible = y > yContenido - 40 && y < yContenido + altoVisible + 10;

        int color = switch (info.estado()) {
            case HAY_ACTUALIZACION -> Paleta.NEUTRO;
            case AL_DIA -> Paleta.OK;
            case DESCONOCIDO -> Paleta.TEXTO_APAGADO;
            case ERROR -> Paleta.ERROR;
            case CONSULTANDO -> Paleta.VIOLETA;
            case SIN_CONSULTAR -> Paleta.TEXTO_TENUE;
        };

        boolean hayNueva = info.estado() == ActualizadorMods.Estado.HAY_ACTUALIZACION;
        int alto = hayNueva ? 24 : 22;

        if (!visible) {
            return y + alto + 18 + (hayNueva ? 20 : 0);
        }

        g.fill(x, y, x + 2, y + alto - 4, color);
        Widgets.lineaRecortada(g, this.font, jar.nombreVisible(), x + 6, y, ancho - 90, Paleta.TEXTO);

        String etiqueta = info.estado().etiqueta;
        g.drawString(this.font, etiqueta, x + ancho - this.font.width(etiqueta), y, color, false);
        y += 10;

        String versiones = jar.version().isBlank() ? "version no declarada"
                : "tenes " + info.versionInstalada();
        if (hayNueva) {
            versiones += "   ->   " + info.versionNueva();
        }
        Widgets.lineaRecortada(g, this.font, versiones, x + 6, y, ancho - 12, Paleta.TEXTO_APAGADO);
        y += 12;

        if (info.estado() == ActualizadorMods.Estado.SIN_CONSULTAR) {
            boton(g, x + 6, y, "Buscar actualizacion", Paleta.TEXTO_TENUE,
                    () -> ActualizadorMods.consultar(jar,
                            () -> Minecraft.getInstance().execute(this::refrescar)));
            return y + 20;
        }

        if (hayNueva && info.candidato() != null) {
            List<String> rompe = ActualizadorMods.aQuienRompe(info, info.versionNueva(), motor.jars());

            if (!rompe.isEmpty()) {
                y = Widgets.parrafo(g, this.font,
                        "! Actualizar esto deja sin cargar a: " + String.join(", ", rompe),
                        x + 6, y, ancho - 12, Paleta.ERROR, 3);
            }
            boton(g, x + 6, y, rompe.isEmpty()
                            ? "Actualizar a " + info.versionNueva()
                            : "Actualizar igual (rompe " + rompe.size() + ")",
                    rompe.isEmpty() ? Paleta.OK : Paleta.ADVERTENCIA,
                    () -> Minecraft.getInstance().setScreen(
                            new PantallaConfirmarActualizacion(this, jar, info, rompe)));
            return y + 20;
        }

        if (!info.nota().isEmpty()) {
            y = Widgets.parrafo(g, this.font, info.nota(), x + 6, y, ancho - 12,
                    Paleta.TEXTO_APAGADO, 3);
        }
        return y + 6;
    }
}
