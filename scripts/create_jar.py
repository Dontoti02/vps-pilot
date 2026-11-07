import os
import zipfile

target_dir = r"E:\orq-bolsalaboral\BolsaDeployManager\target"
classes_dir = os.path.join(target_dir, "classes")
lib_dir = os.path.join(target_dir, "lib")
jar_path = os.path.join(target_dir, "bolsadeploy-manager.jar")

lib_jars = [f"lib/{f}" for f in sorted(os.listdir(lib_dir)) if f.endswith(".jar")]
classpath_str = " ".join(lib_jars)

manifest_content = (
    "Manifest-Version: 1.0\r\n"
    "Main-Class: com.bolsadeploy.ui.Launcher\r\n"
    f"Class-Path: {classpath_str}\r\n"
    "Created-By: BolsaDeploy Build Tool\r\n\r\n"
)

with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("META-INF/MANIFEST.MF", manifest_content.encode("utf-8"))
    for root, dirs, files in os.walk(classes_dir):
        for file in files:
            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, classes_dir)
            zf.write(full_path, rel_path.replace("\\", "/"))

print(f"JAR empaquetado con éxito en: {jar_path}")

