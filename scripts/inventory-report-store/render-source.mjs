import { lstat, readFile, readdir } from "node:fs/promises";
import { gzipSync, constants } from "node:zlib";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { once } from "node:events";

const [root, renderer] = process.argv.slice(2);
const { renderItemExport } = await import(pathToFileURL(renderer));
const item = /^item-(\d+)-report\.html$/;
const directories = await readdir(root, { withFileTypes: true });
const brandDir = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;

async function emit(directory, brand, filename, kind, itemId = null) {
    const path = join(directory, filename);
    const stats = await lstat(path);
    if (!stats.isFile() || stats.isSymbolicLink()) return;
    const markup = kind === "item" ? await renderItemExport(path) : await readFile(path, "utf8");
    const bytes = gzipSync(Buffer.from(markup), { level: constants.Z_BEST_SPEED });
    const record = { brand, filename, kind, itemId, directory, updatedAt: stats.mtime.toISOString(),
        htmlGzip: bytes.toString("base64") };
    if (!process.stdout.write(JSON.stringify(record) + "\n")) await once(process.stdout, "drain");
}

for (const dir of directories.filter(entry => entry.isDirectory() && brandDir.test(entry.name))) {
    const path = join(root, dir.name);
    const defaultReport = "brand-" + dir.name + "-report.html";
    const files = await readdir(path, { withFileTypes: true });
    if (!files.some(entry => entry.isFile() && entry.name.toLowerCase() === defaultReport.toLowerCase())) continue;
    for (const file of files.filter(entry => entry.isFile())) {
        const match = item.exec(file.name);
        if (match) await emit(path, dir.name, file.name, "item", match[1]);
        else if (file.name.toLowerCase() === defaultReport.toLowerCase()) await emit(path, dir.name, file.name, "brand");
        else if (dir.name === "Fantaslook" && file.name === "brand-Fantaslook-plus-size-report.html") {
            await emit(path, dir.name, file.name, "edition");
        }
    }
}

for (const path of [root, join(root, "其他单款")]) {
    const files = await readdir(path, { withFileTypes: true }).catch(error => {
        if (error.code === "ENOENT") return [];
        throw error;
    });
    for (const file of files.filter(entry => entry.isFile())) {
        const match = item.exec(file.name);
        if (match) await emit(path, "其他单款", file.name, "item", match[1]);
    }
}
