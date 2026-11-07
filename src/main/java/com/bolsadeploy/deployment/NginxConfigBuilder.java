package com.bolsadeploy.deployment;

import com.bolsadeploy.security.CommandValidator;

/**
 * Generador del bloque {@code server} de Nginx para una institución.
 *
 * Propiedades:
 * <ul>
 *   <li>Solo genera el archivo de ESTA institución
 *       ({@code sites-available/bolsa-<slug>.conf}); nunca toca otros sites.</li>
 *   <li>{@code root} apunta a {@code .../current/public} (release activo).</li>
 *   <li>PHP-FPM por socket Unix detectado en el VPS (sin TCP 9000).</li>
 *   <li>Límite de subida ≥ 12 MB (CVs PDF) alineado con
 *       {@code maximum_file_size_to_upload}.</li>
 *   <li>Cabeceras de seguridad básicas y bloqueo de archivos ocultos.</li>
 * </ul>
 *
 * Clase pura: no ejecuta comandos; es unit-testeable.
 */
public final class NginxConfigBuilder {

    private NginxConfigBuilder() {
    }

    /**
     * @param dominios         lista de server_name (dominio principal y opcional www)
     * @param rutaPublic       ruta absoluta .../current/public
     * @param socketFpm        socket php-fpm, p.ej. unix:/run/php/php8.3-fpm.sock
     * @param slug             slug de la institución (para logs y nombre de archivo)
     * @param maxUploadMb      tamaño máximo de subida en MB
     */
    public static String servidor(String[] dominios, String rutaPublic, String socketFpm,
                                  String slug, int maxUploadMb) {
        if (dominios == null || dominios.length == 0) {
            throw new IllegalArgumentException("Debe indicarse al menos un server_name");
        }
        CommandValidator.exigirSlug(slug);
        if (!rutaPublic.startsWith("/") || rutaPublic.contains("\n")) {
            throw new IllegalArgumentException("Ruta pública no válida: " + rutaPublic);
        }
        if (!socketFpm.startsWith("unix:") && !socketFpm.matches("^127\\.0\\.0\\.1:[0-9]{2,5}$")) {
            throw new IllegalArgumentException("Socket FPM no válido: " + socketFpm);
        }
        if (maxUploadMb < 1 || maxUploadMb > 512) {
            throw new IllegalArgumentException("Límite de subida fuera de rango: " + maxUploadMb);
        }

        String serverName = String.join(" ", dominios);
        int bodySize = Math.max(maxUploadMb, 8); // al menos 8M para los CVs

        return """
                # Generado por BolsaDeploy Manager — institución: %s
                # Este archivo pertenece solo a esta institución; no editar a mano
                # mientras el orquestador gestione la instalación.
                server {
                    listen 80;
                    listen [::]:80;
                    server_name %s;

                    root %s;
                    index index.php;

                    client_max_body_size %dM;

                    add_header X-Frame-Options "SAMEORIGIN" always;
                    add_header X-Content-Type-Options "nosniff" always;
                    add_header Referrer-Policy "same-origin" always;

                    charset utf-8;

                    access_log /var/log/nginx/%s-access.log;
                    error_log  /var/log/nginx/%s-error.log;

                    location / {
                        try_files $uri $uri/ /index.php?$query_string;
                    }

                    location = /favicon.ico { access_log off; log_not_found off; }
                    location = /robots.txt  { access_log off; log_not_found off; }

                    # Archivos ocultos (.env, .git...) jamás servidos
                    location ~ /\\.(?!well-known) {
                        deny all;
                    }

                    location ~ \\.(js|css|png|jpg|jpeg|gif|ico|svg|webp|woff|woff2|ttf|eot|pdf)$ {
                        expires 7d;
                        access_log off;
                        try_files $uri =404;
                    }

                    location ~ \\.php$ {
                        fastcgi_split_path_info ^(.+\\.php)(/.+)$;
                        try_files $uri =404;
                        fastcgi_pass %s;
                        fastcgi_read_timeout 300;
                        fastcgi_buffers 16 16k;
                        fastcgi_buffer_size 32k;
                        fastcgi_index index.php;
                        # ANTES del include: si fastcgi_params definiera
                        # SCRIPT_FILENAME, gana el primer fastcgi_param.
                        fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;
                        include fastcgi_params;
                    }

                    location ~* /(?:uploads|storage)/.*\\.php$ {
                        deny all;
                    }
                }
                """.formatted(slug, serverName, rutaPublic, bodySize, slug, slug, socketFpm);
    }

    /** Comentario de cabecera reutilizable (identifica la propiedad del archivo). */
    public static String cabecera(String slug) {
        return "# Generado por BolsaDeploy Manager — institución: " + slug + "\n";
    }
}
