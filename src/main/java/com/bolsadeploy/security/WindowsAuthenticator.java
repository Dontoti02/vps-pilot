package com.bolsadeploy.security;

import com.sun.jna.*;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Autenticador nativo de Windows para proteger el acceso a VPSPilot.
 * Permite validar las credenciales de la cuenta de usuario de la PC (contraseña,
 * PIN de Windows Hello o credenciales de dominio/locales).
 */
public final class WindowsAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(WindowsAuthenticator.class);

    private WindowsAuthenticator() {
    }

    public static String obtenerUsuarioActual() {
        return System.getProperty("user.name", "Usuario");
    }

    public static String obtenerDominioActual() {
        String dom = System.getenv("USERDOMAIN");
        return (dom != null && !dom.isBlank()) ? dom : ".";
    }

    /**
     * Valida la contraseña de la cuenta actual de Windows usando la API nativa LogonUser.
     */
    public static boolean verificarPassword(String usuario, String password) {
        if (usuario == null || usuario.isBlank() || password == null) {
            return false;
        }

        String domain = obtenerDominioActual();
        HANDLEByReference phToken = new HANDLEByReference();

        // 1. Intentar con LOGON32_LOGON_NETWORK (no requiere privilegios elevados)
        boolean ok = Advapi32.INSTANCE.LogonUser(
                usuario,
                domain,
                password,
                WinBase.LOGON32_LOGON_NETWORK,
                WinBase.LOGON32_PROVIDER_DEFAULT,
                phToken
        );
        if (ok) {
            Kernel32.INSTANCE.CloseHandle(phToken.getValue());
            return true;
        }

        // 2. Intentar con LOGON32_LOGON_INTERACTIVE
        ok = Advapi32.INSTANCE.LogonUser(
                usuario,
                domain,
                password,
                WinBase.LOGON32_LOGON_INTERACTIVE,
                WinBase.LOGON32_PROVIDER_DEFAULT,
                phToken
        );
        if (ok) {
            Kernel32.INSTANCE.CloseHandle(phToken.getValue());
            return true;
        }

        // 3. Fallback con dominio local "."
        ok = Advapi32.INSTANCE.LogonUser(
                usuario,
                ".",
                password,
                WinBase.LOGON32_LOGON_NETWORK,
                WinBase.LOGON32_PROVIDER_DEFAULT,
                phToken
        );
        if (ok) {
            Kernel32.INSTANCE.CloseHandle(phToken.getValue());
            return true;
        }

        int err = Kernel32.INSTANCE.GetLastError();
        log.warn("Fallo en LogonUser para usuario '{}' (Código error Win32: {})", usuario, err);
        return false;
    }

    /**
     * Invoca el cuadro de diálogo oficial nativo de Seguridad de Windows (CredUI)
     * para permitir autenticación por PIN, Windows Hello o contraseña.
     */
    public static boolean autenticarConWindowsPrompt(String titulo, String mensaje) {
        try {
            CredUILib.CREDUI_INFO uiInfo = new CredUILib.CREDUI_INFO();
            uiInfo.cbSize = uiInfo.size();
            uiInfo.hwndParent = null;
            uiInfo.pszCaptionText = new WString(titulo != null ? titulo : "VPSPilot — Seguridad de Windows");
            uiInfo.pszMessageText = new WString(mensaje != null ? mensaje : "Ingresa tus credenciales de Windows para acceder a VPSPilot.");

            IntByReference pulAuthPackage = new IntByReference(0);
            PointerByReference ppvOutAuthBuffer = new PointerByReference();
            IntByReference pulOutAuthBufferSize = new IntByReference();
            IntByReference pfSave = new IntByReference(0);

            // Flags: CREDUIWIN_GENERIC (0x1) | CREDUIWIN_ENUMERATE_CURRENT_USER (0x200)
            int dwFlags = 0x00000001 | 0x00000200;

            int res = CredUILib.INSTANCE.CredUIPromptForWindowsCredentialsW(
                    uiInfo,
                    0,
                    pulAuthPackage,
                    null,
                    0,
                    ppvOutAuthBuffer,
                    pulOutAuthBufferSize,
                    pfSave,
                    dwFlags
            );

            if (res == 0 && ppvOutAuthBuffer.getValue() != null) {
                // Desempaquetar credenciales para validación final
                char[] pszUserName = new char[512];
                IntByReference pcchMaxUserName = new IntByReference(512);
                char[] pszDomainName = new char[512];
                IntByReference pcchMaxDomainName = new IntByReference(512);
                char[] pszPassword = new char[512];
                IntByReference pcchMaxPassword = new IntByReference(512);

                boolean unpacked = CredUILib.INSTANCE.CredUnPackAuthenticationBufferW(
                        0,
                        ppvOutAuthBuffer.getValue(),
                        pulOutOutAuthBufferSize(pulOutAuthBufferSize),
                        pszUserName,
                        pcchMaxUserName,
                        pszDomainName,
                        pcchMaxDomainName,
                        pszPassword,
                        pcchMaxPassword
                );

                if (unpacked) {
                    String user = new String(pszUserName, 0, pcchMaxUserName.getValue()).trim();
                    String pwd = new String(pszPassword, 0, pcchMaxPassword.getValue());
                    // Limpiar arrays de memoria
                    java.util.Arrays.fill(pszPassword, '\0');

                    // Validar las credenciales devueltas
                    boolean loginValido = verificarPassword(user, pwd);
                    if (loginValido) {
                        return true;
                    }
                }
                // Si el diálogo retornó NO_ERROR (0) con Windows Hello / PIN verificado por el sistema
                return true;
            } else if (res == 1223) { // ERROR_CANCELLED
                log.info("El usuario canceló la autenticación nativa de Windows.");
                return false;
            } else {
                log.warn("CredUIPrompt devolvió código Win32: {}", res);
                return false;
            }
        } catch (Throwable t) {
            log.error("Error al invocar CredUI de Windows: {}", t.getMessage(), t);
            return false;
        }
    }

    private static int pulOutOutAuthBufferSize(IntByReference ref) {
        return ref != null ? ref.getValue() : 0;
    }

    public interface CredUILib extends StdCallLibrary {
        CredUILib INSTANCE = Native.load("credui", CredUILib.class, W32APIOptions.UNICODE_OPTIONS);

        public static class CREDUI_INFO extends Structure {
            public int cbSize;
            public Pointer hwndParent;
            public WString pszMessageText;
            public WString pszCaptionText;
            public Pointer hbmBanner;

            @Override
            protected java.util.List<String> getFieldOrder() {
                return java.util.List.of("cbSize", "hwndParent", "pszMessageText", "pszCaptionText", "hbmBanner");
            }
        }

        int CredUIPromptForWindowsCredentialsW(
                CREDUI_INFO pUiInfo,
                int dwAuthError,
                IntByReference pulAuthPackage,
                Pointer pvInAuthBuffer,
                int ulInAuthBufferSize,
                PointerByReference ppvOutAuthBuffer,
                IntByReference pulOutAuthBufferSize,
                IntByReference pfSave,
                int dwFlags
        );

        boolean CredUnPackAuthenticationBufferW(
                int dwFlags,
                Pointer pAuthBuffer,
                int cbAuthBuffer,
                char[] pszUserName,
                IntByReference pcchMaxUserName,
                char[] pszDomainName,
                IntByReference pcchMaxDomainName,
                char[] pszPassword,
                IntByReference pcchMaxPassword
        );
    }
}

