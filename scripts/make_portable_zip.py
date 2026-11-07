import os
import zipfile

src_dir = r"E:\orq-bolsalaboral\BolsaDeployManager\target\dist\VPSPilot"
zip_path = r"E:\orq-bolsalaboral\BolsaDeployManager\target\dist\VPSPilot-1.0.0-portable.zip"
legacy_zip_path = r"E:\orq-bolsalaboral\BolsaDeployManager\target\dist\BolsaDeployManager-1.0.0-portable.zip"

for zp in [zip_path, legacy_zip_path]:
    if os.path.exists(zp):
        try:
            os.remove(zp)
        except Exception:
            pass

print(f"Comprimiendo {src_dir} en {zip_path}...")
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(src_dir):
        for file in files:
            full_path = os.path.join(root, file)
            rel_path = os.path.join("VPSPilot", os.path.relpath(full_path, src_dir))
            zf.write(full_path, rel_path)

print(f"Zip portable VPSPilot creado con éxito: {os.path.getsize(zip_path)} bytes")

# Also write legacy zip copy for existing paths
try:
    import shutil
    shutil.copyfile(zip_path, legacy_zip_path)
    print(f"Copia legacy creada en: {legacy_zip_path}")
except Exception as e:
    print("Nota sobre copia legacy:", e)

