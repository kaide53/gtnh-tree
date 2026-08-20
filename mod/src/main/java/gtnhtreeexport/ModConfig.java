package gtnhtreeexport;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

/**
 * 模组配置，存到 config/gtnhtreeexport.cfg（纯文本，每行一项）：
 *   第 1 行：服务器地址（留空则不上传）
 *   第 2 行：图标导出缩放倍率（1/2/3/4，默认 3 → 48px）
 *
 * 旧版配置文件只有一行服务器地址，读取时会自动补齐默认倍率，向后兼容。
 */
public class ModConfig {

    private static final int DEFAULT_ICON_SCALE = 3;

    private static String serverUrl = "";
    private static int iconScale = DEFAULT_ICON_SCALE;
    private static boolean loaded = false;

    public static File configFile() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "gtnhtreeexport.cfg");
    }

    public static synchronized String getServerUrl() {
        if (!loaded) {
            load();
        }
        return serverUrl;
    }

    public static synchronized void setServerUrl(String url) {
        serverUrl = url == null ? "" : url.trim();
        loaded = true;
        save();
    }

    /** 图标缩放倍率：1=16px、2=32px、3=48px、4=64px。 */
    public static synchronized int getIconScale() {
        if (!loaded) {
            load();
        }
        return iconScale;
    }

    public static synchronized void setIconScale(int scale) {
        if (scale < 1) {
            scale = 1;
        }
        if (scale > 4) {
            scale = 4;
        }
        iconScale = scale;
        loaded = true;
        save();
    }

    private static void load() {
        loaded = true;
        try {
            File f = configFile();
            if (!f.exists()) {
                return;
            }
            BufferedReader r = new BufferedReader(new FileReader(f));
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line.trim());
            }
            r.close();
            if (!lines.isEmpty()) {
                serverUrl = lines.get(0);
            }
            if (lines.size() >= 2) {
                try {
                    iconScale = clampScale(Integer.parseInt(lines.get(1)));
                } catch (NumberFormatException e) {
                    iconScale = DEFAULT_ICON_SCALE;
                }
            }
        } catch (Exception e) {
            // 忽略：首次运行无配置文件
        }
    }

    private static int clampScale(int s) {
        if (s < 1) {
            return 1;
        }
        if (s > 4) {
            return 4;
        }
        return s;
    }

    private static void save() {
        try {
            File f = configFile();
            File parent = f.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            FileWriter w = new FileWriter(f);
            w.write(serverUrl + "\n");
            w.write(iconScale + "\n");
            w.close();
        } catch (Exception e) {
            // 忽略
        }
    }
}
