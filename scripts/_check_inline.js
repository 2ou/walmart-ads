const fs = require("fs");
const { execSync } = require("child_process");
const path = require("path");

const file = process.argv[2];
const html = fs.readFileSync(file, "utf8");
const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
let m, idx = 0, failed = 0;
while ((m = re.exec(html)) !== null) {
  const code = m[1];
  if (!code.trim()) continue;
  idx++;
  const tmp = path.join(require("os").tmpdir(), `inline_${idx}.js`);
  fs.writeFileSync(tmp, code);
  try {
    execSync(`"${process.execPath}" --check "${tmp}"`, { stdio: "pipe" });
    console.log(`OK   inline script #${idx} (${code.length} chars)`);
  } catch (e) {
    failed++;
    console.error(`FAIL inline script #${idx}`);
    console.error(e.stderr ? e.stderr.toString() : e.message);
  }
}
console.log(failed === 0 ? "ALL OK" : `${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
