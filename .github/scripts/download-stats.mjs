// 累计下载量统计(GitHub Releases + npm),输出 shields endpoint JSON 到 public/downloads.json
import { mkdirSync, writeFileSync } from 'node:fs';

const repo = process.env.GITHUB_REPOSITORY ?? 'moumoum0/cx-clear';
const npmPkg = encodeURIComponent('@moumoum/cxclear');

async function getJson(url, headers = {}) {
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`${url} -> ${res.status} ${res.statusText}`);
  return res.json();
}

let github = 0;
for (let page = 1; ; page++) {
  const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'cx-clear-download-stats' };
  if (process.env.GH_TOKEN) headers.Authorization = `Bearer ${process.env.GH_TOKEN}`;
  const releases = await getJson(`https://api.github.com/repos/${repo}/releases?per_page=100&page=${page}`, headers);
  for (const release of releases) {
    for (const asset of release.assets ?? []) {
      github += asset.download_count;
    }
  }
  if (releases.length < 100) break;
}

// npm range 接口对长区间有限制,按整年分段求和
let npm = 0;
const metaRes = await fetch(`https://registry.npmjs.org/${npmPkg}`);
if (metaRes.status === 404) {
  console.warn(`npm 包 @moumoum/cxclear 还没发布,按 0 算`);
} else {
  if (!metaRes.ok) throw new Error(`npm registry -> ${metaRes.status} ${metaRes.statusText}`);
  const meta = await metaRes.json();
  const createdYear = new Date(meta.time?.created ?? Date.now()).getUTCFullYear();
  const endYear = new Date().getUTCFullYear();
  for (let year = createdYear; year <= endYear; year++) {
    const data = await getJson(`https://api.npmjs.org/downloads/range/${year}-01-01:${year}-12-31/${npmPkg}`);
    npm += (data.downloads ?? []).reduce((sum, day) => sum + day.downloads, 0);
  }
}

function format(n) {
  if (n >= 1e6) return `${(n / 1e6).toFixed(1).replace(/\.0$/, '')}M`;
  if (n >= 1e3) return `${(n / 1e3).toFixed(1).replace(/\.0$/, '')}k`;
  return String(n);
}

const total = github + npm;
const badge = {
  schemaVersion: 1,
  label: '累计下载量',
  message: format(total),
  color: 'brightgreen',
  github,
  npm,
  total,
  updated: new Date().toISOString().slice(0, 10),
};
mkdirSync('public', { recursive: true });
writeFileSync('public/downloads.json', JSON.stringify(badge) + '\n');

// README 的下载徽章:和 img/badges/ 同款的描边胶囊,数字走这里动态生成
const message = format(total);
const width = Math.round(96 + message.length * 7.5);
writeFileSync('public/download-badge.svg', `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="28" role="img" aria-label="download: ${message}">
  <style>@media (prefers-color-scheme: dark) { .b { stroke: #49454F } .l { fill: #CAC4D0 } .s { fill: #49454F } .v { fill: #E6E1E5 } .i { stroke: #D0BCFF } }</style>
  <rect x="0.5" y="0.5" width="${width - 1}" height="27" rx="13.5" fill="none" class="b" stroke="#CAC4D0"/>
  <g class="i" stroke="#475D92" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none" transform="translate(14 7.5) scale(0.542)">
    <path d="M12 15V3"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/>
  </g>
  <text x="32" y="18.5" font-family="system-ui, -apple-system, 'Segoe UI', sans-serif" font-size="12">
    <tspan class="l" fill="#44464F">download</tspan><tspan class="s" fill="#CAC4D0"> · </tspan><tspan class="v" fill="#1A1B20" font-weight="600">${message}</tspan>
  </text>
</svg>
`);
console.log(`GitHub Releases: ${github}, npm: ${npm}, total: ${total}`);
