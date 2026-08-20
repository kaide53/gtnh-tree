package gtnhtreeexport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import codechicken.nei.NEIClientConfig;

/**
 * 把导出的 tree.json + 图标（内嵌 base64）POST 到服务器 /api/upload。
 * 在后台线程里执行，不阻塞客户端。
 */
public class TreeUploader {

    private static final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();

    /** 上传（若配置了服务器地址）。在后台线程执行。 */
    public static void upload(final File treeJsonFile, final File iconsDir, final String serverUrl,
            final String playerName) {
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    uploadSync(treeJsonFile, iconsDir, serverUrl, playerName);
                    enqueue("[GTNH合成树] 已上传到服务器（" + playerName + "）");
                } catch (Exception e) {
                    NEIClientConfig.logger.error("[GTNH合成树] 上传失败", e);
                    enqueue("[GTNH合成树] 上传失败: " + e.getMessage());
                }
            }
        }, "GTNHTreeUploader").start();
    }

    /** 供 tick 处理器在主线程读取并打印的消息。 */
    public static String consumeMessage() {
        return pendingMessages.poll();
    }

    private static void enqueue(String msg) {
        pendingMessages.add(msg);
    }

    private static void uploadSync(File treeJsonFile, File iconsDir, String serverUrl, String playerName)
            throws Exception {
        JsonObject tree = new JsonParser().parse(new FileReader(treeJsonFile)).getAsJsonObject();
        embedIcons(tree, iconsDir);

        JsonObject payload = new JsonObject();
        payload.addProperty("player", playerName);
        payload.add("tree", tree);

        String base = serverUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URL url = new URL(base + "/api/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        OutputStream os = conn.getOutputStream();
        os.write(payload.toString().getBytes("UTF-8"));
        os.close();
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("服务器返回 HTTP " + code);
        }
    }

    /** 把 icon 的相对路径替换成 data:image/png;base64,... 内嵌数据。 */
    private static void embedIcons(JsonObject tree, File iconsDir) {
        JsonArray roots = tree.getAsJsonArray("roots");
        if (roots != null) {
            embedIconsRec(roots, iconsDir);
        }
    }

    private static void embedIconsRec(JsonArray nodes, File iconsDir) {
        for (JsonElement el : nodes) {
            JsonObject n = el.getAsJsonObject();
            if (n.has("icon")) {
                String icon = n.get("icon").getAsString();
                if (icon != null && !icon.startsWith("data:")) {
                    String fileName = icon.substring(icon.lastIndexOf('/') + 1);
                    File png = new File(iconsDir, fileName);
                    if (png.exists()) {
                        try {
                            byte[] bytes = readAllBytes(png);
                            n.addProperty("icon", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
                        } catch (Exception e) {
                            // 保持原样
                        }
                    }
                }
            }
            if (n.has("children")) {
                embedIconsRec(n.getAsJsonArray("children"), iconsDir);
            }
        }
    }

    private static byte[] readAllBytes(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
