/** 文件职责：共享基础能力：实现该文件名所对应的单一职责。 */
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { listPackage } from "@electron/asar";

const builder = readFileSync(
  new URL("../../electron-builder.yml", import.meta.url),
  "utf8",
);
const packageJson = JSON.parse(
  readFileSync(new URL("../../package.json", import.meta.url), "utf8"),
);
const main = readFileSync(new URL("../main.ts", import.meta.url), "utf8");

const failures = [];
if (/from:\s*["']?python-backend/i.test(builder))
  failures.push("electron-builder packages python-backend");
if (
  !builder.includes("java-runtime") ||
  !builder.includes("opengis-server.jar")
)
  failures.push("bundled JRE/server resources are missing");
if (packageJson.scripts["setup:python"])
  failures.push("setup:python is still a normal product script");
if (/ensurePythonEnv|loading:install-start|loading:install-progress/.test(main))
  failures.push("first-launch Python setup remains in main startup");
if (!main.includes("BackendManager") || !main.includes("backend:status"))
  failures.push("generic BackendManager IPC is missing");

const packagedResources = resolve("dist", "win-unpacked", "resources");
if (existsSync(packagedResources)) {
  if (!existsSync(join(packagedResources, "java-runtime", "bin", "java.exe")))
    failures.push("packaged Java executable is missing");
  if (
    !existsSync(join(packagedResources, "java-backend", "opengis-server.jar"))
  )
    failures.push("packaged Java server is missing");
  if (existsSync(join(packagedResources, "python-backend")))
    failures.push("packaged application contains python-backend");
  const asar = join(packagedResources, "app.asar");
  if (
    existsSync(asar) &&
    listPackage(asar).some((path) => /pythonManager|python-backend/i.test(path))
  ) {
    failures.push(
      "packaged application contains the development-only Python manager",
    );
  }
}

if (failures.length) {
  console.error(failures.map((failure) => `FAIL ${failure}`).join("\n"));
  process.exit(1);
}
console.log("PHASE9_PACKAGE_AUDIT_OK");
