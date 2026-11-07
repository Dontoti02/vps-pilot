package com.bolsadeploy.infrastructure.persistence;

import com.bolsadeploy.domain.AppSettings;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Preferencias generales persistidas como pares clave/valor. */
public class SettingsRepository {

    private final Database db;

    public SettingsRepository(Database db) {
        this.db = db;
    }

    public AppSettings cargar() throws SQLException {
        Map<String, String> mapa = db.leer(c -> {
            Map<String, String> m = new HashMap<>();
            try (var st = c.prepareStatement("SELECT clave, valor FROM settings");
                 var rs = st.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString("clave"), rs.getString("valor"));
                }
            }
            return m;
        });
        AppSettings s = new AppSettings();
        if (mapa.containsKey("tema")) s.setTema(mapa.get("tema"));
        if (mapa.containsKey("carpetaRespaldos")) s.setCarpetaRespaldos(mapa.get("carpetaRespaldos"));
        if (mapa.containsKey("rutaInstalacionBase")) s.setRutaInstalacionBase(mapa.get("rutaInstalacionBase"));
        if (mapa.containsKey("repoGitHub")) s.setRepoGitHub(mapa.get("repoGitHub"));
        if (mapa.containsKey("ramaDefault")) s.setRamaDefault(mapa.get("ramaDefault"));
        if (mapa.containsKey("githubToken")) s.setGithubToken(mapa.get("githubToken"));
        if (mapa.containsKey("concurrenciaActualizaciones"))
            s.setConcurrenciaActualizaciones(parseInt(mapa.get("concurrenciaActualizaciones"), 2));
        if (mapa.containsKey("puertoSshDefault"))
            s.setPuertoSshDefault(parseInt(mapa.get("puertoSshDefault"), 22));
        if (mapa.containsKey("timeoutSshSeg"))
            s.setTimeoutSshSeg(parseInt(mapa.get("timeoutSshSeg"), 30));
        if (mapa.containsKey("respaldoPreActualizacion"))
            s.setRespaldoPreActualizacion("true".equals(mapa.get("respaldoPreActualizacion")));
        if (mapa.containsKey("verificarHostKeys"))
            s.setVerificarHostKeys("true".equals(mapa.get("verificarHostKeys")));
        if (mapa.containsKey("onboardingVisto"))
            s.setOnboardingVisto("true".equals(mapa.get("onboardingVisto")));
        if (mapa.containsKey("requerirLoginWindows"))
            s.setRequerirLoginWindows("true".equals(mapa.get("requerirLoginWindows")));
        return s;
    }

    public void guardar(AppSettings s) throws SQLException {
        Map<String, String> mapa = new HashMap<>();
        mapa.put("tema", s.getTema());
        mapa.put("carpetaRespaldos", s.getCarpetaRespaldos());
        mapa.put("rutaInstalacionBase", s.getRutaInstalacionBase());
        mapa.put("repoGitHub", s.getRepoGitHub());
        mapa.put("ramaDefault", s.getRamaDefault());
        mapa.put("githubToken", s.getGithubToken());
        mapa.put("concurrenciaActualizaciones", String.valueOf(s.getConcurrenciaActualizaciones()));
        mapa.put("puertoSshDefault", String.valueOf(s.getPuertoSshDefault()));
        mapa.put("timeoutSshSeg", String.valueOf(s.getTimeoutSshSeg()));
        mapa.put("respaldoPreActualizacion", String.valueOf(s.isRespaldoPreActualizacion()));
        mapa.put("verificarHostKeys", String.valueOf(s.isVerificarHostKeys()));
        mapa.put("onboardingVisto", String.valueOf(s.isOnboardingVisto()));
        mapa.put("requerirLoginWindows", String.valueOf(s.isRequerirLoginWindows()));
        db.escribir(c -> {
            try (var st = c.prepareStatement(
                    "INSERT INTO settings (clave, valor) VALUES (?,?)"
                            + " ON CONFLICT(clave) DO UPDATE SET valor=excluded.valor")) {
                for (var e : mapa.entrySet()) {
                    st.setString(1, e.getKey());
                    st.setString(2, e.getValue());
                    st.executeUpdate();
                }
            }
            return null;
        });
    }

    private static int parseInt(String v, int porDefecto) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return porDefecto;
        }
    }
}
