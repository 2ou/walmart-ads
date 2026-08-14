const fs = require('fs');
const p = 'D:/Tools/Code/walmart-ads/src/main/resources/static/index.html';
const s = fs.readFileSync(p, 'utf8');
const m = s.match(/<script type="module">([\s\S]*?)<\/script>/);
if (!m) { console.error('NO MODULE SCRIPT'); process.exit(2); }
fs.writeFileSync('D:/Tools/Code/walmart-ads/scripts/inline_check.mjs', m[1]);
console.log('extracted bytes:', m[1].length);
