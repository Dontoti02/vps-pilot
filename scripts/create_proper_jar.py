import os
import subprocess

target_dir = r"E:\orq-bolsalaboral\BolsaDeployManager\target"
classes_dir = os.path.join(target_dir, "classes")
lib_dir = os.path.join(target_dir, "lib")
jar_path = os.path.join(target_dir, "bolsadeploy-manager.jar")
manifest_path = os.path.join(target_dir, "manifest.txt")

lib_jars = [f"lib/{f}" for f in sorted(os.listdir(lib_dir)) if f.endswith(".jar")]
classpath_str = " ".join(lib_jars)

# Write manifest with proper 72-char line folding per JAR specification
manifest_lines = [
    "Manifest-Version: 1.0",
    "Main-Class: com.bolsadeploy.ui.Launcher",
]

# Break Class-Path into 70-character lines preceded by a space for continuation lines
cp_header = "Class-Path: "
remaining = classpath_str
first = True
while remaining:
    if first:
        chunk = remaining[:68 - len(cp_header)]
        manifest_lines.append(cp_header + chunk)
        remaining = remaining[len(chunk):]
        first = False
    else:
        chunk = remaining[:70]
        manifest_lines.append(" " + chunk)
        remaining = remaining[len(chunk):]

manifest_lines.append("") # empty line at end

with open(manifest_path, "w", encoding="utf-8", newline="\r\n") as f:
    f.write("\r\n".join(manifest_lines) + "\r\n")

print(f"Manifest generado en {manifest_path}")

# Run JDK jar.exe
jar_exe = r"E:\orq-bolsalaboral\tools\jdk-25.0.5+8\bin\jar.exe"
cmd = [
    jar_exe,
    "--create",
    "--file", jar_path,
    "--manifest", manifest_path,
    "-C", classes_dir, "."
]

res = subprocess.run(cmd, capture_output=True, text=True)
if res.returncode != 0:
    print("Error al crear jar:", res.stderr)
else:
    print(f"JAR creado con éxito usando jar.exe ({os.path.getsize(jar_path)} bytes)")

