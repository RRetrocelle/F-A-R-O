package com.coco.faro.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Comparador de versiones y evaluador de rangos al estilo Maven, que es el que
 * usa Forge en los versionRange de mods.toml.
 *
 * Sintaxis soportada:
 * <pre>
 *   [1.0,2.0)   1.0 &lt;= v &lt; 2.0
 *   [1.0,)      v &gt;= 1.0
 *   (,1.0]      v &lt;= 1.0
 *   [1.0]       exactamente 1.0
 *   1.0         requisito "blando": Forge lo acepta con cualquier version
 *   [1,2),[3,)  varios rangos: alcanza con que entre en uno
 * </pre>
 *
 * El caso del requisito blando importa de verdad: un addon viejo que declara
 * "0.5.1" pasa el chequeo contra Create 6.0.8 aunque en la practica reviente.
 * Por eso {@link #esBlando()} existe y quien llama puede avisar del riesgo en
 * vez de dar el visto bueno en silencio.
 */
public final class RangoVersion {

    private record Tramo(String min, boolean minInclusive, String max, boolean maxInclusive) {
    }

    private final String original;
    private final List<Tramo> tramos = new ArrayList<>();
    private final boolean blando;

    private RangoVersion(String expresion) {
        this.original = expresion == null ? "" : expresion.trim();
        this.blando = !original.isEmpty()
                && !original.startsWith("[")
                && !original.startsWith("(");
        if (!blando) {
            parsear(original);
        }
    }

    public static RangoVersion de(String expresion) {
        return new RangoVersion(expresion);
    }

    private void parsear(String expr) {
        int i = 0;
        while (i < expr.length()) {
            int apertura = expr.indexOf('[', i);
            int apertura2 = expr.indexOf('(', i);
            if (apertura < 0 || (apertura2 >= 0 && apertura2 < apertura)) {
                apertura = apertura2;
            }
            if (apertura < 0) {
                return;
            }
            int cierre = expr.indexOf(']', apertura);
            int cierre2 = expr.indexOf(')', apertura);
            if (cierre < 0 || (cierre2 >= 0 && cierre2 < cierre)) {
                cierre = cierre2;
            }
            if (cierre < 0) {
                return;
            }

            boolean minInc = expr.charAt(apertura) == '[';
            boolean maxInc = expr.charAt(cierre) == ']';
            String cuerpo = expr.substring(apertura + 1, cierre).trim();

            if (!cuerpo.contains(",")) {
                // [1.0] -> version exacta
                tramos.add(new Tramo(cuerpo, true, cuerpo, true));
            } else {
                int coma = cuerpo.indexOf(',');
                String min = cuerpo.substring(0, coma).trim();
                String max = cuerpo.substring(coma + 1).trim();
                tramos.add(new Tramo(
                        min.isEmpty() ? null : min, minInc,
                        max.isEmpty() ? null : max, maxInc));
            }
            i = cierre + 1;
        }
    }

    /** true si es un requisito blando (sin corchetes): acepta cualquier version. */
    public boolean esBlando() {
        return blando;
    }

    public boolean esVacio() {
        return original.isEmpty();
    }

    /** Limite inferior del primer tramo, o null si el rango es abierto por abajo. */
    public String limiteInferior() {
        return tramos.isEmpty() ? null : tramos.get(0).min();
    }

    /** true si algun tramo pone techo. Sin techo, el rango acepta versiones futuras. */
    public boolean tieneLimiteSuperior() {
        for (Tramo t : tramos) {
            if (t.max() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detecta el caso peligroso: el rango acepta la version instalada solo porque
     * no tiene techo, pero fue escrito para una linea mayor muy anterior.
     *
     * Ejemplo real: un addon declara "[0.5.1.f,)" y Create instalado es 6.0.8.
     * Matematicamente entra, pero el addon nunca vio la API de Create 6. Forge lo
     * carga sin chistar y revienta en runtime. Devolver true aca permite avisar
     * en vez de dar un visto bueno enganoso.
     */
    public boolean aceptaPorFaltaDeTecho(String versionInstalada) {
        if (blando) {
            return true;
        }
        if (tieneLimiteSuperior() || versionInstalada == null) {
            return false;
        }
        String min = limiteInferior();
        if (min == null) {
            return false;
        }
        Integer mayorMin = mayorDe(min);
        Integer mayorInstalada = mayorDe(versionInstalada);
        if (mayorMin == null || mayorInstalada == null) {
            return false;
        }
        return mayorInstalada - mayorMin >= 1;
    }

    /** Primer segmento numerico de una version ("6.0.8" -> 6, "0.5.1.f" -> 0). */
    private static Integer mayorDe(String v) {
        String[] partes = limpiar(v).split("[.\\-+_]");
        return partes.length == 0 ? null : comoEntero(partes[0]);
    }

    public String original() {
        return original;
    }

    /** Evalua si una version concreta cae dentro del rango. */
    public boolean acepta(String version) {
        if (version == null || original.isEmpty() || blando) {
            return true;
        }
        if (tramos.isEmpty()) {
            return true;
        }
        for (Tramo t : tramos) {
            boolean ok = true;
            if (t.min() != null) {
                int c = comparar(version, t.min());
                ok = t.minInclusive() ? c >= 0 : c > 0;
            }
            if (ok && t.max() != null) {
                int c = comparar(version, t.max());
                ok = t.maxInclusive() ? c <= 0 : c < 0;
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compara dos versiones. Parte por '.', '-' y '+', y compara segmento a
     * segmento: numerico cuando ambos lados son numeros, alfabetico si no.
     * Devuelve &lt;0, 0 o &gt;0 como cualquier Comparator.
     */
    public static int comparar(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        String[] sa = limpiar(a).split("[.\\-+_]");
        String[] sb = limpiar(b).split("[.\\-+_]");
        int n = Math.max(sa.length, sb.length);

        for (int i = 0; i < n; i++) {
            String pa = i < sa.length ? sa[i] : "0";
            String pb = i < sb.length ? sb[i] : "0";

            Integer na = comoEntero(pa);
            Integer nb = comoEntero(pb);

            int c;
            if (na != null && nb != null) {
                c = Integer.compare(na, nb);
            } else if (na != null) {
                // Un numero pesa mas que un sufijo tipo "beta": 1.0 > 1.0-beta
                c = 1;
            } else if (nb != null) {
                c = -1;
            } else {
                c = pa.compareToIgnoreCase(pb);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static String limpiar(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        // Prefijos habituales que no aportan al orden: "v1.2", "mc1.20.1-1.0"
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        if (s.startsWith("mc")) {
            s = s.substring(2);
        }
        return s;
    }

    private static Integer comoEntero(String s) {
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return original;
    }
}
