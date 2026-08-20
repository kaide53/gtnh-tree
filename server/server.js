// GTNH 合成树共享服务器（零依赖，Node.js 自带模块）
//
// 功能：
//   - POST /api/upload   接收 { player, tree }，按玩家名存到 data/<player>.json
//   - GET  /api/players  返回玩家列表 ["KaiDe", "Alice", ...]
//   - GET  /api/tree?player=X  返回该玩家的树 JSON
//   - GET  /              托管 public/ 下的多玩家网页
//
// 运行：node server.js   （默认端口 8080，可用 PORT 环境变量改）

const http = require("http");
const fs = require("fs");
const path = require("path");
const url = require("url");

const PORT = process.env.PORT || 8080;
const ROOT = __dirname;
const DATA_DIR = path.join(ROOT, "data");
const PUBLIC_DIR = path.join(ROOT, "public");

if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
};

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "Content-Type": "application/json; charset=utf-8" });
  res.end(body);
}

function readBody(req, maxBytes, cb) {
  let size = 0;
  const chunks = [];
  req.on("data", (c) => {
    size += c.length;
    if (size > maxBytes) {
      cb(new Error("body too large"));
      req.destroy();
      return;
    }
    chunks.push(c);
  });
  req.on("end", () => cb(null, Buffer.concat(chunks).toString("utf-8")));
  req.on("error", (e) => cb(e));
}

function listPlayers() {
  if (!fs.existsSync(DATA_DIR)) return [];
  return fs
    .readdirSync(DATA_DIR)
    .filter((f) => f.endsWith(".json") && !f.endsWith("-checks.json"))
    .map((f) => f.slice(0, -".json".length))
    .sort();
}

function playerFile(player) {
  const safe = String(player).replace(/[^A-Za-z0-9_.\-\u4e00-\u9fff]/g, "_");
  return path.join(DATA_DIR, safe + ".json");
}

function checkFile(player) {
  const safe = String(player).replace(/[^A-Za-z0-9_.\-\u4e00-\u9fff]/g, "_");
  return path.join(DATA_DIR, safe + "-checks.json");
}

function readChecks(player) {
  const f = checkFile(player);
  if (!fs.existsSync(f)) return {};
  try {
    return JSON.parse(fs.readFileSync(f, "utf-8"));
  } catch (e) {
    return {};
  }
}

function safeKey(player) {
  return String(player).replace(/[^A-Za-z0-9_.\-\u4e00-\u9fff]/g, "_");
}

// 每个玩家名 -> 一组 SSE 连接（用于主动推送勾选变化）
const clients = {};

function broadcastChecks(player) {
  const set = clients[safeKey(player)];
  if (!set || set.size === 0) return;
  const msg = "data: " + JSON.stringify({ checks: readChecks(player) }) + "\n\n";
  for (const res of set) {
    try {
      res.write(msg);
    } catch (e) {
      set.delete(res);
    }
  }
}

const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url, true);
  const pathname = parsed.pathname;

  // 上传
  if (req.method === "POST" && pathname === "/api/upload") {
    readBody(req, 64 * 1024 * 1024, (err, body) => {
      if (err) return sendJson(res, 413, { ok: false, error: "body too large" });
      try {
        const payload = JSON.parse(body);
        const player = payload.player;
        const tree = payload.tree;
        if (!player || !tree || !tree.roots) {
          return sendJson(res, 400, { ok: false, error: "需要 player 和 tree.roots" });
        }
        fs.writeFileSync(playerFile(player), JSON.stringify(tree, null, 2), "utf-8");
        console.log("[" + new Date().toISOString() + "] 上传成功: " + player);
        return sendJson(res, 200, { ok: true, player });
      } catch (e) {
        console.error("上传失败:", e.message);
        return sendJson(res, 500, { ok: false, error: e.message });
      }
    });
    return;
  }

  // 保存单个勾选状态
  if (req.method === "POST" && pathname === "/api/check") {
    readBody(req, 1024 * 1024, (err, body) => {
      if (err) return sendJson(res, 413, { ok: false, error: "body too large" });
      try {
        const payload = JSON.parse(body);
        const player = payload.player;
        const id = payload.id;
        if (!player || !id) return sendJson(res, 400, { ok: false, error: "需要 player 和 id" });
        const checks = readChecks(player);
        if (payload.done) checks[id] = true;
        else delete checks[id];
        fs.writeFileSync(checkFile(player), JSON.stringify(checks), "utf-8");
        broadcastChecks(player); // 主动推送给所有在看这个玩家的人
        return sendJson(res, 200, { ok: true });
      } catch (e) {
        return sendJson(res, 500, { ok: false, error: e.message });
      }
    });
    return;
  }

  // SSE：订阅某玩家的勾选变化（服务器主动推送，客户端无需轮询）
  if (req.method === "GET" && pathname === "/api/events") {
    const player = parsed.query.player;
    if (!player) return sendJson(res, 400, { error: "缺少 player 参数" });
    res.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    res.write("retry: 3000\n\n");
    // 连接后立刻发一次当前状态
    res.write("data: " + JSON.stringify({ checks: readChecks(player) }) + "\n\n");
    const key = safeKey(player);
    if (!clients[key]) clients[key] = new Set();
    clients[key].add(res);
    req.on("close", () => {
      const set = clients[key];
      if (set) {
        set.delete(res);
        if (set.size === 0) delete clients[key];
      }
    });
    return;
  }

  // 读取某玩家的全部勾选状态
  if (req.method === "GET" && pathname === "/api/checks") {
    const player = parsed.query.player;
    if (!player) return sendJson(res, 400, { error: "缺少 player 参数" });
    return sendJson(res, 200, { checks: readChecks(player) });
  }

  // 玩家列表
  if (req.method === "GET" && pathname === "/api/players") {
    return sendJson(res, 200, { players: listPlayers() });
  }

  // 某个玩家的树
  if (req.method === "GET" && pathname === "/api/tree") {
    const player = parsed.query.player;
    if (!player) return sendJson(res, 400, { error: "缺少 player 参数" });
    const file = playerFile(player);
    if (!fs.existsSync(file)) return sendJson(res, 404, { error: "没有该玩家的数据" });
    try {
      const tree = JSON.parse(fs.readFileSync(file, "utf-8"));
      return sendJson(res, 200, tree);
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  // 静态文件（多玩家网页）
  let filePath = pathname === "/" ? "/viewer.html" : pathname;
  filePath = path.join(PUBLIC_DIR, path.normalize(filePath).replace(/^([.][.][\\/])+/, ""));
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    return res.end("Forbidden");
  }
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      return res.end("404 Not Found");
    }
    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, { "Content-Type": MIME[ext] || "application/octet-stream" });
    res.end(data);
  });
});

server.listen(PORT, () => {
  console.log("GTNH 合成树服务器已启动: http://localhost:" + PORT);
  console.log("模组里设置服务器地址为: http://<你的IP>:" + PORT);
});
