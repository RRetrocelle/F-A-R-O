package com.coco.faro.diag;

import com.coco.faro.Faro;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interceptor de errores de OpenGL.
 *
 * OpenGL no lanza excepciones: cuando algo sale mal deja un codigo de error en
 * una variable interna y sigue como si nada. Si nadie lo consulta, el sintoma que
 * ve el usuario es un bloque negro y violeta, una entidad invisible, una pantalla
 * que parpadea — sin una sola linea en el log.
 *
 * Lo que hace esta clase es preguntar {@code glGetError()} una vez por cuadro y
 * anotar el codigo junto con la fase de render en la que estabamos. Eso convierte
 * "se ve raro" en "hay un GL_INVALID_OPERATION en la fase de entidades".
 *
 * Sobre lo que se puede y no se puede afirmar, dicho de frente:
 *
 *   - El codigo de error y el momento son medicion directa: certeza alta.
 *   - Atribuirle el error a un mod concreto NO es posible desde aca. El error
 *     queda pendiente hasta que alguien lo consulta, asi que solo sabemos en que
 *     fase se detecto, no que linea de que mod lo produjo. La pantalla lo dice.
 *   - Ver que un bloque salio negro-y-violeta sigue requiriendo mirar el render.
 *     Esto detecta el error grafico subyacente, que es la causa, no el sintoma
 *     visible.
 *
 * Costo: {@code glGetError()} fuerza una sincronizacion con el driver y no es
 * gratis. Por eso viene APAGADO por defecto y se prende desde los ajustes solo
 * mientras se investiga un problema grafico. La pantalla lo aclara.
 */
public final class VigilanteOpenGL {

    /** Un error de OpenGL con su contexto. */
    public record ErrorGl(int codigo, String nombre, String fase, long momento, int repeticiones) {
    }

    private static final VigilanteOpenGL INSTANCIA = new VigilanteOpenGL();

    private volatile boolean activo = false;
    private volatile String faseActual = "desconocida";

    /** Clave = codigo + fase, para agrupar en vez de acumular miles de copias. */
    private final Map<String, ErrorGl> errores = new LinkedHashMap<>();
    private volatile long totalDetectados = 0L;
    private volatile long cuadrosRevisados = 0L;

    private VigilanteOpenGL() {
    }

    public static VigilanteOpenGL get() {
        return INSTANCIA;
    }

    public boolean activo() {
        return activo;
    }

    public void activar(boolean v) {
        this.activo = v;
        if (v) {
            Faro.LOG.info("[Faro] Vigilancia de OpenGL activada. Cuesta rendimiento: apagala al terminar.");
        }
    }

    /** La fase de render en curso, para poder ubicar el error. */
    public void fase(String f) {
        this.faseActual = f == null ? "desconocida" : f;
    }

    /**
     * Consulta el estado de OpenGL. Se llama una vez por cuadro desde el render.
     *
     * Se vacia la cola completa: OpenGL puede tener varios errores pendientes y
     * quedarse con el primero perderia los demas.
     */
    public void revisar() {
        if (!activo) {
            return;
        }
        cuadrosRevisados++;
        int vueltas = 0;
        int codigo;
        // Tope de 8: si el driver devuelve errores sin parar, salir del bucle es
        // preferible a colgar el hilo de render.
        while ((codigo = GL11.glGetError()) != GL11.GL_NO_ERROR && vueltas++ < 8) {
            anotar(codigo);
        }
    }

    private void anotar(int codigo) {
        totalDetectados++;
        String nombre = nombreDe(codigo);
        String clave = codigo + "@" + faseActual;
        synchronized (errores) {
            ErrorGl previo = errores.get(clave);
            errores.put(clave, new ErrorGl(codigo, nombre, faseActual,
                    System.currentTimeMillis(), previo == null ? 1 : previo.repeticiones() + 1));
        }
        // Solo la primera vez de cada combinacion va al log: si no, un error por
        // cuadro llena latest.log de 60 lineas por segundo.
        if (totalDetectados <= 32) {
            Faro.LOG.warn("[Faro] Error de OpenGL {} ({}) en la fase '{}'", nombre, codigo, faseActual);
        }
    }

    public List<ErrorGl> errores() {
        synchronized (errores) {
            List<ErrorGl> out = new ArrayList<>(errores.values());
            out.sort((a, b) -> Integer.compare(b.repeticiones(), a.repeticiones()));
            return out;
        }
    }

    public long totalDetectados() {
        return totalDetectados;
    }

    public long cuadrosRevisados() {
        return cuadrosRevisados;
    }

    public void reiniciar() {
        synchronized (errores) {
            errores.clear();
        }
        totalDetectados = 0;
        cuadrosRevisados = 0;
    }

    public static String nombreDe(int codigo) {
        return switch (codigo) {
            case GL11.GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL11.GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL11.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL11.GL_STACK_OVERFLOW -> "GL_STACK_OVERFLOW";
            case GL11.GL_STACK_UNDERFLOW -> "GL_STACK_UNDERFLOW";
            case GL11.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "codigo " + codigo;
        };
    }

    /** Que significa cada error, en criollo y con la consecuencia visible. */
    public static String explicar(int codigo) {
        return switch (codigo) {
            case GL11.GL_INVALID_ENUM, GL11.GL_INVALID_VALUE -> """
                    Un mod le paso a la placa de video un valor que no corresponde. \
                    Suele venir de un shader o un mod de render escrito contra otra version \
                    de OpenGL. El sintoma tipico es una textura que sale mal o no sale.""";
            case GL11.GL_INVALID_OPERATION -> """
                    Se pidio una operacion grafica en un momento en que no era valida. \
                    Es el error mas comun cuando dos mods de render pelean por el mismo \
                    estado. Suele verse como parpadeos o cosas que aparecen y desaparecen.""";
            case GL11.GL_OUT_OF_MEMORY -> """
                    La placa de video se quedo sin memoria. Bajá la distancia de render, \
                    el pack de texturas, o sacá shaders. Con VRAM llena los sintomas van \
                    desde bloques negros hasta que el juego se cierre solo.""";
            case GL11.GL_STACK_OVERFLOW, GL11.GL_STACK_UNDERFLOW -> """
                    Un mod desbalanceo la pila de transformaciones: guardo mas veces de las \
                    que restauro, o al reves. Todo lo que se dibuje despues sale corrido o \
                    con el tamano equivocado.""";
            case 0x0506 -> """
                    Se intento dibujar sobre un framebuffer incompleto. Casi siempre es un \
                    mod de shaders o de post-procesado en conflicto con otro.""";
            default -> "Error de OpenGL sin explicacion conocida en la base de Faro.";
        };
    }

    public String veredicto() {
        if (!activo) {
            return "Apagado. Prendelo solo mientras investigas un problema grafico: "
                    + "consultar el estado de OpenGL cada cuadro cuesta rendimiento.";
        }
        if (totalDetectados == 0) {
            return "Sin errores en " + cuadrosRevisados + " cuadros revisados. "
                    + "El pipeline grafico esta limpio: si algo se ve mal, no es un error de OpenGL.";
        }
        List<ErrorGl> lista = errores();
        ErrorGl peor = lista.get(0);
        return totalDetectados + " errores detectados. El mas repetido es " + peor.nombre()
                + " en la fase '" + peor.fase() + "'.";
    }
}
